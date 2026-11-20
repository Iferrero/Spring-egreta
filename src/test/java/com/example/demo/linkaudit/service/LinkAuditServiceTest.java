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
import com.example.demo.linkaudit.model.LinkStatus;
import com.example.demo.linkaudit.repository.LinkAuditStore;
import com.example.demo.linkaudit.verifier.LinkVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkAuditServiceTest {

    private LinkAuditStore store;
    private CollectionLinkExtractor extractor;
    private LinkVerifier linkVerifier;
    private LinkAuditProperties properties;
    private LinkAuditService service;

    @BeforeEach
    void setUp() {
        store = mock(LinkAuditStore.class);
        extractor = mock(CollectionLinkExtractor.class);
        linkVerifier = mock(LinkVerifier.class);
        properties = new LinkAuditProperties();
        service = new LinkAuditService(store, extractor, linkVerifier, properties);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startScanDeduplicatesRepeatedUrlsAcrossCollectionsAndCommitsAtomically() throws Exception {
        String sharedBrokenUrl = "https://broken.example/missing";
        String okUrl = "https://ok.example/home";
        String blockedUrl = "https://blocked.example/paywall";

        when(store.listBusinessCollections()).thenReturn(List.of("Awards", "Persons"));
        when(store.estimatedDocumentCount("Awards")).thenReturn(10L);
        when(store.estimatedDocumentCount("Persons")).thenReturn(20L);

        ExtractedLinkRef awardRef1 = ref("Awards", "a1", "uuid-a1", 1L, "Award 1", "documents", sharedBrokenUrl);
        ExtractedLinkRef awardRef2 = ref("Awards", "a2", "uuid-a2", 2L, "Award 2", "links", "  " + sharedBrokenUrl + "  ");
        ExtractedLinkRef awardRef3 = ref("Awards", "a3", "uuid-a3", 3L, "Award 3", "links", okUrl);
        when(extractor.extractCollection(eq("Awards"), any())).thenReturn(
                new ExtractionResult(3L, List.of(awardRef1, awardRef2, awardRef3))
        );

        ExtractedLinkRef personRef1 = ref("Persons", "p1", "uuid-p1", 4L, "Person 1", "documents", sharedBrokenUrl);
        ExtractedLinkRef personRef2 = ref("Persons", "p2", "uuid-p2", 5L, "Person 2", "links", blockedUrl);
        when(extractor.extractCollection(eq("Persons"), any())).thenReturn(
                new ExtractionResult(2L, List.of(personRef1, personRef2))
        );

        when(linkVerifier.extractValidHttpHost(sharedBrokenUrl)).thenReturn(Optional.of("broken.example"));
        when(linkVerifier.extractValidHttpHost(okUrl)).thenReturn(Optional.of("ok.example"));
        when(linkVerifier.extractValidHttpHost(blockedUrl)).thenReturn(Optional.of("blocked.example"));

        Instant checkedAt = Instant.parse("2026-11-20T12:00:00Z");
        when(linkVerifier.verify(sharedBrokenUrl)).thenReturn(
                new LinkCheckResult(sharedBrokenUrl, sharedBrokenUrl, sharedBrokenUrl,
                        LinkStatus.BROKEN, FailureReason.HTTP_404, 404, "HTTP 404", checkedAt)
        );
        when(linkVerifier.verify(okUrl)).thenReturn(
                new LinkCheckResult(okUrl, okUrl, okUrl,
                        LinkStatus.OK, FailureReason.OK, 200, null, checkedAt)
        );
        when(linkVerifier.verify(blockedUrl)).thenReturn(
                new LinkCheckResult(blockedUrl, blockedUrl, blockedUrl,
                        LinkStatus.BLOCKED, FailureReason.HTTP_403, 403, "HTTP 403", checkedAt)
        );

        AuditJobState initial = service.startScan(null);
        assertEquals(AuditJobStatus.RUNNING, initial.status());
        assertEquals("ALL", initial.scope());
        assertEquals(2, initial.collectionsTotal());

        AuditJobState finished = awaitTerminalStatus();
        assertEquals(AuditJobStatus.COMPLETED, finished.status());
        assertEquals(2, finished.collectionsCompleted());
        assertEquals(3L, finished.brokenFound());
        assertEquals(1L, finished.blockedFound());
        assertNull(finished.currentCollection());
        assertNotNull(finished.finishedAt());
        assertNull(finished.lastError());

        // Global scan deduplicates sharedBrokenUrl across Awards (x2) and Persons (x1) into 1 verify call.
        verify(linkVerifier, times(1)).verify(sharedBrokenUrl);
        verify(linkVerifier, times(1)).verify(okUrl);
        verify(linkVerifier, times(1)).verify(blockedUrl);

        ArgumentCaptor<List<LinkAuditIssue>> issuesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<CollectionAuditSummary> summaryCaptor = ArgumentCaptor.forClass(CollectionAuditSummary.class);
        ArgumentCaptor<String> deletedScanIdCaptor = ArgumentCaptor.forClass(String.class);

        InOrder inOrder = inOrder(store);
        inOrder.verify(store).insertIssuesInBatches(issuesCaptor.capture());
        inOrder.verify(store).upsertSummary(summaryCaptor.capture());
        inOrder.verify(store).deleteIssuesOtherThanScan(eq("Awards"), deletedScanIdCaptor.capture());

        inOrder.verify(store).insertIssuesInBatches(issuesCaptor.capture());
        inOrder.verify(store).upsertSummary(summaryCaptor.capture());
        inOrder.verify(store).deleteIssuesOtherThanScan(eq("Persons"), deletedScanIdCaptor.capture());

        List<CollectionAuditSummary> summaries = summaryCaptor.getAllValues();
        CollectionAuditSummary awardsSummary = summaries.get(0);
        assertEquals("Awards", awardsSummary.collection());
        assertEquals(awardsSummary.scanId(), deletedScanIdCaptor.getAllValues().get(0));
        assertEquals(3L, awardsSummary.recordsWithLinks());
        assertEquals(3L, awardsSummary.totalLinks());
        assertEquals(2L, awardsSummary.uniqueUrls());
        assertEquals(1L, awardsSummary.okCount());
        assertEquals(2L, awardsSummary.brokenCount());
        assertEquals(0L, awardsSummary.blockedCount());
        assertEquals(new FieldAuditCounts(1L, 0L, 1L, 0L), awardsSummary.byField().get("documents"));
        assertEquals(new FieldAuditCounts(2L, 1L, 1L, 0L), awardsSummary.byField().get("links"));
        assertEquals(new FieldAuditCounts(0L, 0L, 0L, 0L), awardsSummary.byField().get("electronicVersions"));

        List<LinkAuditIssue> awardsIssues = issuesCaptor.getAllValues().get(0);
        assertEquals(2, awardsIssues.size());
        assertTrue(awardsIssues.stream().allMatch(i -> awardsSummary.scanId().equals(i.scanId())));
        assertEquals(LinkAuditConstants.buildEgretaWorkspaceUrl("uuid-a1"), awardsIssues.get(0).egretaUrl());

        CollectionAuditSummary personsSummary = summaries.get(1);
        assertEquals("Persons", personsSummary.collection());
        assertNotEquals(awardsSummary.scanId(), personsSummary.scanId());
        assertEquals(personsSummary.scanId(), deletedScanIdCaptor.getAllValues().get(1));
        assertEquals(2L, personsSummary.totalLinks());
        assertEquals(1L, personsSummary.brokenCount());
        assertEquals(1L, personsSummary.blockedCount());

        List<LinkAuditIssue> personsIssues = issuesCaptor.getAllValues().get(1);
        assertEquals(2, personsIssues.size());
    }

    @Test
    void startGlobalScanOrdersCollectionsByEstimatedCountWithResearchoutputsLast() throws Exception {
        when(store.listBusinessCollections()).thenReturn(List.of("Researchoutputs", "Awards", "Activities"));
        when(store.estimatedDocumentCount("Researchoutputs")).thenReturn(200_000L);
        when(store.estimatedDocumentCount("Awards")).thenReturn(500L);
        when(store.estimatedDocumentCount("Activities")).thenReturn(100L);

        List<String> executedOrder = new CopyOnWriteArrayList<>();
        when(extractor.extractCollection(anyString(), any())).thenAnswer(inv -> {
            String col = inv.getArgument(0);
            executedOrder.add(col);
            return new ExtractionResult(0L, List.of());
        });

        service.startScan(null);
        AuditJobState finished = awaitTerminalStatus();

        assertEquals(AuditJobStatus.COMPLETED, finished.status());
        assertEquals(List.of("Activities", "Awards", "Researchoutputs"), executedOrder);
    }

    @Test
    void startScanThrowsScanAlreadyRunningExceptionWhenJobActive() throws Exception {
        String slowUrl = "https://slow.example/1";
        when(store.collectionExists("Awards")).thenReturn(true);
        when(extractor.extractCollection(eq("Awards"), any())).thenReturn(
                new ExtractionResult(1L, List.of(ref("Awards", "a1", "uuid-1", 1L, "Slow", "documents", slowUrl)))
        );
        when(linkVerifier.extractValidHttpHost(slowUrl)).thenReturn(Optional.of("slow.example"));

        CountDownLatch verifyStarted = new CountDownLatch(1);
        CountDownLatch releaseVerify = new CountDownLatch(1);
        when(linkVerifier.verify(slowUrl)).thenAnswer(inv -> {
            verifyStarted.countDown();
            releaseVerify.await(5, TimeUnit.SECONDS);
            return new LinkCheckResult(slowUrl, slowUrl, slowUrl,
                    LinkStatus.OK, FailureReason.OK, 200, null, Instant.now());
        });

        AuditJobState started = service.startScan("Awards");
        assertTrue(verifyStarted.await(5, TimeUnit.SECONDS));

        try {
            ScanAlreadyRunningException ex = assertThrows(
                    ScanAlreadyRunningException.class,
                    () -> service.startScan(null)
            );
            assertNotNull(ex.getActiveJobState());
            assertEquals(started.jobId(), ex.getActiveJobState().jobId());
            assertEquals(AuditJobStatus.RUNNING, ex.getActiveJobState().status());
        } finally {
            releaseVerify.countDown();
        }

        awaitTerminalStatus();
    }

    @Test
    void cancelScanCleansPartialNewScanWithoutOverwritingPreviousSummary() throws Exception {
        String okUrl = "https://ok.example/1";
        String blockingUrl = "https://hang.example/2";

        when(store.listBusinessCollections()).thenReturn(List.of("Activities", "Awards"));
        when(store.estimatedDocumentCount("Activities")).thenReturn(10L);
        when(store.estimatedDocumentCount("Awards")).thenReturn(20L);

        when(extractor.extractCollection(eq("Activities"), any())).thenReturn(
                new ExtractionResult(1L, List.of(ref("Activities", "act-1", "uuid-act", 1L, "Act", "links", okUrl)))
        );
        when(extractor.extractCollection(eq("Awards"), any())).thenReturn(
                new ExtractionResult(1L, List.of(ref("Awards", "aw-1", "uuid-aw", 2L, "Aw", "documents", blockingUrl)))
        );

        when(linkVerifier.extractValidHttpHost(okUrl)).thenReturn(Optional.of("ok.example"));
        when(linkVerifier.extractValidHttpHost(blockingUrl)).thenReturn(Optional.of("hang.example"));
        when(linkVerifier.verify(okUrl)).thenReturn(
                new LinkCheckResult(okUrl, okUrl, okUrl, LinkStatus.OK, FailureReason.OK, 200, null, Instant.now())
        );

        CountDownLatch awardsVerifyStarted = new CountDownLatch(1);
        CountDownLatch releaseHungVerify = new CountDownLatch(1);
        when(linkVerifier.verify(blockingUrl)).thenAnswer(inv -> {
            awardsVerifyStarted.countDown();
            try {
                releaseHungVerify.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Intentionally verify cancellation completes even if underlying call ignores interruption briefly.
                releaseHungVerify.await(5, TimeUnit.SECONDS);
            }
            return new LinkCheckResult(blockingUrl, blockingUrl, blockingUrl,
                    LinkStatus.BROKEN, FailureReason.TIMEOUT, null, "Timeout", Instant.now());
        });

        service.startScan(null);
        assertTrue(awardsVerifyStarted.await(5, TimeUnit.SECONDS));

        AuditJobState cancellingState = service.cancelScan();
        assertTrue(cancellingState.status() == AuditJobStatus.CANCELLING
                || cancellingState.status() == AuditJobStatus.CANCELLED);

        try {
            AuditJobState finalState = awaitTerminalStatus();
            assertEquals(AuditJobStatus.CANCELLED, finalState.status());

            verify(store).upsertSummary(argThat(summary -> "Activities".equals(summary.collection())));
            verify(store, never()).upsertSummary(argThat(summary -> "Awards".equals(summary.collection())));
            verify(store).deleteIssuesForScan(eq("Awards"), argThat(scanId -> scanId != null && !scanId.isBlank()));
        } finally {
            releaseHungVerify.countDown();
        }
    }

    @Test
    void globalScanIsolatesCollectionFailureAndContinuesRemainingCollections() throws Exception {
        String brokenUrl = "https://broken.example/award";

        when(store.listBusinessCollections()).thenReturn(List.of("Activities", "Awards"));
        when(store.estimatedDocumentCount("Activities")).thenReturn(10L);
        when(store.estimatedDocumentCount("Awards")).thenReturn(20L);

        when(extractor.extractCollection(eq("Activities"), any()))
                .thenThrow(new RuntimeException("Mongo cursor error"));
        when(extractor.extractCollection(eq("Awards"), any())).thenReturn(
                new ExtractionResult(1L, List.of(ref("Awards", "aw-1", "uuid-aw", 2L, "Aw", "documents", brokenUrl)))
        );

        when(linkVerifier.extractValidHttpHost(brokenUrl)).thenReturn(Optional.of("broken.example"));
        when(linkVerifier.verify(brokenUrl)).thenReturn(
                new LinkCheckResult(brokenUrl, brokenUrl, brokenUrl,
                        LinkStatus.BROKEN, FailureReason.HTTP_404, 404, "HTTP 404", Instant.now())
        );

        service.startScan(null);
        AuditJobState finished = awaitTerminalStatus();

        assertEquals(AuditJobStatus.COMPLETED, finished.status());
        assertEquals(2, finished.collectionsCompleted());
        assertNotNull(finished.lastError());
        assertTrue(finished.lastError().contains("Activities"));

        verify(store).deleteIssuesForScan(eq("Activities"), argThat(scanId -> scanId != null && !scanId.isBlank()));
        verify(store, never()).upsertSummary(argThat(summary -> "Activities".equals(summary.collection())));

        verify(store).insertIssuesInBatches(argThat(list -> list.size() == 1 && "Awards".equals(list.get(0).collection())));
        verify(store).upsertSummary(argThat(summary -> "Awards".equals(summary.collection())));
        verify(store).deleteIssuesOtherThanScan(eq("Awards"), argThat(scanId -> scanId != null && !scanId.isBlank()));
    }

    @Test
    void startScanAndQueryMethodsRejectInvalidOrInternalCollections() {
        when(store.collectionExists(anyString())).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.startScan(LinkAuditConstants.SUMMARY_COLLECTION));
        assertThrows(IllegalArgumentException.class, () -> service.startScan(LinkAuditConstants.ISSUES_COLLECTION));
        assertThrows(IllegalArgumentException.class, () -> service.startScan("system.views"));
        assertThrows(IllegalArgumentException.class, () -> service.startScan(""));
        assertThrows(IllegalArgumentException.class, () -> service.startScan("   "));
        assertThrows(IllegalArgumentException.class, () -> service.startScan("NonExistentCollection"));

        assertThrows(IllegalArgumentException.class,
                () -> service.getIssues(null, "BROKEN", null, null, null, 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> service.getIssues("", "BROKEN", null, null, null, 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> service.getIssues("   ", "BROKEN", null, null, null, 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> service.getIssues(LinkAuditConstants.SUMMARY_COLLECTION, "BROKEN", null, null, null, 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> service.getIssues(LinkAuditConstants.ISSUES_COLLECTION, "BROKEN", null, null, null, 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> service.getIssues("system.profile", "BROKEN", null, null, null, 0, 50));

        Consumer<LinkAuditIssue> noop = issue -> {
        };
        assertThrows(IllegalArgumentException.class,
                () -> service.streamIssuesForExport(null, "ALL", null, null, null, noop));
        assertThrows(IllegalArgumentException.class,
                () -> service.streamIssuesForExport("", "ALL", null, null, null, noop));
        assertThrows(IllegalArgumentException.class,
                () -> service.streamIssuesForExport(LinkAuditConstants.SUMMARY_COLLECTION, "ALL", null, null, null, noop));
        assertThrows(IllegalArgumentException.class,
                () -> service.streamIssuesForExport(LinkAuditConstants.ISSUES_COLLECTION, "ALL", null, null, null, noop));
        assertThrows(IllegalArgumentException.class,
                () -> service.streamIssuesForExport("system.views", "ALL", null, null, null, noop));
    }

    @Test
    void invalidSyntaxUrlVerifiesWithoutAcquiringNetworkSemaphores() throws Exception {
        properties.setGlobalConcurrency(1);
        properties.setPerHostConcurrency(1);
        service.shutdown();
        service = new LinkAuditService(store, extractor, linkVerifier, properties);

        String validSlowUrl = "https://slow.example/1";
        String invalidUrl = "ht tp://bad syntax";
        when(store.collectionExists("Awards")).thenReturn(true);
        when(extractor.extractCollection(eq("Awards"), any())).thenReturn(
                new ExtractionResult(2L, List.of(
                        ref("Awards", "1", "u1", 1L, "Valid", "links", validSlowUrl),
                        ref("Awards", "2", "u2", 2L, "Invalid", "links", invalidUrl)
                ))
        );

        when(linkVerifier.extractValidHttpHost(validSlowUrl)).thenReturn(Optional.of("slow.example"));
        when(linkVerifier.extractValidHttpHost(invalidUrl)).thenReturn(Optional.empty());

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch invalidVerified = new CountDownLatch(1);

        when(linkVerifier.verify(validSlowUrl)).thenAnswer(inv -> {
            slowStarted.countDown();
            releaseSlow.await(5, TimeUnit.SECONDS);
            return new LinkCheckResult(validSlowUrl, validSlowUrl, validSlowUrl,
                    LinkStatus.OK, FailureReason.OK, 200, null, Instant.now());
        });
        when(linkVerifier.verify(invalidUrl)).thenAnswer(inv -> {
            invalidVerified.countDown();
            return new LinkCheckResult(invalidUrl, invalidUrl, null,
                    LinkStatus.BROKEN, FailureReason.INVALID_SYNTAX, null, "Invalid URI", Instant.now());
        });

        service.startScan("Awards");
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));

        try {
            assertTrue(invalidVerified.await(5, TimeUnit.SECONDS),
                    "Invalid-syntax URL must verify without waiting on the single global semaphore permit");
        } finally {
            releaseSlow.countDown();
        }

        AuditJobState finished = awaitTerminalStatus();
        assertEquals(AuditJobStatus.COMPLETED, finished.status());
        assertEquals(1L, finished.brokenFound());
    }

    @Test
    void getSummaryAndQueryMethodsDelegateAndAggregateTotals() {
        Instant t1 = Instant.parse("2026-11-20T09:00:00Z");
        Instant t2 = Instant.parse("2026-11-20T10:30:00Z");
        CollectionAuditSummary s1 = new CollectionAuditSummary(
                "Activities", "scan-1", t1, 100L, 4L, 6L, 5L, 4L, 1L, 1L, Map.of(), null
        );
        CollectionAuditSummary s2 = new CollectionAuditSummary(
                "Awards", "scan-2", t2, 200L, 10L, 14L, 12L, 10L, 3L, 1L, Map.of(), null
        );
        when(store.findAllSummaries()).thenReturn(List.of(s1, s2));

        AuditSummaryResponse summary = service.getSummary();
        assertEquals(2L, summary.collectionsAudited());
        assertEquals(20L, summary.totalLinksChecked());
        assertEquals(4L, summary.totalBroken());
        assertEquals(2L, summary.totalBlocked());
        assertEquals(t2, summary.lastAuditedAt());
        assertEquals(AuditJobStatus.IDLE, summary.job().status());
        assertEquals(List.of(s1, s2), summary.collections());

        LinkIssuesPageResponse expectedPage = new LinkIssuesPageResponse(
                "Awards", "scan-2", t2, 0, 50, 0L, 0, Map.of(), Map.of(), Map.of(), List.of()
        );
        when(store.findIssuesPage("Awards", "BROKEN", "documents", "HTTP_404", "pdf", 0, 50))
                .thenReturn(expectedPage);

        assertEquals(expectedPage, service.getIssues("Awards", "BROKEN", "documents", "HTTP_404", "pdf", 0, 50));

        Consumer<LinkAuditIssue> consumer = issue -> {
        };
        service.streamIssuesForExport("Awards", "ALL", null, null, null, consumer);
        verify(store).streamIssuesForExport("Awards", "ALL", null, null, null, consumer);
    }

    @Test
    void startGlobalScanKeepsResearchoutputsLastEvenWithSmallEstimatedCount() throws Exception {
        when(store.listBusinessCollections()).thenReturn(List.of("Researchoutputs", "Awards", "Activities"));
        when(store.estimatedDocumentCount("Researchoutputs")).thenReturn(5L);
        when(store.estimatedDocumentCount("Awards")).thenReturn(500L);
        when(store.estimatedDocumentCount("Activities")).thenReturn(100L);

        List<String> executedOrder = new CopyOnWriteArrayList<>();
        when(extractor.extractCollection(anyString(), any())).thenAnswer(inv -> {
            executedOrder.add(inv.getArgument(0));
            return new ExtractionResult(0L, List.of());
        });

        service.startScan(null);
        AuditJobState finished = awaitTerminalStatus();

        assertEquals(AuditJobStatus.COMPLETED, finished.status());
        assertEquals(List.of("Activities", "Awards", "Researchoutputs"), executedOrder);
    }

    @Test
    void perHostConcurrencyLimitsSameHostWithoutBlockingOtherHosts() throws Exception {
        properties.setGlobalConcurrency(10);
        properties.setPerHostConcurrency(2);
        service.shutdown();
        service = new LinkAuditService(store, extractor, linkVerifier, properties);

        String a1 = "https://host-a.example/1";
        String a2 = "https://host-a.example/2";
        String a3 = "https://host-a.example/3";
        String b1 = "https://host-b.example/1";

        when(store.collectionExists("Awards")).thenReturn(true);
        when(extractor.extractCollection(eq("Awards"), any())).thenReturn(
                new ExtractionResult(4L, List.of(
                        ref("Awards", "1", "u1", 1L, "A1", "links", a1),
                        ref("Awards", "2", "u2", 2L, "A2", "links", a2),
                        ref("Awards", "3", "u3", 3L, "A3", "links", a3),
                        ref("Awards", "4", "u4", 4L, "B1", "links", b1)
                ))
        );
        when(linkVerifier.extractValidHttpHost(a1)).thenReturn(Optional.of("host-a.example"));
        when(linkVerifier.extractValidHttpHost(a2)).thenReturn(Optional.of("host-a.example"));
        when(linkVerifier.extractValidHttpHost(a3)).thenReturn(Optional.of("host-a.example"));
        when(linkVerifier.extractValidHttpHost(b1)).thenReturn(Optional.of("host-b.example"));

        AtomicInteger hostAInFlight = new AtomicInteger(0);
        AtomicInteger hostAMaxInFlight = new AtomicInteger(0);
        CountDownLatch twoHostAStarted = new CountDownLatch(2);
        CountDownLatch releaseHostA = new CountDownLatch(1);
        CountDownLatch hostBCompleted = new CountDownLatch(1);

        when(linkVerifier.verify(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (url.startsWith("https://host-a.example/")) {
                int active = hostAInFlight.incrementAndGet();
                hostAMaxInFlight.accumulateAndGet(active, Math::max);
                twoHostAStarted.countDown();
                try {
                    releaseHostA.await(5, TimeUnit.SECONDS);
                } finally {
                    hostAInFlight.decrementAndGet();
                }
            } else {
                hostBCompleted.countDown();
            }
            return new LinkCheckResult(url, url, url, LinkStatus.OK, FailureReason.OK, 200, null, Instant.now());
        });

        service.startScan("Awards");
        assertTrue(twoHostAStarted.await(5, TimeUnit.SECONDS));

        try {
            assertTrue(hostBCompleted.await(5, TimeUnit.SECONDS),
                    "Different host should not be blocked by host-a saturation");
            assertEquals(2, hostAMaxInFlight.get(),
                    "Per-host semaphore must cap concurrent requests to host-a at 2");
        } finally {
            releaseHostA.countDown();
        }

        AuditJobState finished = awaitTerminalStatus();
        assertEquals(AuditJobStatus.COMPLETED, finished.status());
        assertEquals(2, hostAMaxInFlight.get());
    }

    @Test
    void singleCollectionFailureTransitionsJobToFailedWithoutOverwritingSummary() throws Exception {
        when(store.collectionExists("Awards")).thenReturn(true);
        when(extractor.extractCollection(eq("Awards"), any()))
                .thenThrow(new IllegalStateException("Extraction failed"));

        assertEquals(AuditJobStatus.IDLE, service.cancelScan().status());

        service.startScan("Awards");
        AuditJobState finished = awaitTerminalStatus();

        assertEquals(AuditJobStatus.FAILED, finished.status());
        assertNotNull(finished.lastError());
        assertTrue(finished.lastError().contains("Awards"));
        verify(store).deleteIssuesForScan(eq("Awards"), argThat(scanId -> scanId != null && !scanId.isBlank()));
        verify(store, never()).upsertSummary(any());
    }

    private AuditJobState awaitTerminalStatus() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AuditJobState state = service.getStatus();
            if (state.status() == AuditJobStatus.COMPLETED
                    || state.status() == AuditJobStatus.CANCELLED
                    || state.status() == AuditJobStatus.FAILED) {
                return state;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for terminal job status; last status was: " + service.getStatus());
        return service.getStatus();
    }

    private static ExtractedLinkRef ref(
            String collection,
            String recordId,
            String recordUuid,
            Long pureId,
            String title,
            String sourceField,
            String rawUrl
    ) {
        return new ExtractedLinkRef(
                collection,
                recordId,
                recordUuid,
                pureId,
                title,
                "Project",
                sourceField,
                "Article",
                "PDF",
                rawUrl
        );
    }
}
