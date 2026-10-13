package com.example.demo.curation.service;

import com.example.demo.curation.model.AffiliationStatus;
import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.service.AuthorAffiliationValidatorService.CandidateEvaluationContext;
import com.example.demo.curation.util.UabAffiliationCatalog;
import com.example.demo.curation.util.UabAffiliationCatalog.AffiliationMatch;
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
}
