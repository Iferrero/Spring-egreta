package com.example.demo.curation.service;

import com.example.demo.curation.model.AffiliationStatus;
import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.example.demo.curation.util.UabAffiliationCatalog;
import com.example.demo.curation.util.UabAffiliationCatalog.AffiliationMatch;
import com.example.demo.model.Persona;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Motor de scoring y triangulación de autoría y afiliaciones frente a la Universitat Autònoma de Barcelona (UAB).
 * Evalúa señales heurísticas (+40 UAB, +25 coautor, +20 ORCID, +10 área temática, -50 ajena, -20 disciplina disjunta)
 * y clasifica la candidatura en el semáforo institucional (🟢/🟡/🔴).
 */
@Service
public class AuthorAffiliationValidatorService {

    public static final double SCORE_THRESHOLD_GREEN = 80.0;
    public static final double SCORE_THRESHOLD_YELLOW = 40.0;

    private static final String[] DOI_PREFIXES = {
            "https://doi.org/",
            "http://doi.org/",
            "https://dx.doi.org/",
            "http://dx.doi.org/",
            "doi.org/",
            "dx.doi.org/",
            "doi:"
    };

    private final UabAffiliationCatalog catalog;

    public AuthorAffiliationValidatorService() {
        this(new UabAffiliationCatalog());
    }

    public AuthorAffiliationValidatorService(UabAffiliationCatalog catalog) {
        this.catalog = (catalog != null) ? catalog : new UabAffiliationCatalog();
    }

    /**
     * Contexto de evaluación que encapsula los metadatos de la obra y los datos del investigador.
     */
    public static class CandidateEvaluationContext {
        private String personUuid;
        private String personName;
        private String departmentId;
        private String departmentName;
        private String authorOrcid;
        private Set<String> knownCoauthors = new HashSet<>();
        private Set<String> knownTopics = new HashSet<>();

        private String doi;
        private String title;
        private Integer year;
        private String venue;
        private String openAlexWorkId;
        private String workAuthorOrcid;
        private List<String> rawAffiliations = new ArrayList<>();
        private List<String> institutionIds = new ArrayList<>();
        private List<String> workCoauthors = new ArrayList<>();
        private List<String> workTopics = new ArrayList<>();

        private Boolean orcidMatched;
        private Boolean coauthorMatched;
        private Boolean topicMatched;
        private Boolean topicDisjoint;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final CandidateEvaluationContext ctx = new CandidateEvaluationContext();

            public Builder personUuid(String personUuid) {
                ctx.personUuid = personUuid;
                return this;
            }

            public Builder personName(String personName) {
                ctx.personName = personName;
                return this;
            }

            public Builder departmentId(String departmentId) {
                ctx.departmentId = departmentId;
                return this;
            }

            public Builder departmentName(String departmentName) {
                ctx.departmentName = departmentName;
                return this;
            }

            public Builder authorOrcid(String authorOrcid) {
                ctx.authorOrcid = authorOrcid;
                return this;
            }

            public Builder knownCoauthors(Collection<String> knownCoauthors) {
                if (knownCoauthors != null) ctx.knownCoauthors = new HashSet<>(knownCoauthors);
                return this;
            }

            public Builder knownTopics(Collection<String> knownTopics) {
                if (knownTopics != null) ctx.knownTopics = new HashSet<>(knownTopics);
                return this;
            }

            public Builder doi(String doi) {
                ctx.doi = doi;
                return this;
            }

            public Builder title(String title) {
                ctx.title = title;
                return this;
            }

            public Builder year(Integer year) {
                ctx.year = year;
                return this;
            }

            public Builder venue(String venue) {
                ctx.venue = venue;
                return this;
            }

            public Builder openAlexWorkId(String openAlexWorkId) {
                ctx.openAlexWorkId = openAlexWorkId;
                return this;
            }

            public Builder workAuthorOrcid(String workAuthorOrcid) {
                ctx.workAuthorOrcid = workAuthorOrcid;
                return this;
            }

            public Builder rawAffiliations(List<String> rawAffiliations) {
                if (rawAffiliations != null) ctx.rawAffiliations = new ArrayList<>(rawAffiliations);
                return this;
            }

            public Builder institutionIds(List<String> institutionIds) {
                if (institutionIds != null) ctx.institutionIds = new ArrayList<>(institutionIds);
                return this;
            }

            public Builder workCoauthors(List<String> workCoauthors) {
                if (workCoauthors != null) ctx.workCoauthors = new ArrayList<>(workCoauthors);
                return this;
            }

            public Builder workTopics(List<String> workTopics) {
                if (workTopics != null) ctx.workTopics = new ArrayList<>(workTopics);
                return this;
            }

            public Builder orcidMatched(Boolean orcidMatched) {
                ctx.orcidMatched = orcidMatched;
                return this;
            }

            public Builder coauthorMatched(Boolean coauthorMatched) {
                ctx.coauthorMatched = coauthorMatched;
                return this;
            }

            public Builder topicMatched(Boolean topicMatched) {
                ctx.topicMatched = topicMatched;
                return this;
            }

            public Builder topicDisjoint(Boolean topicDisjoint) {
                ctx.topicDisjoint = topicDisjoint;
                return this;
            }

            public CandidateEvaluationContext build() {
                return ctx;
            }
        }

        public String getPersonUuid() { return personUuid; }
        public String getPersonName() { return personName; }
        public String getDepartmentId() { return departmentId; }
        public String getDepartmentName() { return departmentName; }
        public String getAuthorOrcid() { return authorOrcid; }
        public Set<String> getKnownCoauthors() { return knownCoauthors; }
        public Set<String> getKnownTopics() { return knownTopics; }
        public String getDoi() { return doi; }
        public String getTitle() { return title; }
        public Integer getYear() { return year; }
        public String getVenue() { return venue; }
        public String getOpenAlexWorkId() { return openAlexWorkId; }
        public String getWorkAuthorOrcid() { return workAuthorOrcid; }
        public List<String> getRawAffiliations() { return rawAffiliations; }
        public List<String> getInstitutionIds() { return institutionIds; }
        public List<String> getWorkCoauthors() { return workCoauthors; }
        public List<String> getWorkTopics() { return workTopics; }
        public Boolean getOrcidMatched() { return orcidMatched; }
        public Boolean getCoauthorMatched() { return coauthorMatched; }
        public Boolean getTopicMatched() { return topicMatched; }
        public Boolean getTopicDisjoint() { return topicDisjoint; }
    }

    /**
     * Evalúa una obra candidata a partir del contexto y devuelve una entidad PersonPublicationCandidate
     * con puntuación, razones explicables, semáforo y estado PENDING.
     *
     * @param context contexto de la candidatura
     * @return candidato evaluado con estado PENDING
     */
    public PersonPublicationCandidate evaluateCandidate(CandidateEvaluationContext context) {
        if (context == null) {
            context = CandidateEvaluationContext.builder().build();
        }

        PersonPublicationCandidate candidate = new PersonPublicationCandidate();
        candidate.setPersonUuid(context.getPersonUuid());
        candidate.setPersonName(context.getPersonName());
        candidate.setDepartmentId(context.getDepartmentId());
        candidate.setDepartmentName(context.getDepartmentName());
        candidate.setDoi(normalizeDoi(context.getDoi()));
        candidate.setTitle(context.getTitle());
        candidate.setYear(context.getYear());
        candidate.setVenue(context.getVenue());
        candidate.setOpenAlexWorkId(context.getOpenAlexWorkId());
        candidate.setStatus(CandidateStatus.PENDING);

        List<String> reasons = new ArrayList<>();
        double score = 0.0;

        // 1. Evaluación de Afiliación Institucional frente al catálogo UAB (+40 / -50)
        AffiliationMatch affMatch = catalog.matchAffiliation(context.getRawAffiliations(), context.getInstitutionIds());
        candidate.setAffiliationStatus(affMatch.getStatus());
        candidate.setMatchedAffiliations(affMatch.getMatchedAffiliations());

        boolean isConfirmedUab = affMatch.isConfirmedUab();
        boolean isOtherInstitution = affMatch.isOtherInstitution();

        if (isConfirmedUab) {
            score += 40.0;
            String matchedStr = String.join(", ", affMatch.getMatchedAffiliations());
            reasons.add("+40: Afiliación institucional confirmada en la esfera UAB (" + matchedStr + ")");
        } else if (isOtherInstitution) {
            score -= 50.0;
            String otherStr = String.join(", ", affMatch.getMatchedAffiliations());
            reasons.add("-50: Afiliación explícita a otra institución ajena a la UAB (" + otherStr + ")");
        } else {
            reasons.add("0: Sin filiación institucional declarada o metadatos no concluyentes");
        }

        // 2. Coautoría histórica en EGRETA (+25)
        List<String> matchedCoauthors = findMatchedCoauthors(context);
        boolean coauthorMatched = (context.getCoauthorMatched() != null)
                ? context.getCoauthorMatched()
                : !matchedCoauthors.isEmpty();

        if (coauthorMatched) {
            score += 25.0;
            String namesStr = !matchedCoauthors.isEmpty() ? String.join(", ", matchedCoauthors) : "Detectado";
            reasons.add("+25: Coautor coincidente con la red histórica de EGRETA (" + namesStr + ")");
            candidate.setCoauthorSignals("Coautores EGRETA detectados: " + namesStr);
        } else {
            if (context.getWorkCoauthors() != null && !context.getWorkCoauthors().isEmpty()) {
                reasons.add("0: Ningún coautor coincide con la red histórica de EGRETA");
                candidate.setCoauthorSignals("Sin coincidencias en red de coautores");
            } else {
                reasons.add("0: Obra individual o sin lista de coautores");
                candidate.setCoauthorSignals("Sin coautores listados");
            }
        }

        // Refuerzo institucional: firma UAB y coautoría conjunta
        if (isConfirmedUab && coauthorMatched && score < SCORE_THRESHOLD_GREEN) {
            double reinforcement = SCORE_THRESHOLD_GREEN - score;
            score += reinforcement;
            reasons.add("+" + (int) reinforcement + ": Respaldo institucional reforzado (firma UAB conjunta con coautor histórico)");
        }

        // 3. Identificador ORCID coincidente (+20)
        boolean orcidMatched = evaluateOrcidMatch(context);
        if (orcidMatched) {
            score += 20.0;
            String orcidVal = (context.getAuthorOrcid() != null) ? normalizeOrcid(context.getAuthorOrcid()) : "ORCID coincidente";
            reasons.add("+20: Identificador ORCID coincidente verificado (" + orcidVal + ")");
        } else {
            reasons.add("0: Identificador ORCID no verificado en la publicación");
        }

        // 4. Consistencia temática (+10 coincidente / -20 disciplina ajena)
        List<String> matchedTopics = findMatchedTopics(context);
        boolean topicMatched = (context.getTopicMatched() != null)
                ? context.getTopicMatched()
                : !matchedTopics.isEmpty();

        boolean topicDisjoint = (context.getTopicDisjoint() != null)
                ? context.getTopicDisjoint()
                : (isTopicDisjoint(context) && !coauthorMatched);

        if (topicMatched) {
            score += 10.0;
            String topicsStr = !matchedTopics.isEmpty() ? String.join(", ", matchedTopics) : "Área coincidente";
            reasons.add("+10: Coincidencia temática en subdisciplina científica (" + topicsStr + ")");
        } else if (topicDisjoint) {
            score -= 20.0;
            reasons.add("-20: Disciplina temática disjunta respecto al historial del investigador sin coautores conocidos");
        } else {
            reasons.add("0: Sin evidencia temática concluyente");
        }

        // 5. Ajuste a umbral de revisión cuando ORCID coincide pero la afiliación no consta
        if (orcidMatched && affMatch.isAmbiguousOrMissing() && score < SCORE_THRESHOLD_YELLOW) {
            score = SCORE_THRESHOLD_YELLOW;
            reasons.add("Ajuste a umbral de revisión (🟡): autor verificado por ORCID con metadatos de afiliación ausentes");
        }

        // Normalización final del score [0.0, 100.0]
        double finalScore = Math.max(0.0, Math.min(100.0, score));
        candidate.setConfidenceScore(finalScore);

        // 6. Asignación del semáforo explicable
        CandidateVerdict verdict;
        if (finalScore >= SCORE_THRESHOLD_GREEN) {
            verdict = CandidateVerdict.HIGH_CONFIDENCE_UAB;
        } else if (finalScore >= SCORE_THRESHOLD_YELLOW) {
            verdict = CandidateVerdict.REQUIRES_REVIEW;
        } else {
            verdict = CandidateVerdict.LIKELY_FALSE_POSITIVE;
        }

        candidate.setVerdict(verdict);
        candidate.setReasons(reasons);

        return candidate;
    }

    public PersonPublicationCandidate evaluateCandidate(
            Persona author,
            String doi,
            String title,
            Integer year,
            String venue,
            String openAlexWorkId,
            List<String> rawAffiliations,
            List<String> institutionIds,
            String workAuthorOrcid,
            List<String> workCoauthors,
            Set<String> knownCoauthors,
            List<String> workTopics,
            Set<String> knownTopics) {

        CandidateEvaluationContext.Builder builder = CandidateEvaluationContext.builder()
                .doi(doi)
                .title(title)
                .year(year)
                .venue(venue)
                .openAlexWorkId(openAlexWorkId)
                .rawAffiliations(rawAffiliations)
                .institutionIds(institutionIds)
                .workAuthorOrcid(workAuthorOrcid)
                .workCoauthors(workCoauthors)
                .knownCoauthors(knownCoauthors)
                .workTopics(workTopics)
                .knownTopics(knownTopics);

        if (author != null) {
            builder.personUuid(author.getUuid())
                    .personName(author.getFullName())
                    .authorOrcid(author.getOrcid());

            if (author.getStaffOrganizationAssociations() != null && !author.getStaffOrganizationAssociations().isEmpty()) {
                var firstAssoc = author.getStaffOrganizationAssociations().get(0);
                if (firstAssoc != null && firstAssoc.getOrganization() != null) {
                    builder.departmentId(firstAssoc.getOrganization().getUuid());
                    if (firstAssoc.getOrganization().getName() != null) {
                        builder.departmentName(firstAssoc.getOrganization().getName().getNombre());
                    }
                }
            }
        }

        return evaluateCandidate(builder.build());
    }

    private boolean evaluateOrcidMatch(CandidateEvaluationContext context) {
        if (context.getOrcidMatched() != null) {
            return context.getOrcidMatched();
        }

        String authorOrcid = normalizeOrcid(context.getAuthorOrcid());
        String workOrcid = normalizeOrcid(context.getWorkAuthorOrcid());

        if (authorOrcid != null && workOrcid != null) {
            return authorOrcid.equalsIgnoreCase(workOrcid);
        }

        return false;
    }

    private String normalizeOrcid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        clean = clean.replace("https://orcid.org/", "")
                     .replace("http://orcid.org/", "")
                     .replace("orcid.org/", "")
                     .trim();
        return clean.isEmpty() ? null : clean;
    }

    private List<String> findMatchedCoauthors(CandidateEvaluationContext context) {
        if (context.getWorkCoauthors() == null || context.getKnownCoauthors() == null) {
            return Collections.emptyList();
        }
        if (context.getWorkCoauthors().isEmpty() || context.getKnownCoauthors().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> matches = new ArrayList<>();
        for (String workAuthor : context.getWorkCoauthors()) {
            if (workAuthor == null || workAuthor.isBlank()) continue;
            String normWork = CurationTextNormalizer.normalizeText(workAuthor);

            for (String knownAuthor : context.getKnownCoauthors()) {
                if (knownAuthor == null || knownAuthor.isBlank()) continue;
                String normKnown = CurationTextNormalizer.normalizeText(knownAuthor);

                if (isAuthorNameMatch(normWork, normKnown)) {
                    matches.add(workAuthor.trim());
                    break;
                }
            }
        }
        return matches;
    }

    private boolean isAuthorNameMatch(String normWork, String normKnown) {
        if (normWork.equals(normKnown)) {
            return true;
        }

        // Comparación por tokens sin importar orden (ej. "Sole, Joan" vs "Joan Sole")
        Set<String> tokensWork = new HashSet<>(Arrays.asList(normWork.split("\\s+")));
        Set<String> tokensKnown = new HashSet<>(Arrays.asList(normKnown.split("\\s+")));

        tokensWork.removeIf(String::isBlank);
        tokensKnown.removeIf(String::isBlank);

        if (!tokensWork.isEmpty() && tokensWork.equals(tokensKnown)) {
            return true;
        }

        // Si comparten apellido y al menos un nombre o inicial
        if (tokensWork.size() >= 2 && tokensKnown.size() >= 2) {
            Set<String> intersection = new HashSet<>(tokensWork);
            intersection.retainAll(tokensKnown);
            if (intersection.size() >= 2) {
                return true;
            }
        }

        return CurationTextNormalizer.calculateStringSimilarity(normWork, normKnown) >= 0.88;
    }

    private List<String> findMatchedTopics(CandidateEvaluationContext context) {
        if (context.getWorkTopics() == null || context.getKnownTopics() == null) {
            return Collections.emptyList();
        }
        if (context.getWorkTopics().isEmpty() || context.getKnownTopics().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> matches = new ArrayList<>();
        for (String workTopic : context.getWorkTopics()) {
            if (workTopic == null || workTopic.isBlank()) continue;
            String normWork = CurationTextNormalizer.normalizeText(workTopic);

            for (String knownTopic : context.getKnownTopics()) {
                if (knownTopic == null || knownTopic.isBlank()) continue;
                String normKnown = CurationTextNormalizer.normalizeText(knownTopic);

                if (normWork.contains(normKnown) || normKnown.contains(normWork) ||
                        CurationTextNormalizer.calculateStringSimilarity(normWork, normKnown) >= 0.80) {
                    matches.add(workTopic.trim());
                    break;
                }
            }
        }
        return matches;
    }

    private boolean isTopicDisjoint(CandidateEvaluationContext context) {
        if (context.getWorkTopics() == null || context.getKnownTopics() == null) {
            return false;
        }
        if (context.getWorkTopics().isEmpty() || context.getKnownTopics().isEmpty()) {
            return false;
        }
        return findMatchedTopics(context).isEmpty();
    }

    private String normalizeDoi(String rawDoi) {
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
}
