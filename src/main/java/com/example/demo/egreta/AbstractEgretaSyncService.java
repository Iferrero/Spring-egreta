package com.example.demo.egreta;

import com.example.demo.egreta.SyncProgressRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.CollectionOptions;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public abstract class AbstractEgretaSyncService {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Autowired
    protected WebClient.Builder webClientBuilder;

    @Autowired
    protected SyncProgressRegistry syncProgressRegistry;

    @Value("${egreta.base.url:https://egreta.uab.cat/ws/api}")
    protected String baseUrl;

    @Value("${egreta.api.key:}")
    protected String apiKey;

    protected abstract String getEndpoint();
    protected abstract String getCollectionName();

    public String collectionName() {
        return getCollectionName();
    }

    // Configurables por subclase
    protected int getPageSize() { return 1000; }
    protected int getMaxBufferSizeMB() { return 10; }
    protected int getMaxWorkers() { return 4; }
    protected String getItemsField() { return "items"; }
    protected String getCountField() { return "count"; }

    public long getMongoCount() {
        try {
            if (!mongoTemplate.collectionExists(getCollectionName())) {
                return 0L;
            }
            return mongoTemplate.getCollection(getCollectionName()).countDocuments();
        } catch (Exception e) {
            logger.warn("Error counting Mongo documents for '{}': {}", getCollectionName(), e.getMessage());
            return 0L;
        }
    }

    public long getSourceCollectionCount() {
        if (apiKey == null || apiKey.isEmpty()) {
            return 0L;
        }
        return fetchTotalCount(buildClient());
    }

    protected WebClient buildClient() {
        return webClientBuilder
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(getMaxBufferSizeMB() * 1024 * 1024))
                .build();
    }

    // Nombre de la colección temporal
    protected String getTmpCollectionName() { return getCollectionName() + "_tmp"; }

    @Async
    public void sync() {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.error("egreta.api.key not set in properties");
            return;
        }

        WebClient client = buildClient();

        // 1. Obtener total de registros
        int total = fetchTotalCount(client);
        if (total <= 0) {
            logger.warn("No records to sync (total={})", total);
            return;
        }
        logger.info("Total records to sync: {}", total);
        syncProgressRegistry.setProgress(getCollectionName(), total, 0);

        // 2. Preparar colección temporal
        String tmpCollection = getTmpCollectionName();
        try {
            if (mongoTemplate.collectionExists(tmpCollection)) {
                mongoTemplate.dropCollection(tmpCollection);
            }
            mongoTemplate.createCollection(tmpCollection, CollectionOptions.empty());
        } catch (DataAccessException e) {
            logger.error("Error preparing temp collection '{}': {}", tmpCollection, e.getMessage(), e);
            syncProgressRegistry.clearProgress(getCollectionName());
            return;
        }

        // 3. Descarga concurrente de páginas
        int pageSize = getPageSize();
        List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < total; offset += pageSize) {
            offsets.add(offset);
        }
        ExecutorService executor = Executors.newFixedThreadPool(getMaxWorkers());
        List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (int offset : offsets) {
            futures.add(executor.submit(() -> fetchPage(client, offset, pageSize)));
        }

        int totalSaved = 0;
        List<Integer> failedOffsets = new ArrayList<>();

        try {
            for (int i = 0; i < offsets.size(); i++) {
                int offset = offsets.get(i);
                try {
                    List<Map<String, Object>> items = futures.get(i).get();
                    if (items == null) {
                        failedOffsets.add(offset);
                        continue;
                    }
                    saveBulkToCollection(items, tmpCollection);
                    totalSaved += items.size();
                    syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved);
                    logger.info("[{}] Saved {} items (offset={}, total={})", tmpCollection, items.size(), offset, totalSaved);
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Error fetching page at offset {}: {}", offset, e.getMessage(), e);
                    failedOffsets.add(offset);
                }
            }
            executor.shutdown();
            try { executor.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}

            // 4. Swap atómico si no hubo errores
            if (failedOffsets.isEmpty()) {
                try {
                    if (mongoTemplate.collectionExists(getCollectionName())) {
                        mongoTemplate.dropCollection(getCollectionName());
                    }
                    com.mongodb.MongoNamespace targetNamespace = new com.mongodb.MongoNamespace(
                            mongoTemplate.getDb().getName(), getCollectionName());
                    mongoTemplate.getCollection(tmpCollection).renameCollection(
                            targetNamespace,
                            new com.mongodb.client.model.RenameCollectionOptions().dropTarget(true)
                    );
                    logger.info("Swap completed: '{}' → '{}'", tmpCollection, getCollectionName());
                } catch (Exception e) {
                    logger.error("Error during atomic swap: {}", e.getMessage(), e);
                    syncProgressRegistry.markError(getCollectionName(), "Error en el swap de colecciones: " + e.getMessage());
                    try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
                    syncProgressRegistry.clearProgress(getCollectionName());
                    return;
                }
                // 5. Post-procesado
                postProcess();
            } else {
                logger.warn("Swap cancelled due to {} failed pages. Temp collection '{}' preserved for review.",
                        failedOffsets.size(), tmpCollection);
            }

            logger.info("[{}] Sync completed. Total saved: {} / Expected: {} | Failed pages: {}",
                    getCollectionName(), totalSaved, total, failedOffsets.size());

        } finally {
            // Comparar registros guardados con el total esperado
            if (!failedOffsets.isEmpty() || totalSaved < total) {
                String errorMsg = String.format(
                        "Error: se esperaban %d registros pero solo se guardaron %d (%d páginas fallidas).",
                        total, totalSaved, failedOffsets.size());
                logger.error("[{}] {}", getCollectionName(), errorMsg);
                syncProgressRegistry.markError(getCollectionName(), errorMsg);
            } else {
                syncProgressRegistry.markCompleted(getCollectionName());
            }
            // Dar 10 segundos al frontend para leer el estado final antes de limpiar
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
            syncProgressRegistry.clearProgress(getCollectionName());
        }
    }

    // Obtiene el total de registros del endpoint
    protected int fetchTotalCount(WebClient client) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/" + getEndpoint())
                .queryParam("size", 1)
                .toUriString();
        try {
            ResponseEntity<Map> response = client.get()
                    .uri(url)
                    .header("api-key", apiKey)
                    .retrieve()
                    .toEntity(Map.class)
                    .block();
            if (response == null || !response.getStatusCode().is2xxSuccessful()) return 0;
            Map body = response.getBody();
            if (body == null) return 0;
            Object count = body.get(getCountField());
            if (count instanceof Number) return ((Number) count).intValue();
            if (count instanceof String) return Integer.parseInt((String) count);
            for (String alt : new String[]{"count", "total", "totalItems", "totalResults"}) {
                if (body.containsKey(alt)) {
                    Object v = body.get(alt);
                    if (v instanceof Number) return ((Number) v).intValue();
                    if (v instanceof String) return Integer.parseInt((String) v);
                }
            }
            return 0;
        } catch (Exception e) {
            logger.error("Error fetching total count: {}", e.getMessage(), e);
            return 0;
        }
    }

    // Descarga una página con reintentos
    protected List<Map<String, Object>> fetchPage(WebClient client, int offset, int pageSize) {
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String url = UriComponentsBuilder.fromUriString(baseUrl + "/" + getEndpoint())
                        .queryParam("size", pageSize)
                        .queryParam("offset", offset)
                        .toUriString();
                ResponseEntity<Map> response = client.get()
                        .uri(url)
                        .header("api-key", apiKey)
                        .retrieve()
                        .toEntity(Map.class)
                        .block();
                if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                    logger.warn("Non-2xx response at offset {}: {}", offset,
                            response != null ? response.getStatusCode() : "null");
                    continue;
                }
                Map body = response.getBody();
                if (body == null || !body.containsKey(getItemsField())) {
                    logger.warn("No '{}' key in response at offset {}", getItemsField(), offset);
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) body.get(getItemsField());
                return items != null ? items : Collections.emptyList();
            } catch (Exception e) {
                logger.warn("Attempt {}/{} failed at offset {}: {}", attempt, maxAttempts, offset, e.getMessage());
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
            }
        }
        logger.error("All attempts failed for offset {}", offset);
        return null;
    }

    // Inserta en bulk en la colección indicada
    protected void saveBulkToCollection(List<Map<String, Object>> items, String collectionName) {
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName);
        for (Map<String, Object> doc : items) {
            Map<String, Object> normalized = normalize(doc);
            org.bson.Document bsonDoc = new org.bson.Document(normalized);
            Object id = normalized.get("_id");
            if (id != null) {
                Query query = new Query(Criteria.where("_id").is(id));
                Update update = Update.fromDocument(bsonDoc);
                bulk.upsert(query, update);
            } else {
                bulk.insert(bsonDoc);
            }
        }
        bulk.execute();
    }

    protected Map<String, Object> normalize(Map<String, Object> doc) {
        return doc;
    }

    protected void postProcess() {
        // Opcional: sobreescribir en subclase
    }
}