package com.example.demo.plannacional.engine;

import com.example.demo.plannacional.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanNacionalEligibilityEngineTest {

    private PlanNacionalEligibilityEngine engine;
    private static final LocalDate REF_DATE = LocalDate.of(2026, 11, 19);
    private static final LocalDate PROJ_END = LocalDate.of(2029, 11, 19);

    @BeforeEach
    void setUp() {
        engine = new PlanNacionalEligibilityEngine();
    }

    private ExtractedMember member(MemberRole role, String name, String entity) {
        return new ExtractedMember(role, name, null, null, "12345678A", entity, null, name);
    }

    private ContractSnapshot staff(String empType, String contractType, String staffType, LocalDate start, LocalDate end) {
        return new ContractSnapshot("STAFF", "Departament de Ciències", "org-1", empType, contractType, staffType, start, end, true, false);
    }

    private ContractSnapshot visiting(String jobTitle, LocalDate start, LocalDate end) {
        return new ContractSnapshot("VISITING", "Institut de Recerca", "org-2", jobTitle, null, "Acadèmic", start, end, false, false);
    }

    private PersonLookupMatch found(ContractSnapshot primary, MatchConfidence confidence) {
        return new PersonLookupMatch(
                confidence,
                "person-uuid-1",
                1001L,
                "Persona Prova",
                "https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=1001",
                primary,
                primary != null ? List.of(primary) : List.of(),
                null,
                List.of()
        );
    }

    @Test
    void classifyCategoryMapsRealEgretaTerms() {
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(staff("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(staff("Professorat titular d'universitat", "Funcionari/a", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(staff("Agregat/da contractat doctor/a", "Contracte laboral indefinit", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(staff("Investigador/a ordinari/a", "Contracte laboral indefinit", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(staff("Investigador/a postdoctoral indefinit/da", "Contracte laboral indefinit", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(visiting("Personal investigador ICREA", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(visiting("Personal investigador CIBER", null, null)));
        assertEquals(EmploymentCategory.PDI_ESTABLE, engine.classifyCategory(visiting("Personal investigador vinculat", null, null)));

        assertEquals(EmploymentCategory.PDI_TEMPORAL, engine.classifyCategory(staff("Professor/a lector/a ajudant doctor/a", "Contracte laboral temporal", "Acadèmic", null, PROJ_END)));
        assertEquals(EmploymentCategory.PDI_TEMPORAL, engine.classifyCategory(staff("Investigadors/es programa Ramón Y Cajal", "Contracte laboral temporal", "Acadèmic", null, PROJ_END)));
        assertEquals(EmploymentCategory.PDI_TEMPORAL, engine.classifyCategory(staff("Personal investigador postdoctoral projectes", "Contracte laboral temporal", "Acadèmic", null, PROJ_END)));
        assertEquals(EmploymentCategory.PDI_TEMPORAL, engine.classifyCategory(staff("Professor/a visitant laboral", "Contracte laboral temporal", "Acadèmic", null, PROJ_END)));

        assertEquals(EmploymentCategory.EMERIT, engine.classifyCategory(staff("Professorat emèrit laboral", "Emèrit/a", "Acadèmic", null, PROJ_END)));
        assertEquals(EmploymentCategory.EMERIT, engine.classifyCategory(staff("Categoria desconeguda", "Emèrit/a", "Acadèmic", null, PROJ_END)));

        assertEquals(EmploymentCategory.ASSOCIAT, engine.classifyCategory(staff("Professor/a associat/da laboral", "Contracte laboral temporal", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.ASSOCIAT, engine.classifyCategory(staff("Associat/da mèdic/a laboral", "Contracte laboral temporal", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.ASSOCIAT, engine.classifyCategory(staff("Professor associat indefinit", "Contracte laboral indefinit", "Acadèmic", null, null)));

        assertEquals(EmploymentCategory.SUBSTITUT, engine.classifyCategory(staff("Professor/a substitut/a", "Contracte laboral temporal", "Acadèmic", null, null)));

        assertEquals(EmploymentCategory.PREDOCTORAL, engine.classifyCategory(staff("Estudiant de doctorat", "Personal Becat", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PREDOCTORAL, engine.classifyCategory(staff("Investigador en Formació FI-JOAN ORÓ", "Personal Becat", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.PREDOCTORAL, engine.classifyCategory(staff("Formació personal investigador FPI", "Personal Becat", "Acadèmic", null, null)));

        assertEquals(EmploymentCategory.PTGAS, engine.classifyCategory(staff("Tècnic/a superior suport a la recerca", "Contracte laboral indefinit", "No acadèmic", null, null)));
        assertEquals(EmploymentCategory.PTGAS, engine.classifyCategory(staff("Altres", "Contracte laboral indefinit", "No acadèmic", null, null)));

        assertEquals(EmploymentCategory.HONORARI_JUBILAT, engine.classifyCategory(staff("Professor/a Honorari/a", "Altres", "Acadèmic", null, null)));
        assertEquals(EmploymentCategory.HONORARI_JUBILAT, engine.classifyCategory(staff("Personal acadèmic jubilat", "Altres", "Acadèmic", null, null)));

        ContractSnapshot onlyResearch = new ContractSnapshot("STAFF", "ICTA", "org-3", "Adscripció a recerca", null, "Acadèmic", null, null, true, true);
        assertEquals(EmploymentCategory.ADSCRIPCIO_RECERCA, engine.classifyCategory(onlyResearch));

        assertEquals(EmploymentCategory.DESCONEGUDA, engine.classifyCategory(staff("Nova categoria experimental", "Altres", "Acadèmic", null, null)));
    }

    @Test
    void evaluateMemberCoversFullRoleMatrix() {
        ContractSnapshot stableOpen = staff("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", LocalDate.of(2010, 1, 1), null);
        MemberValidationResult stableIp = engine.evaluateMember(member(MemberRole.IP, "Joan Garcia", "UAB"), found(stableOpen, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.OK, stableIp.status());
        assertEquals("ELIGIBLE_STABLE", stableIp.reasonCode());

        MemberValidationResult stableApprox = engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Joan Garcia", "UAB"), found(stableOpen, MatchConfidence.NOM_APROXIMAT), REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.AVIS, stableApprox.status());

        ContractSnapshot stableEndingEarly = staff("Agregat/da contractat doctor/a", "Contracte laboral indefinit", "Acadèmic", LocalDate.of(2020, 1, 1), PROJ_END.minusDays(10));
        assertEquals(EligibilityStatus.AVIS, engine.evaluateMember(member(MemberRole.IP, "Anna Puig", "UAB"), found(stableEndingEarly, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.AVIS, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Anna Puig", "UAB"), found(stableEndingEarly, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Anna Puig", "UAB"), found(stableEndingEarly, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot associat = staff("Professor/a associat/da laboral", "Contracte laboral temporal", "Acadèmic", REF_DATE.minusYears(1), PROJ_END.plusYears(1));
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.IP, "Pau Soler", "UAB"), found(associat, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Pau Soler", "UAB"), found(associat, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Pau Soler", "UAB"), found(associat, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot substitut = staff("Professor/a substitut/a", "Contracte laboral temporal", "Acadèmic", REF_DATE.minusMonths(2), PROJ_END);
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Pere Mas", "UAB"), found(substitut, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Pere Mas", "UAB"), found(substitut, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot predoc = staff("Estudiant de doctorat", "Personal Becat", "Acadèmic", REF_DATE.minusYears(1), PROJ_END);
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Laia Prat", "UAB"), found(predoc, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Laia Prat", "UAB"), found(predoc, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot ptgas = staff("Tècnic/a de suport a la recerca", "Contracte laboral indefinit", "No acadèmic", REF_DATE.minusYears(2), null);
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Marc Font", "UAB"), found(ptgas, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Marc Font", "UAB"), found(ptgas, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot honorari = staff("Professor/a Honorari/a", "Altres", "Acadèmic", REF_DATE.minusYears(1), PROJ_END);
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Rosa Valls", "UAB"), found(honorari, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.AVIS, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Rosa Valls", "UAB"), found(honorari, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot onlyAdscripcio = new ContractSnapshot("STAFF", "ICTA", "org-3", "Adscripció a recerca", null, "Acadèmic", REF_DATE.minusYears(1), null, true, true);
        assertEquals(EligibilityStatus.AVIS, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Núria Camps", "UAB"), found(onlyAdscripcio, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot historical = staff("Professor/a lector/a ajudant doctor/a", "Contracte laboral temporal", "Acadèmic", LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31));
        PersonLookupMatch noActive = new PersonLookupMatch(MatchConfidence.NIF_EXACTE, "u1", 10L, "Ex PDI", "url", null, List.of(), historical, List.of());
        MemberValidationResult noActiveEi = engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Ex PDI", "UAB"), noActive, REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.NO_OK, noActiveEi.status());
        assertEquals("NO_ACTIVE_CONTRACT", noActiveEi.reasonCode());
        assertTrue(noActiveEi.reasonMessage().contains("2025-12-31"));
        assertEquals(EligibilityStatus.AVIS, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Ex PDI", "UAB"), noActive, REF_DATE, PROJ_END).status());

        PersonLookupMatch notFound = new PersonLookupMatch(MatchConfidence.NO_TROBAT, null, null, null, null, null, List.of(), null, List.of());
        assertEquals("PERSON_NOT_FOUND", engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Desconegut", "UAB"), notFound, REF_DATE, PROJ_END).reasonCode());

        PersonLookupMatch ambiguous = new PersonLookupMatch(MatchConfidence.AMBIGU, null, null, null, null, null, List.of(), null,
                List.of(new PersonCandidateSummary("u1", 1L, "Garcia, Joan", "Catedràtic/a", "Dept A")));
        assertEquals("AMBIGUOUS_PERSON_MATCH", engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Joan Garcia", ""), ambiguous, REF_DATE, PROJ_END).reasonCode());

        ContractSnapshot unknown = staff("Categoria nova desconeguda", "Altres", "Acadèmic", REF_DATE.minusMonths(1), null);
        assertEquals("UNKNOWN_CATEGORY", engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Marta Rius", "UAB"), found(unknown, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).reasonCode());
    }

    @Test
    void evaluateMemberEmeritAndDateBoundaries() {
        // Boundary: endDate == referenceDate.plusMonths(12) -> darrer any (NO_OK per tots els rols)
        ContractSnapshot emeritBoundary12m = staff("Professorat emèrit laboral", "Emèrit/a", "Acadèmic", REF_DATE.minusYears(2), REF_DATE.plusMonths(12));
        for (MemberRole role : MemberRole.values()) {
            MemberValidationResult res = engine.evaluateMember(member(role, "Emèrit 12m", "UAB"), found(emeritBoundary12m, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END);
            assertEquals(EligibilityStatus.NO_OK, res.status(), "Role " + role);
            assertEquals("EMERIT_LAST_YEAR", res.reasonCode());
        }

        // Boundary: +1 dia fora del darrer any, però < projectEnd -> NO_OK IP / AVIS EI / OK ET
        ContractSnapshot emeritMid = staff("Professorat emèrit laboral", "Emèrit/a", "Acadèmic", REF_DATE.minusYears(1), REF_DATE.plusMonths(12).plusDays(1));
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.IP, "Emèrit Mid", "UAB"), found(emeritMid, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        MemberValidationResult emeritMidEi = engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Emèrit Mid", "UAB"), found(emeritMid, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.AVIS, emeritMidEi.status());
        assertEquals("EMERIT_EXPIRES_BEFORE_END", emeritMidEi.reasonCode());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Emèrit Mid", "UAB"), found(emeritMid, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        // Cobertura completa fins exactament effectiveProjectEndDate -> AVIS IP (co-IP), OK EI i ET
        ContractSnapshot emeritFull = staff("Professorat emèrit laboral", "Emèrit/a", "Acadèmic", REF_DATE, PROJ_END);
        MemberValidationResult emeritFullIp = engine.evaluateMember(member(MemberRole.IP, "Emèrit Full", "UAB"), found(emeritFull, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.AVIS, emeritFullIp.status());
        assertEquals("EMERIT_COVERS_PROJECT", emeritFullIp.reasonCode());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Emèrit Full", "UAB"), found(emeritFull, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        // Temporal: endDate == effectiveProjectEndDate -> OK; minusDays(1) -> NO_OK IP / AVIS EI
        ContractSnapshot lectorExact = staff("Professor/a lector/a ajudant doctor/a", "Contracte laboral temporal", "Acadèmic", REF_DATE.minusYears(1), PROJ_END);
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.IP, "Lector Exact", "UAB"), found(lectorExact, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());

        ContractSnapshot lectorShort = staff("Professor/a lector/a ajudant doctor/a", "Contracte laboral temporal", "Acadèmic", REF_DATE.minusYears(1), PROJ_END.minusDays(1));
        assertEquals(EligibilityStatus.NO_OK, engine.evaluateMember(member(MemberRole.IP, "Lector Short", "UAB"), found(lectorShort, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.AVIS, engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Lector Short", "UAB"), found(lectorShort, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
        assertEquals(EligibilityStatus.OK, engine.evaluateMember(member(MemberRole.EQUIPO_TRABAJO, "Lector Short", "UAB"), found(lectorShort, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END).status());
    }

    @Test
    void evaluateMemberExternalAndDiscrepancyRules() {
        ContractSnapshot historicalOnly = staff("Professor/a lector/a", "Contracte laboral temporal", "Acadèmic", LocalDate.of(2019, 1, 1), LocalDate.of(2022, 1, 1));
        PersonLookupMatch externalMatchedHistorical = new PersonLookupMatch(MatchConfidence.NIF_EXACTE, "u-hist", 55L, "Extern Ara", "url", null, List.of(), historicalOnly, List.of());
        MemberValidationResult extRes = engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Extern Ara", "CSIC"), externalMatchedHistorical, REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.EXTERN, extRes.status());
        assertEquals("EXTERNAL_ENTITY", extRes.reasonCode());

        // Discrepància: al PDF consta entitat externa però té contracte vigent UAB
        ContractSnapshot stableUab = staff("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", LocalDate.of(2015, 1, 1), null);
        MemberValidationResult discStable = engine.evaluateMember(member(MemberRole.EQUIPO_INVESTIGACION, "Dual UAB", "Hospital Vall d'Hebron"), found(stableUab, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.AVIS, discStable.status());
        assertTrue(discStable.reasonMessage().toLowerCase().contains("extern"));

        ContractSnapshot assocUab = staff("Professor/a associat/da laboral", "Contracte laboral temporal", "Acadèmic", REF_DATE.minusYears(1), null);
        MemberValidationResult discAssoc = engine.evaluateMember(member(MemberRole.IP, "Dual Assoc", "UPC"), found(assocUab, MatchConfidence.NIF_EXACTE), REF_DATE, PROJ_END);
        assertEquals(EligibilityStatus.NO_OK, discAssoc.status());

        assertFalse(engine.isExternalEntity(null));
        assertFalse(engine.isExternalEntity(""));
        assertFalse(engine.isExternalEntity("UAB"));
        assertFalse(engine.isExternalEntity("Universitat Autònoma de Barcelona"));
        assertFalse(engine.isExternalEntity("Universidad Autónoma de Barcelona"));
        assertTrue(engine.isExternalEntity("Universitat de Barcelona"));
    }

    @Test
    void evaluateProposalCalculatesEffectiveDatesSummaryAndCatalog() {
        ExtractedProposal proposalNoDuration = new ExtractedProposal(
                "sol.pdf", "PID2025-001", "TEST", "Projecte Test",
                null, null, null, "UAB", ExtractionMethod.AEI_PARSER,
                List.of(), "preview", List.of()
        );
        // Llista de membres buida -> overallStatus AVIS, durada per defecte 3 anys
        ProposalValidationResponse emptyResp = engine.evaluateProposal(proposalNoDuration, List.of(), REF_DATE, null);
        assertEquals(3, emptyResp.effectiveDurationYears());
        assertEquals(REF_DATE.plusYears(3), emptyResp.effectiveProjectEndDate());
        assertEquals(EligibilityStatus.AVIS, emptyResp.overallStatus());

        // Si el PDF té expectedEndDate i no hi ha override, usa expectedEndDate; amb override, recalcula
        ExtractedProposal proposalWithDates = new ExtractedProposal(
                "sol2.pdf", "PID2025-002", "ACR", "Títol", 4,
                LocalDate.of(2027, 1, 1), LocalDate.of(2030, 12, 31),
                "UAB", ExtractionMethod.AEI_PARSER, List.of(), "preview",
                List.of(
                        member(MemberRole.IP, "IP Bo", "UAB"),
                        member(MemberRole.EQUIPO_INVESTIGACION, "Extern Bo", "CSIC")
                )
        );
        ContractSnapshot stable = staff("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", null, null);
        List<PersonLookupMatch> matches = List.of(
                found(stable, MatchConfidence.NIF_EXACTE),
                new PersonLookupMatch(MatchConfidence.NO_TROBAT, null, null, null, null, null, List.of(), null, List.of())
        );

        ProposalValidationResponse respPdfDate = engine.evaluateProposal(proposalWithDates, matches, REF_DATE, null);
        assertEquals(LocalDate.of(2030, 12, 31), respPdfDate.effectiveProjectEndDate());
        assertEquals(EligibilityStatus.OK, respPdfDate.overallStatus());
        assertEquals(2, respPdfDate.summaryCounts().total());
        assertEquals(1, respPdfDate.summaryCounts().ok());
        assertEquals(1, respPdfDate.summaryCounts().extern());

        ProposalValidationResponse respOverride = engine.evaluateProposal(proposalWithDates, matches, REF_DATE, 3);
        assertEquals(3, respOverride.effectiveDurationYears());
        assertEquals(LocalDate.of(2030, 1, 1), respOverride.effectiveProjectEndDate());

        RuleCatalogResponse catalog = engine.buildRuleCatalog();
        assertEquals(3, catalog.defaultDurationYears());
        assertEquals(12, catalog.emeritWarningMonths());
        assertFalse(catalog.rules().isEmpty());
        assertFalse(catalog.categoryDefinitions().isEmpty());
    }
}
