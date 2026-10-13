package com.example.demo.curation.service;

import com.example.demo.curation.model.BatchScanProgressDto;
import com.example.demo.curation.model.BatchScanProgressDto.BatchScanStatus;
import com.example.demo.curation.model.BatchScanRequest;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.model.Persona;
import com.example.demo.repository.PersonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchReconciliationServiceTest {

    private OpenAlexReconciliationService openAlexService;
    private PersonaRepository personaRepository;
    private BatchReconciliationService batchService;

    @BeforeEach
    void setUp() {
        openAlexService = mock(OpenAlexReconciliationService.class);
        personaRepository = mock(PersonaRepository.class);
        // Using synchronous/single thread executor with 0ms throttle for fast tests
        batchService = new BatchReconciliationService(
                openAlexService,
                personaRepository,
                Executors.newSingleThreadExecutor(),
                0L
        );
    }

    private Persona createPersona(String uuid, String firstName, String lastName, String deptUuid) {
        Persona persona = new Persona();
        persona.setUuid(uuid);
        Persona.Name name = new Persona.Name();
        name.setFirstName(firstName);
        name.setLastName(lastName);
        persona.setName(name);

        Persona.StaffOrganizationAssociation assoc = new Persona.StaffOrganizationAssociation();
        Persona.OrgDetail org = new Persona.OrgDetail();
        org.setUuid(deptUuid);
        assoc.setOrganization(org);
        persona.setStaffOrganizationAssociations(List.of(assoc));
        return persona;
    }

    private PersonPublicationCandidate createCandidate(String personUuid, String doi, CandidateVerdict verdict) {
        PersonPublicationCandidate candidate = new PersonPublicationCandidate();
        candidate.setPersonUuid(personUuid);
        candidate.setDoi(doi);
        candidate.setVerdict(verdict);
        return candidate;
    }

    @Test
    @DisplayName("🧪 launchBatchScan: departamento con 3 investigadores actualiza progreso y semáforos verde/amarillo/rojo")
    void testLaunchBatchScan_departmentWithThreeAuthors_tracksProgressAndCandidateVerdicts() throws Exception {
        // Arrange: 3 researchers in department "dept-biomedicina"
        Persona p1 = createPersona("p-001", "Joan", "Garcia", "dept-biomedicina");
        Persona p2 = createPersona("p-002", "Anna", "Sole", "dept-biomedicina");
        Persona p3 = createPersona("p-003", "Carles", "Vidal", "dept-biomedicina");

        when(personaRepository.findByOrganizationUuid("dept-biomedicina")).thenReturn(List.of(p1, p2, p3));

        // p1: 1 green, 1 yellow
        PersonPublicationCandidate c1Green = createCandidate("p-001", "10.1016/j.cell.001", CandidateVerdict.HIGH_CONFIDENCE_UAB);
        PersonPublicationCandidate c1Yellow = createCandidate("p-001", "10.1016/j.cell.002", CandidateVerdict.REQUIRES_REVIEW);
        when(openAlexService.scanPerson("p-001", false)).thenReturn(List.of(c1Green, c1Yellow));

        // p2: 1 red
        PersonPublicationCandidate c2Red = createCandidate("p-002", "10.1088/astro.001", CandidateVerdict.LIKELY_FALSE_POSITIVE);
        when(openAlexService.scanPerson("p-002", false)).thenReturn(List.of(c2Red));

        // p3: 1 green
        PersonPublicationCandidate c3Green = createCandidate("p-003", "10.1016/j.nature.001", CandidateVerdict.HIGH_CONFIDENCE_UAB);
        when(openAlexService.scanPerson("p-003", false)).thenReturn(List.of(c3Green));

        BatchScanRequest request = new BatchScanRequest();
        request.setDepartmentId("dept-biomedicina");
        request.setStrictOrcidOnly(false);

        // Act
        String jobId = batchService.launchBatchScan(request);

        // Assert
        assertNotNull(jobId, "jobId must not be null");
        assertFalse(jobId.isBlank(), "jobId must not be blank");

        // Wait up to 3 seconds for async completion
        BatchScanProgressDto progress = null;
        for (int i = 0; i < 30; i++) {
            progress = batchService.getBatchProgress(jobId);
            if (progress != null && progress.getStatus() == BatchScanStatus.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(progress);
        assertEquals(BatchScanStatus.COMPLETED, progress.getStatus());
        assertEquals(3, progress.getTotalAuthors());
        assertEquals(3, progress.getProcessedAuthors());
        assertEquals(3, progress.getProcessedCount());
        assertEquals(0, progress.getFailedAuthors());

        // Verdicts: 2 green, 1 yellow, 1 red
        assertEquals(2, progress.getGreenCandidatesCount());
        assertEquals(2, progress.getGreenCount());
        assertEquals(1, progress.getYellowCandidatesCount());
        assertEquals(1, progress.getYellowCount());
        assertEquals(1, progress.getRedCandidatesCount());
        assertEquals(1, progress.getRedCount());

        assertTrue(progress.getErrors().isEmpty(), "Errors list should be empty on successful run");
        assertNotNull(progress.getStartedAt());
        assertNotNull(progress.getFinishedAt());

        verify(openAlexService).scanPerson("p-001", false);
        verify(openAlexService).scanPerson("p-002", false);
        verify(openAlexService).scanPerson("p-003", false);
    }

    @Test
    @DisplayName("🛡️ Tolerancia a fallos: autor lanza excepción, los restantes continúan y se registra incidencia")
    void testLaunchBatchScan_faultTolerance_authorThrowsException_continuesWithRemainingAuthors() throws Exception {
        BatchScanRequest request = new BatchScanRequest();
        request.setPersonUuids(List.of("p-001", "p-002", "p-003"));
        request.setStrictOrcidOnly(false);

        // p-001: succeeds with 1 green
        PersonPublicationCandidate c1Green = createCandidate("p-001", "10.1016/j.cell.001", CandidateVerdict.HIGH_CONFIDENCE_UAB);
        when(openAlexService.scanPerson("p-001", false)).thenReturn(List.of(c1Green));

        // p-002: throws timeout exception
        when(openAlexService.scanPerson("p-002", false))
                .thenThrow(new RuntimeException("OpenAlex 504 Gateway Timeout"));

        // p-003: succeeds with 1 yellow
        PersonPublicationCandidate c3Yellow = createCandidate("p-003", "10.1016/j.nature.001", CandidateVerdict.REQUIRES_REVIEW);
        when(openAlexService.scanPerson("p-003", false)).thenReturn(List.of(c3Yellow));

        // Act
        String jobId = batchService.launchBatchScan(request);
        assertNotNull(jobId);

        // Wait for completion
        BatchScanProgressDto progress = null;
        for (int i = 0; i < 30; i++) {
            progress = batchService.getBatchProgress(jobId);
            if (progress != null && progress.getStatus() == BatchScanStatus.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(progress);
        assertEquals(BatchScanStatus.COMPLETED, progress.getStatus(), "Batch job should complete successfully despite 1 failed author");
        assertEquals(3, progress.getTotalAuthors());
        assertEquals(3, progress.getProcessedAuthors());
        assertEquals(1, progress.getFailedAuthors());

        // Verdicts
        assertEquals(1, progress.getGreenCandidatesCount());
        assertEquals(1, progress.getYellowCandidatesCount());
        assertEquals(0, progress.getRedCandidatesCount());

        // Errors
        assertEquals(1, progress.getErrors().size());
        assertTrue(progress.getErrors().get(0).contains("OpenAlex 504 Gateway Timeout"));
        assertTrue(progress.getErrors().get(0).contains("p-002"));

        assertNotNull(progress.getFinishedAt());

        // Verify author 3 was called even after author 2 threw an exception
        verify(openAlexService).scanPerson("p-001", false);
        verify(openAlexService).scanPerson("p-002", false);
        verify(openAlexService).scanPerson("p-003", false);
    }

    @Test
    @DisplayName("🔍 getBatchProgress con jobId inexistente devuelve null")
    void testGetBatchProgress_nonExistentJobId_returnsNull() {
        BatchScanProgressDto progress = batchService.getBatchProgress("non-existent-" + UUID.randomUUID());
        assertNull(progress, "Non-existent job ID should return null");
    }

    @Test
    @DisplayName("🛑 cancelJob cancela la ejecución del lote")
    void testCancelJob_cancelsExecution() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        // Slow mock that blocks until cancelled or released
        when(openAlexService.scanPerson(eq("p-slow"), anyBoolean())).thenAnswer(inv -> {
            latch.countDown();
            Thread.sleep(2000);
            return Collections.emptyList();
        });

        BatchScanRequest request = new BatchScanRequest();
        request.setPersonUuids(List.of("p-slow", "p-never"));

        String jobId = batchService.launchBatchScan(request);

        // Wait until task starts
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        // Cancel job
        batchService.cancelJob(jobId);

        // Check status
        BatchScanProgressDto progress = batchService.getBatchProgress(jobId);
        assertNotNull(progress);
        assertEquals(BatchScanStatus.CANCELLED, progress.getStatus());

        // Verify p-never was never called
        verify(openAlexService, never()).scanPerson(eq("p-never"), anyBoolean());
    }

    @Test
    @DisplayName("⚠️ launchBatchScan valida la petición y lanza IllegalArgumentException si no hay autores")
    void testLaunchBatchScan_validationErrors() {
        // Null request
        assertThrows(IllegalArgumentException.class, () -> batchService.launchBatchScan(null));

        // Empty request without department or uuids
        BatchScanRequest emptyRequest = new BatchScanRequest();
        assertThrows(IllegalArgumentException.class, () -> batchService.launchBatchScan(emptyRequest));

        // Unknown department returning empty author list
        when(personaRepository.findByOrganizationUuid("unknown-dept")).thenReturn(Collections.emptyList());
        BatchScanRequest deptRequest = new BatchScanRequest();
        deptRequest.setDepartmentId("unknown-dept");
        assertThrows(IllegalArgumentException.class, () -> batchService.launchBatchScan(deptRequest));
    }

    @Test
    @DisplayName("🎯 launchBatchScan con strictOrcidOnly propaga el flag al servicio de conciliación")
    void testLaunchBatchScan_propagatesStrictOrcidOnlyFlag() throws Exception {
        BatchScanRequest request = new BatchScanRequest();
        request.setPersonUuids(List.of("p-001"));
        request.setStrictOrcidOnly(true);

        when(openAlexService.scanPerson("p-001", true)).thenReturn(Collections.emptyList());

        String jobId = batchService.launchBatchScan(request);

        BatchScanProgressDto progress = null;
        for (int i = 0; i < 30; i++) {
            progress = batchService.getBatchProgress(jobId);
            if (progress != null && progress.getStatus() == BatchScanStatus.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(progress);
        assertEquals(BatchScanStatus.COMPLETED, progress.getStatus());
        verify(openAlexService).scanPerson("p-001", true);
    }
}
