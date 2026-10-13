package com.example.demo.curation.service;

import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.model.Publicacion;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio encargado de la normalizacion de identificadores (DOI) y deteccion de duplicados
 * contra las publicaciones ya registradas en EGRETA o candidatos preexistentes.
 */
@Service
public class PublicationDeduplicationService {

    public static final double TITLE_SIMILARITY_THRESHOLD = 0.85;

    private final PersonPublicationCandidateRepository candidateRepository;

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
        this.candidateRepository = candidateRepository;
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

        // 2. Coincidencia exacta por DOI frente a publicaciones de EGRETA
        if (normalizedDoi != null && existingEgretaWorks != null) {
            for (Publicacion pub : existingEgretaWorks) {
                if (pub != null && extractDois(pub).contains(normalizedDoi)) {
                    return true;
                }
            }
        }

        // 3. Coincidencia difusa por titulo con Jaccard > 0.85 cuando falta DOI
        if (rawTitle != null && !rawTitle.isBlank()) {
            // Frente a EGRETA (cuando el DOI falta en la candidata o en el registro de EGRETA)
            if (existingEgretaWorks != null) {
                for (Publicacion pub : existingEgretaWorks) {
                    if (pub != null) {
                        Set<String> egretaDois = extractDois(pub);
                        boolean doiMissing = (normalizedDoi == null || egretaDois.isEmpty());
                        if (doiMissing) {
                            String egretaTitle = extractTitle(pub);
                            if (egretaTitle != null && !egretaTitle.isBlank()) {
                                double sim = calculateTitleSimilarity(rawTitle, egretaTitle);
                                if (sim > TITLE_SIMILARITY_THRESHOLD) {
                                    return true;
                                }
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
                        if (sim > TITLE_SIMILARITY_THRESHOLD) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private Set<String> extractDois(Publicacion pub) {
        if (pub == null || pub.getIdentifiers() == null) {
            return Collections.emptySet();
        }
        Set<String> dois = new HashSet<>();
        for (Publicacion.Identifier identifier : pub.getIdentifiers()) {
            if (identifier != null && identifier.getValue() != null) {
                String candidateDoi = normalizeDoi(identifier.getValue());
                if (candidateDoi != null && (isDoiIdentifier(identifier) || candidateDoi.startsWith("10."))) {
                    dois.add(candidateDoi);
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
        String clean = Normalizer.normalize(title, Normalizer.Form.NFD)
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
