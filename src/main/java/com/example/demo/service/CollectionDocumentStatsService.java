package com.example.demo.service;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class CollectionDocumentStatsService {

    private static final Logger log = LoggerFactory.getLogger(CollectionDocumentStatsService.class);

    private final MongoTemplate mongoTemplate;

    // Cache holding the latest stats
    private volatile Map<String, Object> cachedStats = null;
    private volatile Instant lastCalculated = null;
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    public CollectionDocumentStatsService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public synchronized Map<String, Object> getDocumentStats(boolean forceRefresh) {
        if (!forceRefresh && cachedStats != null && lastCalculated != null) {
            long age = Instant.now().getEpochSecond() - lastCalculated.getEpochSecond();
            if (age < CACHE_TTL_SECONDS) {
                log.debug("Returning cached document stats (age: {}s)", age);
                return cachedStats;
            }
        }

        log.info("Calculating collection document statistics with types breakdown from MongoDB (kraken)...");
        long startTime = System.currentTimeMillis();

        Set<String> collectionNames = new TreeSet<>(mongoTemplate.getDb().listCollectionNames().into(new ArrayList<>()));

        List<Map<String, Object>> collectionStatsList = new ArrayList<>();
        long grandTotalBytes = 0;
        long grandTotalRecordsWithDocs = 0;
        long grandTotalRecords = 0;
        int collectionsWithDocsCount = 0;

        for (String colName : collectionNames) {
            try {
                MongoCollection<Document> collection = mongoTemplate.getCollection(colName);
                long totalRecords = collection.estimatedDocumentCount();
                grandTotalRecords += totalRecords;

                if (totalRecords == 0) {
                    continue;
                }

                // In Researchoutputs, inspect 'links'; in other collections, inspect 'documents'
                String targetField = "Researchoutputs".equalsIgnoreCase(colName) ? "links" : "documents";

                String attTypeField = targetField.equals("documents") ? ".type.term." : ".linkType.term.";

                Document facetPipeline = new Document("$facet", new Document()
                    .append("summary", List.of(
                        new Document("$group", new Document("_id", null)
                            .append("documentCount", new Document("$sum", 1))
                            .append("totalBytes", new Document("$sum", new Document("$bsonSize", "$$ROOT")))
                        )
                    ))
                    .append("byRecordType", List.of(
                        new Document("$group", new Document("_id",
                            new Document("$ifNull", List.of(
                                "$type.term.ca_ES",
                                new Document("$ifNull", List.of(
                                    "$type.term.es_ES",
                                    new Document("$ifNull", List.of("$type.term.en_GB", "Sense especificar"))
                                ))
                            ))
                        )
                        .append("count", new Document("$sum", 1))
                        .append("bytes", new Document("$sum", new Document("$bsonSize", "$$ROOT")))
                        ),
                        new Document("$sort", new Document("count", -1))
                    ))
                    .append("byAttachmentType", List.of(
                        new Document("$unwind", "$" + targetField),
                        new Document("$group", new Document("_id",
                            new Document("$ifNull", List.of(
                                "$" + targetField + attTypeField + "ca_ES",
                                new Document("$ifNull", List.of(
                                    "$" + targetField + attTypeField + "es_ES",
                                    new Document("$ifNull", List.of(
                                        "$" + targetField + attTypeField + "en_GB",
                                        "Sense especificar"
                                    ))
                                ))
                            ))
                        )
                        .append("count", new Document("$sum", 1))
                        ),
                        new Document("$sort", new Document("count", -1))
                    ))
                );

                List<Document> pipeline = List.of(
                    new Document("$match", new Document(targetField, new Document("$exists", true).append("$ne", List.of()))),
                    facetPipeline
                );

                Document facetResult = collection.aggregate(pipeline).first();

                if (facetResult != null) {
                    List<?> summaryList = facetResult.get("summary", List.class);
                    if (summaryList != null && !summaryList.isEmpty()) {
                        Document summary = (Document) summaryList.get(0);
                        long docCount = ((Number) summary.get("documentCount")).longValue();
                        long totalBytes = summary.get("totalBytes") != null ? ((Number) summary.get("totalBytes")).longValue() : 0;
                        double totalMB = (double) totalBytes / 1048576.0;

                        // Parse Record Types
                        List<Map<String, Object>> recordTypes = new ArrayList<>();
                        List<?> byRecordTypeList = facetResult.get("byRecordType", List.class);
                        if (byRecordTypeList != null) {
                            for (Object item : byRecordTypeList) {
                                Document d = (Document) item;
                                String typeName = Objects.toString(d.get("_id"), "Sense especificar");
                                long count = ((Number) d.get("count")).longValue();
                                long bytes = d.get("bytes") != null ? ((Number) d.get("bytes")).longValue() : 0;
                                double mb = round((double) bytes / 1048576.0, 2);
                                double pct = docCount > 0 ? round((double) count / docCount * 100.0, 1) : 0.0;

                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("typeName", typeName);
                                r.put("count", count);
                                r.put("totalMB", mb);
                                r.put("pctOfCollection", pct);
                                recordTypes.add(r);
                            }
                        }

                        // Parse Attachment/Link Types
                        List<Map<String, Object>> attachmentTypes = new ArrayList<>();
                        List<?> byAttList = facetResult.get("byAttachmentType", List.class);
                        if (byAttList != null) {
                            long totalAtts = 0;
                            for (Object item : byAttList) {
                                Document d = (Document) item;
                                totalAtts += ((Number) d.get("count")).longValue();
                            }

                            for (Object item : byAttList) {
                                Document d = (Document) item;
                                String typeName = Objects.toString(d.get("_id"), "Sense especificar");
                                long count = ((Number) d.get("count")).longValue();
                                double pct = totalAtts > 0 ? round((double) count / totalAtts * 100.0, 1) : 0.0;

                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("typeName", typeName);
                                r.put("count", count);
                                r.put("pctOfAttachments", pct);
                                attachmentTypes.add(r);
                            }
                        }

                        Map<String, Object> stat = buildStatEntry(colName, targetField, totalRecords, docCount, totalBytes, totalMB);
                        stat.put("recordTypes", recordTypes);
                        stat.put("attachmentTypes", attachmentTypes);
                        collectionStatsList.add(stat);

                        grandTotalBytes += totalBytes;
                        grandTotalRecordsWithDocs += docCount;
                        collectionsWithDocsCount++;
                        continue;
                    }
                }

                // If collection has 0 matching docs
                Map<String, Object> emptyStat = buildStatEntry(colName, targetField, totalRecords, 0, 0, 0.0);
                emptyStat.put("recordTypes", Collections.emptyList());
                emptyStat.put("attachmentTypes", Collections.emptyList());
                collectionStatsList.add(emptyStat);

            } catch (Exception e) {
                log.warn("Error processing collection {}: {}", colName, e.getMessage());
            }
        }

        // Sort descending by totalBytes (largest storage first)
        collectionStatsList.sort((a, b) -> Long.compare(
            ((Number) b.get("totalBytes")).longValue(),
            ((Number) a.get("totalBytes")).longValue()
        ));

        // Compute percentage of grand total storage for each collection
        for (Map<String, Object> item : collectionStatsList) {
            long tb = ((Number) item.get("totalBytes")).longValue();
            double pctStorage = grandTotalBytes > 0 ? round((double) tb / grandTotalBytes * 100.0, 2) : 0.0;
            item.put("pctOfTotalStorage", pctStorage);
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("Finished document statistics with types in {}ms: {} matched documents, {} MB",
                elapsedMs, grandTotalRecordsWithDocs, grandTotalBytes / 1048576);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("calculatedAt", Instant.now().toString());
        response.put("elapsedMs", elapsedMs);
        response.put("totalCollectionsScanned", collectionNames.size());
        response.put("collectionsWithDocumentsCount", collectionsWithDocsCount);
        response.put("grandTotalRecords", grandTotalRecords);
        response.put("grandTotalRecordsWithDocs", grandTotalRecordsWithDocs);
        response.put("totalFiles", grandTotalRecordsWithDocs);
        response.put("totalBytes", grandTotalBytes);
        response.put("totalMegabytes", round((double) grandTotalBytes / 1048576.0, 2));
        response.put("totalGigabytes", round((double) grandTotalBytes / (1048576.0 * 1024.0), 2));
        response.put("collections", collectionStatsList);

        this.cachedStats = response;
        this.lastCalculated = Instant.now();

        return response;
    }

    private Map<String, Object> buildStatEntry(String collectionName, String fieldName, long totalRecords,
                                              long recordsWithDocs, long totalBytes, double totalMB) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("collection", collectionName);
        map.put("field", fieldName);
        map.put("totalRecords", totalRecords);
        map.put("recordsWithDocuments", recordsWithDocs);
        
        double pctRecords = totalRecords > 0 ? round((double) recordsWithDocs / totalRecords * 100.0, 2) : 0.0;
        map.put("pctRecordsWithDocuments", pctRecords);

        map.put("totalFiles", recordsWithDocs);
        map.put("totalBytes", totalBytes);

        double roundedMB = round(totalMB, 2);
        double totalGB = round(roundedMB / 1024.0, 2);
        map.put("totalMB", roundedMB);
        map.put("totalGB", totalGB);

        double avgSizeKB = recordsWithDocs > 0 ? round(((double) totalBytes / recordsWithDocs) / 1024.0, 2) : 0.0;
        map.put("avgDocSizeKB", avgSizeKB);

        return map;
    }

    private double round(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value)
                .setScale(places, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
