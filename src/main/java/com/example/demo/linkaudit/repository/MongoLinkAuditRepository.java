package com.example.demo.linkaudit.repository;

import com.example.demo.linkaudit.LinkAuditConstants;
import com.example.demo.linkaudit.config.LinkAuditProperties;
import com.example.demo.linkaudit.model.CollectionAuditSummary;
import com.example.demo.linkaudit.model.FailureReason;
import com.example.demo.linkaudit.model.FieldAuditCounts;
import com.example.demo.linkaudit.model.LinkAuditIssue;
import com.example.demo.linkaudit.model.LinkIssuesPageResponse;
import com.example.demo.linkaudit.model.LinkStatus;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MongoDB repository implementing scan-id atomic swap persistence and filtered queries
 * against {@code CollectionLinkAuditSummary} and {@code CollectionLinkAuditIssues}.
 */
@Repository
public class MongoLinkAuditRepository implements LinkAuditStore {

    private static final Logger log = LoggerFactory.getLogger(MongoLinkAuditRepository.class);
    private static final int EXPORT_BATCH_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 200;

    // BROKEN > BLOCKED lexicographically, so descending status puts BROKEN rows first.
    private static final Bson ISSUES_SORT = Sorts.orderBy(
            Sorts.descending("status"),
            Sorts.ascending("sourceField"),
            Sorts.ascending("recordTitle")
    );

    private final MongoTemplate mongoTemplate;
    private final LinkAuditProperties properties;

    public MongoLinkAuditRepository(MongoTemplate mongoTemplate, LinkAuditProperties properties) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @PostConstruct
    @Override
    public void ensureIndexes() {
        try {
            MongoCollection<Document> issuesCol = mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION);
            issuesCol.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("collection"),
                            Indexes.ascending("scanId"),
                            Indexes.ascending("status"),
                            Indexes.ascending("sourceField")
                    ),
                    new IndexOptions().name("idx_audit_issues_col_scan_status_field")
            );
            issuesCol.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("collection"),
                            Indexes.ascending("scanId"),
                            Indexes.ascending("reason")
                    ),
                    new IndexOptions().name("idx_audit_issues_col_scan_reason")
            );
            issuesCol.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("collection"),
                            Indexes.ascending("scanId"),
                            Indexes.ascending("httpStatus")
                    ),
                    new IndexOptions().name("idx_audit_issues_col_scan_http")
            );
        } catch (Exception ex) {
            log.warn("Could not ensure link audit MongoDB indexes: {}", ex.getMessage());
        }
    }

    @Override
    public List<String> listBusinessCollections() {
        Set<String> names = new TreeSet<>();
        for (String name : mongoTemplate.getDb().listCollectionNames().into(new ArrayList<>())) {
            if (!LinkAuditConstants.isInternalCollection(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public boolean collectionExists(String collection) {
        if (collection == null || collection.isBlank() || LinkAuditConstants.isInternalCollection(collection)) {
            return false;
        }
        return mongoTemplate.getDb().listCollectionNames().into(new ArrayList<>()).contains(collection);
    }

    @Override
    public long estimatedDocumentCount(String collection) {
        if (collection == null || collection.isBlank()) {
            return 0L;
        }
        return mongoTemplate.getCollection(collection).estimatedDocumentCount();
    }

    @Override
    public List<CollectionAuditSummary> findAllSummaries() {
        List<Document> docs = mongoTemplate.getCollection(LinkAuditConstants.SUMMARY_COLLECTION)
                .find()
                .sort(Sorts.ascending("_id"))
                .into(new ArrayList<>());
        List<CollectionAuditSummary> summaries = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            summaries.add(toSummary(doc));
        }
        return summaries;
    }

    @Override
    public Optional<CollectionAuditSummary> findSummary(String collection) {
        if (collection == null || collection.isBlank()) {
            return Optional.empty();
        }
        Document doc = mongoTemplate.getCollection(LinkAuditConstants.SUMMARY_COLLECTION)
                .find(Filters.eq("_id", collection))
                .first();
        return Optional.ofNullable(doc).map(this::toSummary);
    }

    @Override
    public void insertIssuesInBatches(List<LinkAuditIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, properties.getInsertBatchSize());
        MongoCollection<Document> issuesCol = mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION);
        for (int start = 0; start < issues.size(); start += batchSize) {
            int end = Math.min(start + batchSize, issues.size());
            List<Document> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                batch.add(toIssueDocument(issues.get(i)));
            }
            issuesCol.insertMany(batch);
        }
    }

    @Override
    public void upsertSummary(CollectionAuditSummary summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        mongoTemplate.getCollection(LinkAuditConstants.SUMMARY_COLLECTION).replaceOne(
                Filters.eq("_id", summary.collection()),
                toSummaryDocument(summary),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public long deleteIssuesOtherThanScan(String collection, String activeScanId) {
        if (collection == null || collection.isBlank() || activeScanId == null || activeScanId.isBlank()) {
            return 0L;
        }
        Bson filter = Filters.and(
                Filters.eq("collection", collection),
                Filters.ne("scanId", activeScanId)
        );
        return deleteByFilter(filter);
    }

    @Override
    public long deleteIssuesForScan(String collection, String scanId) {
        if (collection == null || collection.isBlank() || scanId == null || scanId.isBlank()) {
            return 0L;
        }
        Bson filter = Filters.and(
                Filters.eq("collection", collection),
                Filters.eq("scanId", scanId)
        );
        return deleteByFilter(filter);
    }

    @Override
    public LinkIssuesPageResponse findIssuesPage(
            String collection,
            String status,
            String field,
            String reason,
            String query,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        Optional<CollectionAuditSummary> summaryOpt = findSummary(collection);
        if (summaryOpt.isEmpty() || summaryOpt.get().scanId() == null || summaryOpt.get().scanId().isBlank()) {
            return new LinkIssuesPageResponse(
                    collection,
                    null,
                    summaryOpt.map(CollectionAuditSummary::scannedAt).orElse(null),
                    safePage,
                    safeSize,
                    0L,
                    0,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of()
            );
        }

        CollectionAuditSummary summary = summaryOpt.get();
        String activeScanId = summary.scanId();
        MongoCollection<Document> issuesCol = mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION);

        FacetCounts facets = loadFacetCounts(issuesCol, collection, activeScanId);
        Bson filter = buildIssuesFilter(collection, activeScanId, status, field, reason, query);
        long totalItems = issuesCol.countDocuments(filter);
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil((double) totalItems / safeSize);
        int skip = (int) Math.min(Integer.MAX_VALUE, (long) safePage * safeSize);

        List<Document> docs = issuesCol.find(filter)
                .sort(ISSUES_SORT)
                .skip(skip)
                .limit(safeSize)
                .into(new ArrayList<>());

        List<LinkAuditIssue> items = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            items.add(toIssue(doc));
        }

        return new LinkIssuesPageResponse(
                collection,
                activeScanId,
                summary.scannedAt(),
                safePage,
                safeSize,
                totalItems,
                totalPages,
                facets.byStatus(),
                facets.byField(),
                facets.byReason(),
                items
        );
    }

    @Override
    public void streamIssuesForExport(
            String collection,
            String status,
            String field,
            String reason,
            String query,
            Consumer<LinkAuditIssue> consumer
    ) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        Optional<CollectionAuditSummary> summaryOpt = findSummary(collection);
        if (summaryOpt.isEmpty() || summaryOpt.get().scanId() == null || summaryOpt.get().scanId().isBlank()) {
            return;
        }

        Bson filter = buildIssuesFilter(collection, summaryOpt.get().scanId(), status, field, reason, query);
        FindIterable<Document> iterable = mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION)
                .find(filter)
                .sort(ISSUES_SORT)
                .batchSize(EXPORT_BATCH_SIZE)
                .noCursorTimeout(false);

        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while (cursor.hasNext()) {
                consumer.accept(toIssue(cursor.next()));
            }
        }
    }

    Bson buildIssuesFilter(
            String collection,
            String scanId,
            String status,
            String field,
            String reason,
            String query
    ) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq("collection", collection));
        conditions.add(Filters.eq("scanId", scanId));

        if (hasSpecificFilter(status)) {
            conditions.add(Filters.eq("status", status.trim().toUpperCase(Locale.ROOT)));
        }
        if (hasSpecificFilter(field)) {
            conditions.add(Filters.eq("sourceField", field.trim()));
        }
        if (hasSpecificFilter(reason)) {
            conditions.add(Filters.eq("reason", reason.trim().toUpperCase(Locale.ROOT)));
        }
        if (query != null && !query.isBlank()) {
            String quoted = Pattern.quote(query.trim());
            conditions.add(Filters.or(
                    Filters.regex("url", quoted, "i"),
                    Filters.regex("recordTitle", quoted, "i"),
                    Filters.regex("recordUuid", quoted, "i")
            ));
        }
        return Filters.and(conditions);
    }

    private long deleteByFilter(Bson filter) {
        DeleteResult result = mongoTemplate.getCollection(LinkAuditConstants.ISSUES_COLLECTION).deleteMany(filter);
        return result != null ? result.getDeletedCount() : 0L;
    }

    private FacetCounts loadFacetCounts(MongoCollection<Document> issuesCol, String collection, String activeScanId) {
        Document facetStage = new Document("$facet", new Document()
                .append("byStatus", List.of(
                        new Document("$group", new Document("_id", "$status").append("count", new Document("$sum", 1)))
                ))
                .append("byField", List.of(
                        new Document("$group", new Document("_id", "$sourceField").append("count", new Document("$sum", 1)))
                ))
                .append("byReason", List.of(
                        new Document("$group", new Document("_id", "$reason").append("count", new Document("$sum", 1))),
                        new Document("$sort", new Document("count", -1).append("_id", 1))
                )));

        List<Document> pipeline = List.of(
                new Document("$match", new Document("collection", collection).append("scanId", activeScanId)),
                facetStage
        );

        Document result = issuesCol.aggregate(pipeline).first();
        if (result == null) {
            return new FacetCounts(Map.of(), Map.of(), Map.of());
        }
        return new FacetCounts(
                extractFacetMap(result, "byStatus"),
                extractFacetMap(result, "byField"),
                extractFacetMap(result, "byReason")
        );
    }

    private Map<String, Long> extractFacetMap(Document facetResult, String facetKey) {
        Object raw = facetResult.get(facetKey);
        if (!(raw instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object item : list) {
            if (item instanceof Document entry) {
                Object id = entry.get("_id");
                if (id != null) {
                    String key = id.toString();
                    if (!key.isBlank()) {
                        counts.put(key, toLong(entry.get("count")));
                    }
                }
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Document toSummaryDocument(CollectionAuditSummary summary) {
        Document byFieldDoc = new Document();
        if (summary.byField() != null) {
            for (Map.Entry<String, FieldAuditCounts> entry : summary.byField().entrySet()) {
                FieldAuditCounts counts = entry.getValue();
                if (counts != null) {
                    byFieldDoc.append(entry.getKey(), new Document()
                            .append("totalLinks", counts.totalLinks())
                            .append("okCount", counts.okCount())
                            .append("brokenCount", counts.brokenCount())
                            .append("blockedCount", counts.blockedCount()));
                }
            }
        }
        return new Document("_id", summary.collection())
                .append("collection", summary.collection())
                .append("scanId", summary.scanId())
                .append("scannedAt", toDate(summary.scannedAt()))
                .append("durationMs", summary.durationMs())
                .append("recordsWithLinks", summary.recordsWithLinks())
                .append("totalLinks", summary.totalLinks())
                .append("uniqueUrls", summary.uniqueUrls())
                .append("okCount", summary.okCount())
                .append("brokenCount", summary.brokenCount())
                .append("blockedCount", summary.blockedCount())
                .append("byField", byFieldDoc)
                .append("lastError", summary.lastError());
    }

    private CollectionAuditSummary toSummary(Document doc) {
        String collection = doc.getString("collection");
        if (collection == null || collection.isBlank()) {
            collection = Objects.toString(doc.get("_id"), null);
        }
        Map<String, FieldAuditCounts> byField = new LinkedHashMap<>();
        Object rawByField = doc.get("byField");
        if (rawByField instanceof Document fieldDocs) {
            for (Map.Entry<String, Object> entry : fieldDocs.entrySet()) {
                if (entry.getValue() instanceof Document fieldDoc) {
                    byField.put(entry.getKey(), new FieldAuditCounts(
                            toLong(fieldDoc.get("totalLinks")),
                            toLong(fieldDoc.get("okCount")),
                            toLong(fieldDoc.get("brokenCount")),
                            toLong(fieldDoc.get("blockedCount"))
                    ));
                }
            }
        }
        return new CollectionAuditSummary(
                collection,
                doc.getString("scanId"),
                toInstant(doc.get("scannedAt")),
                toLong(doc.get("durationMs")),
                toLong(doc.get("recordsWithLinks")),
                toLong(doc.get("totalLinks")),
                toLong(doc.get("uniqueUrls")),
                toLong(doc.get("okCount")),
                toLong(doc.get("brokenCount")),
                toLong(doc.get("blockedCount")),
                Collections.unmodifiableMap(byField),
                doc.getString("lastError")
        );
    }

    private Document toIssueDocument(LinkAuditIssue issue) {
        return new Document("collection", issue.collection())
                .append("scanId", issue.scanId())
                .append("status", issue.status() != null ? issue.status().name() : null)
                .append("reason", issue.reason() != null ? issue.reason().name() : null)
                .append("httpStatus", issue.httpStatus())
                .append("errorMessage", issue.errorMessage())
                .append("url", issue.url())
                .append("finalUrl", issue.finalUrl())
                .append("sourceField", issue.sourceField())
                .append("subType", issue.subType())
                .append("linkLabel", issue.linkLabel())
                .append("recordId", issue.recordId())
                .append("recordUuid", issue.recordUuid())
                .append("recordPureId", issue.recordPureId())
                .append("recordTitle", issue.recordTitle())
                .append("recordType", issue.recordType())
                .append("egretaUrl", issue.egretaUrl())
                .append("checkedAt", toDate(issue.checkedAt()));
    }

    private LinkAuditIssue toIssue(Document doc) {
        return new LinkAuditIssue(
                doc.getString("collection"),
                doc.getString("scanId"),
                parseEnum(LinkStatus.class, doc.getString("status")),
                parseEnum(FailureReason.class, doc.getString("reason")),
                toNullableInteger(doc.get("httpStatus")),
                doc.getString("errorMessage"),
                doc.getString("url"),
                doc.getString("finalUrl"),
                doc.getString("sourceField"),
                doc.getString("subType"),
                doc.getString("linkLabel"),
                doc.getString("recordId"),
                doc.getString("recordUuid"),
                toNullableLong(doc.get("recordPureId")),
                doc.getString("recordTitle"),
                doc.getString("recordType"),
                doc.getString("egretaUrl"),
                toInstant(doc.get("checkedAt"))
        );
    }

    private static boolean hasSpecificFilter(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return !"ALL".equalsIgnoreCase(value.trim());
    }

    private static Date toDate(Instant instant) {
        return instant != null ? Date.from(instant) : null;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Instant.parse(str.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long toNullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer toNullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record FacetCounts(
            Map<String, Long> byStatus,
            Map<String, Long> byField,
            Map<String, Long> byReason
    ) {
    }
}
