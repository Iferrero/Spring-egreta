package com.example.demo.linkaudit.service;

import com.example.demo.linkaudit.LinkAuditConstants;
import com.example.demo.linkaudit.config.LinkAuditProperties;
import com.example.demo.linkaudit.extractor.CollectionLinkExtractor;
import com.example.demo.linkaudit.extractor.CollectionLinkExtractor.ExtractionResult;
import com.example.demo.linkaudit.model.AuditJobState;
import com.example.demo.linkaudit.model.AuditJobStatus;
import com.example.demo.linkaudit.model.AuditSummaryResponse;
import com.example.demo.linkaudit.model.CollectionAuditSummary;
import com.example.demo.linkaudit.model.ExtractedLinkRef;
import com.example.demo.linkaudit.model.FailureReason;
import com.example.demo.linkaudit.model.FieldAuditCounts;
import com.example.demo.linkaudit.model.LinkAuditIssue;
import com.example.demo.linkaudit.model.LinkCheckResult;
import com.example.demo.linkaudit.model.LinkIssuesPageResponse;
import com.example.demo.linkaudit.model.LinkSourceField;
import com.example.demo.linkaudit.model.LinkStatus;
import com.example.demo.linkaudit.repository.LinkAuditStore;
import com.example.demo.linkaudit.verifier.LinkVerifier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Coordinates asynchronous HTTP link audits across MongoDB collections with
 * single-active-job mutual exclusion, in-scan URL deduplication, Virtual Threads,
 * global/per-host concurrency limits, and logical scan-id atomic swaps.
 */
@Service
public class LinkAuditService {

    private static final Logger log = LoggerFactory.getLogger(LinkAuditService.class);
    private static final String RESEARCH_OUTPUTS_COLLECTION = "Researchoutputs";
    private static final String GLOBAL_SCOPE = "ALL";
    private static final int MAX_PAGE_SIZE = 200;

    private final LinkAuditStore linkAuditStore;
    private final CollectionLinkExtractor extractor;
    private final LinkVerifier linkVerifier;
    private final int globalConcurrency;
    private final int perHostConcurrency;
    private final ExecutorService executorService;

    private final Object jobLock = new Object();
    private final AtomicReference<AuditJobState> jobStateRef = new AtomicReference<>(idleState());
    private JobRunContext activeRun;
    private boolean shuttingDown;

    public LinkAuditService(
            LinkAuditStore linkAuditStore,
            CollectionLinkExtractor extractor,
            LinkVerifier linkVerifier,
            LinkAuditProperties properties
    ) {
        this.linkAuditStore = Objects.requireNonNull(linkAuditStore, "linkAuditStore must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.linkVerifier = Objects.requireNonNull(linkVerifier, "linkVerifier must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.globalConcurrency = Math.max(1, properties.getGlobalConcurrency());
        this.perHostConcurrency = Math.max(1, properties.getPerHostConcurrency());
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public AuditSummaryResponse getSummary() {
        List<CollectionAuditSummary> rawSummaries = linkAuditStore.findAllSummaries();
        List<CollectionAuditSummary> collections = rawSummaries == null ? List.of() : List.copyOf(rawSummaries);

        long totalLinksChecked = 0L;
        long totalBroken = 0L;
        long totalBlocked = 0L;
        Instant lastAuditedAt = null;

        for (CollectionAuditSummary summary : collections) {
            totalLinksChecked += summary.totalLinks();
            totalBroken += summary.brokenCount();
            totalBlocked += summary.blockedCount();
            if (summary.scannedAt() != null && (lastAuditedAt == null || summary.scannedAt().isAfter(lastAuditedAt))) {
                lastAuditedAt = summary.scannedAt();
            }
        }

        return new AuditSummaryResponse(
                Instant.now(),
                collections.size(),
                totalLinksChecked,
                totalBroken,
                totalBlocked,
                lastAuditedAt,
                getStatus(),
                collections
        );
    }

    public AuditJobState startScan(String collection) {
        synchronized (jobLock) {
            if (shuttingDown) {
                throw new IllegalStateException("El servei d'auditoria d'enllaços s'està aturant");
            }
            AuditJobState current = jobStateRef.get();
            if (activeRun != null
                    || current.status() == AuditJobStatus.RUNNING
                    || current.status() == AuditJobStatus.CANCELLING) {
                throw new ScanAlreadyRunningException(current);
            }

            String scope;
            List<String> targetCollections;
            if (collection != null) {
                String trimmed = collection.trim();
                if (LinkAuditConstants.isInternalCollection(trimmed) || !linkAuditStore.collectionExists(trimmed)) {
                    throw new IllegalArgumentException("Col·lecció invàlida, interna o inexistent: " + collection);
                }
                scope = trimmed;
                targetCollections = List.of(trimmed);
            } else {
                scope = GLOBAL_SCOPE;
                targetCollections = orderGlobalCollections(linkAuditStore.listBusinessCollections());
            }

            JobRunContext runContext = new JobRunContext(
                    UUID.randomUUID().toString(),
                    scope,
                    Instant.now(),
                    targetCollections.size(),
                    globalConcurrency,
                    perHostConcurrency
            );
            runContext.setCurrentCollection(targetCollections.isEmpty() ? null : targetCollections.get(0));
            AuditJobState initialState = runContext.toState(AuditJobStatus.RUNNING, null);

            Thread orchestrator = Thread.ofVirtual()
                    .name("link-audit-orchestrator-" + runContext.jobId())
                    .unstarted(() -> runJob(runContext, targetCollections));
            runContext.setOrchestratorThread(orchestrator);

            this.activeRun = runContext;
            this.jobStateRef.set(initialState);
            orchestrator.start();
            return initialState;
        }
    }

    public AuditJobState getStatus() {
        return jobStateRef.get();
    }

    public AuditJobState cancelScan() {
        synchronized (jobLock) {
            AuditJobState current = jobStateRef.get();
            if (activeRun == null
                    || (current.status() != AuditJobStatus.RUNNING && current.status() != AuditJobStatus.CANCELLING)) {
                return current;
            }
            if (current.status() == AuditJobStatus.CANCELLING) {
                return current;
            }
            activeRun.cancel();
            AuditJobState cancellingState = activeRun.toState(AuditJobStatus.CANCELLING, null);
            jobStateRef.set(cancellingState);
            return cancellingState;
        }
    }

    public LinkIssuesPageResponse getIssues(
            String collection,
            String status,
            String field,
            String reason,
            String query,
            int page,
            int size
    ) {
        String targetCollection = validateQueryCollection(collection);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return linkAuditStore.findIssuesPage(targetCollection, status, field, reason, query, safePage, safeSize);
    }

    public void streamIssuesForExport(
            String collection,
            String status,
            String field,
            String reason,
            String query,
            Consumer<LinkAuditIssue> consumer
    ) {
        String targetCollection = validateQueryCollection(collection);
        Objects.requireNonNull(consumer, "consumer must not be null");
        linkAuditStore.streamIssuesForExport(targetCollection, status, field, reason, query, consumer);
    }

    @PreDestroy
    public void shutdown() {
        synchronized (jobLock) {
            shuttingDown = true;
            if (activeRun != null) {
                activeRun.cancel();
                if (jobStateRef.get().status() == AuditJobStatus.RUNNING) {
                    jobStateRef.set(activeRun.toState(AuditJobStatus.CANCELLING, null));
                }
            }
        }
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Link audit executor did not terminate within timeout during shutdown");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void runJob(JobRunContext runContext, List<String> targetCollections) {
        int failedCount = 0;
        try {
            for (String collection : targetCollections) {
                if (runContext.isCancelled()) {
                    finishRun(runContext, AuditJobStatus.CANCELLED);
                    return;
                }

                runContext.startCollection(collection);
                publishProgress(runContext);

                String newScanId = UUID.randomUUID().toString();
                boolean summaryCommitted = false;
                try {
                    summaryCommitted = scanAndPersistCollection(runContext, collection, newScanId);
                    if (runContext.isCancelled() && !summaryCommitted) {
                        cleanupPartialScan(collection, newScanId);
                        finishRun(runContext, AuditJobStatus.CANCELLED);
                        return;
                    }
                } catch (CancellationException | InterruptedException ex) {
                    // Clear interrupt flag so best-effort MongoDB cleanup does not fail with MongoInterruptedException.
                    Thread.interrupted();
                    if (!summaryCommitted) {
                        cleanupPartialScan(collection, newScanId);
                    }
                    finishRun(runContext, AuditJobStatus.CANCELLED);
                    return;
                } catch (Exception ex) {
                    if (runContext.isCancelled()) {
                        Thread.interrupted();
                        if (!summaryCommitted) {
                            cleanupPartialScan(collection, newScanId);
                        }
                        finishRun(runContext, AuditJobStatus.CANCELLED);
                        return;
                    }
                    failedCount++;
                    if (!summaryCommitted) {
                        cleanupPartialScan(collection, newScanId);
                    }
                    String errorMsg = collection + ": " + formatError(ex);
                    log.error("Error auditing links for collection '{}': {}", collection, ex.getMessage(), ex);
                    runContext.recordError(errorMsg);
                }

                runContext.incrementCollectionsCompleted();
                publishProgress(runContext);

                if (runContext.isCancelled()) {
                    finishRun(runContext, AuditJobStatus.CANCELLED);
                    return;
                }
            }

            AuditJobStatus finalStatus = (!targetCollections.isEmpty() && failedCount == targetCollections.size())
                    ? AuditJobStatus.FAILED
                    : AuditJobStatus.COMPLETED;
            finishRun(runContext, finalStatus);
        } catch (Throwable fatal) {
            if (runContext.isCancelled()) {
                Thread.interrupted();
                finishRun(runContext, AuditJobStatus.CANCELLED);
            } else {
                log.error("Unexpected failure in link audit job {}", runContext.jobId(), fatal);
                runContext.recordError(formatError(fatal));
                finishRun(runContext, AuditJobStatus.FAILED);
            }
        }
    }

    private boolean scanAndPersistCollection(JobRunContext runContext, String collection, String newScanId)
            throws InterruptedException, ExecutionException {
        Instant collectionStartedAt = Instant.now();
        ExtractionResult extraction = extractor.extractCollection(collection, runContext::isCancelled);
        if (runContext.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Scan cancelled during extraction");
        }

        List<ExtractedLinkRef> refs = (extraction == null || extraction.refs() == null)
                ? List.of()
                : extraction.refs();
        long recordsWithLinks = extraction == null ? 0L : extraction.recordsWithLinks();

        Map<String, List<ExtractedLinkRef>> refsByUrl = new LinkedHashMap<>();
        for (ExtractedLinkRef ref : refs) {
            if (ref.rawUrl() == null || ref.rawUrl().isBlank()) {
                continue;
            }
            refsByUrl.computeIfAbsent(ref.rawUrl().trim(), k -> new ArrayList<>()).add(ref);
        }

        runContext.setCurrentCollectionUniqueUrls(refsByUrl.size());
        publishProgress(runContext);

        Map<String, LinkCheckResult> resultsByTrimmedUrl = new ConcurrentHashMap<>(Math.max(16, refsByUrl.size()));
        if (!refsByUrl.isEmpty()) {
            List<CompletableFuture<Void>> pending = new ArrayList<>(refsByUrl.size());
            for (Map.Entry<String, List<ExtractedLinkRef>> entry : refsByUrl.entrySet()) {
                if (runContext.isCancelled()) {
                    throw new CancellationException("Scan cancelled before scheduling remaining URLs");
                }
                String trimmedUrl = entry.getKey();
                int occurrenceCount = entry.getValue().size();
                CompletableFuture<LinkCheckResult> checkFuture = getOrVerifyUrl(trimmedUrl, runContext);
                CompletableFuture<Void> trackingFuture = checkFuture.thenAccept(result -> {
                    resultsByTrimmedUrl.put(trimmedUrl, result);
                    runContext.recordUrlProgress(occurrenceCount, result.status());
                    publishProgress(runContext);
                });
                pending.add(trackingFuture);
            }

            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get();
        }

        if (runContext.isCancelled()) {
            throw new CancellationException("Scan cancelled before persisting results");
        }

        CollectionOutcome outcome = buildCollectionOutcome(
                collection,
                newScanId,
                collectionStartedAt,
                recordsWithLinks,
                refs,
                refsByUrl.size(),
                resultsByTrimmedUrl
        );

        if (!outcome.issues().isEmpty()) {
            linkAuditStore.insertIssuesInBatches(outcome.issues());
        }
        if (runContext.isCancelled()) {
            throw new CancellationException("Scan cancelled before committing summary");
        }

        linkAuditStore.upsertSummary(outcome.summary());

        boolean wasInterrupted = Thread.interrupted();
        try {
            linkAuditStore.deleteIssuesOtherThanScan(collection, newScanId);
        } catch (Exception cleanupEx) {
            log.warn("Could not purge obsolete issues for collection '{}' (scanId={}): {}",
                    collection, newScanId, cleanupEx.getMessage());
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    private CompletableFuture<LinkCheckResult> getOrVerifyUrl(String trimmedUrl, JobRunContext runContext) {
        return runContext.urlCache().computeIfAbsent(trimmedUrl, key -> {
            CompletableFuture<LinkCheckResult> created = new CompletableFuture<>();
            try {
                Future<?> task = executorService.submit(() -> {
                    try {
                        if (runContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                            created.cancel(false);
                            return;
                        }
                        LinkCheckResult result = verifyWithSemaphores(key, runContext);
                        created.complete(result);
                    } catch (CancellationException ex) {
                        created.cancel(false);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        created.cancel(false);
                    } catch (Throwable ex) {
                        if (runContext.isCancelled()) {
                            created.cancel(false);
                        } else {
                            created.complete(new LinkCheckResult(
                                    key,
                                    key,
                                    null,
                                    LinkStatus.BROKEN,
                                    FailureReason.CONNECTION_ERROR,
                                    null,
                                    formatError(ex),
                                    Instant.now()
                            ));
                        }
                    }
                });
                runContext.registerTask(task, created);
                created.whenComplete((res, err) -> runContext.unregisterTask(task));
            } catch (RejectedExecutionException ex) {
                created.completeExceptionally(ex);
            }
            return created;
        });
    }

    private LinkCheckResult verifyWithSemaphores(String trimmedUrl, JobRunContext runContext) throws InterruptedException {
        Optional<String> hostOpt = linkVerifier.extractValidHttpHost(trimmedUrl);
        if (hostOpt == null || hostOpt.isEmpty() || hostOpt.get().isBlank()) {
            return linkVerifier.verify(trimmedUrl);
        }

        // Acquire per-host permit before global permit so slow hosts cannot monopolize global slots.
        Semaphore hostSemaphore = runContext.hostSemaphore(hostOpt.get());
        hostSemaphore.acquire();
        try {
            if (runContext.isCancelled()) {
                throw new CancellationException("Scan cancelled");
            }
            runContext.globalSemaphore().acquire();
            try {
                if (runContext.isCancelled()) {
                    throw new CancellationException("Scan cancelled");
                }
                return linkVerifier.verify(trimmedUrl);
            } finally {
                runContext.globalSemaphore().release();
            }
        } finally {
            hostSemaphore.release();
        }
    }

    private CollectionOutcome buildCollectionOutcome(
            String collection,
            String scanId,
            Instant startedAt,
            long recordsWithLinks,
            List<ExtractedLinkRef> refs,
            long uniqueUrls,
            Map<String, LinkCheckResult> resultsByTrimmedUrl
    ) {
        long okCount = 0L;
        long brokenCount = 0L;
        long blockedCount = 0L;
        long totalLinks = 0L;

        Map<String, MutableFieldCounts> fieldCounts = new LinkedHashMap<>();
        for (LinkSourceField sourceField : LinkSourceField.values()) {
            fieldCounts.put(sourceField.getFieldName(), new MutableFieldCounts());
        }

        List<LinkAuditIssue> issues = new ArrayList<>();
        for (ExtractedLinkRef ref : refs) {
            if (ref.rawUrl() == null || ref.rawUrl().isBlank()) {
                continue;
            }
            LinkCheckResult result = resultsByTrimmedUrl.get(ref.rawUrl().trim());
            if (result == null) {
                continue;
            }
            totalLinks++;
            LinkStatus status = result.status();
            if (status == LinkStatus.OK) {
                okCount++;
            } else if (status == LinkStatus.BLOCKED) {
                blockedCount++;
            } else {
                brokenCount++;
            }

            String sourceField = ref.sourceField() != null ? ref.sourceField() : LinkAuditConstants.UNSPECIFIED_LABEL;
            fieldCounts.computeIfAbsent(sourceField, k -> new MutableFieldCounts()).record(status);

            if (status == LinkStatus.BROKEN || status == LinkStatus.BLOCKED) {
                issues.add(toIssue(collection, scanId, ref, result));
            }
        }

        Map<String, FieldAuditCounts> immutableByField = new LinkedHashMap<>();
        for (Map.Entry<String, MutableFieldCounts> entry : fieldCounts.entrySet()) {
            immutableByField.put(entry.getKey(), entry.getValue().toRecord());
        }

        Instant scannedAt = Instant.now();
        long durationMs = Math.max(0L, Duration.between(startedAt, scannedAt).toMillis());
        CollectionAuditSummary summary = new CollectionAuditSummary(
                collection,
                scanId,
                scannedAt,
                durationMs,
                recordsWithLinks,
                totalLinks,
                uniqueUrls,
                okCount,
                brokenCount,
                blockedCount,
                Collections.unmodifiableMap(immutableByField),
                null
        );
        return new CollectionOutcome(summary, issues);
    }

    private LinkAuditIssue toIssue(
            String collection,
            String scanId,
            ExtractedLinkRef ref,
            LinkCheckResult result
    ) {
        return new LinkAuditIssue(
                collection,
                scanId,
                result.status(),
                result.reason(),
                result.httpStatus(),
                result.errorMessage(),
                ref.rawUrl(),
                result.finalUrl(),
                ref.sourceField(),
                ref.subType(),
                ref.linkLabel(),
                ref.recordId(),
                ref.recordUuid(),
                ref.recordPureId(),
                ref.recordTitle(),
                ref.recordType(),
                LinkAuditConstants.buildEgretaWorkspaceUrl(ref.recordUuid()),
                result.checkedAt() != null ? result.checkedAt() : Instant.now()
        );
    }

    private List<String> orderGlobalCollections(List<String> rawCollections) {
        if (rawCollections == null || rawCollections.isEmpty()) {
            return List.of();
        }
        List<CollectionEstimate> estimates = new ArrayList<>(rawCollections.size());
        for (String name : rawCollections) {
            if (!LinkAuditConstants.isInternalCollection(name)) {
                long count = linkAuditStore.estimatedDocumentCount(name);
                boolean isResearchOutputs = RESEARCH_OUTPUTS_COLLECTION.equalsIgnoreCase(name.trim());
                estimates.add(new CollectionEstimate(name, count, isResearchOutputs));
            }
        }
        estimates.sort(Comparator.comparing(CollectionEstimate::researchOutputs)
                .thenComparingLong(CollectionEstimate::estimatedCount)
                .thenComparing(CollectionEstimate::name, String.CASE_INSENSITIVE_ORDER));

        List<String> ordered = new ArrayList<>(estimates.size());
        for (CollectionEstimate estimate : estimates) {
            ordered.add(estimate.name());
        }
        return List.copyOf(ordered);
    }

    private void cleanupPartialScan(String collection, String scanId) {
        try {
            linkAuditStore.deleteIssuesForScan(collection, scanId);
        } catch (Exception ex) {
            log.warn("Could not clean up partial issues for collection '{}' (scanId={}): {}",
                    collection, scanId, ex.getMessage());
        }
    }

    private void publishProgress(JobRunContext runContext) {
        synchronized (jobLock) {
            if (activeRun != runContext) {
                return;
            }
            AuditJobStatus currentStatus = jobStateRef.get().status();
            AuditJobStatus nextStatus = currentStatus == AuditJobStatus.CANCELLING
                    ? AuditJobStatus.CANCELLING
                    : AuditJobStatus.RUNNING;
            jobStateRef.set(runContext.toState(nextStatus, null));
        }
    }

    private void finishRun(JobRunContext runContext, AuditJobStatus finalStatus) {
        synchronized (jobLock) {
            if (activeRun != runContext) {
                return;
            }
            runContext.setCurrentCollection(null);
            jobStateRef.set(runContext.toState(finalStatus, Instant.now()));
            activeRun = null;
        }
    }

    private static String validateQueryCollection(String collection) {
        if (LinkAuditConstants.isInternalCollection(collection)) {
            throw new IllegalArgumentException("Col·lecció invàlida o interna: " + collection);
        }
        return collection.trim();
    }

    private static String formatError(Throwable ex) {
        if (ex == null) {
            return "Unknown error";
        }
        String message = ex.getMessage();
        return (message == null || message.isBlank())
                ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + message;
    }

    private static AuditJobState idleState() {
        return new AuditJobState(
                null,
                AuditJobStatus.IDLE,
                null,
                null,
                null,
                null,
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                null
        );
    }

    private record CollectionEstimate(String name, long estimatedCount, boolean researchOutputs) {
    }

    private record CollectionOutcome(CollectionAuditSummary summary, List<LinkAuditIssue> issues) {
    }

    private static final class MutableFieldCounts {
        private long totalLinks;
        private long okCount;
        private long brokenCount;
        private long blockedCount;

        void record(LinkStatus status) {
            totalLinks++;
            if (status == LinkStatus.OK) {
                okCount++;
            } else if (status == LinkStatus.BLOCKED) {
                blockedCount++;
            } else {
                brokenCount++;
            }
        }

        FieldAuditCounts toRecord() {
            return new FieldAuditCounts(totalLinks, okCount, brokenCount, blockedCount);
        }
    }

    private static final class JobRunContext {
        private final String jobId;
        private final String scope;
        private final Instant startedAt;
        private final int collectionsTotal;
        private final int perHostConcurrency;
        private final Semaphore globalSemaphore;
        private final ConcurrentHashMap<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CompletableFuture<LinkCheckResult>> urlCache = new ConcurrentHashMap<>();
        private final Set<Future<?>> activeTasks = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger collectionsCompleted = new AtomicInteger(0);
        private final AtomicLong currentCollectionUniqueUrls = new AtomicLong(0);
        private final AtomicLong currentCollectionCheckedUrls = new AtomicLong(0);
        private final AtomicLong totalUrlsChecked = new AtomicLong(0);
        private final AtomicLong brokenFound = new AtomicLong(0);
        private final AtomicLong blockedFound = new AtomicLong(0);

        private volatile Thread orchestratorThread;
        private volatile String currentCollection;
        private volatile String lastError;

        JobRunContext(
                String jobId,
                String scope,
                Instant startedAt,
                int collectionsTotal,
                int globalConcurrency,
                int perHostConcurrency
        ) {
            this.jobId = jobId;
            this.scope = scope;
            this.startedAt = startedAt;
            this.collectionsTotal = collectionsTotal;
            this.perHostConcurrency = perHostConcurrency;
            this.globalSemaphore = new Semaphore(globalConcurrency);
        }

        String jobId() {
            return jobId;
        }

        Semaphore globalSemaphore() {
            return globalSemaphore;
        }

        Semaphore hostSemaphore(String rawHost) {
            String normalizedHost = rawHost.trim().toLowerCase(Locale.ROOT);
            return hostSemaphores.computeIfAbsent(normalizedHost, h -> new Semaphore(perHostConcurrency));
        }

        ConcurrentHashMap<String, CompletableFuture<LinkCheckResult>> urlCache() {
            return urlCache;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        String lastError() {
            return lastError;
        }

        void setOrchestratorThread(Thread orchestratorThread) {
            this.orchestratorThread = orchestratorThread;
        }

        void setCurrentCollection(String currentCollection) {
            this.currentCollection = currentCollection;
        }

        void startCollection(String collection) {
            this.currentCollection = collection;
            this.currentCollectionUniqueUrls.set(0L);
            this.currentCollectionCheckedUrls.set(0L);
        }

        void setCurrentCollectionUniqueUrls(long uniqueUrls) {
            this.currentCollectionUniqueUrls.set(uniqueUrls);
        }

        void incrementCollectionsCompleted() {
            this.collectionsCompleted.incrementAndGet();
        }

        void recordUrlProgress(int occurrenceCount, LinkStatus status) {
            currentCollectionCheckedUrls.incrementAndGet();
            totalUrlsChecked.incrementAndGet();
            if (status == LinkStatus.BROKEN) {
                brokenFound.addAndGet(occurrenceCount);
            } else if (status == LinkStatus.BLOCKED) {
                blockedFound.addAndGet(occurrenceCount);
            }
        }

        void recordError(String error) {
            if (this.lastError == null || this.lastError.isBlank()) {
                this.lastError = error;
            } else {
                this.lastError = this.lastError + " | " + error;
            }
        }

        void registerTask(Future<?> task, CompletableFuture<?> completion) {
            if (!completion.isDone()) {
                activeTasks.add(task);
                if (cancelled.get() || completion.isDone()) {
                    if (cancelled.get()) {
                        task.cancel(true);
                    }
                    activeTasks.remove(task);
                }
            }
        }

        void unregisterTask(Future<?> task) {
            activeTasks.remove(task);
        }

        void cancel() {
            cancelled.set(true);
            Thread orchestrator = orchestratorThread;
            if (orchestrator != null) {
                orchestrator.interrupt();
            }
            for (Future<?> task : activeTasks) {
                task.cancel(true);
            }
            for (CompletableFuture<LinkCheckResult> cf : urlCache.values()) {
                cf.cancel(true);
            }
        }

        AuditJobState toState(AuditJobStatus status, Instant finishedAt) {
            return new AuditJobState(
                    jobId,
                    status,
                    scope,
                    startedAt,
                    finishedAt,
                    currentCollection,
                    collectionsTotal,
                    collectionsCompleted.get(),
                    currentCollectionUniqueUrls.get(),
                    currentCollectionCheckedUrls.get(),
                    totalUrlsChecked.get(),
                    brokenFound.get(),
                    blockedFound.get(),
                    lastError
            );
        }
    }
}
