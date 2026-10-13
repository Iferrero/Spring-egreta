package com.example.demo.curation.service;

import com.example.demo.curation.model.AffiliationStatus;
import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.service.AuthorAffiliationValidatorService.CandidateEvaluationContext;
import com.example.demo.curation.util.UabAffiliationCatalog;
import com.example.demo.curation.util.UabAffiliationCatalog.AffiliationMatch;
import com.example.demo.model.Persona;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthorAffiliationValidatorServiceTest {

    private UabAffiliationCatalog catalog;
    private AuthorAffiliationValidatorService validatorService;

    @BeforeEach
    void setUp() {
        catalog = new UabAffiliationCatalog();
        validatorService = new AuthorAffiliationValidatorService(catalog);
    }

    @Test
    @DisplayName("🟢 Obra con firma Universitat Autònoma de Barcelona y coautor conocido de EGRETA -> score >= 80, HIGH_CONFIDENCE_UAB")
    void testHighConfidenceUab_whenUabAffiliationAndKnownCoauthor_thenScoreGte80AndGreen() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-garcia-101")
                .personName("Garcia, Maria")
                .departmentId("DEPT-MED")
                .departmentName("Departament de Medicina")
                .authorOrcid("0000-0002-1825-0097")
                .doi("10.1016/j.cell.2023.01.001")
                .title("Advanced Oncology Treatment Approaches")
                .year(2023)
                .venue("Cell Oncology")
                .openAlexWorkId("https://openalex.org/W4001")
                .rawAffiliations(List.of("Universitat Autònoma de Barcelona, Bellaterra, Spain"))
                .institutionIds(List.of("https://openalex.org/I123044942"))
                .workAuthorOrcid("0000-0002-1825-0097")
                .workCoauthors(List.of("Sole, Joan", "Perez, Anna"))
                .knownCoauthors(Set.of("Sole, Joan"))
                .workTopics(List.of("Oncology", "Immunotherapy"))
                .knownTopics(Set.of("Oncology", "Internal Medicine"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertNotNull(candidate);
        assertEquals("uuid-garcia-101", candidate.getPersonUuid());
        assertEquals("10.1016/j.cell.2023.01.001", candidate.getDoi());
        assertEquals(AffiliationStatus.CONFIRMED_UAB, candidate.getAffiliationStatus());
        assertEquals(CandidateVerdict.HIGH_CONFIDENCE_UAB, candidate.getVerdict());
        assertTrue(candidate.getConfidenceScore() >= 80.0, "Score should be >= 80 but was " + candidate.getConfidenceScore());
        assertEquals(CandidateStatus.PENDING, candidate.getStatus());
        assertFalse(candidate.getReasons().isEmpty(), "Reasons should not be empty");
        assertTrue(candidate.getCoauthorSignals().contains("Sole, Joan") || candidate.getCoauthorSignals().contains("EGRETA"));
    }

    @Test
    @DisplayName("🟢 Obra con firma UAB y coautor conocido sin ORCID explícito -> score >= 80 con refuerzo institucional")
    void testHighConfidenceUab_whenUabAffiliationAndCoauthorWithoutOrcid_thenScoreGte80() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-marti-202")
                .personName("Marti, Jordi")
                .doi("10.1038/s41586-022-0001-1")
                .title("Quantum computing breakthroughs")
                .rawAffiliations(List.of("Universitat Autònoma de Barcelona"))
                .workCoauthors(List.of("Vidal, Clara"))
                .knownCoauthors(Set.of("Vidal, Clara"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertEquals(AffiliationStatus.CONFIRMED_UAB, candidate.getAffiliationStatus());
        assertEquals(CandidateVerdict.HIGH_CONFIDENCE_UAB, candidate.getVerdict());
        assertTrue(candidate.getConfidenceScore() >= 80.0);
    }

    @Test
    @DisplayName("🏥 Detección de hospital universitario vinculado: Hospital Universitari Vall d'Hebron")
    void testDetectsUabSphereHospital_vallDHebron() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-doc-001")
                .personName("Dr. Medico")
                .doi("10.1056/nejmoa2022")
                .title("Clinical study on cardiac surgery")
                .rawAffiliations(List.of("Servei de Cardiologia, Hospital Universitari Vall d'Hebron, Barcelona, Spain"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertEquals(AffiliationStatus.CONFIRMED_UAB, candidate.getAffiliationStatus());
        assertFalse(candidate.getMatchedAffiliations().isEmpty());
        assertTrue(candidate.getMatchedAffiliations().stream().anyMatch(a -> a.toLowerCase().contains("vall")));
    }

    @Test
    @DisplayName("🏥 Detección de otros hospitales vinculados: Germans Trias, Santa Creu i Sant Pau, Parc Taulí")
    void testDetectsUabSphereHospital_otherHospitals() {
        List<String> hospitalAffiliations = List.of(
                "Hospital Universitari Germans Trias i Pujol, Badalona",
                "Hospital de la Santa Creu i Sant Pau, Barcelona",
                "Hospital Universitari Parc Taulí, Sabadell"
        );

        for (String aff : hospitalAffiliations) {
            AffiliationMatch match = catalog.matchAffiliation(List.of(aff), Collections.emptyList());
            assertEquals(AffiliationStatus.CONFIRMED_UAB, match.getStatus(), "Deberia reconocer como UAB esfera: " + aff);
            assertTrue(match.isConfirmedUab());
        }
    }

    @Test
    @DisplayName("🔬 Detección de institutos de investigación consorciados / CERCA / CSIC esfera UAB")
    void testDetectsUabSphereInstitutes() {
        List<String> institutes = List.of(
                "ICMAB-CSIC, Campus de la UAB, Bellaterra",
                "Catalan Institute of Nanoscience and Nanotechnology (ICN2)",
                "Institut de Fisica d'Altes Energies (IFAE)",
                "Centre for Research in Agricultural Genomics (CRAG)",
                "Computer Vision Center (CVC), Edifici O",
                "ICTA-UAB, Institute of Environmental Science and Technology",
                "IIIA-CSIC, Artificial Intelligence Research Institute",
                "Institut de Biotecnologia i de Biomedicina (IBB)"
        );

        for (String inst : institutes) {
            AffiliationMatch match = catalog.matchAffiliation(List.of(inst), Collections.emptyList());
            assertEquals(AffiliationStatus.CONFIRMED_UAB, match.getStatus(), "Deberia reconocer instituto: " + inst);
        }
    }

    @Test
    @DisplayName("🆔 Detección por identificadores canónicos ROR y OpenAlex")
    void testDetectsUabIdentifiers_rorAndOpenAlex() {
        AffiliationMatch matchRor = catalog.matchAffiliation(
                Collections.emptyList(),
                List.of("https://ror.org/052gg0110")
        );
        assertEquals(AffiliationStatus.CONFIRMED_UAB, matchRor.getStatus());

        AffiliationMatch matchOpenAlex = catalog.matchAffiliation(
                Collections.emptyList(),
                List.of("https://openalex.org/I123044942")
        );
        assertEquals(AffiliationStatus.CONFIRMED_UAB, matchOpenAlex.getStatus());
    }

    @Test
    @DisplayName("🔴 Obra con firma explícita en otra institución (Universidad Complutense de Madrid) sin coautores UAB -> score < 40, LIKELY_FALSE_POSITIVE")
    void testLikelyFalsePositive_whenExplicitOtherInstitution_withoutCoauthors() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-garcia-101")
                .personName("Garcia, Maria")
                .doi("10.1016/j.madrid.2021.05")
                .title("Estudios de Historia Medieval Castellana")
                .rawAffiliations(List.of("Universidad Complutense de Madrid, Facultad de Geografia e Historia"))
                .workCoauthors(List.of("Alvarez, Carlos", "Gutierrez, Luis"))
                .knownCoauthors(Set.of("Sole, Joan")) // Disjoint coauthors
                .workTopics(List.of("Medieval History"))
                .knownTopics(Set.of("Oncology", "Cell Biology")) // Disjoint topics
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertNotNull(candidate);
        assertEquals(AffiliationStatus.OTHER_INSTITUTION, candidate.getAffiliationStatus());
        assertEquals(CandidateVerdict.LIKELY_FALSE_POSITIVE, candidate.getVerdict());
        assertTrue(candidate.getConfidenceScore() < 40.0, "Score should be < 40 but was " + candidate.getConfidenceScore());
        assertEquals(CandidateStatus.PENDING, candidate.getStatus());
        assertTrue(candidate.getReasons().stream().anyMatch(r -> r.contains("-50") || r.toLowerCase().contains("ajena")));
    }

    @Test
    @DisplayName("🔴 Desambiguación UAB vs UAM: Universidad Autónoma de Madrid es ajena a la UAB")
    void testUabVsUamDisambiguation() {
        AffiliationMatch match = catalog.matchAffiliation(
                List.of("Universidad Autónoma de Madrid, Cantoblanco, Madrid"),
                Collections.emptyList()
        );
        assertEquals(AffiliationStatus.OTHER_INSTITUTION, match.getStatus());
        assertTrue(match.isOtherInstitution());
    }

    @Test
    @DisplayName("🟡 Obra sin afiliación declarada pero con ORCID coincidente -> REQUIRES_REVIEW")
    void testRequiresReview_whenAmbiguousAffiliationWithMatchingOrcid() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-garcia-101")
                .personName("Garcia, Maria")
                .authorOrcid("0000-0002-1825-0097")
                .doi("10.1038/s41598-020-00001-x")
                .title("Interdisciplinary Analysis of Complex Systems")
                .rawAffiliations(Collections.emptyList())
                .institutionIds(Collections.emptyList())
                .workAuthorOrcid("0000-0002-1825-0097")
                .workCoauthors(Collections.emptyList())
                .knownCoauthors(Collections.emptySet())
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertNotNull(candidate);
        assertEquals(AffiliationStatus.AMBIGUOUS_OR_MISSING, candidate.getAffiliationStatus());
        assertEquals(CandidateVerdict.REQUIRES_REVIEW, candidate.getVerdict());
        assertTrue(candidate.getConfidenceScore() >= 40.0 && candidate.getConfidenceScore() < 80.0,
                "Score should be in [40, 80) but was " + candidate.getConfidenceScore());
        assertEquals(CandidateStatus.PENDING, candidate.getStatus());
        assertTrue(candidate.getReasons().stream().anyMatch(r -> r.contains("+20") || r.toLowerCase().contains("orcid")));
    }

    @Test
    @DisplayName("🟡 Obra con firma UAB pero sin coautores ni ORCID -> REQUIRES_REVIEW (score 40)")
    void testRequiresReview_whenUabAffiliationOnly_withoutCoauthorOrOrcid() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-alone-505")
                .personName("Lopez, Pedro")
                .doi("10.1007/s00259-021-05555-5")
                .title("Monographic review on local biomarkers")
                .rawAffiliations(List.of("Universitat Autònoma de Barcelona"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertEquals(AffiliationStatus.CONFIRMED_UAB, candidate.getAffiliationStatus());
        assertEquals(CandidateVerdict.REQUIRES_REVIEW, candidate.getVerdict());
        assertEquals(40.0, candidate.getConfidenceScore());
    }

    @Test
    @DisplayName("🔧 Normalización de DOI al persistir candidato")
    void testDoiNormalizationInCandidate() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-norm-01")
                .personName("Test Author")
                .doi("  https://doi.org/10.1016/J.CELL.2023.01.001  ")
                .title("Sample Paper Title")
                .rawAffiliations(List.of("Universitat Autonoma de Barcelona"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertEquals("10.1016/j.cell.2023.01.001", candidate.getDoi());
    }

    @Test
    @DisplayName("Catalog: Casos bordes de texto nulo, vacío y variantes textuales")
    void testCatalogEdgeCases() {
        AffiliationMatch matchEmpty = catalog.matchAffiliation(null, null);
        assertEquals(AffiliationStatus.AMBIGUOUS_OR_MISSING, matchEmpty.getStatus());

        AffiliationMatch matchBlank = catalog.matchAffiliation(List.of("   ", ""), Collections.emptyList());
        assertEquals(AffiliationStatus.AMBIGUOUS_OR_MISSING, matchBlank.getStatus());

        AffiliationMatch matchPostalCode = catalog.matchAffiliation(List.of("Campus Universitari, 08193, Spain"), Collections.emptyList());
        assertEquals(AffiliationStatus.CONFIRMED_UAB, matchPostalCode.getStatus());

        AffiliationMatch matchBellaterra = catalog.matchAffiliation(List.of("Edifici Eureka, Bellaterra"), Collections.emptyList());
        assertEquals(AffiliationStatus.CONFIRMED_UAB, matchBellaterra.getStatus());
    }

    @Test
    @DisplayName("🛡️ Stanford Artificial Intelligence no debe coincidir erróneamente con IIIA/UAB")
    void testNoFalseMatchOnGenericDiscipline_stanfordArtificialIntelligence() {
        AffiliationMatch match = catalog.matchAffiliation(
                List.of("Department of Artificial Intelligence, Stanford University, Stanford, CA, USA"),
                Collections.emptyList()
        );
        assertEquals(AffiliationStatus.OTHER_INSTITUTION, match.getStatus());
        assertTrue(match.isOtherInstitution());
        assertFalse(match.isConfirmedUab());
    }

    @Test
    @DisplayName("🛡️ Nombres compuestos compartidos sin apellido común (José María García vs José María Rodríguez) no deben coincidir")
    void testNoFalseMatchOnCompoundFirstNames() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-garcia")
                .personName("Garcia, Jose Maria")
                .doi("10.1000/182")
                .title("Sample Research Paper")
                .rawAffiliations(Collections.emptyList())
                .workCoauthors(List.of("Jose Maria Rodriguez"))
                .knownCoauthors(Set.of("Jose Maria Garcia"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        // Sin coincidencia de coautor -> 0 puntos de coautoría
        assertFalse(candidate.getCoauthorSignals().contains("Rodriguez"));
        assertEquals(0.0, candidate.getConfidenceScore());
    }

    @Test
    @DisplayName("👤 Sobrecarga evaluateCandidate(Persona author, ...) mapea campos correctamente")
    void testEvaluateCandidateWithPersonaObject() {
        Persona persona = new Persona();
        persona.setUuid("person-uuid-777");
        Persona.Name name = new Persona.Name();
        name.setFirstName("Laura");
        name.setLastName("Gomez");
        persona.setName(name);
        persona.setOrcid("0000-0003-4567-8901");

        Persona.StaffOrganizationAssociation assoc = new Persona.StaffOrganizationAssociation();
        Persona.OrgDetail org = new Persona.OrgDetail();
        org.setUuid("org-dept-chem");
        Persona.LocalizedName locName = new Persona.LocalizedName();
        Persona.TextValue tv = new Persona.TextValue();
        tv.setLocale("ca_ES");
        tv.setValue("Departament de Química");
        locName.setText(List.of(tv));
        org.setName(locName);
        assoc.setOrganization(org);
        persona.setStaffOrganizationAssociations(List.of(assoc));

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(
                persona,
                "10.1021/acs.chemrev.2023",
                "Advanced Chemical Catalysis",
                2023,
                "Chemical Reviews",
                "https://openalex.org/W5555",
                List.of("Universitat Autònoma de Barcelona"),
                Collections.emptyList(),
                "0000-0003-4567-8901",
                List.of("Perez, Joan"),
                Set.of("Perez, Joan"),
                List.of("Catalysis"),
                Set.of("Catalysis", "Organic Chemistry")
        );

        assertNotNull(candidate);
        assertEquals("person-uuid-777", candidate.getPersonUuid());
        assertEquals("Gomez, Laura", candidate.getPersonName());
        assertEquals("org-dept-chem", candidate.getDepartmentId());
        assertEquals("Departament de Química", candidate.getDepartmentName());
        assertEquals("10.1021/acs.chemrev.2023", candidate.getDoi());
        assertEquals(CandidateVerdict.HIGH_CONFIDENCE_UAB, candidate.getVerdict());
        assertEquals(CandidateStatus.PENDING, candidate.getStatus());
    }

    @Test
    @DisplayName("📉 Penalización temática aislada: UAB (+40) con disciplina disjunta (-20) resulta en 20.0 puntos (ROJO)")
    void testStandaloneTopicPenalty_whenUabAffiliationWithDisjointTopic() {
        CandidateEvaluationContext context = CandidateEvaluationContext.builder()
                .personUuid("uuid-topic-pen")
                .personName("Roca, Mireia")
                .doi("10.1016/j.disjoint.2023")
                .title("Disjoint Topic Investigation")
                .rawAffiliations(List.of("Universitat Autònoma de Barcelona"))
                .workTopics(List.of("Theoretical Astrophysics"))
                .knownTopics(Set.of("Medieval Catalan Literature"))
                .build();

        PersonPublicationCandidate candidate = validatorService.evaluateCandidate(context);

        assertEquals(AffiliationStatus.CONFIRMED_UAB, candidate.getAffiliationStatus());
        assertEquals(20.0, candidate.getConfidenceScore(), "40 UAB - 20 disciplina disjunta debe ser exactamente 20.0");
        assertEquals(CandidateVerdict.LIKELY_FALSE_POSITIVE, candidate.getVerdict());
    }

    @Test
    @DisplayName("🏙️ Cadenas ambiguas o geográficas (Campus Nord, Barcelona Spain) no incurren en penalización de otra institución")
    void testAmbiguousGeographicStringDoesNotTriggerOtherInstitution() {
        AffiliationMatch matchCampusNord = catalog.matchAffiliation(List.of("Campus Nord"), Collections.emptyList());
        assertEquals(AffiliationStatus.AMBIGUOUS_OR_MISSING, matchCampusNord.getStatus());

        AffiliationMatch matchCity = catalog.matchAffiliation(List.of("Barcelona, Spain"), Collections.emptyList());
        assertEquals(AffiliationStatus.AMBIGUOUS_OR_MISSING, matchCity.getStatus());
    }
}
