package com.example.demo.curation.service;

import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.model.Publicacion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Servicio encargado de la normalizacion de identificadores (DOI) y deteccion de duplicados
 * contra las publicaciones ya registradas en EGRETA o candidatos preexistentes.
 */
@Service
public class PublicationDeduplicationService {

    public static final double TITLE_SIMILARITY_THRESHOLD = 0.80;

    private final PersonPublicationCandidateRepository candidateRepository;
    private final MongoTemplate mongoTemplate;

    private static final String[] DOI_PREFIXES = {
            "https://doi.org/",
            "http://doi.org/",
            "https://dx.doi.org/",
            "http://dx.doi.org/",
            "doi.org/",
            "dx.doi.org/",
            "doi:"
    };

    public PublicationDeduplicationService(PersonPublicationCandidateRepository candidateRepository) {
        this(candidateRepository, null);
    }

    @Autowired
    public PublicationDeduplicationService(
            PersonPublicationCandidateRepository candidateRepository,
            @Autowired(required = false) MongoTemplate mongoTemplate) {
        this.candidateRepository = candidateRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Normaliza un identificador DOI eliminando prefijos de protocolo/dominio,
     * espacios y transformando a minusculas canonicas.
     *
     * @param rawDoi DOI en bruto
     * @return DOI normalizado o null si esta vacio o nulo
     */
    public String normalizeDoi(String rawDoi) {
        if (rawDoi == null || rawDoi.isBlank()) {
            return null;
        }

        String doi = rawDoi.trim().toLowerCase(Locale.ROOT);

        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : DOI_PREFIXES) {
                if (doi.startsWith(prefix)) {
                    doi = doi.substring(prefix.length()).trim();
                    stripped = true;
                }
            }
        }

        return doi.isEmpty() ? null : doi;
    }

    /**
     * Calcula la similitud Jaccard entre dos titulos a nivel de tokens de palabras normalizados.
     *
     * @param title1 primer titulo
     * @param title2 segundo titulo
     * @return indice de similitud Jaccard [0.0, 1.0]
     */
    public double calculateTitleSimilarity(String title1, String title2) {
        if (title1 == null || title2 == null || title1.isBlank() || title2.isBlank()) {
            return 0.0;
        }

        Set<String> tokens1 = tokenizeTitle(title1);
        Set<String> tokens2 = tokenizeTitle(title2);

        if (tokens1.isEmpty() || tokens2.isEmpty()) {
            return 0.0;
        }

        if (tokens1.equals(tokens2)) {
            return 1.0;
        }

        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);

        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * Determina si una obra ya es conocida para un investigador, verificando primero
     * por openAlexWorkId, luego por DOI y finalmente por titulo difuso.
     */
    public boolean isAlreadyKnown(String personUuid, String rawDoi, String rawTitle, String openAlexWorkId, List<Publicacion> existingEgretaWorks) {
        if (openAlexWorkId != null && !openAlexWorkId.isBlank() && candidateRepository != null && personUuid != null) {
            Optional<PersonPublicationCandidate> existingByOpenAlex = candidateRepository.findByPersonUuidAndOpenAlexWorkId(personUuid, openAlexWorkId.trim());
            if (existingByOpenAlex.isPresent()) {
                return true;
            }
        }
        return isAlreadyKnown(personUuid, rawDoi, rawTitle, existingEgretaWorks);
    }

    /**
     * Determina si una obra ya es conocida para un investigador, ya sea porque
     * existe en el catalogo institucional EGRETA o porque ya ha sido persistida
     * en el repositorio de candidatos (incluso si fue previamente descartada).
     *
     * @param personUuid identificador de la persona en EGRETA
     * @param rawDoi DOI crudo de la obra
     * @param rawTitle titulo crudo de la obra
     * @param existingEgretaWorks lista de publicaciones actuales del autor en EGRETA
     * @return true si la publicacion ya es conocida o evaluada previamente
     */
    public boolean isAlreadyKnown(String personUuid, String rawDoi, String rawTitle, List<Publicacion> existingEgretaWorks) {
        String normalizedDoi = normalizeDoi(rawDoi);

        // 1. Verificacion en repositorio de candidatos por DOI
        if (normalizedDoi != null && candidateRepository != null && personUuid != null) {
            Optional<PersonPublicationCandidate> existingCandidate = candidateRepository.findByPersonUuidAndDoi(personUuid, normalizedDoi);
            if (existingCandidate.isPresent()) {
                return true;
            }
        }

        // 2. Coincidencia exacta por DOI frente a publicaciones de EGRETA del autor
        if (normalizedDoi != null && existingEgretaWorks != null) {
            for (Publicacion pub : existingEgretaWorks) {
                if (pub != null && extractDois(pub).contains(normalizedDoi)) {
                    return true;
                }
            }
        }

        // 3. Coincidencia por similitud de titulo frente a las publicaciones de EGRETA del autor
        if (rawTitle != null && !rawTitle.isBlank()) {
            if (existingEgretaWorks != null) {
                for (Publicacion pub : existingEgretaWorks) {
                    if (pub != null) {
                        String egretaTitle = extractTitle(pub);
                        if (egretaTitle != null && !egretaTitle.isBlank()) {
                            double sim = calculateTitleSimilarity(rawTitle, egretaTitle);
                            Set<String> egretaDois = extractDois(pub);
                            boolean differentDois = (normalizedDoi != null && !egretaDois.isEmpty() && !egretaDois.contains(normalizedDoi));
                            if (differentDois) {
                                // Si ambos tienen DOIs explicitamente distintos, solo es duplicado si el titulo es virtualmente identico (p. ej. preprint vs articulo publicado)
                                if (sim >= 0.95) {
                                    return true;
                                }
                            } else if (sim >= TITLE_SIMILARITY_THRESHOLD) {
                                return true;
                            }
                        }
                    }
                }
            }

            // Frente a candidatos en repositorio si la candidata no tiene DOI
            if (normalizedDoi == null && candidateRepository != null && personUuid != null) {
                List<PersonPublicationCandidate> candidates = candidateRepository.findByPersonUuid(personUuid);
                for (PersonPublicationCandidate candidate : candidates) {
                    if (candidate != null && candidate.getTitle() != null) {
                        double sim = calculateTitleSimilarity(rawTitle, candidate.getTitle());
                        if (sim >= TITLE_SIMILARITY_THRESHOLD) {
                            return true;
                        }
                    }
                }
            }
        }

        // 4. Verificacion global en todo el catalogo institucional de EGRETA (Researchoutputs)
        if (mongoTemplate != null) {
            // 4a. Busqueda global en EGRETA por DOI
            if (normalizedDoi != null && existsInEgretaByDoi(normalizedDoi)) {
                return true;
            }
            // 4b. Busqueda global en EGRETA por titulo
            if (rawTitle != null && !rawTitle.isBlank() && existsInEgretaByTitle(rawTitle)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Comprueba si un DOI normalizado ya existe registrado en la coleccion Researchoutputs de EGRETA,
     * consultando tanto el array 'identifiers' como 'electronicVersions'.
     */
    public boolean existsInEgretaByDoi(String normalizedDoi) {
        if (normalizedDoi == null || normalizedDoi.isBlank() || mongoTemplate == null) {
            return false;
        }
        String escaped = Pattern.quote(normalizedDoi);
        org.bson.Document query = new org.bson.Document("$or", List.of(
                new org.bson.Document("identifiers.value", new org.bson.Document("$regex", escaped).append("$options", "i")),
                new org.bson.Document("electronicVersions.doi", new org.bson.Document("$regex", escaped).append("$options", "i"))
        ));
        return mongoTemplate.getDb().getCollection("Researchoutputs").countDocuments(query) > 0;
    }

    /**
     * Comprueba si un titulo de publicacion ya existe registrado en la coleccion Researchoutputs de EGRETA.
     */
    public boolean existsInEgretaByTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank() || mongoTemplate == null) {
            return false;
        }
        String clean = rawTitle.trim();
        // 1. Busqueda exacta de titulo
        org.bson.Document exactQuery = new org.bson.Document("title.value", clean);
        if (mongoTemplate.getDb().getCollection("Researchoutputs").countDocuments(exactQuery) > 0) {
            return true;
        }
        // 2. Busqueda insensible a mayusculas/minusculas
        String escaped = Pattern.quote(clean);
        org.bson.Document caseInsensitiveQuery = new org.bson.Document("title.value",
                new org.bson.Document("$regex", "^" + escaped + "$").append("$options", "i"));
        return mongoTemplate.getDb().getCollection("Researchoutputs").countDocuments(caseInsensitiveQuery) > 0;
    }

    private Set<String> extractDois(Publicacion pub) {
        if (pub == null) {
            return Collections.emptySet();
        }
        Set<String> dois = new HashSet<>();
        if (pub.getIdentifiers() != null) {
            for (Publicacion.Identifier identifier : pub.getIdentifiers()) {
                if (identifier != null && identifier.getValue() != null) {
                    String candidateDoi = normalizeDoi(identifier.getValue());
                    if (candidateDoi != null && (isDoiIdentifier(identifier) || candidateDoi.startsWith("10."))) {
                        dois.add(candidateDoi);
                    }
                }
            }
        }
        if (pub.getElectronicVersions() != null) {
            for (Publicacion.ElectronicVersion ev : pub.getElectronicVersions()) {
                if (ev != null && ev.getDoi() != null) {
                    String candidateDoi = normalizeDoi(ev.getDoi());
                    if (candidateDoi != null) {
                        dois.add(candidateDoi);
                    }
                }
            }
        }
        return dois;
    }

    private boolean isDoiIdentifier(Publicacion.Identifier id) {
        if (id.getIdSource() != null && id.getIdSource().equalsIgnoreCase("doi")) {
            return true;
        }
        if (id.getTypeDiscriminator() != null && id.getTypeDiscriminator().toLowerCase(Locale.ROOT).contains("doi")) {
            return true;
        }
        return false;
    }

    private String extractTitle(Publicacion pub) {
        if (pub == null) {
            return null;
        }
        String fullTitle = pub.getFullTitle();
        if (fullTitle != null) {
            String val = fullTitle.trim();
            if (!val.isBlank() && !"Sin título".equalsIgnoreCase(val) && !"Sense títol".equalsIgnoreCase(val)) {
                return val;
            }
        }
        return null;
    }

    private Set<String> tokenizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return Collections.emptySet();
        }
        // 1. Strip HTML tags like <sup>, </sup>, <i>, </i>, <b>, </b>, <br>, etc.
        String clean = title.replaceAll("(?i)<[^>]+>", " ");
        // 2. Strip RTF formatting tags like \i, \i0, \b, \b0, \plain, etc.
        clean = clean.replaceAll("\\\\[a-zA-Z]+[0-9]*", " ");
        // 3. Separate numbers from letters: e.g. 1s -> 1 s, A2 -> A 2
        clean = clean.replaceAll("([0-9]+)([a-zA-Z]+)", "$1 $2");
        clean = clean.replaceAll("([a-zA-Z]+)([0-9]+)", "$1 $2");
        // 4. Normalize accents and convert to lower-case
        clean = Normalizer.normalize(clean, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        String[] tokens = clean.split("[^a-z0-9]+");
        Set<String> tokenSet = new HashSet<>();
        for (String t : tokens) {
            if (!t.isBlank()) {
                tokenSet.add(t);
            }
        }
        return tokenSet;
    }
}
