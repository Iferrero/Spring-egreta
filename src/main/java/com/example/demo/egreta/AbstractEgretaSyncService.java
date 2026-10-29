package com.example.demo.egreta;

import com.example.demo.egreta.SyncProgressRegistry;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.bson.Document;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public abstract class AbstractEgretaSyncService {

    private record PageResult(int offset, List<Map<String, Object>> items) {}

    // Códigos transitorios (incluye 403 y 52x de Cloudflare, idéntico a egreta_sync_base.py)
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(
            403, 408, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 527, 530
    );

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Autowired(required = false)
    protected WebClient.Builder webClientBuilder;

    @Autowired
    protected SyncProgressRegistry syncProgressRegistry;

    @Value("${egreta.base.url:https://egreta.uab.cat/ws/api}")
    protected String baseUrl;

    @Value("${egreta.api.key:}")
    protected String apiKey;

    protected abstract String getEndpoint();
    protected abstract String getCollectionName();

    protected String getApiBaseUrl() { return baseUrl; }
    protected String getApiKey() { return apiKey; }

    public String collectionName() {
        return getCollectionName();
    }

    // Configurables por subclase
    protected int getPageSize() { return 1000; }
    protected int getMaxBufferSizeMB() { return 50; }
    protected int getMaxWorkers() { return 4; }
    protected int getMaxTailPages() { return 20; }
    protected int getMinSplitSize() { return 10; }
    protected int getConnectTimeoutSeconds() { return 15; }
    protected int getResponseTimeoutSeconds() { return 120; }
    protected int getReadTimeoutSeconds() { return 120; }
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
        if (getApiKey() == null || getApiKey().isEmpty()) {
            return 0L;
        }
        return fetchTotalCount(buildClient());
    }

    protected WebClient buildClient() {
        ConnectionProvider provider = ConnectionProvider.builder("egreta-sync-" + getCollectionName().toLowerCase())
                .maxConnections(Math.max(20, getMaxWorkers() * 4))
                .maxIdleTime(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(60))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getConnectTimeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(getResponseTimeoutSeconds()))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(getReadTimeoutSeconds(), TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(getMaxBufferSizeMB() * 1024 * 1024))
                .build();
    }

    // Nombre de la colección temporal
    protected String getTmpCollectionName() { return getCollectionName() + "_tmp"; }

    protected void rebuildIndexes() {
        try {
            mongoTemplate.getCollection(getCollectionName()).createIndex(new Document("uuid", 1));
            logger.info("[{}] Rebuilt base indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding base indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Extrae las definiciones de índices existentes de una colección (excluyendo _id_).
     */
    protected List<IndexModel> extractIndexes(String collectionName) {
        List<IndexModel> models = new ArrayList<>();
        try {
            if (mongoTemplate == null || !mongoTemplate.collectionExists(collectionName)) {
                return models;
            }
            for (Document doc : mongoTemplate.getCollection(collectionName).listIndexes()) {
                String name = doc.getString("name");
                if ("_id_".equals(name)) {
                    continue;
                }
                Document key = (Document) doc.get("key");
                if (key == null || key.isEmpty()) {
                    continue;
                }

                IndexOptions options = new IndexOptions();
                if (name != null) {
                    options.name(name);
                }
                if (doc.containsKey("unique")) {
                    options.unique(Boolean.TRUE.equals(doc.getBoolean("unique")));
                }
                if (doc.containsKey("sparse")) {
                    options.sparse(Boolean.TRUE.equals(doc.getBoolean("sparse")));
                }
                if (doc.containsKey("expireAfterSeconds")) {
                    Number exp = (Number) doc.get("expireAfterSeconds");
                    if (exp != null) {
                        options.expireAfter(exp.longValue(), TimeUnit.SECONDS);
                    }
                }
                if (doc.containsKey("partialFilterExpression") && doc.get("partialFilterExpression") instanceof Document pfe) {
                    options.partialFilterExpression(pfe);
                }
                if (doc.containsKey("default_language")) {
                    options.defaultLanguage(doc.getString("default_language"));
                }
                if (doc.containsKey("language_override")) {
                    options.languageOverride(doc.getString("language_override"));
                }
                if (doc.containsKey("weights") && doc.get("weights") instanceof Document w) {
                    options.weights(w);
                }
                if (doc.containsKey("hidden")) {
                    options.hidden(Boolean.TRUE.equals(doc.getBoolean("hidden")));
                }

                models.add(new IndexModel(key, options));
            }
        } catch (Exception e) {
            logger.warn("[{}] Error capturando índices existentes: {}", collectionName, e.getMessage());
        }
        return models;
    }

    /**
     * Aplica una lista de modelos de índices sobre una colección de destino.
     */
    protected void applyIndexes(String targetCollection, List<IndexModel> indexes) {
        if (mongoTemplate == null || indexes == null || indexes.isEmpty()) {
            return;
        }
        var coll = mongoTemplate.getCollection(targetCollection);
        int created = 0;
        for (IndexModel model : indexes) {
            try {
                coll.createIndexes(List.of(model));
                created++;
            } catch (Exception e) {
                String indexName = model.getOptions() != null ? model.getOptions().getName() : model.getKeys().toString();
                logger.warn("[{}] No se pudo recrear el índice '{}': {}",
                        targetCollection, indexName, e.getMessage());
            }
        }
        logger.info("[{}] Recreados {}/{} índices de la colección original", targetCollection, created, indexes.size());
    }

    @Async
    public void sync() {
        if (getApiKey() == null || getApiKey().isEmpty()) {
            logger.error("egreta.api.key not set in properties");
            return;
        }

        WebClient client = buildClient();

        // 1. Obtener total de registros y capturar índices existentes de la colección original
        List<IndexModel> originalIndexes = extractIndexes(getCollectionName());
        logger.info("[{}] Capturados {} índices de la colección original antes de sincronizar",
                getCollectionName(), originalIndexes.size());

        int total = fetchTotalCount(client);
        if (total <= 0) {
            logger.warn("No records to sync (total={})", total);
            return;
        }
        logger.info("[{}] Total records to sync: {}", getCollectionName(), total);
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

        // 3. Descarga concurrente con límite de páginas en vuelo para evitar picos de heap
        int pageSize = getPageSize();
        int maxWorkers = Math.max(1, getMaxWorkers());
        int maxInFlight = Math.max(maxWorkers, maxWorkers * 2);
        ExecutorService executor = Executors.newFixedThreadPool(maxWorkers);
        ExecutorCompletionService<PageResult> completionService = new ExecutorCompletionService<>(executor);

        int nextOffset = 0;
        int submitted = 0;
        int completed = 0;

        while (nextOffset < total && submitted < maxInFlight) {
            final int offsetToSubmit = nextOffset;
            completionService.submit(() -> new PageResult(offsetToSubmit, fetchPage(client, offsetToSubmit, pageSize)));
            submitted++;
            nextOffset += pageSize;
        }

        int totalSaved = 0;
        List<Integer> failedOffsets = new ArrayList<>();
        List<Integer> fetchFailed = new ArrayList<>();
        String finalErrorMessage = null;

        try {
            while (completed < submitted) {
                Future<PageResult> future;
                try {
                    future = completionService.take();
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for page result", e);
                    Thread.currentThread().interrupt();
                    break;
                }

                completed++;

                try {
                    PageResult result = future.get();
                    int offset = result.offset();
                    List<Map<String, Object>> items = result.items();

                    if (items == null) {
                        failedOffsets.add(offset);
                        fetchFailed.add(offset);
                        logger.error("[{}] [FETCH ERROR] Offset {} fallat en passada paral·lela", getCollectionName(), offset);
                    } else if (items.isEmpty()) {
                        logger.warn("[{}] [BUIT] Offset {}: sense ítems, saltant", getCollectionName(), offset);
                    } else {
                        saveBulkToCollection(items, tmpCollection);
                        totalSaved += items.size();
                        syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved);
                        logger.info("[{}] Saved {} items (offset={}, total={})", tmpCollection, items.size(), offset, totalSaved);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Error processing page result: {}", e.getMessage(), e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (nextOffset < total) {
                    final int offsetToSubmit = nextOffset;
                    completionService.submit(() -> new PageResult(offsetToSubmit, fetchPage(client, offsetToSubmit, pageSize)));
                    submitted++;
                    nextOffset += pageSize;
                }
            }
            executor.shutdown();
            try { executor.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}

            // Segunda pasada en serie con partición adaptativa (Chunk Splitting)
            if (!fetchFailed.isEmpty()) {
                logger.warn("[{}] Reintentando en serie {} página(s) fallida(s) con partición adaptativa...",
                        getCollectionName(), fetchFailed.size());
                syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved,
                        "Pausa de refredament abans de reintentar " + fetchFailed.size() + " pàgina(s)...");
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                List<Integer> toRetry = new ArrayList<>(fetchFailed);
                int retryIndex = 0;
                for (int off : toRetry) {
                    retryIndex++;
                    syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved,
                            "Reintentant pàgina fallida " + retryIndex + "/" + toRetry.size() + " (offset " + off + ")...");

                    List<Integer> unrecoverable = new ArrayList<>();
                    int recovered = recoverRangeWithSubdivision(client, off, pageSize, getMinSplitSize(), tmpCollection, unrecoverable);

                    if (unrecoverable.isEmpty() && recovered > 0) {
                        totalSaved += recovered;
                        failedOffsets.remove(Integer.valueOf(off));
                        syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved);
                        logger.info("[{}] [RETRY PASS OK] Offset {} completament recuperat (+{} registres, total: {})",
                                getCollectionName(), off, recovered, totalSaved);
                    } else if (recovered > 0) {
                        totalSaved += recovered;
                        syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved);
                        logger.warn("[{}] [RETRY PASS PARCIAL] Offset {} parcialment recuperat (+{} registres, {} sub-trams fallits)",
                                getCollectionName(), off, recovered, unrecoverable.size());
                    } else {
                        logger.error("[{}] [RETRY PASS FAILED] Offset {} no s'ha pogut recuperar", getCollectionName(), off);
                    }
                }
            }

            // Comprobación de total final y drenaje dinámico de páginas adicionales (tail) si no hubo fallos
            if (failedOffsets.isEmpty()) {
                int totalFinal;
                try {
                    totalFinal = fetchTotalCount(client);
                    logger.info("[{}] Total registres a l'API al finalitzar: {}", getCollectionName(), totalFinal);
                } catch (Exception e) {
                    logger.warn("[{}] No s'ha pogut obtenir el total final de l'API: {}", getCollectionName(), e.getMessage());
                    totalFinal = total;
                }

                int currentTailOffset = nextOffset;
                int tailPagesCount = 0;
                int maxTailPages = getMaxTailPages();
                while (currentTailOffset < totalFinal && tailPagesCount < maxTailPages) {
                    logger.info("[{}] [TAIL] Descarregant pàgina addicional offset {} (total API final: {})...",
                            getCollectionName(), currentTailOffset, totalFinal);
                    List<Map<String, Object>> tailItems = fetchPage(client, currentTailOffset, pageSize);
                    if (tailItems == null) {
                        logger.error("[{}] [TAIL ERROR] Offset {}", getCollectionName(), currentTailOffset);
                        failedOffsets.add(currentTailOffset);
                        break;
                    }
                    if (tailItems.isEmpty()) {
                        break;
                    }
                    try {
                        saveBulkToCollection(tailItems, tmpCollection);
                        totalSaved += tailItems.size();
                        total = Math.max(total, totalSaved);
                        syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved);
                        logger.info("[{}] [TAIL OK] Offset {}: +{} (total: {})",
                                getCollectionName(), currentTailOffset, tailItems.size(), totalSaved);
                    } catch (Exception e) {
                        logger.error("[{}] [TAIL MONGO ERROR] Offset {}: {}", getCollectionName(), currentTailOffset, e.getMessage(), e);
                        failedOffsets.add(currentTailOffset);
                        break;
                    }

                    if (tailItems.size() < pageSize) {
                        break;
                    }
                    currentTailOffset += pageSize;
                    tailPagesCount++;
                }
            }

            // 4. Recrear índices y Swap atómico si no hubo errores
            if (failedOffsets.isEmpty()) {
                try {
                    // Refrescar índices de la colección original por si se añadieron índices nuevos
                    List<IndexModel> latestIndexes = extractIndexes(getCollectionName());
                    if (!latestIndexes.isEmpty()) {
                        originalIndexes = latestIndexes;
                    }

                    // Recrear índices originales en la colección temporal ANTES del swap
                    if (!originalIndexes.isEmpty()) {
                        syncProgressRegistry.setProgress(getCollectionName(), total, totalSaved,
                                "Recreant " + originalIndexes.size() + " índexs originals...");
                        applyIndexes(tmpCollection, originalIndexes);
                    }

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
                // 5. Post-procesado y asegurar índices base
                try {
                    postProcess();
                    rebuildIndexes();
                } catch (Exception e) {
                    finalErrorMessage = "Error al reconstruir índices o ejecutar el post-procesado: " + e.getMessage();
                    logger.error("[{}] {}", getCollectionName(), finalErrorMessage, e);
                }
            } else {
                logger.warn("Swap cancelled due to {} failed pages. Temp collection '{}' preserved for review.",
                        failedOffsets.size(), tmpCollection);
            }

            logger.info("[{}] Sync completed. Total saved: {} / Expected: {} | Failed pages: {}",
                    getCollectionName(), totalSaved, total, failedOffsets.size());

        } finally {
            // Comparar registros guardados con el total esperado
            if (finalErrorMessage != null) {
                syncProgressRegistry.markError(getCollectionName(), finalErrorMessage);
            } else if (!failedOffsets.isEmpty() || totalSaved < total) {
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

    /**
     * Intenta recuperar un rango [offset, offset + size] subdividiéndolo
     * recursivamente si falla la petición principal (Chunk Splitting adaptativo).
     * Guarda directamente en tmpCollection los sub-lotes que respondan correctamente.
     *
     * @param client                Cliente WebClient.
     * @param offset                Offset inicial del tramo.
     * @param size                  Número de registros solicitados.
     * @param minSize               Tamaño mínimo antes de considerar un tramo irrecoverable.
     * @param tmpCollection         Nombre de la colección temporal en Mongo.
     * @param unrecoverableOffsets  Lista acumulativa de offsets que no se han podido recuperar.
     * @return Número de registros recuperados e insertados.
     */
    protected int recoverRangeWithSubdivision(
            WebClient client,
            int offset,
            int size,
            int minSize,
            String tmpCollection,
            List<Integer> unrecoverableOffsets) {

        // 1. Intento directo con el tamaño actual
        List<Map<String, Object>> items = fetchPage(client, offset, size);
        if (items != null) {
            if (!items.isEmpty()) {
                saveBulkToCollection(items, tmpCollection);
            }
            logger.info("[{}] [RECOVER OK] Offset {} (size={}): +{} registres",
                    getCollectionName(), offset, size, items.size());
            return items.size();
        }

        // 2. Si ha fallado y ya estamos en el tamaño mínimo indivisible
        if (size <= minSize) {
            logger.error("[{}] [UNRECOVERABLE] No s'ha pogut recuperar el rang offset {} (size={})",
                    getCollectionName(), offset, size);
            unrecoverableOffsets.add(offset);
            return 0;
        }

        // 3. Subdivisión en 2 mitades
        int half1 = size / 2;
        int half2 = size - half1;
        logger.warn("[{}] [SPLIT] Offset {} (size={}) ha fallat. Subdividint a: [offset={}, size={}] i [offset={}, size={}]",
                getCollectionName(), offset, size, offset, half1, offset + half1, half2);

        int currentTotal = 0;
        int currentSaved = 0;
        SyncProgressRegistry.SyncProgress p = syncProgressRegistry.getProgress(getCollectionName());
        if (p != null) {
            currentTotal = p.total;
            currentSaved = p.current;
        }
        syncProgressRegistry.setProgress(getCollectionName(), currentTotal, currentSaved,
                "Subdividint bloc " + offset + " (mida " + size + " → " + half1 + ")...");

        int rec1 = recoverRangeWithSubdivision(client, offset, half1, minSize, tmpCollection, unrecoverableOffsets);
        int rec2 = recoverRangeWithSubdivision(client, offset + half1, half2, minSize, tmpCollection, unrecoverableOffsets);

        return rec1 + rec2;
    }

    // Obtiene el total de registros del endpoint
    protected int fetchTotalCount(WebClient client) {
        String url = UriComponentsBuilder.fromUriString(getApiBaseUrl() + "/" + getEndpoint())
                .queryParam("size", 1)
                .toUriString();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ResponseEntity<Map> response = client.get()
                        .uri(url)
                        .header("api-key", getApiKey())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(Map.class)
                        .block();
                if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                    if (attempt < 2) {
                        Thread.sleep(1000);
                        continue;
                    }
                    return 0;
                }
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
                logger.warn("[{}] Intent {}/3 per obtenir total count ha fallat: {}", getCollectionName(), attempt + 1, e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                }
            }
        }
        return 0;
    }

    // Descarga una página con reintentos (idéntico a Python egreta_sync_base.py)
    protected List<Map<String, Object>> fetchPage(WebClient client, int offset, int pageSize) {
        int maxAttempts = 8;
        String lastError = "Agotados todos los reintentos";

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double retryAfter = 0.0;
            try {
                String url = UriComponentsBuilder.fromUriString(getApiBaseUrl() + "/" + getEndpoint())
                        .queryParam("size", pageSize)
                        .queryParam("offset", offset)
                        .toUriString();

                ResponseEntity<Map> response = client.get()
                        .uri(url)
                        .header("api-key", getApiKey())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(Map.class)
                        .block();

                if (response != null && response.getStatusCode().is2xxSuccessful()) {
                    Map body = response.getBody();
                    if (body != null && body.containsKey(getItemsField())) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get(getItemsField());
                        return items != null ? items : Collections.emptyList();
                    }
                    lastError = "JSON inválido o respuesta vacía";
                } else {
                    int statusCode = response != null ? response.getStatusCode().value() : 0;
                    if (RETRYABLE_STATUS.contains(statusCode)) {
                        lastError = "HTTP " + statusCode;
                    } else {
                        // Error no transitorio (400, 401, 404...): no tiene sentido reintentar
                        logger.error("[{}] Error no transitorio a offset {}: HTTP {}", getCollectionName(), offset, statusCode);
                        return null;
                    }
                }
            } catch (WebClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                if (RETRYABLE_STATUS.contains(statusCode)) {
                    String bodySnippet = e.getResponseBodyAsString();
                    if (bodySnippet != null && bodySnippet.length() > 200) {
                        bodySnippet = bodySnippet.substring(0, 200);
                    }
                    lastError = "HTTP " + statusCode + ": " + bodySnippet;
                    String retryAfterHeader = e.getHeaders().getFirst("Retry-After");
                    if (retryAfterHeader != null) {
                        try {
                            retryAfter = Double.parseDouble(retryAfterHeader);
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    // Error no transitorio (400, 401, 404...): no tiene sentido reintentar
                    logger.error("[{}] Error no transitorio a offset {}: HTTP {}: {}",
                            getCollectionName(), offset, statusCode, e.getMessage());
                    return null;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            if (attempt < maxAttempts - 1) {
                // Backoff exponencial (tope 60s) + jitter para desincronizar workers
                double backoff = Math.min(Math.pow(2, attempt), 60.0);
                double wait = Math.max(retryAfter, backoff) + ThreadLocalRandom.current().nextDouble(0.5, 2.0);
                logger.warn("[{}] [RETRY] Offset {}, intento {}/{} ({}), esperando {}s",
                        getCollectionName(), offset, attempt + 1, maxAttempts, lastError,
                        String.format(Locale.ROOT, "%.1f", wait));
                try {
                    Thread.sleep((long) (wait * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.error("[{}] Reintento interrumpido para offset {}", getCollectionName(), offset);
                    return null;
                }
            }
        }

        logger.error("[{}] Todos los intentos fallaron para offset {}: {}", getCollectionName(), offset, lastError);
        return null;
    }

    // Inserta en bulk en la colección indicada
    protected void saveBulkToCollection(List<Map<String, Object>> items, String collectionName) {
        List<Map<String, Object>> normalizedItems = normalizeBatch(items);
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName);
        for (Map<String, Object> normalized : normalizedItems) {
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

    /**
     * Hook opcional para normalizar un lote (página) completo de golpe.
     * Útil cuando normalize() necesita hacer I/O adicional (p.ej. llamadas
     * HTTP por documento) que conviene paralelizar en vez de hacer en serie.
     * Por defecto aplica normalize() a cada documento secuencialmente
     * (equivalente al comportamiento por defecto de egreta_sync_base.py).
     */
    protected List<Map<String, Object>> normalizeBatch(List<Map<String, Object>> items) {
        List<Map<String, Object>> result = new ArrayList<>(items.size());
        for (Map<String, Object> doc : items) {
            result.add(normalize(doc));
        }
        return result;
    }

    protected void postProcess() {
        // Opcional: sobreescribir en subclase
    }

    /**
     * Sincronitza un únic document de l'API d'Egreta pel seu UUID i l'actualitza a MongoDB.
     */
    public Map<String, Object> syncOne(String uuid) {
        if (getApiKey() == null || getApiKey().isEmpty()) {
            throw new IllegalStateException("egreta.api.key not set in properties");
        }
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("UUID cannot be null or empty");
        }

        WebClient client = buildClient();
        String url = UriComponentsBuilder.fromUriString(getApiBaseUrl() + "/" + getEndpoint() + "/" + uuid.trim())
                .toUriString();

        ResponseEntity<Map> response;
        try {
            response = client.get()
                    .uri(url)
                    .header("api-key", getApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(Map.class)
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.NotFound e) {
            throw new RuntimeException("Document with UUID '" + uuid + "' not found in Egreta (" + getEndpoint() + ")", e);
        } catch (Exception e) {
            throw new RuntimeException("Error fetching document from Egreta API for UUID '" + uuid + "': " + e.getMessage(), e);
        }

        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Error fetching document from Egreta API for UUID '" + uuid + "': Status " + (response != null ? response.getStatusCode() : "null response"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rawItem = (Map<String, Object>) response.getBody();
        Map<String, Object> normalized = normalize(rawItem);

        org.bson.Document bsonDoc = new org.bson.Document(normalized);
        Query query = new Query(Criteria.where("uuid").is(uuid.trim()));
        Update update = Update.fromDocument(bsonDoc);
        mongoTemplate.upsert(query, update, getCollectionName());

        logger.info("[{}] Successfully synced single document with UUID '{}'", getCollectionName(), uuid.trim());
        return normalized;
    }
}
