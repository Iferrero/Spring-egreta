package com.example.demo.plannacional.engine;

import com.example.demo.plannacional.model.*;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Motor pur de regles d'elegibilitat PDI per a sol·licituds del Pla Nacional (AEI / MICIU).
 */
@Service
public class PlanNacionalEligibilityEngine {

    public static final int DEFAULT_DURATION_YEARS = 3;
    public static final int EMERIT_WARNING_MONTHS = 12;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern UAB_WORD = Pattern.compile("\\buab\\b");

    public EmploymentCategory classifyCategory(ContractSnapshot contract) {
        if (contract == null) {
            return EmploymentCategory.DESCONEGUDA;
        }
        if (contract.researchAffiliationOnly()) {
            return EmploymentCategory.ADSCRIPCIO_RECERCA;
        }

        String emp = normalize(contract.employmentType());
        String empNoDot = emp.replace("·", "");
        String ct = normalize(contract.contractType());
        String st = normalize(contract.staffType());
        boolean isVisiting = "VISITING".equalsIgnoreCase(contract.source());

        if (emp.equals("adscripcio a recerca")) {
            return EmploymentCategory.ADSCRIPCIO_RECERCA;
        }
        if (ct.contains("emerit") || emp.contains("emerit")) {
            return EmploymentCategory.EMERIT;
        }
        if (emp.contains("associat")) {
            return EmploymentCategory.ASSOCIAT;
        }
        if (emp.contains("substitut")) {
            return EmploymentCategory.SUBSTITUT;
        }
        if (emp.contains("honorari") || emp.contains("jubilat")) {
            return EmploymentCategory.HONORARI_JUBILAT;
        }
        if (isPredoctoral(emp)) {
            return EmploymentCategory.PREDOCTORAL;
        }
        if (isVisiting && (emp.contains("icrea") || emp.contains("ciber") || emp.contains("vinculat"))) {
            return EmploymentCategory.PDI_ESTABLE;
        }
        if (isPdiEstable(emp, empNoDot)) {
            return EmploymentCategory.PDI_ESTABLE;
        }
        if (isPdiTemporal(emp, isVisiting)) {
            return EmploymentCategory.PDI_TEMPORAL;
        }
        if (isPtgas(emp, st)) {
            return EmploymentCategory.PTGAS;
        }
        return EmploymentCategory.DESCONEGUDA;
    }

    public boolean isExternalEntity(String entity) {
        if (entity == null || entity.isBlank()) {
            return false;
        }
        String norm = normalize(entity);
        return !UAB_WORD.matcher(norm).find()
                && !norm.contains("autonoma de barcelona");
    }

    public int resolveEffectiveDurationYears(ExtractedProposal proposal, Integer durationYearsOverride) {
        if (durationYearsOverride != null && durationYearsOverride > 0) {
            return durationYearsOverride;
        }
        if (proposal != null && proposal.durationYears() != null && proposal.durationYears() > 0) {
            return proposal.durationYears();
        }
        return DEFAULT_DURATION_YEARS;
    }

    public LocalDate resolveEffectiveProjectEndDate(
            ExtractedProposal proposal,
            LocalDate referenceDate,
            Integer durationYearsOverride) {
        LocalDate effectiveRef = referenceDate != null ? referenceDate : LocalDate.now();
        if (durationYearsOverride == null && proposal != null && proposal.expectedEndDate() != null) {
            return proposal.expectedEndDate();
        }
        LocalDate baseStart = (proposal != null && proposal.expectedStartDate() != null)
                ? proposal.expectedStartDate()
                : effectiveRef;
        return baseStart.plusYears(resolveEffectiveDurationYears(proposal, durationYearsOverride));
    }

    public ProposalValidationResponse evaluateProposal(
            ExtractedProposal proposal,
            List<PersonLookupMatch> matches,
            LocalDate referenceDate,
            Integer durationYearsOverride) {
        LocalDate effectiveRef = referenceDate != null ? referenceDate : LocalDate.now();
        int effectiveDuration = resolveEffectiveDurationYears(proposal, durationYearsOverride);
        LocalDate effectiveEnd = resolveEffectiveProjectEndDate(proposal, effectiveRef, durationYearsOverride);

        List<ExtractedMember> extractedMembers = proposal != null ? proposal.members() : List.of();
        List<MemberValidationResult> evaluatedMembers = new ArrayList<>(extractedMembers.size());

        for (int i = 0; i < extractedMembers.size(); i++) {
            ExtractedMember member = extractedMembers.get(i);
            PersonLookupMatch match = (matches != null && i < matches.size())
                    ? matches.get(i)
                    : notFoundMatch();
            evaluatedMembers.add(evaluateMember(member, match, effectiveRef, effectiveEnd));
        }

        SummaryCounts counts = computeCounts(evaluatedMembers);
        EligibilityStatus overall = computeOverallStatus(evaluatedMembers, counts);

        return new ProposalValidationResponse(
                proposal != null ? proposal.fileName() : null,
                proposal != null ? proposal.projectCode() : null,
                proposal != null ? proposal.acronym() : null,
                proposal != null ? proposal.title() : null,
                proposal != null ? proposal.durationYears() : null,
                proposal != null ? proposal.expectedStartDate() : null,
                proposal != null ? proposal.expectedEndDate() : null,
                proposal != null ? proposal.applicantEntity() : null,
                proposal != null ? proposal.extractionMethod() : ExtractionMethod.AEI_PARSER,
                effectiveRef,
                effectiveDuration,
                effectiveEnd,
                overall,
                counts,
                evaluatedMembers,
                proposal != null ? proposal.warnings() : List.of(),
                proposal != null ? proposal.rawTextPreview() : ""
        );
    }

    public MemberValidationResult evaluateMember(
            ExtractedMember member,
            PersonLookupMatch match,
            LocalDate referenceDate,
            LocalDate effectiveProjectEndDate) {
        LocalDate effectiveRef = referenceDate != null ? referenceDate : LocalDate.now();
        LocalDate effectiveEnd = effectiveProjectEndDate != null ? effectiveProjectEndDate : effectiveRef.plusYears(DEFAULT_DURATION_YEARS);
        PersonLookupMatch safeMatch = match != null ? match : notFoundMatch();
        MemberRole role = member != null && member.role() != null ? member.role() : MemberRole.EQUIPO_INVESTIGACION;
        boolean externalEntity = member != null && isExternalEntity(member.entity());

        if (safeMatch.matchConfidence() == MatchConfidence.AMBIGU) {
            return buildResult(member, safeMatch, EmploymentCategory.DESCONEGUDA, EligibilityStatus.AVIS,
                    "AMBIGUOUS_PERSON_MATCH",
                    "Múltiples coincidències a Egreta — seleccioneu o reviseu manualment");
        }

        if (safeMatch.matchConfidence() == MatchConfidence.NO_TROBAT) {
            if (externalEntity) {
                return externalResult(member, safeMatch);
            }
            return buildResult(member, safeMatch, EmploymentCategory.DESCONEGUDA, EligibilityStatus.AVIS,
                    "PERSON_NOT_FOUND",
                    "Persona no trobada a la col·lecció Persons d'Egreta");
        }

        ContractSnapshot primary = safeMatch.primaryContract();
        if (primary == null) {
            if (externalEntity) {
                return externalResult(member, safeMatch);
            }
            EligibilityStatus noActiveStatus = (role == MemberRole.EQUIPO_TRABAJO)
                    ? EligibilityStatus.AVIS
                    : EligibilityStatus.NO_OK;
            String histDetails = formatHistoricalSuffix(safeMatch.latestHistoricalContract());
            return buildResult(member, safeMatch, EmploymentCategory.DESCONEGUDA, noActiveStatus,
                    "NO_ACTIVE_CONTRACT",
                    "Sense contracte vigent a Egreta a data " + effectiveRef + histDetails);
        }

        EmploymentCategory category = classifyCategory(primary);
        RuleOutcome outcome = evaluateByCategoryAndRole(category, role, primary, effectiveRef, effectiveEnd);

        EligibilityStatus finalStatus = outcome.status();
        String finalCode = outcome.code();
        String finalMessage = outcome.message();

        if (safeMatch.matchConfidence() == MatchConfidence.NOM_APROXIMAT && finalStatus == EligibilityStatus.OK) {
            finalStatus = EligibilityStatus.AVIS;
            finalMessage += " (concordança aproximada — confirmeu identitat)";
        }

        if (externalEntity) {
            String entityLabel = member.entity().trim();
            if (finalStatus == EligibilityStatus.OK) {
                finalStatus = EligibilityStatus.AVIS;
                finalCode = "EXTERNAL_ENTITY_DISCREPANCY";
                finalMessage = "Consta com a extern al PDF (" + entityLabel
                        + ") però té contracte vigent a la UAB — reviseu afiliació (" + finalMessage + ")";
            } else {
                finalMessage += " [Consta com a extern al PDF (" + entityLabel + "), però té contracte vigent a la UAB]";
            }
        }

        return buildResult(member, safeMatch, category, finalStatus, finalCode, finalMessage);
    }

    public RuleCatalogResponse buildRuleCatalog() {
        List<RuleDescription> rules = List.of(
                new RuleDescription("PDI_ESTABLE", "PDI estable / permanent / ICREA / CIBER / Vinculat",
                        "OK (AVÍS si data fi < fi projecte)", "OK (AVÍS si data fi < fi projecte)", "OK",
                        "Les associacions únicament d'Adscripció a recerca es descarten quan hi ha contracte laboral vigent."),
                new RuleDescription("PDI_TEMPORAL", "PDI i postdoctoral temporal (Lector, RyC, Beatriu de Pinós, etc.)",
                        "OK si fi >= fi projecte; NO OK si venç abans", "OK si fi >= fi projecte; AVÍS si venç abans", "OK",
                        "Requisit de cobertura contractual per al període del projecte."),
                new RuleDescription("EMERIT", "Professorat emèrit",
                        "NO OK si darrer any (<= 12 mesos) o venç abans; AVÍS (co-IP) si cobreix tot el projecte",
                        "NO OK si darrer any (<= 12 mesos); AVÍS si venç abans; OK si cobreix tot el projecte",
                        "NO OK si darrer any (<= 12 mesos); OK en la resta",
                        "Comprovació sobre period.endDate en contractes trianuals d'emèrits."),
                new RuleDescription("ASSOCIAT", "Professorat associat (totes les especialitats)",
                        "NO OK", "NO OK", "OK",
                        "No elegible com a IP ni membre de l'Equip d'Investigació."),
                new RuleDescription("SUBSTITUT", "Professorat substitut",
                        "NO OK", "NO OK", "OK",
                        "No elegible com a IP ni membre de l'Equip d'Investigació."),
                new RuleDescription("PREDOCTORAL", "Personal predoctoral / en formació (FPI, FPU, FI, etc.)",
                        "NO OK", "NO OK", "OK",
                        "Només pot participar dins l'Equip de Treball."),
                new RuleDescription("PTGAS", "PTGAS / Tècnic de suport a la recerca (TSR)",
                        "NO OK", "NO OK", "OK",
                        "No elegible com a IP ni membre de l'Equip d'Investigació."),
                new RuleDescription("HONORARI_JUBILAT", "Professorat honorari / Personal acadèmic jubilat",
                        "NO OK", "NO OK", "AVÍS",
                        "Sense contracte elegible per a IP / Equip d'Investigació."),
                new RuleDescription("NO_ACTIVE_CONTRACT", "Sense contracte vigent a la data de referència",
                        "NO OK", "NO OK", "AVÍS",
                        "Mostra el darrer contracte històric registrat a Egreta."),
                new RuleDescription("EXTERNAL_ENTITY", "Personal extern (entitat diferent de la UAB al PDF)",
                        "EXTERN", "EXTERN", "EXTERN",
                        "Si consta extern al PDF però té contracte actiu a la UAB, es marca AVÍS (o manté NO OK) per discrepància."),
                new RuleDescription("PERSON_NOT_FOUND", "Persona no trobada a Egreta (entitat UAB o sense entitat)",
                        "AVÍS", "AVÍS", "AVÍS",
                        "No hi ha cap coincidència a la col·lecció Persons per NIF ni per nom; cal revisar manualment."),
                new RuleDescription("AMBIGUOUS_PERSON_MATCH", "Coincidència ambigua (múltiples candidats)",
                        "AVÍS", "AVÍS", "AVÍS",
                        "Mai es tria un homònim a l'atzar; es mostren fins a 5 candidats per revisar."),
                new RuleDescription("ONLY_RESEARCH_AFFILIATION", "Només consta 'Adscripció a recerca'",
                        "AVÍS", "AVÍS", "AVÍS",
                        "No hi ha contracte laboral vigent; reviseu el contracte principal a Egreta."),
                new RuleDescription("UNKNOWN_CATEGORY", "Categoria laboral no classificada",
                        "AVÍS", "AVÍS", "AVÍS",
                        "Mai s'assumeix OK en silenci quan la categoria no es reconeix.")
        );

        List<CategoryDefinition> categories = List.of(
                new CategoryDefinition(EmploymentCategory.PDI_ESTABLE, "PDI estable",
                        "Personal docent i investigador permanent o vinculat de llarga durada.",
                        List.of("Catedràtic/a d'universitat", "Professorat titular d'universitat", "Agregat/da contractat doctor/a", "Personal investigador ICREA")),
                new CategoryDefinition(EmploymentCategory.PDI_TEMPORAL, "PDI temporal",
                        "Professorat lector i personal investigador postdoctoral o programa d'atracció temporal.",
                        List.of("Professor/a lector/a ajudant doctor/a", "Investigadors/es programa Ramón Y Cajal", "Personal investigador postdoctoral projectes")),
                new CategoryDefinition(EmploymentCategory.EMERIT, "Professorat emèrit",
                        "Professorat emèrit amb contracte vigent i data de finalització definida.",
                        List.of("Professorat emèrit laboral", "Emèrit/a")),
                new CategoryDefinition(EmploymentCategory.ASSOCIAT, "Professorat associat",
                        "Professorat associat laboral, mèdic, d'infermeria o altres àmbits.",
                        List.of("Professor/a associat/da laboral", "Associat/da mèdic/a laboral", "Professor associat indefinit")),
                new CategoryDefinition(EmploymentCategory.SUBSTITUT, "Professorat substitut",
                        "Professorat contractat per substitució docent.",
                        List.of("Professor/a substitut/a")),
                new CategoryDefinition(EmploymentCategory.PREDOCTORAL, "Predoctoral / en formació",
                        "Personal investigador en formació doctoral o estudiant de doctorat.",
                        List.of("Estudiant de doctorat", "Formació personal investigador FPI", "Investigador en Formació FI-JOAN ORÓ")),
                new CategoryDefinition(EmploymentCategory.PTGAS, "PTGAS / Suport a la recerca",
                        "Personal tècnic de suport a la recerca i gestió administrativa.",
                        List.of("Tècnic/a de suport a la recerca", "Tècnic/a superior suport a la recerca", "TSR Acreditat")),
                new CategoryDefinition(EmploymentCategory.HONORARI_JUBILAT, "Honorari / Jubilat",
                        "Professorat honorari o personal acadèmic jubilat sense contracte actiu d'investigació.",
                        List.of("Professor/a Honorari/a", "Personal acadèmic jubilat"))
        );

        return new RuleCatalogResponse(DEFAULT_DURATION_YEARS, EMERIT_WARNING_MONTHS, rules, categories);
    }

    private RuleOutcome evaluateByCategoryAndRole(
            EmploymentCategory category,
            MemberRole role,
            ContractSnapshot contract,
            LocalDate referenceDate,
            LocalDate projectEnd) {
        String term = displayTerm(contract);
        LocalDate endDate = contract.endDate();
        boolean isWorkTeam = role == MemberRole.EQUIPO_TRABAJO;
        boolean isIp = role == MemberRole.IP;

        return switch (category) {
            case PDI_ESTABLE -> {
                if (endDate == null || !endDate.isBefore(projectEnd)) {
                    yield new RuleOutcome(EligibilityStatus.OK, "ELIGIBLE_STABLE",
                            "Contracte estable vigent (" + term + ")");
                }
                EligibilityStatus status = isWorkTeam ? EligibilityStatus.OK : EligibilityStatus.AVIS;
                yield new RuleOutcome(status, "STABLE_EXPIRES_BEFORE_END",
                        "Contracte estable (" + term + ") amb data de fi " + endDate + " anterior a la fi del projecte (" + projectEnd + ")");
            }
            case PDI_TEMPORAL -> {
                if (endDate == null || !endDate.isBefore(projectEnd)) {
                    String endInfo = endDate != null ? " (fi: " + endDate + ")" : " (sense data de fi)";
                    yield new RuleOutcome(EligibilityStatus.OK, "ELIGIBLE_TEMPORARY",
                            "Contracte temporal vigent (" + term + ") cobreix tot el projecte" + endInfo);
                }
                EligibilityStatus status = isIp ? EligibilityStatus.NO_OK
                        : (isWorkTeam ? EligibilityStatus.OK : EligibilityStatus.AVIS);
                yield new RuleOutcome(status, "TEMPORARY_EXPIRES_BEFORE_END",
                        "Contracte temporal (" + term + ") finalitza el " + endDate + ", abans de la fi prevista del projecte (" + projectEnd + ")");
            }
            case EMERIT -> evaluateEmerit(role, endDate, referenceDate, projectEnd);
            case ASSOCIAT -> new RuleOutcome(
                    isWorkTeam ? EligibilityStatus.OK : EligibilityStatus.NO_OK,
                    "INELIGIBLE_ASSOCIAT",
                    "Professorat associat (" + term + ") — no elegible com a IP / Equip d'Investigació");
            case SUBSTITUT -> new RuleOutcome(
                    isWorkTeam ? EligibilityStatus.OK : EligibilityStatus.NO_OK,
                    "INELIGIBLE_SUBSTITUT",
                    "Professorat substitut (" + term + ") — no elegible com a IP / Equip d'Investigació");
            case PREDOCTORAL -> new RuleOutcome(
                    isWorkTeam ? EligibilityStatus.OK : EligibilityStatus.NO_OK,
                    "INELIGIBLE_PREDOCTORAL",
                    "Personal predoctoral / en formació (" + term + ") — només pot participar a l'Equip de Treball");
            case PTGAS -> new RuleOutcome(
                    isWorkTeam ? EligibilityStatus.OK : EligibilityStatus.NO_OK,
                    "INELIGIBLE_PTGAS",
                    "Personal tècnic / PTGAS (" + term + ") — no elegible com a IP / Equip d'Investigació");
            case HONORARI_JUBILAT -> new RuleOutcome(
                    isWorkTeam ? EligibilityStatus.AVIS : EligibilityStatus.NO_OK,
                    "INELIGIBLE_HONORARY_RETIRED",
                    "Professorat honorari / jubilat (" + term + ") — sense contracte elegible per a IP / Equip d'Investigació");
            case ADSCRIPCIO_RECERCA -> new RuleOutcome(
                    EligibilityStatus.AVIS,
                    "ONLY_RESEARCH_AFFILIATION",
                    "Només consta 'Adscripció a recerca'; reviseu el contracte principal a Egreta");
            case DESCONEGUDA -> new RuleOutcome(
                    EligibilityStatus.AVIS,
                    "UNKNOWN_CATEGORY",
                    "Categoria laboral no classificada: '" + term + "' — reviseu manualment");
        };
    }

    private RuleOutcome evaluateEmerit(MemberRole role, LocalDate endDate, LocalDate referenceDate, LocalDate projectEnd) {
        if (endDate != null && !endDate.isAfter(referenceDate.plusMonths(EMERIT_WARNING_MONTHS))) {
            return new RuleOutcome(EligibilityStatus.NO_OK, "EMERIT_LAST_YEAR",
                    "Emèrit/a en darrer any de contracte (fi: " + endDate + ")");
        }
        if (endDate != null && endDate.isBefore(projectEnd)) {
            EligibilityStatus status = switch (role) {
                case IP -> EligibilityStatus.NO_OK;
                case EQUIPO_INVESTIGACION -> EligibilityStatus.AVIS;
                case EQUIPO_TRABAJO -> EligibilityStatus.OK;
            };
            return new RuleOutcome(status, "EMERIT_EXPIRES_BEFORE_END",
                    "Emèrit/a amb contracte fins " + endDate + ", anterior a la fi prevista del projecte (" + projectEnd + ")");
        }
        String endLabel = endDate != null ? endDate.toString() : "sense data de fi";
        if (role == MemberRole.IP) {
            return new RuleOutcome(EligibilityStatus.AVIS, "EMERIT_COVERS_PROJECT",
                    "Emèrit/a amb contracte fins " + endLabel + " — verifiqueu requisit de co-IP");
        }
        return new RuleOutcome(EligibilityStatus.OK, "EMERIT_COVERS_PROJECT",
                "Emèrit/a amb contracte vigent fins " + endLabel);
    }

    private MemberValidationResult externalResult(ExtractedMember member, PersonLookupMatch match) {
        String entityLabel = member != null && member.entity() != null ? member.entity().trim() : "Externa";
        return buildResult(member, match, EmploymentCategory.DESCONEGUDA, EligibilityStatus.EXTERN,
                "EXTERNAL_ENTITY",
                "Personal extern (" + entityLabel + ") — no aplica control PDI UAB");
    }

    private MemberValidationResult buildResult(
            ExtractedMember member,
            PersonLookupMatch match,
            EmploymentCategory category,
            EligibilityStatus status,
            String reasonCode,
            String reasonMessage) {
        return new MemberValidationResult(
                member,
                match.matchConfidence(),
                match.matchedPersonUuid(),
                match.matchedPureId(),
                match.matchedFullName(),
                match.egretaEditorUrl(),
                match.primaryContract(),
                match.activeContracts(),
                match.latestHistoricalContract(),
                match.ambiguousCandidates(),
                category,
                status,
                reasonCode,
                reasonMessage
        );
    }

    private SummaryCounts computeCounts(List<MemberValidationResult> members) {
        int ok = 0;
        int avis = 0;
        int noOk = 0;
        int extern = 0;
        for (MemberValidationResult m : members) {
            switch (m.status()) {
                case OK -> ok++;
                case AVIS -> avis++;
                case NO_OK -> noOk++;
                case EXTERN -> extern++;
            }
        }
        return new SummaryCounts(members.size(), ok, avis, noOk, extern);
    }

    private EligibilityStatus computeOverallStatus(List<MemberValidationResult> members, SummaryCounts counts) {
        if (counts.noOk() > 0) {
            return EligibilityStatus.NO_OK;
        }
        if (members.isEmpty() || counts.avis() > 0) {
            return EligibilityStatus.AVIS;
        }
        return EligibilityStatus.OK;
    }

    private PersonLookupMatch notFoundMatch() {
        return new PersonLookupMatch(MatchConfidence.NO_TROBAT, null, null, null, null, null, List.of(), null, List.of());
    }

    private String formatHistoricalSuffix(ContractSnapshot hist) {
        if (hist == null) {
            return "";
        }
        String term = displayTerm(hist);
        String end = hist.endDate() != null ? hist.endDate().toString() : "sense data de fi";
        return " (darrer: " + term + ", fi " + end + ")";
    }

    private String displayTerm(ContractSnapshot contract) {
        if (contract.employmentType() != null && !contract.employmentType().isBlank()) {
            return contract.employmentType();
        }
        if (contract.contractType() != null && !contract.contractType().isBlank()) {
            return contract.contractType();
        }
        return "Sense categoria";
    }

    private boolean isPredoctoral(String emp) {
        return emp.contains("predoctoral")
                || emp.contains("en formaci")
                || emp.contains("estudiant de doctorat")
                || emp.contains("doctorant")
                || emp.contains("fpi")
                || emp.contains("fpu")
                || emp.contains("fi-joan")
                || emp.contains("fi-sdur")
                || emp.contains("fi-generalitat")
                || emp.contains("fiap-")
                || emp.contains("novell")
                || emp.contains("la caixa")
                || emp.contains("becari")
                || emp.contains("pif")
                || emp.contains("senacyt")
                || emp.contains("govern basc");
    }

    private boolean isPdiEstable(String emp, String empNoDot) {
        if (emp.contains("catedr") && !emp.contains("interi")) {
            return true;
        }
        if (emp.contains("titular") && !emp.contains("interi")) {
            return true;
        }
        if (emp.contains("agregat") && !emp.contains("interi") && !emp.contains("visitant")) {
            return true;
        }
        return empNoDot.contains("collaborador/a permanent")
                || emp.contains("colaborador/a permanent")
                || emp.contains("distingit")
                || emp.contains("director/a investigaci")
                || emp.contains("ordinari")
                || (emp.contains("postdoctoral") && emp.contains("indefinit"))
                || emp.contains("icrea");
    }

    private boolean isPdiTemporal(String emp, boolean isVisiting) {
        return emp.contains("lector")
                || emp.contains("ramon y cajal")
                || emp.contains("beatriz de galindo")
                || emp.contains("beatriu de pinos")
                || emp.contains("juan de la cierva")
                || emp.contains("sklodowska")
                || emp.contains("margarita salas")
                || emp.contains("maria zambrano")
                || emp.contains("sara borrell")
                || emp.contains("postdoctoral")
                || emp.contains("visitant")
                || emp.contains("convidat")
                || emp.contains("laboral estranger")
                || (isVisiting && emp.contains("convidat"))
                || (emp.contains("interi") && (emp.contains("titular") || emp.contains("agregat") || emp.contains("catedr") || emp.contains("adjunt")));
    }

    private boolean isPtgas(String emp, String st) {
        return emp.contains("tecnic")
                || emp.contains("tsr")
                || emp.contains("administratiu")
                || emp.contains("auxiliar")
                || emp.contains("cos de gestio")
                || emp.contains("cos tecnic")
                || emp.contains("director/a de servei")
                || emp.contains("director/a d'oficina")
                || emp.contains("cap d'unitat")
                || emp.contains("bibliotecar")
                || emp.contains("oficial")
                || emp.contains("vicegerent")
                || st.contains("no academic");
    }

    private String normalize(String input) {
        if (input == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private record RuleOutcome(EligibilityStatus status, String code, String message) {}
}
