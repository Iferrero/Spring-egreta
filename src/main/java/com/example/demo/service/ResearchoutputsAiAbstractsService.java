package com.example.demo.service;

import com.mongodb.client.model.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class ResearchoutputsAiAbstractsService {

    public static final String COLLECTION_NAME = "ResearchoutputsAiAbstracts";
    public static final String ORIGINAL_COLLECTION_NAME = "Researchoutputs";

    private final MongoTemplate mongoTemplate;
    private final PublicacionAbstractsService abstractsService;

    private final Map<String, CacheEntry> statsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> yearsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> filterOptionsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minuts

    private final AtomicBoolean isPopulating = new AtomicBoolean(false);
    private final AtomicLong populateTotalProcessed = new AtomicLong(0);
    private final AtomicLong populateTotalUpdated = new AtomicLong(0);
    private final AtomicLong populateDiscrepantFound = new AtomicLong(0);
    private final AtomicReference<String> populateStatus = new AtomicReference<>("IDLE");
    private final AtomicReference<String> populateError = new AtomicReference<>(null);
    private long populateStartTime = 0;
    private long populateEndTime = 0;

    public record CacheEntry(Object data, long timestamp) {}

    @Autowired
    public ResearchoutputsAiAbstractsService(
            MongoTemplate mongoTemplate,
            @Lazy PublicacionAbstractsService abstractsService) {
        this.mongoTemplate = mongoTemplate;
        this.abstractsService = abstractsService;
    }

    public void clearCache() {
        statsCache.clear();
        yearsCache.clear();
        filterOptionsCache.clear();
    }

    /**
     * Obté les opcions úniques de filtre (llengües font, models, anys min/max).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFilterOptions() {
        String cacheKey = "filter_options";
        CacheEntry ce = filterOptionsCache.get(cacheKey);
        if (ce != null && (System.currentTimeMillis() - ce.timestamp() < CACHE_TTL_MS)) {
            return (Map<String, Object>) ce.data();
        }

        List<String> models = mongoTemplate.getCollection(COLLECTION_NAME)
                .distinct("abstract_ai_metadata.model", String.class)
                .into(new ArrayList<>());
        models.removeIf(Objects::isNull);
        Collections.sort(models);

        List<String> sourceLangs = mongoTemplate.getCollection(COLLECTION_NAME)
                .distinct("abstract_ai_metadata.source_language", String.class)
                .into(new ArrayList<>());
        sourceLangs.removeIf(Objects::isNull);
        Collections.sort(sourceLangs);

        List<String> genLangs = mongoTemplate.getCollection(COLLECTION_NAME)
                .distinct("abstract_ai_metadata.generated_languages", String.class)
                .into(new ArrayList<>());
        genLangs.removeIf(Objects::isNull);
        Collections.sort(genLangs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);
        result.put("sourceLanguages", sourceLangs);
        result.put("generatedLanguages", genLangs);

        filterOptionsCache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    /**
     * Obté KPIs i estadístiques globals d'IA abstracts.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAiStats(Integer desde, Integer hasta, String sourceLang, String genLang, String yearType) {
        String effectiveYearField = "submissionYear".equalsIgnoreCase(yearType) ? "submissionYear" : "publicationYear";
        String cacheKey = String.format("%s_%s_%s_%s_%s", desde, hasta, sourceLang, genLang, effectiveYearField);
        CacheEntry ce = statsCache.get(cacheKey);
        if (ce != null && (System.currentTimeMillis() - ce.timestamp() < CACHE_TTL_MS)) {
            return (Map<String, Object>) ce.data();
        }

        List<Document> pipeline = new ArrayList<>();
        Document match = buildMatchCriteria(desde, hasta, sourceLang, genLang, null, null, effectiveYearField);
        if (!match.isEmpty()) {
            pipeline.add(new Document("$match", match));
        }

        // Agrupació general
        pipeline.add(new Document("$group", new Document("_id", null)
                .append("total", new Document("$sum", 1))
                .append("synced", new Document("$sum", new Document("$cond", List.of(
                        new Document("$eq", List.of("$abstract_ai_metadata.egreta_sync.synced", true)), 1, 0))))
                .append("notSynced", new Document("$sum", new Document("$cond", List.of(
                        new Document("$ne", List.of("$abstract_ai_metadata.egreta_sync.synced", true)), 1, 0))))
                .append("avgLatency", new Document("$avg", "$abstract_ai_metadata.total_latency_ms"))
                .append("minLatency", new Document("$min", "$abstract_ai_metadata.total_latency_ms"))
                .append("maxLatency", new Document("$max", "$abstract_ai_metadata.total_latency_ms"))
        ));

        Document globalStats = mongoTemplate.getCollection(COLLECTION_NAME).aggregate(pipeline).first();

        Map<String, Object> response = new LinkedHashMap<>();
        long total = globalStats != null ? ((Number) globalStats.getOrDefault("total", 0)).longValue() : 0;
        long synced = globalStats != null ? ((Number) globalStats.getOrDefault("synced", 0)).longValue() : 0;
        long notSynced = globalStats != null ? ((Number) globalStats.getOrDefault("notSynced", 0)).longValue() : 0;
        double avgLatency = globalStats != null && globalStats.get("avgLatency") instanceof Number num
                ? Math.round(num.doubleValue() * 10.0) / 10.0 : 0.0;
        double minLatency = globalStats != null && globalStats.get("minLatency") instanceof Number num
                ? Math.round(num.doubleValue() * 10.0) / 10.0 : 0.0;
        double maxLatency = globalStats != null && globalStats.get("maxLatency") instanceof Number num
                ? Math.round(num.doubleValue() * 10.0) / 10.0 : 0.0;
        double pctSynced = total > 0 ? Math.round((synced * 1000.0) / total) / 10.0 : 0.0;

        response.put("total", total);
        response.put("synced", synced);
        response.put("notSynced", notSynced);
        response.put("pctSynced", pctSynced);
        response.put("avgLatencyMs", avgLatency);
        response.put("minLatencyMs", minLatency);
        response.put("maxLatencyMs", maxLatency);

        // Breakdown per llengua font
        List<Document> langPipeline = new ArrayList<>();
        if (!match.isEmpty()) langPipeline.add(new Document("$match", match));
        langPipeline.add(new Document("$group", new Document("_id", "$abstract_ai_metadata.source_language")
                .append("count", new Document("$sum", 1))
                .append("synced", new Document("$sum", new Document("$cond", List.of(
                        new Document("$eq", List.of("$abstract_ai_metadata.egreta_sync.synced", true)), 1, 0))))
        ));
        langPipeline.add(new Document("$sort", new Document("count", -1)));

        List<Document> langResults = mongoTemplate.getCollection(COLLECTION_NAME).aggregate(langPipeline).into(new ArrayList<>());
        List<Map<String, Object>> sourceLangBreakdown = new ArrayList<>();
        for (Document d : langResults) {
            String lang = d.getString("_id");
            long c = ((Number) d.getOrDefault("count", 0)).longValue();
            long s = ((Number) d.getOrDefault("synced", 0)).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("language", lang != null ? lang : "Desconegut");
            m.put("count", c);
            m.put("synced", s);
            m.put("pctOfTotal", total > 0 ? Math.round((c * 1000.0) / total) / 10.0 : 0.0);
            sourceLangBreakdown.add(m);
        }
        response.put("sourceLanguageBreakdown", sourceLangBreakdown);

        // Breakdown per Model
        List<Document> modelPipeline = new ArrayList<>();
        if (!match.isEmpty()) modelPipeline.add(new Document("$match", match));
        modelPipeline.add(new Document("$group", new Document("_id", "$abstract_ai_metadata.model")
                .append("count", new Document("$sum", 1))
                .append("avgLatency", new Document("$avg", "$abstract_ai_metadata.total_latency_ms"))
        ));
        modelPipeline.add(new Document("$sort", new Document("count", -1)));

        List<Document> modelResults = mongoTemplate.getCollection(COLLECTION_NAME).aggregate(modelPipeline).into(new ArrayList<>());
        List<Map<String, Object>> modelBreakdown = new ArrayList<>();
        for (Document d : modelResults) {
            String model = d.getString("_id");
            long c = ((Number) d.getOrDefault("count", 0)).longValue();
            double avgL = d.get("avgLatency") instanceof Number num ? Math.round(num.doubleValue() * 10.0) / 10.0 : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", model != null ? model : "Desconegut");
            m.put("count", c);
            m.put("avgLatencyMs", avgL);
            modelBreakdown.add(m);
        }
        response.put("modelBreakdown", modelBreakdown);

        statsCache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis()));
        return response;
    }

    /**
     * Obté l'evolució per any de publicació o submissió.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAiAbstractsByYear(Integer desde, Integer hasta, String yearType) {
        String effectiveYearField = "submissionYear".equalsIgnoreCase(yearType) ? "submissionYear" : "publicationYear";
        String cacheKey = String.format("years_%s_%s_%s", desde, hasta, effectiveYearField);
        CacheEntry ce = yearsCache.get(cacheKey);
        if (ce != null && (System.currentTimeMillis() - ce.timestamp() < CACHE_TTL_MS)) {
            return (List<Map<String, Object>>) ce.data();
        }

        List<Document> pipeline = new ArrayList<>();
        Document match = buildMatchCriteria(desde, hasta, null, null, null, null, effectiveYearField);
        if (!match.isEmpty()) {
            pipeline.add(new Document("$match", match));
        }

        pipeline.add(new Document("$group", new Document("_id", "$" + effectiveYearField)
                .append("total", new Document("$sum", 1))
                .append("synced", new Document("$sum", new Document("$cond", List.of(
                        new Document("$eq", List.of("$abstract_ai_metadata.egreta_sync.synced", true)), 1, 0))))
                .append("notSynced", new Document("$sum", new Document("$cond", List.of(
                        new Document("$ne", List.of("$abstract_ai_metadata.egreta_sync.synced", true)), 1, 0))))
                .append("avgLatency", new Document("$avg", "$abstract_ai_metadata.total_latency_ms"))
        ));

        pipeline.add(new Document("$sort", new Document("_id", -1)));

        List<Document> rawResults = mongoTemplate.getCollection(COLLECTION_NAME).aggregate(pipeline).into(new ArrayList<>());
        List<Map<String, Object>> response = new ArrayList<>();

        for (Document d : rawResults) {
            Object yr = d.get("_id");
            Map<String, Object> item = new LinkedHashMap<>();
            long total = ((Number) d.getOrDefault("total", 0)).longValue();
            long synced = ((Number) d.getOrDefault("synced", 0)).longValue();
            long notSynced = ((Number) d.getOrDefault("notSynced", 0)).longValue();
            double avgLatency = d.get("avgLatency") instanceof Number num ? Math.round(num.doubleValue() * 10.0) / 10.0 : 0.0;

            boolean isNoYear = (yr == null || (yr instanceof Number && ((Number) yr).intValue() == 0));
            item.put("year", isNoYear ? "Sense any" : yr);
            item.put("yearValue", isNoYear ? 0 : ((Number) yr).intValue());
            item.put("isNoYear", isNoYear);
            item.put("total", total);
            item.put("synced", synced);
            item.put("notSynced", notSynced);
            item.put("pctSynced", total > 0 ? Math.round((synced * 1000.0) / total) / 10.0 : 0.0);
            item.put("avgLatencyMs", avgLatency);
            response.add(item);
        }

        yearsCache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis()));
        return response;
    }

    /**
     * Llistat paginat amb cerca i filtres de la col·lecció ResearchoutputsAiAbstracts.
     */
    public Map<String, Object> getAiAbstractsList(
            Boolean synced,
            String sourceLang,
            String genLang,
            String search,
            Integer desde,
            Integer hasta,
            String yearType,
            int page,
            int size,
            String sortField,
            String sortDir) {

        String effectiveYearField = "submissionYear".equalsIgnoreCase(yearType) ? "submissionYear" : "publicationYear";
        Document match = buildMatchCriteria(desde, hasta, sourceLang, genLang, synced, search, effectiveYearField);

        long total = mongoTemplate.getCollection(COLLECTION_NAME).countDocuments(match);

        // Sorting
        int dir = "asc".equalsIgnoreCase(sortDir) ? 1 : -1;
        String sortMongoField = switch (sortField != null ? sortField : "") {
            case "submissionYear" -> "submissionYear";
            case "title" -> "title";
            case "latency", "total_latency_ms" -> "abstract_ai_metadata.total_latency_ms";
            case "generated_at" -> "abstract_ai_metadata.generated_at";
            case "updated_at" -> "updated_at";
            default -> effectiveYearField;
        };

        Document sortDoc = new Document(sortMongoField, dir);
        if (!sortMongoField.equals("_id")) {
            sortDoc.append("_id", -1);
        }

        int pageSize = Math.max(1, Math.min(size, 100));
        int skip = Math.max(0, page) * pageSize;

        List<Document> rawDocs = mongoTemplate.getCollection(COLLECTION_NAME)
                .find(match)
                .sort(sortDoc)
                .skip(skip)
                .limit(pageSize)
                .into(new ArrayList<>());

        List<Map<String, Object>> items = new ArrayList<>();
        for (Document doc : rawDocs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", doc.getString("uuid"));
            item.put("title", doc.getString("title"));
            item.put("publicationYear", doc.get("publicationYear"));
            item.put("submissionYear", doc.get("submissionYear"));
            item.put("updated_at", doc.get("updated_at"));

            Document meta = (Document) doc.get("abstract_ai_metadata");
            if (meta != null) {
                item.put("model", meta.getString("model"));
                item.put("sourceLanguage", meta.getString("source_language"));
                item.put("generatedLanguages", meta.get("generated_languages"));
                item.put("generatedAt", meta.getString("generated_at"));
                item.put("totalLatencyMs", meta.get("total_latency_ms"));

                Document sync = (Document) meta.get("egreta_sync");
                if (sync != null) {
                    item.put("synced", Boolean.TRUE.equals(sync.getBoolean("synced")));
                    item.put("syncEnv", sync.getString("env"));
                    item.put("syncedAt", sync.getString("synced_at"));
                    item.put("syncMessage", sync.getString("message"));
                } else {
                    item.put("synced", false);
                    item.put("syncEnv", null);
                    item.put("syncedAt", null);
                    item.put("syncMessage", "Sense informació de sincronització");
                }

                @SuppressWarnings("unchecked")
                List<Document> transDetail = (List<Document>) meta.get("translations_detail");
                if (transDetail != null && !transDetail.isEmpty()) {
                    List<Map<String, Object>> transList = new ArrayList<>();
                    int totalPromptTokens = 0;
                    int totalCompletionTokens = 0;
                    for (Document td : transDetail) {
                        Map<String, Object> tMap = new LinkedHashMap<>();
                        tMap.put("fromLang", td.getString("from_lang"));
                        tMap.put("toLang", td.getString("to_lang"));
                        tMap.put("latencyMs", td.get("latency_ms"));
                        tMap.put("promptTokens", td.get("prompt_tokens"));
                        tMap.put("completionTokens", td.get("completion_tokens"));
                        tMap.put("sourcePreview", td.getString("source_preview"));
                        tMap.put("translationPreview", td.getString("translation_preview"));
                        tMap.put("simulated", td.getBoolean("simulated", false));
                        transList.add(tMap);

                        if (td.get("prompt_tokens") instanceof Number pTok) totalPromptTokens += pTok.intValue();
                        if (td.get("completion_tokens") instanceof Number cTok) totalCompletionTokens += cTok.intValue();
                    }
                    item.put("translations", transList);
                    item.put("totalPromptTokens", totalPromptTokens);
                    item.put("totalCompletionTokens", totalCompletionTokens);
                    item.put("totalTokens", totalPromptTokens + totalCompletionTokens);
                } else {
                    item.put("translations", Collections.emptyList());
                    item.put("totalPromptTokens", 0);
                    item.put("totalCompletionTokens", 0);
                    item.put("totalTokens", 0);
                }
            } else {
                item.put("synced", false);
                item.put("model", "N/A");
                item.put("sourceLanguage", "N/A");
                item.put("generatedLanguages", Collections.emptyList());
                item.put("translations", Collections.emptyList());
            }

            Document disc = (Document) doc.get("discrepancy_analysis");
            if (disc != null) {
                Map<String, Object> discMap = new LinkedHashMap<>();
                discMap.put("severity", disc.getString("severity"));
                discMap.put("diffWords", disc.get("diff_words"));
                discMap.put("ratioWords", disc.get("ratio_words"));
                discMap.put("shortestLang", disc.getString("shortest_lang"));
                discMap.put("hasDiscrepancy", disc.getBoolean("has_discrepancy", false));
                item.put("discrepancy", discMap);
            }

            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", total);
        response.put("page", page);
        response.put("size", pageSize);
        response.put("totalPages", (int) Math.ceil((double) total / pageSize));
        response.put("items", items);
        return response;
    }

    /**
     * Obté el detall complet d'una publicació amb els seus abstracts IA
     * i els abstracts originals de Researchoutputs (si existeix).
     */
    public Optional<Map<String, Object>> getAiAbstractDetail(String uuid) {
        if (uuid == null || uuid.isBlank()) return Optional.empty();

        Document aiDoc = mongoTemplate.getCollection(COLLECTION_NAME)
                .find(new Document("uuid", uuid.trim()))
                .first();

        if (aiDoc == null) {
            return Optional.empty();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("uuid", aiDoc.getString("uuid"));
        detail.put("title", aiDoc.getString("title"));
        detail.put("publicationYear", aiDoc.get("publicationYear"));
        detail.put("submissionYear", aiDoc.get("submissionYear"));
        detail.put("updated_at", aiDoc.get("updated_at"));

        Document meta = (Document) aiDoc.get("abstract_ai_metadata");
        if (meta != null) {
            detail.put("model", meta.getString("model"));
            detail.put("sourceLanguage", meta.getString("source_language"));
            detail.put("generatedLanguages", meta.get("generated_languages"));
            detail.put("generatedAt", meta.getString("generated_at"));
            detail.put("totalLatencyMs", meta.get("total_latency_ms"));

            Document sync = (Document) meta.get("egreta_sync");
            if (sync != null) {
                detail.put("synced", Boolean.TRUE.equals(sync.getBoolean("synced")));
                detail.put("syncEnv", sync.getString("env"));
                detail.put("syncedAt", sync.getString("synced_at"));
                detail.put("syncMessage", sync.getString("message"));
            }

            @SuppressWarnings("unchecked")
            List<Document> transDetail = (List<Document>) meta.get("translations_detail");
            if (transDetail != null) {
                List<Map<String, Object>> transList = new ArrayList<>();
                for (Document td : transDetail) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("fromLang", td.getString("from_lang"));
                    t.put("toLang", td.getString("to_lang"));
                    t.put("latencyMs", td.get("latency_ms"));
                    t.put("promptTokens", td.get("prompt_tokens"));
                    t.put("completionTokens", td.get("completion_tokens"));
                    t.put("sourcePreview", td.getString("source_preview"));
                    t.put("translationPreview", td.getString("translation_preview"));
                    t.put("simulated", td.getBoolean("simulated", false));
                    transList.add(t);
                }
                detail.put("translationsDetail", transList);
            }
        }

        Document discDoc = (Document) aiDoc.get("discrepancy_analysis");
        if (discDoc != null) {
            detail.put("discrepancyAnalysis", discDoc);
        }

        // Consultem la col·lecció mare Researchoutputs per creuar amb els abstracts emmagatzemats a Pure
        try {
            Document origDoc = mongoTemplate.getCollection(ORIGINAL_COLLECTION_NAME)
                    .find(new Document("uuid", uuid.trim()))
                    .projection(new Document("abstract", 1).append("type", 1).append("pureId", 1).append("info", 1))
                    .first();

            if (origDoc != null) {
                Map<String, Object> originalInfo = new LinkedHashMap<>();
                originalInfo.put("pureId", origDoc.get("pureId"));
                originalInfo.put("type", origDoc.get("type"));

                Document absObj = (Document) origDoc.get("abstract");
                Map<String, String> pureAbstracts = new LinkedHashMap<>();
                if (absObj != null) {
                    for (String key : absObj.keySet()) {
                        Object val = absObj.get(key);
                        if (val instanceof Document langDoc) {
                            String txt = langDoc.getString("value");
                            if (txt != null) pureAbstracts.put(key, txt);
                        } else if (val instanceof String strVal) {
                            pureAbstracts.put(key, strVal);
                        }
                    }
                }
                originalInfo.put("abstracts", pureAbstracts);
                detail.put("pureOriginal", originalInfo);
            }
        } catch (Exception e) {
            // Ignorem error secundari si no troba Researchoutputs
        }

        return Optional.of(detail);
    }

    /**
     * Construeix el filtre de cerca MongoDB combinat.
     */
    private Document buildMatchCriteria(
            Integer desde,
            Integer hasta,
            String sourceLang,
            String genLang,
            Boolean synced,
            String search,
            String effectiveYearField) {

        Document match = new Document();

        // Anys
        if (desde != null || hasta != null) {
            Document yearFilter = new Document();
            if (desde != null) yearFilter.append("$gte", desde);
            if (hasta != null) yearFilter.append("$lte", hasta);
            match.append(effectiveYearField, yearFilter);
        }

        // Sincronitzat
        if (synced != null) {
            if (synced) {
                match.append("abstract_ai_metadata.egreta_sync.synced", true);
            } else {
                match.append("abstract_ai_metadata.egreta_sync.synced", new Document("$ne", true));
            }
        }

        // Llengua font
        if (sourceLang != null && !sourceLang.isBlank() && !"all".equalsIgnoreCase(sourceLang)) {
            match.append("abstract_ai_metadata.source_language", sourceLang.trim());
        }

        // Llengua generada
        if (genLang != null && !genLang.isBlank() && !"all".equalsIgnoreCase(genLang)) {
            match.append("abstract_ai_metadata.generated_languages", genLang.trim());
        }

        // Cerca lliure (títol, uuid, preview de traducció)
        if (search != null && !search.trim().isEmpty()) {
            String s = Pattern.quote(search.trim());
            Pattern p = Pattern.compile(s, Pattern.CASE_INSENSITIVE);
            List<Document> orList = List.of(
                    new Document("title", new Document("$regex", p)),
                    new Document("uuid", new Document("$regex", p)),
                    new Document("abstract_ai_metadata.translations_detail.translation_preview", new Document("$regex", p)),
                    new Document("abstract_ai_metadata.translations_detail.source_preview", new Document("$regex", p))
            );
            match.append("$or", orList);
        }

        return match;
    }

    public Map<String, Object> getPopulateStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", populateStatus.get());
        status.put("processed", populateTotalProcessed.get());
        status.put("updated", populateTotalUpdated.get());
        status.put("discrepantFound", populateDiscrepantFound.get());
        long now = System.currentTimeMillis();
        long durationMs = populateStartTime > 0 ? (populateEndTime > 0 ? populateEndTime - populateStartTime : now - populateStartTime) : 0;
        status.put("elapsedSeconds", Math.round(durationMs / 100.0) / 10.0);
        status.put("error", populateError.get());
        status.put("collection", COLLECTION_NAME);
        return status;
    }

    public void ensureDiscrepancyIndexes() {
        try {
            var col = mongoTemplate.getCollection(COLLECTION_NAME);
            col.createIndex(new Document("discrepancy_analysis.severity", 1));
            col.createIndex(new Document("discrepancy_analysis.diff_words", -1));
            col.createIndex(new Document("discrepancy_analysis.ratio_words", -1));
            col.createIndex(new Document("discrepancy_analysis.shortest_lang", 1));
            col.createIndex(new Document("discrepancy_analysis.has_discrepancy", 1));
        } catch (Exception e) {
            System.err.println("Error creant índexs a " + COLLECTION_NAME + ": " + e.getMessage());
        }
    }

    public Map<String, Object> populateDiscrepanciesAsync(Boolean onlyDiscrepant, Integer limit, Boolean includeLingua) {
        if (!isPopulating.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("status", "ALREADY_RUNNING");
            busy.put("message", "El procés d'actualització de discrepàncies ja està en execució");
            busy.put("progress", getPopulateStatus());
            return busy;
        }

        populateTotalProcessed.set(0);
        populateTotalUpdated.set(0);
        populateDiscrepantFound.set(0);
        populateError.set(null);
        populateStartTime = System.currentTimeMillis();
        populateEndTime = 0;
        populateStatus.set("RUNNING");

        CompletableFuture.runAsync(() -> {
            try {
                ensureDiscrepancyIndexes();

                Document filter = new Document("abstract", new Document("$exists", true).append("$ne", null));
                Document projection = new Document("uuid", 1)
                        .append("title.value", 1)
                        .append("publicationYear", 1)
                        .append("submissionYear", 1)
                        .append("type.term.ca_ES", 1)
                        .append("contributors.name", 1)
                        .append("abstract.ca_ES", 1)
                        .append("abstract.es_ES", 1)
                        .append("abstract.en_GB", 1);

                var query = mongoTemplate.getCollection(ORIGINAL_COLLECTION_NAME)
                        .find(filter)
                        .projection(projection)
                        .batchSize(100);

                if (limit != null && limit > 0) {
                    query = query.limit(limit);
                }

                List<WriteModel<Document>> bulkList = new ArrayList<>(100);
                var targetCol = mongoTemplate.getCollection(COLLECTION_NAME);

                try (var cursor = query.iterator()) {
                    while (cursor.hasNext()) {
                        Document doc = cursor.next();
                        String uuid = doc.getString("uuid");
                        if (uuid == null || uuid.isBlank()) continue;

                        populateTotalProcessed.incrementAndGet();

                        Document absDoc = (Document) doc.get("abstract");
                        String ca = extractAbstractText(absDoc, "ca_ES");
                        String es = extractAbstractText(absDoc, "es_ES");
                        String en = extractAbstractText(absDoc, "en_GB");

                        String authors = extractAuthors(doc);
                        String typeStr = extractType(doc);

                        Document discrepancy = buildDiscrepancyDocument(ca, es, en, Boolean.TRUE.equals(includeLingua));
                        discrepancy.append("preview_ca", truncate(ca, 200))
                                   .append("preview_es", truncate(es, 200))
                                   .append("preview_en", truncate(en, 200));

                        boolean hasDisc = discrepancy.getBoolean("has_discrepancy", false);
                        if (hasDisc) {
                            populateDiscrepantFound.incrementAndGet();
                        }

                        if (Boolean.TRUE.equals(onlyDiscrepant) && !hasDisc) {
                            continue;
                        }

                        Document setDoc = new Document("discrepancy_analysis", discrepancy)
                                .append("updated_at", Instant.now().toString());
                        if (authors != null && !authors.isBlank() && !"Sense autors".equals(authors)) {
                            setDoc.append("authors", authors);
                        }
                        if (typeStr != null && !typeStr.isBlank() && !"Sense tipus".equals(typeStr)) {
                            setDoc.append("type", typeStr);
                        }

                        Document updateDoc = new Document("$set", setDoc);

                        Document setOnInsert = new Document("uuid", uuid);
                        Object title = doc.get("title");
                        if (title instanceof Document td) {
                            setOnInsert.append("title", td.getString("value"));
                        } else if (title instanceof String ts) {
                            setOnInsert.append("title", ts);
                        }
                        if (doc.get("publicationYear") != null) setOnInsert.append("publicationYear", doc.get("publicationYear"));
                        if (doc.get("submissionYear") != null) setOnInsert.append("submissionYear", doc.get("submissionYear"));
                        updateDoc.append("$setOnInsert", setOnInsert);

                        bulkList.add(new UpdateOneModel<>(
                                Filters.eq("uuid", uuid),
                                updateDoc,
                                new UpdateOptions().upsert(true)
                        ));

                        if (bulkList.size() >= 100) {
                            var bulkResult = targetCol.bulkWrite(bulkList, new BulkWriteOptions().ordered(false));
                            populateTotalUpdated.addAndGet(bulkResult.getModifiedCount() + bulkResult.getUpserts().size());
                            bulkList.clear();
                        }
                    }
                }

                if (!bulkList.isEmpty()) {
                    var bulkResult = targetCol.bulkWrite(bulkList, new BulkWriteOptions().ordered(false));
                    populateTotalUpdated.addAndGet(bulkResult.getModifiedCount() + bulkResult.getUpserts().size());
                    bulkList.clear();
                }

                populateStatus.set("COMPLETED");
                clearCache();
            } catch (Exception e) {
                System.err.println("Error en populateDiscrepanciesAsync: " + e.getMessage());
                e.printStackTrace();
                populateError.set(e.getMessage());
                populateStatus.set("ERROR");
            } finally {
                populateEndTime = System.currentTimeMillis();
                isPopulating.set(false);
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "STARTED");
        resp.put("message", "Procés d'actualització iniciat en segon pla per a " + COLLECTION_NAME);
        resp.put("progress", getPopulateStatus());
        return resp;
    }

    public Optional<Map<String, Object>> populateSingleDiscrepancy(String uuid) {
        if (uuid == null || uuid.isBlank()) return Optional.empty();

        Document doc = mongoTemplate.getCollection(ORIGINAL_COLLECTION_NAME)
                .find(Filters.eq("uuid", uuid.trim()))
                .projection(new Document("uuid", 1)
                        .append("title.value", 1)
                        .append("publicationYear", 1)
                        .append("submissionYear", 1)
                        .append("type.term.ca_ES", 1)
                        .append("contributors.name", 1)
                        .append("abstract", 1))
                .first();

        if (doc == null) return Optional.empty();

        Document absDoc = (Document) doc.get("abstract");
        String ca = extractAbstractText(absDoc, "ca_ES");
        String es = extractAbstractText(absDoc, "es_ES");
        String en = extractAbstractText(absDoc, "en_GB");

        String authors = extractAuthors(doc);
        String typeStr = extractType(doc);

        Document discrepancy = buildDiscrepancyDocument(ca, es, en, true);
        discrepancy.append("preview_ca", truncate(ca, 200))
                   .append("preview_es", truncate(es, 200))
                   .append("preview_en", truncate(en, 200));

        Document setDoc = new Document("discrepancy_analysis", discrepancy)
                .append("updated_at", Instant.now().toString());
        if (!"Sense autors".equals(authors)) {
            setDoc.append("authors", authors);
        }
        if (!"Sense tipus".equals(typeStr)) {
            setDoc.append("type", typeStr);
        }

        Document updateDoc = new Document("$set", setDoc);

        Document setOnInsert = new Document("uuid", uuid.trim());
        Object title = doc.get("title");
        if (title instanceof Document td) {
            setOnInsert.append("title", td.getString("value"));
        } else if (title instanceof String ts) {
            setOnInsert.append("title", ts);
        }
        if (doc.get("publicationYear") != null) setOnInsert.append("publicationYear", doc.get("publicationYear"));
        if (doc.get("submissionYear") != null) setOnInsert.append("submissionYear", doc.get("submissionYear"));
        updateDoc.append("$setOnInsert", setOnInsert);

        mongoTemplate.getCollection(COLLECTION_NAME).updateOne(
                Filters.eq("uuid", uuid.trim()),
                updateDoc,
                new UpdateOptions().upsert(true)
        );

        return Optional.of(discrepancy);
    }

    public Document buildDiscrepancyDocument(String ca, String es, String en, boolean runLinguaAlways) {
        boolean hasCa = !PublicacionAbstractsService.isHtmlEmpty(ca);
        boolean hasEs = !PublicacionAbstractsService.isHtmlEmpty(es);
        boolean hasEn = !PublicacionAbstractsService.isHtmlEmpty(en);

        int wCa = hasCa ? PublicacionAbstractsService.countWords(ca) : 0;
        int wEs = hasEs ? PublicacionAbstractsService.countWords(es) : 0;
        int wEn = hasEn ? PublicacionAbstractsService.countWords(en) : 0;

        int cCa = ca != null ? ca.length() : 0;
        int cEs = es != null ? es.length() : 0;
        int cEn = en != null ? en.length() : 0;

        List<Integer> activeCounts = new ArrayList<>();
        if (hasCa) activeCounts.add(wCa);
        if (hasEs) activeCounts.add(wEs);
        if (hasEn) activeCounts.add(wEn);

        int diffW = 0;
        double ratioW = 1.0;
        double pctDiscrepancy = 0.0;
        String shortestLang = "none";
        String longestLang = "none";
        String severity = "none";
        boolean hasDiscrepancy = false;

        if (activeCounts.size() >= 2) {
            int maxW = Collections.max(activeCounts);
            int minW = Collections.min(activeCounts);
            diffW = maxW - minW;
            ratioW = minW > 0 ? (double) maxW / minW : (maxW > 0 ? 99.0 : 1.0);
            pctDiscrepancy = maxW > 0 ? ((double) diffW / maxW) * 100.0 : 0.0;

            shortestLang = (hasCa && wCa == minW) ? "ca" : ((hasEs && wEs == minW) ? "es" : "en");
            longestLang = (hasEn && wEn == maxW) ? "en" : ((hasEs && wEs == maxW) ? "es" : "ca");

            if (ratioW >= 4.0 || diffW >= 150) {
                severity = "critical";
            } else if (ratioW >= 2.5 || diffW >= 80) {
                severity = "high";
            } else if (ratioW >= 1.5 || diffW >= 50) {
                severity = "moderate";
            } else {
                severity = "none";
            }
            hasDiscrepancy = (diffW >= 50 || ratioW >= 1.5);
        }

        String detectedCa = "none";
        String detectedEs = "none";
        String detectedEn = "none";

        if (abstractsService != null && (runLinguaAlways || hasDiscrepancy)) {
            if (hasCa) detectedCa = abstractsService.detectLanguage(ca);
            if (hasEs) detectedEs = abstractsService.detectLanguage(es);
            if (hasEn) detectedEn = abstractsService.detectLanguage(en);
        }

        return new Document()
                .append("has_ca", hasCa)
                .append("has_es", hasEs)
                .append("has_en", hasEn)
                .append("words_ca", wCa)
                .append("words_es", wEs)
                .append("words_en", wEn)
                .append("chars_ca", cCa)
                .append("chars_es", cEs)
                .append("chars_en", cEn)
                .append("diff_words", diffW)
                .append("ratio_words", Math.round(ratioW * 10.0) / 10.0)
                .append("pct_discrepancy", Math.round(pctDiscrepancy * 10.0) / 10.0)
                .append("shortest_lang", shortestLang)
                .append("longest_lang", longestLang)
                .append("severity", severity)
                .append("has_discrepancy", hasDiscrepancy)
                .append("detected_ca", detectedCa)
                .append("detected_es", detectedEs)
                .append("detected_en", detectedEn)
                .append("analyzed_at", Instant.now().toString());
    }

    public boolean hasDiscrepanciesPopulated() {
        try {
            return mongoTemplate.getCollection(COLLECTION_NAME)
                    .countDocuments(new Document("discrepancy_analysis.has_discrepancy", true)) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDiscrepanciesStatsFromAi(
            Integer desde, Integer hasta, String type, String yearType,
            Integer minDiff, Double minRatio, Integer minLangs) {

        int effectiveMinDiff = minDiff != null ? minDiff : 50;
        double effectiveMinRatio = minRatio != null ? minRatio : 1.5;
        int effectiveMinLangs = minLangs != null && minLangs == 3 ? 3 : 2;

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("discrepancy_analysis.has_discrepancy", new Document("$exists", true)));

        if (effectiveMinLangs == 3) {
            matchConditions.add(new Document("discrepancy_analysis.has_ca", true));
            matchConditions.add(new Document("discrepancy_analysis.has_es", true));
            matchConditions.add(new Document("discrepancy_analysis.has_en", true));
        }

        String yearField = "submissionYear".equalsIgnoreCase(yearType) ? "submissionYear" : "publicationYear";
        if (desde != null) {
            if (desde == 0) {
                matchConditions.add(new Document("$or", List.of(
                    new Document(yearField, null),
                    new Document(yearField, 0),
                    new Document(yearField, new Document("$exists", false))
                )));
            } else {
                matchConditions.add(new Document(yearField, new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) {
            matchConditions.add(new Document(yearField, new Document("$lte", hasta)));
        }
        if (type != null && !type.isBlank()) {
            matchConditions.add(new Document("type", type));
        }

        Document baseMatch = matchConditions.size() == 1 ? matchConditions.get(0) : new Document("$and", matchConditions);

        Document discCond = new Document("$or", List.of(
            new Document("$gte", List.of("$discrepancy_analysis.diff_words", effectiveMinDiff)),
            new Document("$gte", List.of("$discrepancy_analysis.ratio_words", effectiveMinRatio))
        ));

        Document group = new Document("_id", null)
            .append("totalMulti", new Document("$sum", 1))
            .append("totalWordsCa", new Document("$sum", "$discrepancy_analysis.words_ca"))
            .append("totalWordsEs", new Document("$sum", "$discrepancy_analysis.words_es"))
            .append("totalWordsEn", new Document("$sum", "$discrepancy_analysis.words_en"))
            .append("countCa", new Document("$sum", new Document("$cond", List.of("$discrepancy_analysis.has_ca", 1, 0))))
            .append("countEs", new Document("$sum", new Document("$cond", List.of("$discrepancy_analysis.has_es", 1, 0))))
            .append("countEn", new Document("$sum", new Document("$cond", List.of("$discrepancy_analysis.has_en", 1, 0))))
            .append("totalDiscrepant", new Document("$sum", new Document("$cond", List.of(discCond, 1, 0))))
            .append("criticalCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.severity", "critical")))), 1, 0))))
            .append("highCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.severity", "high")))), 1, 0))))
            .append("moderateCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.severity", "moderate")))), 1, 0))))
            .append("caShortestCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.shortest_lang", "ca")))), 1, 0))))
            .append("esShortestCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.shortest_lang", "es")))), 1, 0))))
            .append("enShortestCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.shortest_lang", "en")))), 1, 0))))
            .append("caLongestCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.longest_lang", "ca")))), 1, 0))))
            .append("esLongestCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.longest_lang", "es")))), 1, 0))))
            .append("enLongestCount", new Document("$sum", new Document("$cond", List.of(
                new Document("$and", List.of(discCond, new Document("$eq", List.of("$discrepancy_analysis.longest_lang", "en")))), 1, 0))))
            .append("sumDiffWords", new Document("$sum", new Document("$cond", List.of(discCond, "$discrepancy_analysis.diff_words", 0))));

        List<Document> pipeline = List.of(new Document("$match", baseMatch), new Document("$group", group));
        Document res = mongoTemplate.getCollection(COLLECTION_NAME).aggregate(pipeline).first();

        Map<String, Object> resp = new LinkedHashMap<>();
        if (res == null) {
            resp.put("totalMultiLangs", 0);
            resp.put("totalDiscrepant", 0);
            resp.put("pctDiscrepant", 0.0);
            resp.put("criticalCount", 0);
            resp.put("highCount", 0);
            resp.put("moderateCount", 0);
            resp.put("caShortestCount", 0);
            resp.put("esShortestCount", 0);
            resp.put("enShortestCount", 0);
            resp.put("caLongestCount", 0);
            resp.put("esLongestCount", 0);
            resp.put("enLongestCount", 0);
            resp.put("avgWordsCa", 0);
            resp.put("avgWordsEs", 0);
            resp.put("avgWordsEn", 0);
            resp.put("avgDiffWords", 0);
            resp.put("source", COLLECTION_NAME);
            return resp;
        }

        long totalMulti = ((Number) res.getOrDefault("totalMulti", 0)).longValue();
        long totalDiscrepant = ((Number) res.getOrDefault("totalDiscrepant", 0)).longValue();
        long criticalCount = ((Number) res.getOrDefault("criticalCount", 0)).longValue();
        long highCount = ((Number) res.getOrDefault("highCount", 0)).longValue();
        long moderateCount = ((Number) res.getOrDefault("moderateCount", 0)).longValue();

        long caShortestCount = ((Number) res.getOrDefault("caShortestCount", 0)).longValue();
        long esShortestCount = ((Number) res.getOrDefault("esShortestCount", 0)).longValue();
        long enShortestCount = ((Number) res.getOrDefault("enShortestCount", 0)).longValue();

        long caLongestCount = ((Number) res.getOrDefault("caLongestCount", 0)).longValue();
        long esLongestCount = ((Number) res.getOrDefault("esLongestCount", 0)).longValue();
        long enLongestCount = ((Number) res.getOrDefault("enLongestCount", 0)).longValue();

        long countCa = ((Number) res.getOrDefault("countCa", 0)).longValue();
        long countEs = ((Number) res.getOrDefault("countEs", 0)).longValue();
        long countEn = ((Number) res.getOrDefault("countEn", 0)).longValue();

        long totalWordsCa = ((Number) res.getOrDefault("totalWordsCa", 0)).longValue();
        long totalWordsEs = ((Number) res.getOrDefault("totalWordsEs", 0)).longValue();
        long totalWordsEn = ((Number) res.getOrDefault("totalWordsEn", 0)).longValue();
        long sumDiffWords = ((Number) res.getOrDefault("sumDiffWords", 0)).longValue();

        resp.put("totalMultiLangs", totalMulti);
        resp.put("totalDiscrepant", totalDiscrepant);
        resp.put("pctDiscrepant", totalMulti > 0 ? Math.round(((double) totalDiscrepant / totalMulti) * 1000.0) / 10.0 : 0.0);
        resp.put("criticalCount", criticalCount);
        resp.put("highCount", highCount);
        resp.put("moderateCount", moderateCount);

        resp.put("caShortestCount", caShortestCount);
        resp.put("esShortestCount", esShortestCount);
        resp.put("enShortestCount", enShortestCount);

        resp.put("caLongestCount", caLongestCount);
        resp.put("esLongestCount", esLongestCount);
        resp.put("enLongestCount", enLongestCount);

        resp.put("avgWordsCa", countCa > 0 ? Math.round((double) totalWordsCa / countCa) : 0);
        resp.put("avgWordsEs", countEs > 0 ? Math.round((double) totalWordsEs / countEs) : 0);
        resp.put("avgWordsEn", countEn > 0 ? Math.round((double) totalWordsEn / countEn) : 0);
        resp.put("avgDiffWords", totalDiscrepant > 0 ? Math.round((double) sumDiffWords / totalDiscrepant) : 0);
        resp.put("source", COLLECTION_NAME);
        return resp;
    }

    public Map<String, Object> getDiscrepanciesListFromAi(
            Integer desde, Integer hasta, String type, String yearType,
            Integer minDiff, Double minRatio, Integer minLangs,
            String shortestLang, String longestLang, String severity,
            String search, int page, int size, String sortField, String sortDir) {

        int effectiveMinDiff = minDiff != null ? minDiff : 50;
        double effectiveMinRatio = minRatio != null ? minRatio : 1.5;
        int effectiveMinLangs = minLangs != null && minLangs == 3 ? 3 : 2;

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("discrepancy_analysis.has_discrepancy", new Document("$exists", true)));
        matchConditions.add(new Document("$or", List.of(
            new Document("discrepancy_analysis.diff_words", new Document("$gte", effectiveMinDiff)),
            new Document("discrepancy_analysis.ratio_words", new Document("$gte", effectiveMinRatio))
        )));

        if (effectiveMinLangs == 3) {
            matchConditions.add(new Document("discrepancy_analysis.has_ca", true));
            matchConditions.add(new Document("discrepancy_analysis.has_es", true));
            matchConditions.add(new Document("discrepancy_analysis.has_en", true));
        }

        String checkYearField = "submissionYear".equalsIgnoreCase(yearType) ? "submissionYear" : "publicationYear";
        if (desde != null) {
            if (desde == 0) {
                matchConditions.add(new Document("$or", List.of(
                    new Document(checkYearField, null),
                    new Document(checkYearField, 0),
                    new Document(checkYearField, new Document("$exists", false))
                )));
            } else {
                matchConditions.add(new Document(checkYearField, new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) {
            matchConditions.add(new Document(checkYearField, new Document("$lte", hasta)));
        }

        if (type != null && !type.isBlank()) {
            matchConditions.add(new Document("type", type));
        }

        if (shortestLang != null && !shortestLang.isBlank() && !"all".equalsIgnoreCase(shortestLang)) {
            matchConditions.add(new Document("discrepancy_analysis.shortest_lang", shortestLang.toLowerCase()));
        }
        if (longestLang != null && !longestLang.isBlank() && !"all".equalsIgnoreCase(longestLang)) {
            matchConditions.add(new Document("discrepancy_analysis.longest_lang", longestLang.toLowerCase()));
        }
        if (severity != null && !severity.isBlank() && !"all".equalsIgnoreCase(severity)) {
            matchConditions.add(new Document("discrepancy_analysis.severity", severity.toLowerCase()));
        }

        if (search != null && !search.isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            matchConditions.add(new Document("$or", List.of(
                new Document("title", p),
                new Document("authors", p),
                new Document("uuid", search.trim())
            )));
        }

        Document finalMatch = new Document("$and", matchConditions);
        long totalElements = mongoTemplate.getCollection(COLLECTION_NAME).countDocuments(finalMatch);

        int dir = "asc".equalsIgnoreCase(sortDir) ? 1 : -1;
        String mongoSortField = switch (sortField != null ? sortField : "") {
            case "publicationYear" -> "publicationYear";
            case "submissionYear" -> "submissionYear";
            case "ratioWords" -> "discrepancy_analysis.ratio_words";
            case "wordsCa" -> "discrepancy_analysis.words_ca";
            case "wordsEs" -> "discrepancy_analysis.words_es";
            case "wordsEn" -> "discrepancy_analysis.words_en";
            case "diffWords" -> "discrepancy_analysis.diff_words";
            default -> "discrepancy_analysis.diff_words";
        };
        Document sortDoc = new Document(mongoSortField, dir).append("_id", -1);

        int pageSize = Math.max(1, Math.min(size, 100));
        int skip = Math.max(0, page) * pageSize;

        List<Document> docs = mongoTemplate.getCollection(COLLECTION_NAME)
                .find(finalMatch)
                .sort(sortDoc)
                .skip(skip)
                .limit(pageSize)
                .into(new ArrayList<>());

        List<Map<String, Object>> items = new ArrayList<>();
        for (Document d : docs) {
            Document disc = (Document) d.get("discrepancy_analysis");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", d.getString("uuid"));
            item.put("title", d.getString("title"));
            item.put("publicationYear", d.get("publicationYear"));
            item.put("submissionYear", d.get("submissionYear"));
            item.put("type", d.getOrDefault("type", "Sense tipus"));
            item.put("authors", d.getOrDefault("authors", "Sense autors"));

            if (disc != null) {
                item.put("hasCa", disc.getBoolean("has_ca", false));
                item.put("hasEs", disc.getBoolean("has_es", false));
                item.put("hasEn", disc.getBoolean("has_en", false));
                item.put("wordsCa", disc.get("words_ca"));
                item.put("wordsEs", disc.get("words_es"));
                item.put("wordsEn", disc.get("words_en"));
                item.put("charsCa", disc.get("chars_ca"));
                item.put("charsEs", disc.get("chars_es"));
                item.put("charsEn", disc.get("chars_en"));
                item.put("diffWords", disc.get("diff_words"));
                item.put("ratioWords", disc.get("ratio_words"));
                item.put("pctDiscrepancy", disc.get("pct_discrepancy"));
                item.put("shortestLang", disc.getString("shortest_lang"));
                item.put("longestLang", disc.getString("longest_lang"));
                item.put("severity", disc.getString("severity"));
                item.put("detectedCa", disc.getString("detected_ca"));
                item.put("detectedEs", disc.getString("detected_es"));
                item.put("detectedEn", disc.getString("detected_en"));
                item.put("abstractPreviewCa", disc.getString("preview_ca"));
                item.put("abstractPreviewEs", disc.getString("preview_es"));
                item.put("abstractPreviewEn", disc.getString("preview_en"));
            }
            items.add(item);
        }

        int totalPages = (int) Math.ceil((double) totalElements / pageSize);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("size", pageSize);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("source", COLLECTION_NAME);
        response.put("items", items);
        return response;
    }

    private String extractAuthors(Document doc) {
        Object contribs = doc.get("contributors");
        if (contribs instanceof List<?> list && !list.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Document cd) {
                    Object nameObj = cd.get("name");
                    if (nameObj instanceof Document nd) {
                        String text = nd.getString("text");
                        if (text != null && !text.isBlank()) names.add(text);
                        else {
                            String fn = nd.getString("firstName");
                            String ln = nd.getString("lastName");
                            if (fn != null || ln != null) names.add(((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim());
                        }
                    } else if (nameObj instanceof String ns && !ns.isBlank()) {
                        names.add(ns);
                    }
                }
            }
            if (!names.isEmpty()) return String.join("; ", names);
        }
        return "Sense autors";
    }

    private String extractType(Document doc) {
        Object typeObj = doc.get("type");
        if (typeObj instanceof Document td) {
            Object termObj = td.get("term");
            if (termObj instanceof Document tm) {
                String t = tm.getString("ca_ES");
                if (t != null && !t.isBlank()) return t;
            }
        }
        return "Sense tipus";
    }

    private String truncate(String text, int maxLength) {
        if (text == null || PublicacionAbstractsService.isHtmlEmpty(text)) return "";
        String cleaned = text.replaceAll("<[^>]+>", " ")
                             .replaceAll("&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?", " ")
                             .replaceAll("\\s+", " ")
                             .trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    private String extractAbstractText(Document absDoc, String langKey) {
        if (absDoc == null) return null;
        Object val = absDoc.get(langKey);
        if (val instanceof String s) return s;
        if (val instanceof Document d) return d.getString("value");
        return null;
    }
}
