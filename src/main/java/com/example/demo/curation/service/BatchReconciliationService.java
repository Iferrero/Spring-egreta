package com.example.demo.curation.service;

import com.example.demo.curation.model.BatchScanProgressDto;
import com.example.demo.curation.model.BatchScanProgressDto.BatchScanStatus;
import com.example.demo.curation.model.BatchScanRequest;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.model.Persona;
import com.example.demo.repository.PersonaRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Coordinador asíncrono para la conciliación de publicaciones por lotes (a nivel de departamento, facultad o lista de autores).
 * Gestiona el ciclo de vida del trabajo en segundo plano, la tolerancia a fallos por autor,
 * el estrangulamiento de peticiones (throttling) frente a la API de OpenAlex y el seguimiento de métricas.
 */
@Service
public class BatchReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BatchReconciliationService.class);

    private final OpenAlexReconciliationService openAlexService;
    private final PersonaRepository personaRepository;
    private final ExecutorService executorService;
    private final long throttleDelayMs;
    private final boolean shutdownExecutorOnExit;

    private final Map<String, JobExecutionState> activeJobs = new ConcurrentHashMap<>();

    @Autowired
    public BatchReconciliationService(
            OpenAlexReconciliationService openAlexService,
            PersonaRepository personaRepository,
            @Autowired(required = false) ExecutorService executorService,
            @Value("${curation.openalex.throttle-delay-ms:200}") long throttleDelayMs) {
        this.openAlexService = Objects.requireNonNull(openAlexService, "openAlexService must not be null");
        this.personaRepository = Objects.requireNonNull(personaRepository, "personaRepository must not be null");
        if (executorService != null) {
            this.executorService = executorService;
            this.shutdownExecutorOnExit = false;
        } else {
            this.executorService = Executors.newCachedThreadPool();
            this.shutdownExecutorOnExit = true;
        }
        this.throttleDelayMs = Math.max(0, throttleDelayMs);
    }

    public BatchReconciliationService(
            OpenAlexReconciliationService openAlexService,
            PersonaRepository personaRepository,
            ExecutorService executorService) {
        this(openAlexService, personaRepository, executorService, 0L);
    }

    public BatchReconciliationService(
            OpenAlexReconciliationService openAlexService,
            PersonaRepository personaRepository) {
        this(openAlexService, personaRepository, null, 0L);
    }

    /**
     * Inicia un trabajo de escaneo por lotes en segundo plano.
     *
     * @param request parámetros del lote (departamento, facultad, o lista explícita de autores)
     * @return identificador único del trabajo (jobId)
     * @throws IllegalArgumentException si la petición es nula o no se localizan autores para procesar
     */
    public String launchBatchScan(BatchScanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BatchScanRequest must not be null");
        }

        List<String> targetUuids = resolveTargetAuthorUuids(request);
        if (targetUuids.isEmpty()) {
            throw new IllegalArgumentException("No authors found to process for the given batch request: " + request);
        }

        String jobId = UUID.randomUUID().toString();
        JobExecutionState jobState = new JobExecutionState(jobId, targetUuids.size());
        activeJobs.put(jobId, jobState);

        log.info("Launching batch scan job {} for {} authors (strictOrcidOnly={})",
                jobId, targetUuids.size(), request.isStrictOrcidOnly());

        Future<?> future = executorService.submit(() -> executeBatch(jobId, jobState, targetUuids, request.isStrictOrcidOnly()));
        jobState.setFuture(future);

        return jobId;
    }

    /**
     * Consulta el progreso actual de un trabajo por lotes.
     *
     * @param jobId identificador del trabajo
     * @return DTO con el snapshot de progreso o null si el trabajo no existe
     */
    public BatchScanProgressDto getBatchProgress(String jobId) {
        if (jobId == null) {
            return null;
        }
        JobExecutionState state = activeJobs.get(jobId);
        return state != null ? state.toDto() : null;
    }

    /**
     * Cancela la ejecución de un trabajo activo.
     *
     * @param jobId identificador del trabajo a cancelar
     */
    public void cancelJob(String jobId) {
        if (jobId == null) {
            return;
        }
        JobExecutionState state = activeJobs.get(jobId);
        if (state != null) {
            state.cancel();
        }
    }

    private List<String> resolveTargetAuthorUuids(BatchScanRequest request) {
        List<String> targetUuids = new ArrayList<>();

        if (request.getPersonUuids() != null && !request.getPersonUuids().isEmpty()) {
            targetUuids = request.getPersonUuids().stream()
                    .filter(u -> u != null && !u.isBlank())
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());
        } else if (request.getDepartmentId() != null && !request.getDepartmentId().isBlank()) {
            List<Persona> personas = personaRepository.findByOrganizationUuid(request.getDepartmentId().trim());
            if (personas != null) {
                targetUuids = personas.stream()
                        .map(p -> p.getUuid() != null ? p.getUuid() : p.getId())
                        .filter(u -> u != null && !u.isBlank())
                        .map(String::trim)
                        .distinct()
                        .collect(Collectors.toList());
            }
        } else if (request.getFacultyId() != null && !request.getFacultyId().isBlank()) {
            List<Persona> personas = personaRepository.findByOrganizationUuid(request.getFacultyId().trim());
            if (personas != null) {
                targetUuids = personas.stream()
                        .map(p -> p.getUuid() != null ? p.getUuid() : p.getId())
                        .filter(u -> u != null && !u.isBlank())
                        .map(String::trim)
                        .distinct()
                        .collect(Collectors.toList());
            }
        }

        return targetUuids;
    }

    private void executeBatch(String jobId, JobExecutionState jobState, List<String> authorUuids, boolean strictOrcidOnly) {
        log.info("Started batch job execution {} for {} authors", jobId, authorUuids.size());

        try {
            for (String authorUuid : authorUuids) {
                if (jobState.isCancelled() || Thread.currentThread().isInterrupted()) {
                    log.info("Batch job {} received cancellation signal, stopping execution.", jobId);
                    jobState.markCancelled();
                    return;
                }

                try {
                    log.debug("Job {}: scanning author {}", jobId, authorUuid);
                    List<PersonPublicationCandidate> candidates = openAlexService.scanPerson(authorUuid, strictOrcidOnly);

                    if (candidates != null) {
                        for (PersonPublicationCandidate candidate : candidates) {
                            if (candidate != null && candidate.getVerdict() != null) {
                                switch (candidate.getVerdict()) {
                                    case HIGH_CONFIDENCE_UAB -> jobState.incrementGreen();
                                    case REQUIRES_REVIEW -> jobState.incrementYellow();
                                    case LIKELY_FALSE_POSITIVE -> jobState.incrementRed();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException || (e.getCause() instanceof InterruptedException) || jobState.isCancelled() || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        log.info("Batch job {} interrupted or cancelled during author scan.", jobId);
                        jobState.markCancelled();
                        return;
                    }
                    log.error("Job {}: error processing author {}: {}", jobId, authorUuid, e.getMessage(), e);
                    jobState.incrementFailed();
                    jobState.addError("Author " + authorUuid + ": " + e.getMessage());
                } finally {
                    jobState.incrementProcessed();
                }

                if (throttleDelayMs > 0) {
                    try {
                        Thread.sleep(throttleDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.info("Batch job {} interrupted during throttle delay.", jobId);
                        jobState.markCancelled();
                        return;
                    }
                }
            }

            if (!jobState.isCancelled()) {
                if (jobState.getFailedAuthors() == authorUuids.size() && !authorUuids.isEmpty()) {
                    jobState.setStatus(BatchScanStatus.FAILED);
                } else {
                    jobState.setStatus(BatchScanStatus.COMPLETED);
                }
                jobState.setFinishedAt(Instant.now());
                log.info("Batch job {} completed: processed={}, failed={}, green={}, yellow={}, red={}",
                        jobId, jobState.getProcessedAuthors(), jobState.getFailedAuthors(),
                        jobState.getGreenCandidatesCount(), jobState.getYellowCandidatesCount(),
                        jobState.getRedCandidatesCount());
            }
        } catch (Exception fatal) {
            log.error("Fatal error running batch job {}: {}", jobId, fatal.getMessage(), fatal);
            jobState.setStatus(BatchScanStatus.FAILED);
            jobState.addError("Fatal error: " + fatal.getMessage());
            jobState.setFinishedAt(Instant.now());
        }
    }

    @PreDestroy
    public void destroy() {
        if (shutdownExecutorOnExit && executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    private static class JobExecutionState {
        private final String jobId;
        private final int totalAuthors;
        private final AtomicInteger processedAuthors = new AtomicInteger(0);
        private final AtomicInteger failedAuthors = new AtomicInteger(0);
        private final AtomicInteger greenCandidatesCount = new AtomicInteger(0);
        private final AtomicInteger yellowCandidatesCount = new AtomicInteger(0);
        private final AtomicInteger redCandidatesCount = new AtomicInteger(0);
        private final Instant startedAt = Instant.now();
        private volatile Instant finishedAt;
        private volatile BatchScanStatus status = BatchScanStatus.RUNNING;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private volatile Future<?> future;

        JobExecutionState(String jobId, int totalAuthors) {
            this.jobId = jobId;
            this.totalAuthors = totalAuthors;
        }

        void setFuture(Future<?> future) {
            this.future = future;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        void cancel() {
            if (status == BatchScanStatus.RUNNING) {
                cancelled.set(true);
                status = BatchScanStatus.CANCELLED;
                finishedAt = Instant.now();
                if (future != null) {
                    future.cancel(true);
                }
            }
        }

        void markCancelled() {
            cancelled.set(true);
            status = BatchScanStatus.CANCELLED;
            finishedAt = Instant.now();
        }

        void incrementGreen() {
            greenCandidatesCount.incrementAndGet();
        }

        void incrementYellow() {
            yellowCandidatesCount.incrementAndGet();
        }

        void incrementRed() {
            redCandidatesCount.incrementAndGet();
        }

        void incrementProcessed() {
            processedAuthors.incrementAndGet();
        }

        void incrementFailed() {
            failedAuthors.incrementAndGet();
        }

        void addError(String error) {
            errors.add(error);
        }

        int getProcessedAuthors() {
            return processedAuthors.get();
        }

        int getFailedAuthors() {
            return failedAuthors.get();
        }

        int getGreenCandidatesCount() {
            return greenCandidatesCount.get();
        }

        int getYellowCandidatesCount() {
            return yellowCandidatesCount.get();
        }

        int getRedCandidatesCount() {
            return redCandidatesCount.get();
        }

        BatchScanStatus getStatus() {
            return status;
        }

        void setStatus(BatchScanStatus status) {
            this.status = status;
        }

        void setFinishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
        }

        BatchScanProgressDto toDto() {
            return new BatchScanProgressDto(
                    jobId,
                    status,
                    totalAuthors,
                    processedAuthors.get(),
                    failedAuthors.get(),
                    greenCandidatesCount.get(),
                    yellowCandidatesCount.get(),
                    redCandidatesCount.get(),
                    startedAt,
                    finishedAt,
                    new ArrayList<>(errors)
            );
        }
    }
}
