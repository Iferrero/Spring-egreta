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

        // 5. Refuerzo institucional: si firma UAB y coautor conocido están presentes pero faltan puntos para verde
        if (isConfirmedUab && coauthorMatched && score < SCORE_THRESHOLD_GREEN) {
            double reinforcement = SCORE_THRESHOLD_GREEN - score;
            score += reinforcement;
            reasons.add("+" + (int) reinforcement + ": Respaldo institucional reforzado (firma UAB conjunta con coautor histórico)");
        }

        // 6. Ajuste a umbral de revisión cuando ORCID coincide pero la afiliación no consta
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

            for (String knownAuthor : context.getKnownCoauthors()) {
                if (knownAuthor == null || knownAuthor.isBlank()) continue;

                if (isAuthorNameMatch(workAuthor, knownAuthor)) {
                    matches.add(workAuthor.trim());
                    break;
                }
            }
        }
        return matches;
    }

    private boolean isAuthorNameMatch(String workAuthor, String knownAuthor) {
        if (workAuthor == null || knownAuthor == null) return false;
        String normWork = CurationTextNormalizer.normalizeText(workAuthor);
        String normKnown = CurationTextNormalizer.normalizeText(knownAuthor);

        if (normWork.isBlank() || normKnown.isBlank()) return false;
        if (normWork.equals(normKnown)) return true;

        AuthorNameParts partsWork = new AuthorNameParts(workAuthor);
        AuthorNameParts partsKnown = new AuthorNameParts(knownAuthor);

        // 1. Debe coincidir al menos un apellido principal
        boolean surnameMatches = partsWork.surnames.stream().anyMatch(partsKnown.surnames::contains);
        if (!surnameMatches) {
            return false;
        }

        // 2. Si ambos tienen nombres de pila especificados, debe coincidir al menos un nombre o inicial
        if (!partsWork.givenNames.isEmpty() && !partsKnown.givenNames.isEmpty()) {
            for (String gw : partsWork.givenNames) {
                for (String gk : partsKnown.givenNames) {
                    if (gw.equals(gk)) {
                        return true;
                    }
                    if (gw.length() == 1 && gk.startsWith(gw)) {
                        return true;
                    }
                    if (gk.length() == 1 && gw.startsWith(gk)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // 3. Si alguno carece de nombre de pila, requiere que el conjunto de apellidos coincida exactamente
        return partsWork.surnames.equals(partsKnown.surnames);
    }

    private static class AuthorNameParts {
        final Set<String> surnames = new HashSet<>();
        final Set<String> givenNames = new HashSet<>();

        AuthorNameParts(String rawName) {
            if (rawName == null || rawName.isBlank()) return;

            String trimmed = rawName.trim();
            if (trimmed.contains(",")) {
                String[] parts = trimmed.split(",", 2);
                String normSurname = CurationTextNormalizer.normalizeText(parts[0]);
                String normGiven = (parts.length > 1) ? CurationTextNormalizer.normalizeText(parts[1]) : "";

                for (String t : normSurname.split("\\s+")) {
                    if (!t.isBlank()) surnames.add(t);
                }
                for (String t : normGiven.split("\\s+")) {
                    if (!t.isBlank()) givenNames.add(t);
                }
            } else {
                String norm = CurationTextNormalizer.normalizeText(trimmed);
                String[] tokens = norm.split("\\s+");
                List<String> cleanTokens = new ArrayList<>();
                for (String t : tokens) {
                    if (!t.isBlank()) cleanTokens.add(t);
                }

                if (cleanTokens.isEmpty()) return;

                if (cleanTokens.size() == 1) {
                    surnames.add(cleanTokens.get(0));
                } else {
                    surnames.add(cleanTokens.get(cleanTokens.size() - 1));

                    if (trimmed.contains("-") && cleanTokens.size() >= 2) {
                        surnames.add(cleanTokens.get(cleanTokens.size() - 2));
                        for (int i = 0; i < cleanTokens.size() - 2; i++) {
                            givenNames.add(cleanTokens.get(i));
                        }
                    } else {
                        for (int i = 0; i < cleanTokens.size() - 1; i++) {
                            givenNames.add(cleanTokens.get(i));
                        }
                    }
                }
            }
        }
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
