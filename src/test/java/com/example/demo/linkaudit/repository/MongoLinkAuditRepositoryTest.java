package com.example.demo.linkaudit.repository;

import com.example.demo.linkaudit.LinkAuditConstants;
import com.example.demo.linkaudit.config.LinkAuditProperties;
import com.example.demo.linkaudit.model.CollectionAuditSummary;
import com.example.demo.linkaudit.model.FailureReason;
import com.example.demo.linkaudit.model.FieldAuditCounts;
import com.example.demo.linkaudit.model.LinkAuditIssue;
import com.example.demo.linkaudit.model.LinkIssuesPageResponse;
import com.example.demo.linkaudit.model.LinkStatus;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class MongoLinkAuditRepositoryTest {

    private MongoTemplate mongoTemplate;
    private MongoDatabase mongoDatabase;
    private MongoCollection<Document> summaryCollection;
    private MongoCollection<Document> issuesCollection;
    private LinkAuditProperties properties;
    private MongoLinkAuditRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        mongoDatabase = mock(MongoDatabase.class);
        summaryCollection = mock(MongoCollection.class);
        issuesCollection = mock(MongoCollection.class);
        properties = new LinkAuditProperties();

        when(mongoTemplate.getDb()).thenReturn(mongoDatabase);
        when(mongoTemplate.getCollection(LinkAuditConstants.SUMMARY_COLLECTION)).thenReturn(summaryCollection);
        when(mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION)).thenReturn(issuesCollection);

        repository = new MongoLinkAuditRepository(mongoTemplate, properties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listBusinessCollectionsExcludesInternalAndSystemCollections() {
        ListCollectionNamesIterable namesIterable = mock(ListCollectionNamesIterable.class);
        when(mongoDatabase.listCollectionNames()).thenReturn(namesIterable);
        when(namesIterable.into(any())).thenAnswer(inv -> {
            Collection<String> target = inv.getArgument(0);
            target.addAll(List.of(
                    "Researchoutputs",
                    LinkAuditConstants.SUMMARY_COLLECTION,
                    "Awards",
                    "system.views",
                    LinkAuditConstants.ISSUES_COLLECTION,
                    "system.profile",
                    "Persons"
            ));
            return target;
        });

        List<String> result = repository.listBusinessCollections();

        assertEquals(List.of("Awards", "Persons", "Researchoutputs"), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertIssuesInBatchesSplitsByConfiguredBatchSize() {
        properties.setInsertBatchSize(2);
        List<LinkAuditIssue> issues = List.of(
                sampleIssue("Awards", "scan-1", "https://example.org/1"),
                sampleIssue("Awards", "scan-1", "https://example.org/2"),
                sampleIssue("Awards", "scan-1", "https://example.org/3"),
                sampleIssue("Awards", "scan-1", "https://example.org/4"),
                sampleIssue("Awards", "scan-1", "https://example.org/5")
        );

        repository.insertIssuesInBatches(issues);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(issuesCollection, times(3)).insertMany(captor.capture());
        List<List<Document>> batches = captor.getAllValues();
        assertEquals(2, batches.get(0).size());
        assertEquals(2, batches.get(1).size());
        assertEquals(1, batches.get(2).size());
        assertEquals("https://example.org/1", batches.get(0).get(0).getString("url"));
        assertEquals("https://example.org/5", batches.get(2).get(0).getString("url"));
    }

    @Test
    void upsertSummaryUsesCollectionAsIdAndReplaceUpsert() {
        Instant scannedAt = Instant.parse("2026-11-20T10:15:30Z");
        CollectionAuditSummary summary = new CollectionAuditSummary(
                "Awards",
                "scan-abc",
                scannedAt,
                1250L,
                10L,
                15L,
                12L,
                11L,
                3L,
                1L,
                Map.of(
                        "documents", new FieldAuditCounts(10L, 8L, 2L, 0L),
                        "links", new FieldAuditCounts(5L, 3L, 1L, 1L)
                ),
                null
        );

        repository.upsertSummary(summary);

        ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<ReplaceOptions> optionsCaptor = ArgumentCaptor.forClass(ReplaceOptions.class);

        verify(summaryCollection).replaceOne(filterCaptor.capture(), docCaptor.capture(), optionsCaptor.capture());

        BsonDocument filterDoc = toBsonDocument(filterCaptor.getValue());
        assertEquals("Awards", filterDoc.getString("_id").getValue());
        assertTrue(optionsCaptor.getValue().isUpsert());

        Document saved = docCaptor.getValue();
        assertEquals("Awards", saved.getString("_id"));
        assertEquals("Awards", saved.getString("collection"));
        assertEquals("scan-abc", saved.getString("scanId"));
        assertEquals(Date.from(scannedAt), saved.get("scannedAt"));
        Document byField = saved.get("byField", Document.class);
        assertNotNull(byField);
        Document docsField = byField.get("documents", Document.class);
        assertEquals(10L, ((Number) docsField.get("totalLinks")).longValue());
        assertEquals(2L, ((Number) docsField.get("brokenCount")).longValue());
    }

    @Test
    void deleteOperationsBuildExactScanIdFilters() {
        DeleteResult otherResult = mock(DeleteResult.class);
        when(otherResult.getDeletedCount()).thenReturn(4L);
        DeleteResult scanResult = mock(DeleteResult.class);
        when(scanResult.getDeletedCount()).thenReturn(2L);
        when(issuesCollection.deleteMany(any(Bson.class)))
                .thenReturn(otherResult)
                .thenReturn(scanResult);

        long deletedOther = repository.deleteIssuesOtherThanScan("Awards", "scan-new");
        long deletedScan = repository.deleteIssuesForScan("Awards", "scan-failed");

        assertEquals(4L, deletedOther);
        assertEquals(2L, deletedScan);

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(issuesCollection, times(2)).deleteMany(captor.capture());

        BsonDocument neFilter = flattenAnd(toBsonDocument(captor.getAllValues().get(0)));
        assertEquals("Awards", neFilter.getString("collection").getValue());
        assertEquals("scan-new", neFilter.getDocument("scanId").getString("$ne").getValue());

        BsonDocument eqFilter = flattenAnd(toBsonDocument(captor.getAllValues().get(1)));
        assertEquals("Awards", eqFilter.getString("collection").getValue());
        assertEquals("scan-failed", eqFilter.getString("scanId").getValue());
    }

    @Test
    void buildIssuesFilterEscapesRegexMetacharactersInQuery() {
        String rawQuery = "http://x.es/a?b=(1)+[2]";

        Bson filter = repository.buildIssuesFilter("Awards", "scan-1", "BROKEN", "documents", "HTTP_404", rawQuery);
        BsonDocument bson = flattenAnd(toBsonDocument(filter));

        assertEquals("Awards", bson.getString("collection").getValue());
        assertEquals("scan-1", bson.getString("scanId").getValue());
        assertEquals("BROKEN", bson.getString("status").getValue());
        assertEquals("documents", bson.getString("sourceField").getValue());
        assertEquals("HTTP_404", bson.getString("reason").getValue());

        var orArray = bson.getArray("$or");
        assertEquals(3, orArray.size());
        String expectedQuoted = Pattern.quote(rawQuery);
        for (int i = 0; i < 3; i++) {
            BsonDocument branch = orArray.get(i).asDocument();
            String fieldName = List.of("url", "recordTitle", "recordUuid").get(i);
            var regexValue = branch.getRegularExpression(fieldName);
            assertEquals(expectedQuoted, regexValue.getPattern());
            assertTrue(regexValue.getOptions().contains("i"));
            Pattern compiled = Pattern.compile(regexValue.getPattern(), Pattern.CASE_INSENSITIVE);
            assertTrue(compiled.matcher("prefix " + rawQuery + " suffix").find());
            assertFalse(compiled.matcher("http://xes/ab=112").find());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void findIssuesPageReturnsEmptyWhenCollectionHasNoSummary() {
        FindIterable<Document> emptyFind = mock(FindIterable.class);
        when(summaryCollection.find(any(Bson.class))).thenReturn(emptyFind);
        when(emptyFind.first()).thenReturn(null);

        LinkIssuesPageResponse response = repository.findIssuesPage("Awards", "BROKEN", null, null, null, 0, 50);

        assertEquals("Awards", response.collection());
        assertNull(response.scanId());
        assertNull(response.scannedAt());
        assertEquals(0L, response.totalItems());
        assertEquals(0, response.totalPages());
        assertTrue(response.items().isEmpty());
        assertTrue(response.countsByStatus().isEmpty());
        assertTrue(response.countsByField().isEmpty());
        assertTrue(response.countsByReason().isEmpty());
        verifyNoInteractions(issuesCollection);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findIssuesPageReturnsPagedItemsAndFacetCounts() {
        Instant scannedAt = Instant.parse("2026-11-20T11:00:00Z");
        Document summaryDoc = new Document("_id", "Awards")
                .append("collection", "Awards")
                .append("scanId", "scan-1")
                .append("scannedAt", Date.from(scannedAt))
                .append("durationMs", 100L)
                .append("recordsWithLinks", 2L)
                .append("totalLinks", 3L)
                .append("uniqueUrls", 3L)
                .append("okCount", 1L)
                .append("brokenCount", 1L)
                .append("blockedCount", 1L)
                .append("byField", new Document());

        FindIterable<Document> summaryFind = mock(FindIterable.class);
        when(summaryCollection.find(any(Bson.class))).thenReturn(summaryFind);
        when(summaryFind.first()).thenReturn(summaryDoc);

        Document facetDoc = new Document()
                .append("byStatus", List.of(
                        new Document("_id", "BROKEN").append("count", 1L),
                        new Document("_id", "BLOCKED").append("count", 1L)
                ))
                .append("byField", List.of(
                        new Document("_id", "documents").append("count", 2L)
                ))
                .append("byReason", List.of(
                        new Document("_id", "HTTP_404").append("count", 1L),
                        new Document("_id", "HTTP_403").append("count", 1L)
                ));
        AggregateIterable<Document> facetAggregate = mock(AggregateIterable.class);
        when(issuesCollection.aggregate(anyList())).thenReturn(facetAggregate);
        when(facetAggregate.first()).thenReturn(facetDoc);

        when(issuesCollection.countDocuments(any(Bson.class))).thenReturn(1L);

        Document issueDoc = new Document("collection", "Awards")
                .append("scanId", "scan-1")
                .append("status", "BROKEN")
                .append("reason", "HTTP_404")
                .append("httpStatus", 404)
                .append("errorMessage", "Not Found")
                .append("url", "https://example.org/missing")
                .append("finalUrl", "https://example.org/missing")
                .append("sourceField", "documents")
                .append("subType", "Article")
                .append("linkLabel", "PDF")
                .append("recordId", "rec-1")
                .append("recordUuid", "uuid-1")
                .append("recordPureId", 42L)
                .append("recordTitle", "Titol")
                .append("recordType", "Project")
                .append("egretaUrl", "https://egreta.uab.cat/admin/workspace.xhtml?uuid=uuid-1")
                .append("checkedAt", Date.from(scannedAt));

        FindIterable<Document> issuesFind = mock(FindIterable.class);
        when(issuesCollection.find(any(Bson.class))).thenReturn(issuesFind);
        when(issuesFind.sort(any(Bson.class))).thenReturn(issuesFind);
        when(issuesFind.skip(anyInt())).thenReturn(issuesFind);
        when(issuesFind.limit(anyInt())).thenReturn(issuesFind);
        when(issuesFind.into(any())).thenAnswer(inv -> {
            List<Document> target = inv.getArgument(0);
            target.add(issueDoc);
            return target;
        });

        LinkIssuesPageResponse page = repository.findIssuesPage("Awards", "BROKEN", "ALL", "ALL", null, 0, 50);

        assertEquals("Awards", page.collection());
        assertEquals("scan-1", page.scanId());
        assertEquals(scannedAt, page.scannedAt());
        assertEquals(1L, page.totalItems());
        assertEquals(1, page.totalPages());
        assertEquals(Map.of("BROKEN", 1L, "BLOCKED", 1L), page.countsByStatus());
        assertEquals(Map.of("documents", 2L), page.countsByField());
        assertEquals(Map.of("HTTP_404", 1L, "HTTP_403", 1L), page.countsByReason());
        assertEquals(1, page.items().size());
        LinkAuditIssue item = page.items().get(0);
        assertEquals(LinkStatus.BROKEN, item.status());
        assertEquals(FailureReason.HTTP_404, item.reason());
        assertEquals(404, item.httpStatus());
        assertEquals(42L, item.recordPureId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamIssuesForExportStreamsMatchingIssuesThroughCursor() {
        Document summaryDoc = new Document("_id", "Awards")
                .append("collection", "Awards")
                .append("scanId", "scan-9");
        FindIterable<Document> summaryFind = mock(FindIterable.class);
        when(summaryCollection.find(any(Bson.class))).thenReturn(summaryFind);
        when(summaryFind.first()).thenReturn(summaryDoc);

        Document issueDoc = new Document("collection", "Awards")
                .append("scanId", "scan-9")
                .append("status", "BLOCKED")
                .append("reason", "HTTP_403")
                .append("url", "https://example.org/paywall");

        FindIterable<Document> exportFind = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(issuesCollection.find(any(Bson.class))).thenReturn(exportFind);
        when(exportFind.sort(any(Bson.class))).thenReturn(exportFind);
        when(exportFind.batchSize(anyInt())).thenReturn(exportFind);
        when(exportFind.noCursorTimeout(false)).thenReturn(exportFind);
        when(exportFind.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(issueDoc);

        List<LinkAuditIssue> streamed = new ArrayList<>();
        repository.streamIssuesForExport("Awards", "ALL", null, null, null, streamed::add);

        assertEquals(1, streamed.size());
        assertEquals(LinkStatus.BLOCKED, streamed.get(0).status());
        assertEquals("https://example.org/paywall", streamed.get(0).url());
        verify(cursor).close();
    }

    @Test
    void ensureIndexesCreatesCompoundIndexesAndGuardsUnavailableMongo() {
        repository.ensureIndexes();

        ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);
        verify(issuesCollection, times(3)).createIndex(any(Bson.class), optionsCaptor.capture());
        List<String> indexNames = optionsCaptor.getAllValues().stream()
                .map(IndexOptions::getName)
                .toList();
        assertEquals(List.of(
                "idx_audit_issues_col_scan_status_field",
                "idx_audit_issues_col_scan_reason",
                "idx_audit_issues_col_scan_http"
        ), indexNames);

        when(mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION))
                .thenThrow(new IllegalStateException("Mongo unavailable"));
        assertDoesNotThrow(() -> repository.ensureIndexes());
    }

    private static BsonDocument toBsonDocument(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    private static BsonDocument flattenAnd(BsonDocument doc) {
        if (!doc.containsKey("$and")) {
            return doc;
        }
        BsonDocument flattened = new BsonDocument();
        for (var entry : doc.getArray("$and")) {
            BsonDocument child = flattenAnd(entry.asDocument());
            child.forEach(flattened::put);
        }
        return flattened;
    }

    private static LinkAuditIssue sampleIssue(String collection, String scanId, String url) {
        return new LinkAuditIssue(
                collection,
                scanId,
                LinkStatus.BROKEN,
                FailureReason.HTTP_404,
                404,
                "Not Found",
                url,
                url,
                "documents",
                "Article",
                "Full text",
                "id-1",
                "uuid-1",
                100L,
                "Sample title",
                "Research output",
                "https://egreta.uab.cat/admin/workspace.xhtml?uuid=uuid-1",
                Instant.parse("2026-11-20T10:00:00Z")
        );
    }
}
