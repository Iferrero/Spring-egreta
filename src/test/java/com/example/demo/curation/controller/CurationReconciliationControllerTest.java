package com.example.demo.curation.controller;

import com.example.demo.curation.model.*;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.curation.service.BatchReconciliationService;
import com.example.demo.curation.service.OpenAlexReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CurationReconciliationControllerTest {

    @Mock
    private OpenAlexReconciliationService openAlexService;

    @Mock
    private BatchReconciliationService batchService;

    @Mock
    private PersonPublicationCandidateRepository candidateRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        CurationReconciliationController controller = new CurationReconciliationController(
                openAlexService,
                batchService,
                candidateRepository
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private PersonPublicationCandidate createTestCandidate(String id, String personUuid, String doi, CandidateVerdict verdict, CandidateStatus status) {
        PersonPublicationCandidate candidate = new PersonPublicationCandidate();
        candidate.setId(id);
        candidate.setPersonUuid(personUuid);
        candidate.setPersonName("Dr. Joan Garcia");
        candidate.setDepartmentId("dept-biomed");
        candidate.setDepartmentName("Departament de Biomedicina");
        candidate.setDoi(doi);
        candidate.setTitle("Study of Cell Signaling in UAB Labs");
        candidate.setYear(2023);
        candidate.setVenue("Nature Cell Biology");
        candidate.setOpenAlexWorkId("https://openalex.org/W123456");
        candidate.setAffiliationStatus(AffiliationStatus.CONFIRMED_UAB);
        candidate.setMatchedAffiliations(List.of("Universitat Autonoma de Barcelona"));
        candidate.setVerdict(verdict);
        candidate.setConfidenceScore(0.95);
        candidate.setStatus(status);
        return candidate;
    }

    @Test
    @DisplayName("POST /api/curation/authors/{personUuid}/scan ejecuta escaneo y devuelve 200 con candidatas")
    void testScanAuthor_returnsCandidates() throws Exception {
        String personUuid = "person-123";
        PersonPublicationCandidate c1 = createTestCandidate("cand-1", personUuid, "10.1000/1", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.PENDING);
        when(openAlexService.scanPerson(personUuid, false)).thenReturn(List.of(c1));

        mockMvc.perform(post("/api/curation/authors/{personUuid}/scan", personUuid)
                        .param("strictOrcidOnly", "false")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("cand-1"))
                .andExpect(jsonPath("$[0].doi").value("10.1000/1"))
                .andExpect(jsonPath("$[0].verdict").value("HIGH_CONFIDENCE_UAB"));

        verify(openAlexService).scanPerson(personUuid, false);
    }

    @Test
    @DisplayName("GET /api/curation/authors/{personUuid}/candidates devuelve candidatos filtrados o todos")
    void testGetCandidates_returnsList() throws Exception {
        String personUuid = "person-123";
        PersonPublicationCandidate c1 = createTestCandidate("cand-1", personUuid, "10.1000/1", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.PENDING);

        // Sin filtro de estado ni veredicto
        when(candidateRepository.findByPersonUuid(personUuid)).thenReturn(List.of(c1));

        mockMvc.perform(get("/api/curation/authors/{personUuid}/candidates", personUuid)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("cand-1"));

        // Con filtro de estado
        when(candidateRepository.findByPersonUuidAndStatus(personUuid, CandidateStatus.APPROVED_IMPORT))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/curation/authors/{personUuid}/candidates", personUuid)
                        .param("status", "APPROVED_IMPORT")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        // Con filtro de veredicto
        when(candidateRepository.findByPersonUuidAndVerdict(personUuid, CandidateVerdict.HIGH_CONFIDENCE_UAB))
                .thenReturn(List.of(c1));

        mockMvc.perform(get("/api/curation/authors/{personUuid}/candidates", personUuid)
                        .param("verdict", "HIGH_CONFIDENCE_UAB")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].verdict").value("HIGH_CONFIDENCE_UAB"));

        // Con filtro de estado y veredicto combinados
        when(candidateRepository.findByPersonUuidAndStatusAndVerdict(personUuid, CandidateStatus.PENDING, CandidateVerdict.HIGH_CONFIDENCE_UAB))
                .thenReturn(List.of(c1));

        mockMvc.perform(get("/api/curation/authors/{personUuid}/candidates", personUuid)
                        .param("status", "PENDING")
                        .param("verdict", "HIGH_CONFIDENCE_UAB")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("PUT /api/curation/candidates/{candidateId}/status actualiza decisión y revisor")
    void testUpdateCandidateStatus_success() throws Exception {
        String candidateId = "cand-1";
        PersonPublicationCandidate candidate = createTestCandidate(candidateId, "p-1", "10.1000/1", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.PENDING);

        when(candidateRepository.findById(candidateId)).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(PersonPublicationCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        CandidateStatusUpdateRequest request = new CandidateStatusUpdateRequest(
                CandidateStatus.APPROVED_IMPORT, "curator_user", "Valid publication from UAB team"
        );

        mockMvc.perform(put("/api/curation/candidates/{candidateId}/status", candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cand-1"))
                .andExpect(jsonPath("$.status").value("APPROVED_IMPORT"))
                .andExpect(jsonPath("$.reviewedBy").value("curator_user"));

        verify(candidateRepository).save(argThat(c ->
                c.getStatus() == CandidateStatus.APPROVED_IMPORT &&
                "curator_user".equals(c.getReviewedBy()) &&
                c.getReviewedAt() != null
        ));
    }

    @Test
    @DisplayName("PUT /api/curation/candidates/{candidateId}/status devuelve 404 si el candidato no existe")
    void testUpdateCandidateStatus_notFound() throws Exception {
        when(candidateRepository.findById("missing-id")).thenReturn(Optional.empty());

        CandidateStatusUpdateRequest request = new CandidateStatusUpdateRequest(CandidateStatus.REJECTED_HOMONYM);

        mockMvc.perform(put("/api/curation/candidates/{candidateId}/status", "missing-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/curation/candidates/bulk-action actualiza múltiples candidatos con findAllById batch query")
    void testBulkCandidateAction_updatesAll() throws Exception {
        PersonPublicationCandidate c1 = createTestCandidate("cand-1", "p-1", "10.1000/1", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.PENDING);
        PersonPublicationCandidate c2 = createTestCandidate("cand-2", "p-1", "10.1000/2", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.PENDING);

        when(candidateRepository.findAllById(List.of("cand-1", "cand-2"))).thenReturn(List.of(c1, c2));
        when(candidateRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BulkCandidateActionRequest request = new BulkCandidateActionRequest(
                List.of("cand-1", "cand-2"), CandidateStatus.APPROVED_IMPORT, "curator_user"
        );

        mockMvc.perform(post("/api/curation/candidates/bulk-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2));

        verify(candidateRepository).findAllById(List.of("cand-1", "cand-2"));
        verify(candidateRepository, never()).findById(anyString());
        verify(candidateRepository).saveAll(argThat(list -> {
            List<PersonPublicationCandidate> items = (List<PersonPublicationCandidate>) list;
            return items.size() == 2 && items.stream().allMatch(c -> c.getStatus() == CandidateStatus.APPROVED_IMPORT);
        }));
    }

    @Test
    @DisplayName("POST /api/curation/batch-scan lanza trabajo asíncrono y devuelve jobId")
    void testLaunchBatchScan_returnsJobId() throws Exception {
        BatchScanRequest request = new BatchScanRequest("dept-biomed", false);
        when(batchService.launchBatchScan(any(BatchScanRequest.class))).thenReturn("job-uuid-999");

        mockMvc.perform(post("/api/curation/batch-scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-uuid-999"));

        verify(batchService).launchBatchScan(any(BatchScanRequest.class));
    }

    @Test
    @DisplayName("GET /api/curation/batch-scan/{jobId}/progress devuelve progreso o 404")
    void testGetBatchProgress_returnsDtoOrNotFound() throws Exception {
        String jobId = "job-uuid-999";
        BatchScanProgressDto progressDto = new BatchScanProgressDto(
                jobId,
                BatchScanProgressDto.BatchScanStatus.RUNNING,
                10,
                5,
                0,
                3,
                1,
                1,
                Instant.now(),
                null,
                List.of()
        );

        when(batchService.getBatchProgress(jobId)).thenReturn(progressDto);

        mockMvc.perform(get("/api/curation/batch-scan/{jobId}/progress", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.totalAuthors").value(10))
                .andExpect(jsonPath("$.processedAuthors").value(5))
                .andExpect(jsonPath("$.greenCandidatesCount").value(3));

        // 404 cuando no existe
        when(batchService.getBatchProgress("missing-job")).thenReturn(null);

        mockMvc.perform(get("/api/curation/batch-scan/{jobId}/progress", "missing-job"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/curation/batch-scan/{jobId}/cancel cancela trabajo y devuelve 200")
    void testCancelBatchScan_callsService() throws Exception {
        String jobId = "job-uuid-999";

        mockMvc.perform(post("/api/curation/batch-scan/{jobId}/cancel", jobId))
                .andExpect(status().isOk());

        verify(batchService).cancelJob(jobId);
    }

    @Test
    @DisplayName("GET /api/curation/candidates/export exporta CSV con cabeceras y candidatos formateados")
    void testExportCsv_returnsCsvFileWithCorrectHeadersAndRows() throws Exception {
        PersonPublicationCandidate c1 = createTestCandidate("cand-1", "person-001", "10.1016/j.cell.2023.01", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.APPROVED_IMPORT);
        c1.setReviewedBy("analista");

        when(candidateRepository.findByDepartmentId("dept-biomed")).thenReturn(List.of(c1));

        String expectedHeader = "\"Person Name\",\"Person UUID\",\"DOI\",\"Title\",\"Year\",\"Venue\",\"Affiliation Status\",\"Matched Affiliations\",\"Verdict\",\"Confidence Score\",\"Status\",\"Reviewed By\"";

        mockMvc.perform(get("/api/curation/candidates/export")
                        .param("departmentId", "dept-biomed"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"curation-candidates.csv\""))
                .andExpect(content().string(Matchers.containsString(expectedHeader)))
                .andExpect(content().string(Matchers.containsString("\"Dr. Joan Garcia\"")))
                .andExpect(content().string(Matchers.containsString("\"10.1016/j.cell.2023.01\"")))
                .andExpect(content().string(Matchers.containsString("\"HIGH_CONFIDENCE_UAB\"")))
                .andExpect(content().string(Matchers.containsString("\"APPROVED_IMPORT\"")));
    }

    @Test
    @DisplayName("GET /api/curation/candidates/export desinfecta inyección de fórmulas CSV (=, +, -, @)")
    void testExportCsv_sanitizesFormulaInjection() throws Exception {
        PersonPublicationCandidate c1 = createTestCandidate("cand-vuln", "person-001", "10.1016/j.cell.2023.01", CandidateVerdict.HIGH_CONFIDENCE_UAB, CandidateStatus.APPROVED_IMPORT);
        c1.setTitle("=cmd|' /C calc'!A0");
        c1.setVenue("+Injected Venue");
        c1.setPersonName("-Negative Author");
        c1.setReviewedBy("@AdminUser");

        when(candidateRepository.findByDepartmentId("dept-biomed")).thenReturn(List.of(c1));

        mockMvc.perform(get("/api/curation/candidates/export")
                        .param("departmentId", "dept-biomed"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("\"'=cmd|' /C calc'!A0\"")))
                .andExpect(content().string(Matchers.containsString("\"'+Injected Venue\"")))
                .andExpect(content().string(Matchers.containsString("\"'-Negative Author\"")))
                .andExpect(content().string(Matchers.containsString("\"'@AdminUser\"")));
    }
}
