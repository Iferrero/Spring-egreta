package com.example.demo.curation.service;

import com.example.demo.curation.adapter.OpenAlexWorksClient;
import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.OpenAlexWorkDto;
import com.example.demo.curation.model.OpenAlexWorkDto.AuthorDto;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.example.demo.model.Persona;
import com.example.demo.model.Publicacion;
import com.example.demo.repository.PersonaRepository;
import com.example.demo.repository.PublicacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orquestador del flujo de conciliación individual de autores contra OpenAlex.
 * Recupera el investigador de EGRETA, extrae su red previa de publicaciones y coautores,
 * consulta candidatos en OpenAlex, aplica deduplicación frente al catálogo institucional y
 * candidatos previos, clasifica mediante el motor de validación de filiación y persiste
 * las candidaturas resultantes.
 */
@Service
public class OpenAlexReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexReconciliationService.class);

    private final PersonaRepository personaRepository;
    private final PublicacionRepository publicacionRepository;
    private final OpenAlexWorksClient openAlexWorksClient;
    private final PublicationDeduplicationService deduplicationService;
    private final AuthorAffiliationValidatorService affiliationValidatorService;
    private final PersonPublicationCandidateRepository candidateRepository;

    public OpenAlexReconciliationService(
            PersonaRepository personaRepository,
            PublicacionRepository publicacionRepository,
            OpenAlexWorksClient openAlexWorksClient,
            PublicationDeduplicationService deduplicationService,
            AuthorAffiliationValidatorService affiliationValidatorService,
            PersonPublicationCandidateRepository candidateRepository) {
        this.personaRepository = personaRepository;
        this.publicacionRepository = publicacionRepository;
        this.openAlexWorksClient = openAlexWorksClient;
        this.deduplicationService = deduplicationService;
        this.affiliationValidatorService = affiliationValidatorService;
        this.candidateRepository = candidateRepository;
    }

    /**
     * Escanea y concilia individualmente las publicaciones de un investigador de EGRETA contra OpenAlex.
     *
     * @param personUuid identificador único del investigador en EGRETA
     * @param strictOrcidOnly si es true, busca exclusivamente por ORCID en OpenAlex
     * @return lista de nuevas candidatas evaluadas y persistidas en esta ejecución
     */
    public List<PersonPublicationCandidate> scanPerson(String personUuid, boolean strictOrcidOnly) {
        if (personUuid == null || personUuid.isBlank()) {
            throw new IllegalArgumentException("personUuid must not be null or blank");
        }

        // 1. Recuperar persona de EGRETA
        Persona persona = personaRepository.findByUuid(personUuid)
                .or(() -> personaRepository.findById(personUuid))
                .orElseThrow(() -> new IllegalArgumentException("Persona not found with uuid: " + personUuid));

        // 2. Extraer publicaciones previas del autor en EGRETA
        List<Publicacion> existingWorks = publicacionRepository.findByContributorsPersonUuid(personUuid);
        if (existingWorks == null) {
            existingWorks = Collections.emptyList();
        }

        // 3. Extraer red histórica de coautores y tópicos
        Set<String> knownCoauthors = extractKnownCoauthors(existingWorks, persona);
        Set<String> knownTopics = new HashSet<>();

        // 4. Preparar parámetros de búsqueda para OpenAlex
        String orcid = persona.getOrcid();
        String authorName = buildAuthorQueryName(persona);

        log.info("Scanning author {} (uuid: {}, orcid: {}) with strictOrcidOnly={}",
                authorName, personUuid, orcid, strictOrcidOnly);

        List<OpenAlexWorkDto> openAlexWorks = openAlexWorksClient.fetchWorksByAuthor(orcid, authorName, strictOrcidOnly);
        if (openAlexWorks == null) {
            openAlexWorks = Collections.emptyList();
        }

        log.info("Retrieved {} works from OpenAlex for author uuid: {}", openAlexWorks.size(), personUuid);

        List<PersonPublicationCandidate> newlySavedCandidates = new ArrayList<>();

        // 5. Deduplicación y evaluación de cada obra
        for (OpenAlexWorkDto work : openAlexWorks) {
            if (work == null) continue;

            // 5a. Descartar si ya es conocida en EGRETA o en candidatos previos
            if (deduplicationService.isAlreadyKnown(personUuid, work.getDoi(), work.getTitle(), existingWorks)) {
                log.debug("Skipping already known work in EGRETA/candidates: doi={}, title={}", work.getDoi(), work.getTitle());
                continue;
            }

            // 5b. Identificar al autor evaluado dentro de la obra y extraer sus filiaciones
            AuthorDto targetAuthor = findTargetAuthor(work, persona);

            List<String> rawAffs;
            List<String> instIds;
            boolean isSingleAuthorOrEmpty = (work.getAuthors() == null || work.getAuthors().size() <= 1);

            if (targetAuthor != null) {
                if (!targetAuthor.getRawAffiliationStrings().isEmpty()) {
                    rawAffs = targetAuthor.getRawAffiliationStrings();
                } else if (isSingleAuthorOrEmpty) {
                    rawAffs = work.getAllRawAffiliations();
                } else {
                    rawAffs = Collections.emptyList();
                }

                if (!targetAuthor.getInstitutionIds().isEmpty()) {
                    instIds = targetAuthor.getInstitutionIds();
                } else if (isSingleAuthorOrEmpty) {
                    instIds = work.getAllInstitutionIds();
                } else {
                    instIds = Collections.emptyList();
                }
            } else {
                rawAffs = work.getAllRawAffiliations();
                instIds = work.getAllInstitutionIds();
            }

            String workAuthorOrcid = (targetAuthor != null) ? targetAuthor.getOrcid() : null;

            List<String> workCoauthors = new ArrayList<>();
            if (work.getAuthors() != null) {
                for (AuthorDto a : work.getAuthors()) {
                    if (a != null && a != targetAuthor && a.getDisplayName() != null && !a.getDisplayName().isBlank()) {
                        workCoauthors.add(a.getDisplayName().trim());
                    }
                }
            }

            List<String> workTopics = new ArrayList<>();
            if (work.getTopics() != null) workTopics.addAll(work.getTopics());
            if (work.getConcepts() != null) workTopics.addAll(work.getConcepts());

            // 5c. Evaluación y semáforo mediante AuthorAffiliationValidatorService
            PersonPublicationCandidate candidate = affiliationValidatorService.evaluateCandidate(
                    persona,
                    work.getDoi(),
                    work.getTitle(),
                    work.getPublicationYear(),
                    work.getVenue(),
                    work.getId(),
                    rawAffs,
                    instIds,
                    workAuthorOrcid,
                    workCoauthors,
                    knownCoauthors,
                    workTopics,
                    knownTopics
            );

            // 5d. Persistencia en MongoDB con estado PENDING
            candidate.setStatus(CandidateStatus.PENDING);
            PersonPublicationCandidate saved = candidateRepository.save(candidate);
            newlySavedCandidates.add(saved != null ? saved : candidate);
        }

        log.info("Scan completed for {}: created {} new candidates", personUuid, newlySavedCandidates.size());

        // 6. Devolver únicamente las nuevas candidatas evaluadas y persistidas en esta ejecución
        return newlySavedCandidates;
    }

    /**
     * Recupera candidatos para un investigador opcionalmente filtrados por estado.
     *
     * @param personUuid identificador de la persona
     * @param status estado de curación o null para todos
     * @return lista de candidatos coincidentes
     */
    public List<PersonPublicationCandidate> getCandidatesForPerson(String personUuid, CandidateStatus status) {
        if (personUuid == null || personUuid.isBlank()) {
            return Collections.emptyList();
        }
        if (status != null) {
            return candidateRepository.findByPersonUuidAndStatus(personUuid, status);
        }
        return candidateRepository.findByPersonUuid(personUuid);
    }

    /**
     * Recupera todos los candidatos para un investigador independientemente del estado.
     *
     * @param personUuid identificador de la persona
     * @return lista de candidatos
     */
    public List<PersonPublicationCandidate> getCandidatesForPerson(String personUuid) {
        return getCandidatesForPerson(personUuid, null);
    }

    private Set<String> extractKnownCoauthors(List<Publicacion> existingWorks, Persona persona) {
        Set<String> knownCoauthors = new HashSet<>();
        if (existingWorks == null || persona == null) return knownCoauthors;

        String personUuid = persona.getUuid();
        String targetLastName = (persona.getName() != null) ? persona.getName().getLastName() : null;
        String targetFirstName = (persona.getName() != null) ? persona.getName().getFirstName() : null;
        String normTargetLn = targetLastName != null ? CurationTextNormalizer.normalizeText(targetLastName) : "";
        String normTargetFn = targetFirstName != null ? CurationTextNormalizer.normalizeText(targetFirstName) : "";

        for (Publicacion pub : existingWorks) {
            if (pub == null || pub.getContributors() == null) continue;
            for (Publicacion.Contributor c : pub.getContributors()) {
                if (c == null) continue;
                if (c.getPerson() != null && personUuid != null && personUuid.equals(c.getPerson().getUuid())) {
                    continue;
                }
                if (c.getName() != null) {
                    String ln = c.getName().getLastName();
                    String fn = c.getName().getFirstName();

                    // Evitar incluir al propio investigador si no tiene person.uuid en EGRETA
                    if (ln != null) {
                        String normLn = CurationTextNormalizer.normalizeText(ln);
                        String normFn = (fn != null) ? CurationTextNormalizer.normalizeText(fn) : "";
                        if (!normTargetLn.isEmpty() && normLn.equals(normTargetLn)) {
                            if (normTargetFn.isEmpty() || normFn.isEmpty() || normFn.equals(normTargetFn)
                                    || (normFn.length() == 1 && normTargetFn.startsWith(normFn))
                                    || (normTargetFn.length() == 1 && normFn.startsWith(normTargetFn))) {
                                continue;
                            }
                        }
                    }

                    if (ln != null && !ln.isBlank()) {
                        if (fn != null && !fn.isBlank()) {
                            knownCoauthors.add(ln.trim() + ", " + fn.trim());
                            knownCoauthors.add(fn.trim() + " " + ln.trim());
                        } else {
                            knownCoauthors.add(ln.trim());
                        }
                    }
                }
            }
        }
        return knownCoauthors;
    }

    private String buildAuthorQueryName(Persona persona) {
        if (persona == null) return "";
        if (persona.getName() != null) {
            String fn = persona.getName().getFirstName() != null ? persona.getName().getFirstName().trim() : "";
            String ln = persona.getName().getLastName() != null ? persona.getName().getLastName().trim() : "";
            if (!fn.isEmpty() && !ln.isEmpty()) {
                return fn + " " + ln;
            } else if (!ln.isEmpty()) {
                return ln;
            }
        }
        return persona.getFullName() != null ? persona.getFullName() : "";
    }

    private AuthorDto findTargetAuthor(OpenAlexWorkDto work, Persona persona) {
        if (work == null || work.getAuthors() == null || work.getAuthors().isEmpty() || persona == null) {
            return null;
        }

        String targetOrcid = cleanOrcid(persona.getOrcid());
        if (targetOrcid != null) {
            for (AuthorDto author : work.getAuthors()) {
                if (author != null) {
                    String authorOrcid = cleanOrcid(author.getOrcid());
                    if (targetOrcid.equalsIgnoreCase(authorOrcid)) {
                        return author;
                    }
                }
            }
        }

        String targetLastName = (persona.getName() != null) ? persona.getName().getLastName() : null;
        String targetFirstName = (persona.getName() != null) ? persona.getName().getFirstName() : null;
        String normLastName = (targetLastName != null) ? CurationTextNormalizer.normalizeText(targetLastName) : "";
        String normFirstName = (targetFirstName != null) ? CurationTextNormalizer.normalizeText(targetFirstName) : "";

        if (!normLastName.isEmpty()) {
            for (AuthorDto author : work.getAuthors()) {
                if (author != null && author.getDisplayName() != null) {
                    String normDisplay = CurationTextNormalizer.normalizeText(author.getDisplayName());
                    if (normDisplay.contains(normLastName)) {
                        if (normFirstName.isEmpty() || normDisplay.contains(normFirstName) ||
                                (normFirstName.length() >= 1 && normDisplay.contains(normFirstName.substring(0, 1)))) {
                            return author;
                        }
                    }
                }
            }
        }

        return null;
    }

    private String cleanOrcid(String orcid) {
        if (orcid == null || orcid.isBlank()) return null;
        String clean = orcid.trim().toLowerCase(Locale.ROOT);
        clean = clean.replace("https://orcid.org/", "")
                .replace("http://orcid.org/", "")
                .replace("orcid.org/", "")
                .trim();
        return clean.isEmpty() ? null : clean;
    }
}
