
package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.demo.model.ExternalOrganization;
import com.example.demo.repository.ExternalOrganizationRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/external-organizations")
@CrossOrigin(origins = "*")
public class ExternalOrganizationController {

     private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ExternalOrganizationController.class);
    // -------------------------------------------------------------------------
    // TTL cache — thread-safe via AtomicReference + double-check (mejora #1)
    // -------------------------------------------------------------------------
    private record CachedSet<T>(Set<T> data, long timestamp) {}
    private record CachedInference(String result, long timestamp) {}

    private static final long REFERENCE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final long GRAPH_SIMILARITY_CACHE_TTL_MS = 2 * 60 * 1000;
    private static final long INFERENCE_CACHE_TTL_MS = 10 * 60 * 1000;  // 10 minutos para inferencias
    private static final long AI_TIMEOUT_BUFFER_MS = 500;               // margen extra sobre el timeout HTTP

    private final AtomicReference<CachedSet<String>> cachedExternalOrgUuids        = new AtomicReference<>();
    private final AtomicReference<CachedSet<String>> cachedReferencedExternalOrgUuids = new AtomicReference<>();
    // Catálogos con TTL propio para no regenerarlos en cada llamada (mejora #4)
    private final AtomicReference<CachedSet<CountryAggregate>> cachedCountryCatalog = new AtomicReference<>();
    private final AtomicReference<CachedSet<TypeAggregate>>    cachedTypeCatalog    = new AtomicReference<>();
    private final Map<String, CachedGraphResult> graphSimilarityCache = new ConcurrentHashMap<>();
    private final Map<String, CachedPairsResult> pairsSimilarityCache = new ConcurrentHashMap<>();
    private final Map<String, SimilarityJobState> similarityJobs = new ConcurrentHashMap<>();
    // Caché de inferencias AI por nombre (mejora #7: evita llamadas repetidas)
    private final Map<String, CachedInference> countryInferenceCache = new ConcurrentHashMap<>();
    private final Map<String, CachedInference> typeInferenceCache = new ConcurrentHashMap<>();

    private final ExternalOrganizationRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final HttpClient aiHttpClient;
    private final ExecutorService asyncExecutor;

    // Nombre de colección resuelto una sola vez al arrancar (mejora #9)
    private String externalOrganizationCollection;

    @Value("${app.ai.country-suggest.enabled:false}")
    private boolean aiCountrySuggestEnabled;

    @Value("${app.ai.country-suggest.url:}")
    private String aiCountrySuggestUrl;

    @Value("${app.ai.country-suggest.model:}")
    private String aiCountrySuggestModel;

    @Value("${app.ai.country-suggest.api-key:}")
    private String aiCountrySuggestApiKey;

    @Value("${app.ai.country-suggest.timeout-ms:2000}")
    private int aiCountrySuggestTimeoutMs;  // Reducido de 8000 a 2000ms para fallback rápido

    @Value("${app.websearch.country-suggest.enabled:false}")
    private boolean websearchCountrySuggestEnabled;

    @Value("${app.websearch.country-suggest.url:}")
    private String websearchCountrySuggestUrl;

    @Value("${app.websearch.country-suggest.timeout-ms:2500}")
    private int websearchCountrySuggestTimeoutMs;

    @Value("${app.websearch.country-suggest.max-results:5}")
    private int websearchCountrySuggestMaxResults;

    @Value("${app.egreta.external-org.enabled:false}")
    private boolean egretaExternalOrgEnabled;

    @Value("${app.egreta.external-org.base-url:https://egreta.uab.cat/ws/api/external-organizations}")
    private String egretaExternalOrgBaseUrl;

    @Value("${app.egreta.external-org.get-url-template:}")
    private String egretaExternalOrgGetUrlTemplate;

    @Value("${app.egreta.external-org.put-url-template:}")
    private String egretaExternalOrgPutUrlTemplate;

    @Value("${app.egreta.external-org.api-key:}")
    private String egretaExternalOrgApiKey;

    @Value("${app.egreta.external-org.timeout-ms:12000}")
    private int egretaExternalOrgTimeoutMs;

    // State variables for auto-apply country process
    private final Object autoApplyLock = new Object();
    private boolean autoApplyRunning = false;
    private int autoApplyTotal = 0;
    private int autoApplyProcessed = 0;
    private int autoApplyApplied = 0;
    private double autoApplyConfidenceThreshold = 0.90;
    private final List<String> autoApplyLogs = new java.util.Vector<>();
    private final String autoApplyLogFilePath = "logs/auto-apply-country.log";

    // State variables for auto-apply type process
    private final Object autoApplyTypeLock = new Object();
    private boolean autoApplyTypeRunning = false;
    private int autoApplyTypeTotal = 0;
    private int autoApplyTypeProcessed = 0;
    private int autoApplyTypeApplied = 0;
    private double autoApplyTypeConfidenceThreshold = 0.90;
    private final List<String> autoApplyTypeLogs = new java.util.Vector<>();
    private final String autoApplyTypeLogFilePath = "logs/auto-apply-type.log";

    // State variables for auto-apply funding process
    private final Object autoApplyFundingLock = new Object();
    private boolean autoApplyFundingRunning = false;
    private int autoApplyFundingTotal = 0;
    private int autoApplyFundingProcessed = 0;
    private int autoApplyFundingApplied = 0;
    private double autoApplyFundingConfidenceThreshold = 0.90;
    private final List<String> autoApplyFundingLogs = new java.util.Vector<>();
    private final String autoApplyFundingLogFilePath = "logs/auto-apply-funding.log";

    // State variables for auto-validate process
    private final Object autoValidateLock = new Object();
    private boolean autoValidateRunning = false;
    private int autoValidateTotal = 0;
    private int autoValidateProcessed = 0;
    private int autoValidateApplied = 0;
    private final List<String> autoValidateLogs = new java.util.Vector<>();
    private final String autoValidateLogFilePath = "logs/auto-validate.log";

    private final ExecutorService aiExecutor;

    @Autowired
    public ExternalOrganizationController(
            ExternalOrganizationRepository repository,
            MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r);
            t.setName("ext-org-async-" + t.threadId());
            t.setDaemon(true);
            return t;
        };
        
        RejectedExecutionHandler rejectionPolicy = new ThreadPoolExecutor.CallerRunsPolicy();
        this.asyncExecutor = new ThreadPoolExecutor(
            2,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            tf,
            rejectionPolicy
        );

        ThreadFactory tfAi = r -> {
            Thread t = new Thread(r);
            t.setName("ext-org-ai-" + t.threadId());
            t.setDaemon(true);
            return t;
        };
        this.aiExecutor = new ThreadPoolExecutor(
            16,
            32,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            tfAi,
            rejectionPolicy
        );

        this.aiHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(500))
        .build();
    }

    // Mejora #9: resolver la colección una sola vez al arrancar el contexto Spring
    @PostConstruct
    private void init() {
        for (String candidate : List.of(
                "ExternalOrganizations", "externalOrganizations",
                "ExternalOrganisation", "ExternalOrganisations")) {
            if (mongoTemplate.collectionExists(candidate)) {
                externalOrganizationCollection = candidate;
                return;
            }
        }
        externalOrganizationCollection = "ExternalOrganizations";
    }

    @PreDestroy
    private void shutdownAsyncExecutor() {
        asyncExecutor.shutdown();
        aiExecutor.shutdown();
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations  — paginated list with optional name search
    // -------------------------------------------------------------------------
    @GetMapping
    public Page<ExternalOrganization> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, buildNameSort());

        if (buscar == null || buscar.isBlank()) {
            return repository.findAll(pageable);
        }

        String escaped = Pattern.quote(buscar.trim());
        Query query = new Query(new Criteria().orOperator(
            Criteria.where("name.ca_ES").regex(escaped, "i"),
            Criteria.where("name.es_ES").regex(escaped, "i"),
            Criteria.where("name.en_GB").regex(escaped, "i")
        )).with(pageable);

        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // ------------------------------------------------------------------------- 
    // POST /external-organizations/merge
    // Body: { "targetId": "uuid", "sourceIds": ["uuid1", "uuid2", ...], "egreta": true/false }
    // Si "egreta" es true, realiza la fusión vía API Egreta
    // -------------------------------------------------------------------------
    @PostMapping("/merge")
    public Map<String, Object> mergeOrganizations(@RequestBody Map<String, Object> payload) {
        String targetId = payload == null ? null : Objects.toString(payload.get("targetId"), null);
        List<String> sourceIds = payload == null ? null : (List<String>) payload.get("sourceIds");
        boolean useEgreta = payload != null && Boolean.TRUE.equals(payload.get("egreta"));
        if (targetId == null || sourceIds == null || sourceIds.isEmpty()) {
            return Map.of("merged", false, "reason", "missing-target-or-sources");
        }

        
            // Llamada a la API de Egreta para fusionar
            if (!egretaExternalOrgEnabled) {
                return Map.of("merged", false, "reason", "egreta-sync-disabled");
            }
            String mergeUrl = egretaExternalOrgBaseUrl + "/merge";
            try {
                // Construir el body según la especificación de Egreta (uuid + systemName)
                List<Map<String, Object>> items = new java.util.ArrayList<>();
                items.add(Map.of("uuid", targetId, "systemName", "ExternalOrganization"));
                for (String src : sourceIds) {
                    items.add(Map.of("uuid", src, "systemName", "ExternalOrganization"));
                }
                Map<String, Object> body = Map.of("items", items);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(mergeUrl))
                        .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
                appendEgretaAuth(reqBuilder);

                HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return Map.of(
                        "merged", false,
                        "reason", "egreta-merge-failed",
                        "status", response.statusCode(),
                        "body", trimResponseBody(response.body())
                    );
                }
                // Devuelve el resultado de Egreta
                return Map.of(
                    "merged", true,
                    "targetId", targetId,
                    "removed", sourceIds,
                    "egreta", true,
                    "response", objectMapper.readTree(response.body())
                );
            } catch (Exception e) {
                return Map.of(
                    "merged", false,
                    "reason", "egreta-merge-exception",
                    "message", e.getMessage() == null ? "unknown" : e.getMessage()
                );
            }
        
        /* 
        // Fusión local MongoDB (comportamiento anterior)
        Query targetQuery = new Query(Criteria.where("uuid").is(targetId));
        ExternalOrganization target = mongoTemplate.findOne(targetQuery, ExternalOrganization.class);
        if (target == null) {
            return Map.of("merged", false, "reason", "target-not-found", "targetId", targetId);
        }

        List<ExternalOrganization> sources = mongoTemplate.find(
            new Query(Criteria.where("uuid").in(sourceIds)),
            ExternalOrganization.class
        );
        if (sources.isEmpty()) {
            return Map.of("merged", false, "reason", "sources-not-found", "sourceIds", sourceIds);
        }

        // Ejemplo de fusión: combinar nombres, identificadores, links, etc.
        for (ExternalOrganization source : sources) {
            // Fusionar nombres (añadir los que falten)
            if (source.getName() != null) {
                if (target.getName() == null) target.setName(new java.util.HashMap<>());
                target.getName().putAll(source.getName());
            }
            // Fusionar identificadores
            if (source.getIdentifiers() != null) {
                if (target.getIdentifiers() == null) target.setIdentifiers(new java.util.ArrayList<>());
                for (var id : source.getIdentifiers()) {
                    if (!target.getIdentifiers().contains(id)) target.getIdentifiers().add(id);
                }
            }
            // Fusionar links
            if (source.getLinks() != null) {
                if (target.getLinks() == null) target.setLinks(new java.util.ArrayList<>());
                for (var link : source.getLinks()) {
                    if (!target.getLinks().contains(link)) target.getLinks().add(link);
                }
            }
            // Fusionar prettyUrlIdentifiers
            if (source.getPrettyUrlIdentifiers() != null) {
                if (target.getPrettyUrlIdentifiers() == null) target.setPrettyUrlIdentifiers(new java.util.ArrayList<>());
                for (var pui : source.getPrettyUrlIdentifiers()) {
                    if (!target.getPrettyUrlIdentifiers().contains(pui)) target.getPrettyUrlIdentifiers().add(pui);
                }
            }
            // Fusionar keywordGroups
            if (source.getKeywordGroups() != null) {
                if (target.getKeywordGroups() == null) target.setKeywordGroups(new java.util.ArrayList<>());
                for (var kg : source.getKeywordGroups()) {
                    if (!target.getKeywordGroups().contains(kg)) target.getKeywordGroups().add(kg);
                }
            }
            // Si el target no tiene algún campo importante, tomarlo del source
            if (target.getType() == null && source.getType() != null) target.setType(source.getType());
            if (target.getNatureTypes() == null && source.getNatureTypes() != null) target.setNatureTypes(source.getNatureTypes());
            if (target.getAddress() == null && source.getAddress() != null) target.setAddress(source.getAddress());
            if (target.getVisibility() == null && source.getVisibility() != null) target.setVisibility(source.getVisibility());
            if (target.getWorkflow() == null && source.getWorkflow() != null) target.setWorkflow(source.getWorkflow());
        }

        // Guardar la organización fusionada
        repository.save(target);

        // Eliminar las fuentes
        for (ExternalOrganization source : sources) {
            mongoTemplate.remove(new Query(Criteria.where("uuid").is(source.getUuid())), ExternalOrganization.class);
        }
        
        // TODO: Actualizar referencias externas en otras colecciones si es necesario

        return Map.of(
            "merged", true,
            "targetId", targetId,
            "removed", sourceIds,
            "target", target
        );
        */
    }

    // -------------------------------------------------------------------------
    // POST /external-organizations/by-uuids
    // Body: { "uuids": ["uuid1", "uuid2", ...] }
    // -------------------------------------------------------------------------
    @PostMapping("/by-uuids")
    public List<ExternalOrganization> findByUuids(@RequestBody Map<String, List<String>> body) {
        List<String> uuids = body == null ? List.of() : body.getOrDefault("uuids", List.of());
        if (uuids.isEmpty()) return List.of();
        return mongoTemplate.find(
            new Query(org.springframework.data.mongodb.core.query.Criteria.where("uuid").in(uuids)),
            ExternalOrganization.class
        );
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/unlinked
    // -------------------------------------------------------------------------
    @GetMapping("/unlinked")
    public Page<ExternalOrganization> unlinked(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Set<String> referencedUuids = collectReferencedUuids();

        Criteria notReferenced = Criteria.where("uuid").nin(referencedUuids);
        Query query = new Query(notReferenced);

        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            Criteria nameCriteria = new Criteria().orOperator(
                Criteria.where("name.ca_ES").regex(escaped, "i"),
                Criteria.where("name.es_ES").regex(escaped, "i"),
                Criteria.where("name.en_GB").regex(escaped, "i")
            );
            query = new Query(new Criteria().andOperator(notReferenced, nameCriteria));
        }

        PageRequest pageable = PageRequest.of(page, size, buildNameSort());
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        query.with(pageable);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/unlinked/count
    // -------------------------------------------------------------------------
    @GetMapping("/unlinked/count")
    public Map<String, Long> unlinkedCount() {
        Set<String> referencedUuids = collectReferencedUuids();
        long total = mongoTemplate.count(
            new Query(Criteria.where("uuid").nin(referencedUuids)),
            ExternalOrganization.class
        );
        long grand = repository.count();
        return Map.of("unlinked", total, "total", grand);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/by-type
    // -------------------------------------------------------------------------
    @GetMapping("/stats/by-type")
    public List<Map<String, Object>> statsByType() {
        List<Document> pipeline = List.of(
            new Document("$group", new Document("_id", "$type.term.ca_ES")
                .append("count", new Document("$sum", 1))),
            new Document("$sort", new Document("count", -1))
        );
        List<Map<String, Object>> result = new ArrayList<>();
        mongoTemplate.getDb().getCollection(externalOrganizationCollection)
            .aggregate(pipeline)
            .forEach(doc -> {
                String label = doc.getString("_id");
                result.add(Map.of(
                    "label", label != null ? label : "(desconegut)",
                    "count", doc.getInteger("count", 0)
                ));
            });
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/by-country[?type=...]
    // -------------------------------------------------------------------------
    @GetMapping("/stats/by-country")
    public List<Map<String, Object>> statsByCountry(
            @RequestParam(required = false) String type) {
        List<Document> pipeline = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            pipeline.add(new Document("$match", new Document("type.term.ca_ES", type.trim())));
        }
        pipeline.add(new Document("$group", new Document("_id", "$address.country.term.en_GB")
            .append("count", new Document("$sum", 1))));
        pipeline.add(new Document("$sort", new Document("count", -1)));

        List<Map<String, Object>> result = new ArrayList<>();
        mongoTemplate.getDb().getCollection(externalOrganizationCollection)
            .aggregate(pipeline)
            .forEach(doc -> {
                String label = doc.getString("_id");
                result.add(Map.of(
                    "label", label != null ? label : "(desconegut)",
                    "count", doc.getInteger("count", 0)
                ));
            });
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/no-country/count
    // -------------------------------------------------------------------------
    @GetMapping("/stats/no-country/count")
    public Map<String, Long> statsNoCountryCount() {
        Criteria noCountry = missingCountryCriteria();
        long count = mongoTemplate.count(new Query(noCountry), ExternalOrganization.class);
        long total = repository.count();
        return Map.of("withoutCountry", count, "total", total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/no-country
    //
    // Mejora #3: la criteria usa orOperator (basta con que falte en UN idioma).
    // -------------------------------------------------------------------------
    @GetMapping("/stats/no-country")
    public Page<ExternalOrganization> statsNoCountry(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection) {

        Criteria base = missingCountryCriteria();
        Query query = new Query(base);

        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            Criteria nameCriteria = new Criteria().orOperator(
                Criteria.where("name.ca_ES").regex(escaped, "i"),
                Criteria.where("name.es_ES").regex(escaped, "i"),
                Criteria.where("name.en_GB").regex(escaped, "i")
            );
            query = new Query(new Criteria().andOperator(base, nameCriteria));
        }

        Sort sort = buildSort(sortBy, sortDirection);
        PageRequest pageable = PageRequest.of(page, size, sort);
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        query.with(pageable);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/no-type/count
    // -------------------------------------------------------------------------
    @GetMapping("/stats/no-type/count")
    public Map<String, Long> statsNoTypeCount() {
        Criteria noType = missingTypeCriteria();
        long count = mongoTemplate.count(new Query(noType), ExternalOrganization.class);
        long total = repository.count();
        return Map.of("withoutType", count, "total", total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/no-type
    // -------------------------------------------------------------------------
    @GetMapping("/stats/no-type")
    public Page<ExternalOrganization> statsNoType(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection) {

        Criteria base = missingTypeCriteria();
        Query query = new Query(base);

        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            Criteria nameCriteria = new Criteria().orOperator(
                Criteria.where("name.ca_ES").regex(escaped, "i"),
                Criteria.where("name.es_ES").regex(escaped, "i"),
                Criteria.where("name.en_GB").regex(escaped, "i")
            );
            query = new Query(new Criteria().andOperator(base, nameCriteria));
        }

        Sort sort = buildSort(sortBy, sortDirection);
        PageRequest pageable = PageRequest.of(page, size, sort);
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        query.with(pageable);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/no-funding/count
    // -------------------------------------------------------------------------
    @GetMapping("/stats/no-funding/count")
    public Map<String, Long> statsNoFundingCount() {
        Criteria noFunding = missingFundingCriteria();
        long count = mongoTemplate.count(new Query(noFunding), ExternalOrganization.class);
        long total = repository.count();
        return Map.of("withoutFunding", count, "total", total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/no-funding
    // -------------------------------------------------------------------------
    @GetMapping("/stats/no-funding")
    public Page<ExternalOrganization> statsNoFunding(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection) {

        Criteria base = missingFundingCriteria();
        Query query = new Query(base);

        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            Criteria nameCriteria = new Criteria().orOperator(
                Criteria.where("name.ca_ES").regex(escaped, "i"),
                Criteria.where("name.es_ES").regex(escaped, "i"),
                Criteria.where("name.en_GB").regex(escaped, "i")
            );
            query = new Query(new Criteria().andOperator(base, nameCriteria));
        }

        Sort sort = buildSort(sortBy, sortDirection);
        PageRequest pageable = PageRequest.of(page, size, sort);
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        query.with(pageable);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/suggest-country?name=<orgName>
    // -------------------------------------------------------------------------
    @GetMapping("/stats/suggest-country")
    public Map<String, Object> suggestCountryByName(@RequestParam String name) {
        return suggestCountryPayload(name);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/suggest-type?name=<orgName>
    // -------------------------------------------------------------------------
    @GetMapping("/stats/suggest-type")
    public Map<String, Object> suggestTypeByName(@RequestParam String name) {
        return suggestTypePayload(name);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/suggest-metadata?name=<orgName>
    // -------------------------------------------------------------------------
    @GetMapping("/stats/suggest-metadata")
    public Map<String, Object> suggestMetadataByName(@RequestParam String name) {
        String orgName = name == null ? "" : name.trim();
        if (orgName.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("suggestedCountry", "");
            empty.put("suggestedCountryUri", "");
            empty.put("countryConfidence", 0.0);
            empty.put("countryReason", "empty-name");
            empty.put("suggestedType", "");
            empty.put("typeConfidence", 0.0);
            empty.put("typeReason", "empty-name");
            empty.put("suggestedFunding", "");
            empty.put("suggestedFundingUri", "");
            empty.put("fundingConfidence", 0.0);
            empty.put("fundingReason", "empty-name");
            return empty;
        }

        // Mejora #4: catálogos cacheados
        List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
        List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();

        MetadataInferenceResult aiResult = inferMetadataWithLocalAi(orgName, countryCatalog, typeCatalog);

        InferenceResult countryResult = null;
        TypeInferenceResult typeResult = null;
        FundingInferenceResult fundingResult = null;

        if (aiResult != null) {
            countryResult = aiResult.country();
            typeResult = aiResult.type();
            fundingResult = aiResult.funding();
        }

        // Fallbacks for country
        if (countryResult == null || countryResult.countryLabel.isBlank()) {
            countryResult = inferCountryWithWebSearch(orgName, countryCatalog);
            if (countryResult == null || countryResult.countryLabel.isBlank()) {
                countryResult = inferCountryFromName(orgName, countryCatalog);
            }
        }

        // Fallbacks for type
        if (typeResult == null || typeResult.typeLabel.isBlank()) {
            typeResult = inferTypeWithLocalAi(orgName, typeCatalog);
            if (typeResult == null || typeResult.typeLabel.isBlank()) {
                typeResult = inferTypeFromName(orgName, typeCatalog);
            }
        }

        // Fallbacks for funding
        if (fundingResult == null || fundingResult.fundingLabel.isBlank()) {
            fundingResult = inferFundingFromName(orgName);
        }
        

        String suggestedCountryUri = resolveCountryUriByLabel(countryResult.countryLabel);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestedCountry", countryResult.countryLabel);
        out.put("suggestedCountryUri", suggestedCountryUri);
        out.put("countryConfidence", countryResult.confidence);
        out.put("countryReason", countryResult.reason);
        out.put("suggestedType", typeResult.typeLabel);
        out.put("typeConfidence", typeResult.confidence);
        out.put("typeReason", typeResult.reason);
        out.put("suggestedFunding", fundingResult.fundingLabel);
        out.put("suggestedFundingUri", resolveFundingUri(fundingResult.fundingLabel));
        out.put("fundingConfidence", fundingResult.confidence);
        out.put("fundingReason", fundingResult.reason);
        return out;
    }

    // -------------------------------------------------------------------------
    // POST /external-organizations/stats/suggest-metadata/batch
    // Body: { "uuids": ["uuid1", "uuid2", ...] }
    // -------------------------------------------------------------------------
    @PostMapping("/stats/suggest-metadata/batch")
    public List<Map<String, Object>> suggestMetadataBatch(@RequestBody Map<String, List<String>> body) {
        List<String> uuids = body == null ? List.of() : body.getOrDefault("uuids", List.of());
        if (uuids.isEmpty()) return List.of();

        // Cargar las organizaciones correspondientes a los UUIDs recibidos
        List<ExternalOrganization> organizations = mongoTemplate.find(
            new Query(Criteria.where("uuid").in(uuids)),
            ExternalOrganization.class
        );
        if (organizations.isEmpty()) return List.of();

        List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
        List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();

        // Inferencia local (sin AI) para todas
        Map<String, InferenceResult>     countryResults = new ConcurrentHashMap<>();
        Map<String, TypeInferenceResult> typeResults    = new ConcurrentHashMap<>();
        Map<String, FundingInferenceResult> fundingResults = new ConcurrentHashMap<>();
        for (ExternalOrganization org : organizations) {
            String uuid    = org.getUuid();
            String orgName = extractOrgName(org);
            countryResults.put(uuid, inferCountryFromName(orgName, countryCatalog));
            typeResults.put(uuid,    inferTypeFromName(orgName, typeCatalog));
            fundingResults.put(uuid, inferFundingFromName(orgName));
        }

        // Batch AI para los que no tienen resultado local
        List<ExternalOrganization> needsAi = organizations.stream()
            .filter(org -> {
                String uuid = org.getUuid();
                return countryResults.getOrDefault(uuid, new InferenceResult("", 0.0, "")).countryLabel.isBlank()
                    || typeResults.getOrDefault(uuid,    new TypeInferenceResult("", 0.0, "")).typeLabel.isBlank()
                    || fundingResults.getOrDefault(uuid, new FundingInferenceResult("", 0.0, "")).fundingLabel.isBlank();
            })
            .collect(Collectors.toList());

        if (!needsAi.isEmpty() && aiCountrySuggestEnabled) {
            Map<String, MetadataInferenceResult> aiResults =
                inferMetadataBatch(needsAi, countryCatalog, typeCatalog);
            aiResults.forEach((uuid, aiResult) -> {
                if (aiResult.country() != null && !aiResult.country().countryLabel.isBlank()
                        && countryResults.getOrDefault(uuid, new InferenceResult("", 0.0, "")).countryLabel.isBlank()) {
                    countryResults.put(uuid, aiResult.country());
                }
                if (aiResult.type() != null && !aiResult.type().typeLabel.isBlank()
                        && typeResults.getOrDefault(uuid, new TypeInferenceResult("", 0.0, "")).typeLabel.isBlank()) {
                    typeResults.put(uuid, aiResult.type());
                }
                if (aiResult.funding() != null && !aiResult.funding().fundingLabel.isBlank()
                        && fundingResults.getOrDefault(uuid, new FundingInferenceResult("", 0.0, "")).fundingLabel.isBlank()) {
                    fundingResults.put(uuid, aiResult.funding());
                }
            });
        }

        // Construir respuesta
        return organizations.stream().map(org -> {
            String uuid          = org.getUuid();
            InferenceResult     cr = countryResults.getOrDefault(uuid, new InferenceResult("", 0.0, "no-signal"));
            TypeInferenceResult tr = typeResults.getOrDefault(uuid,    new TypeInferenceResult("", 0.0, "no-signal"));
            FundingInferenceResult fr = fundingResults.getOrDefault(uuid, new FundingInferenceResult("", 0.0, "no-signal"));
            String countryUri      = resolveCountryUriByLabel(cr.countryLabel);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid",                uuid);
            row.put("name",                extractOrgName(org));
            row.put("suggestedCountry",    cr.countryLabel);
            row.put("suggestedCountryUri", countryUri);
            row.put("countryConfidence",   cr.confidence);
            row.put("countryReason",       cr.reason);
            row.put("suggestedType",       tr.typeLabel);
            row.put("typeConfidence",       tr.confidence);
            row.put("typeReason",          tr.reason);
            row.put("suggestedFunding",    fr.fundingLabel);
            row.put("suggestedFundingUri", resolveFundingUri(fr.fundingLabel));
            row.put("fundingConfidence",   fr.confidence);
            row.put("fundingReason",       fr.reason);
            // Add workflow step if present
            String workflowStep = null;
            if (org.getWorkflow() != null) {
                workflowStep = org.getWorkflow().getStep();
            }
            row.put("workflow", workflowStep);
            return row;
        }).collect(Collectors.toList());
    }

    @GetMapping("/stats/type-catalog")
    public List<Map<String, String>> typeCatalog() {
        Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes"));
        Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
        if (schemeDoc != null) {
            @SuppressWarnings("unchecked")
            List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
            if (contained != null && !contained.isEmpty()) {
                List<Map<String, String>> types = new ArrayList<>();
                for (Document c : contained) {
                    String uri = Objects.toString(c.getString("uri"), "").trim();
                    Boolean disabled = c.getBoolean("disabled");
                    if (disabled != null && disabled) {
                        continue;
                    }
                    if (uri.isBlank()) continue;

                    // Excluir los tipos estándar de Pure
                    String lowerUri = uri.toLowerCase();
                    if (lowerUri.endsWith("/academic") 
                            || lowerUri.endsWith("/corporate") 
                            || lowerUri.endsWith("/government") 
                            || lowerUri.endsWith("/medical") 
                            || lowerUri.endsWith("/oth") 
                            || lowerUri.endsWith("/unknown")) {
                        continue;
                    }

                    String label = "";
                    Document termDoc = (Document) c.get("term");
                    if (termDoc != null) {
                        @SuppressWarnings("unchecked")
                        List<Document> textList = (List<Document>) termDoc.get("text");
                        if (textList != null) {
                            String ca = null, es = null, en = null;
                            for (Document t : textList) {
                                String locale = t.getString("locale");
                                String val = t.getString("value");
                                if ("ca_ES".equals(locale)) ca = val;
                                else if ("es_ES".equals(locale)) es = val;
                                else if ("en_GB".equals(locale)) en = val;
                            }
                            label = ca != null ? ca : (es != null ? es : (en != null ? en : ""));
                        }
                    }
                    label = label.trim();
                    if (!label.isBlank()) {
                        types.add(Map.of("uri", uri, "label", label));
                    }
                }
                if (!types.isEmpty()) {
                    return types.stream()
                        .sorted(Comparator.comparing(
                            r -> r.get("label"),
                            String.CASE_INSENSITIVE_ORDER))
                        .collect(Collectors.toList());
                }
            }
        }

        // Fallback a la agregación sobre organizaciones existentes si no se encuentra en Classificationschemes
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("type").exists(true).ne(null)),
            Aggregation.group("type.uri").first("type.term").as("label"),
            Aggregation.match(Criteria.where("_id").ne(null).ne("")),
            Aggregation.sort(Sort.by("_id"))
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(
            agg, "ExternalOrganizations", Document.class
        );

        return results.getMappedResults().stream()
            .map(doc -> {
                String uri = Objects.toString(doc.getString("_id"), "").trim();
                @SuppressWarnings("unchecked")
                Map<String, String> term = (Map<String, String>) doc.get("label");
                String label = term == null ? "" :
                    term.getOrDefault("ca_ES",
                        term.getOrDefault("es_ES",
                            term.getOrDefault("en_GB", ""))).trim();
                return Map.of("uri", uri, "label", label);
            })
            .filter(row -> {
                String uri = row.get("uri");
                if (uri == null) return false;
                String lowerUri = uri.toLowerCase();
                boolean isStandard = lowerUri.endsWith("/academic") 
                        || lowerUri.endsWith("/corporate") 
                        || lowerUri.endsWith("/government") 
                        || lowerUri.endsWith("/medical") 
                        || lowerUri.endsWith("/oth") 
                        || lowerUri.endsWith("/unknown");
                return !row.get("label").isBlank() && !isStandard;
            })
            .sorted(Comparator.comparing(
                r -> r.get("label"),
                String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
    }

    @PostMapping("/stats/apply-suggested-type")
    public Map<String, Object> applySuggestedType(@RequestBody Map<String, String> payload) {
        String uuid = payload == null ? "" : Objects.toString(payload.get("uuid"), "").trim();
        String suggestedTypeUri = payload == null ? "" : Objects.toString(payload.get("suggestedTypeUri"), "").trim();
        String suggestedType = payload == null ? "" : Objects.toString(payload.get("suggestedType"), "").trim();

        if (uuid.isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }
        if (suggestedTypeUri.isBlank() && suggestedType.isBlank()) {
            return Map.of("updated", false, "reason", "missing-suggested-type-uri");
        }

        Query q = new Query(Criteria.where("uuid").is(uuid));
        ExternalOrganization org = mongoTemplate.findOne(q, ExternalOrganization.class);
        if (org == null) {
            return Map.of("updated", false, "reason", "organization-not-found", "uuid", uuid);
        }

        ExternalOrganization.UriTerm updatedType = resolveTypeUriTermFromPayload(suggestedTypeUri, suggestedType, org.getType());
        if (updatedType == null) {
            return Map.of("updated", false, "reason", "type-not-resolved", "uuid", uuid);
        }

        Map<String, Object> egretaSync = syncExternalOrganizationTypeToEgreta(org, updatedType);
        boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
        if (!egretaUpdated) {
            return Map.of(
                "updated", false,
                "reason", Objects.toString(egretaSync.get("reason"), "egreta-sync-failed"),
                "uuid", uuid,
                "details", egretaSync
            );
        }

        org.setType(updatedType);
        repository.save(org);
        invalidateMetadataCatalogCaches();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated", true);
        response.put("uuid", uuid);
        response.put("appliedType", suggestedType);
        response.put("appliedTypeUri", Objects.toString(updatedType.getUri(), ""));
        response.put("egreta", egretaSync);
        return response;
    }

    @PostMapping("/stats/approve-workflow")
    public Map<String, Object> approveWorkflow(@RequestBody Map<String, String> payload) {
        String uuid = payload == null ? "" : Objects.toString(payload.get("uuid"), "").trim();
        String targetEnv = payload == null ? "" : Objects.toString(payload.get("env"), "").trim();

        if (uuid.isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }

        Query q = new Query(Criteria.where("uuid").is(uuid));
        ExternalOrganization org = mongoTemplate.findOne(q, ExternalOrganization.class);
        if (org == null) {
            return Map.of("updated", false, "reason", "organization-not-found", "uuid", uuid);
        }

        ExternalOrganization.WorkflowStatus updatedWorkflow = org.getWorkflow();
        if (updatedWorkflow == null) {
            updatedWorkflow = new ExternalOrganization.WorkflowStatus();
        }
        updatedWorkflow.setStep("approved");

        // Sync with Egreta
        Map<String, Object> egretaSync = syncExternalOrganizationWorkflowToEgreta(org, updatedWorkflow, targetEnv);
        boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
        if (!egretaUpdated) {
            return Map.of(
                "updated", false,
                "reason", Objects.toString(egretaSync.get("reason"), "egreta-sync-failed"),
                "uuid", uuid,
                "details", egretaSync
            );
        }

        org.setWorkflow(updatedWorkflow);
        repository.save(org);

        return Map.of("updated", true, "uuid", uuid, "workflow", "approved");
    }

    private Map<String, Object> syncExternalOrganizationWorkflowToEgreta(
            ExternalOrganization org,
            ExternalOrganization.WorkflowStatus updatedWorkflow,
            String targetEnv) {
        if (!egretaExternalOrgEnabled) {
            return Map.of("updated", true, "reason", "egreta-sync-disabled");
        }

        String orgUuid = org.getUuid();
        if (orgUuid == null || orgUuid.isBlank()) {
            return Map.of("updated", false, "reason", "missing-org-uuid");
        }

        String getUrl = resolveEgretaUrl(egretaExternalOrgGetUrlTemplate, orgUuid);
        String putUrl = resolveEgretaUrl(egretaExternalOrgPutUrlTemplate, orgUuid);

        if (getUrl.isBlank()) {
            getUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }
        if (putUrl.isBlank()) {
            putUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }

        if ("test".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egreta.uab.cat", "egretat.uab.cat");
            putUrl = putUrl.replace("egreta.uab.cat", "egretat.uab.cat");
        } else if ("prod".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egretat.uab.cat", "egreta.uab.cat");
            putUrl = putUrl.replace("egretat.uab.cat", "egreta.uab.cat");
        }

        if (putUrl.isBlank()) {
            return Map.of("updated", false, "reason", "missing-put-url-template");
        }

        try {
            ObjectNode payloadNode;

            if (!getUrl.isBlank()) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(getUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                    .header("Accept", "application/json; charset=utf-8")
                    .GET();
                appendEgretaAuth(getBuilder);

                HttpResponse<String> getResponse = httpClient.send(getBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (getResponse.statusCode() < 200 || getResponse.statusCode() >= 300) {
                    return Map.of(
                        "updated", false,
                        "reason", "egreta-get-failed",
                        "status", getResponse.statusCode(),
                        "body", trimResponseBody(getResponse.body())
                    );
                }

                JsonNode root = objectMapper.readTree(getResponse.body());
                if (root == null || !root.isObject()) {
                    return Map.of("updated", false, "reason", "egreta-get-invalid-payload");
                }
                payloadNode = (ObjectNode) root;
            } else {
                payloadNode = objectMapper.valueToTree(org);
            }

            payloadNode.set("workflow", objectMapper.valueToTree(updatedWorkflow));

            HttpRequest.Builder putBuilder = HttpRequest.newBuilder()
                .uri(URI.create(putUrl))
                .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payloadNode)));
            appendEgretaAuth(putBuilder);

            HttpResponse<String> putResponse = httpClient.send(putBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (putResponse.statusCode() < 200 || putResponse.statusCode() >= 300) {
                return Map.of(
                    "updated", false,
                    "reason", "egreta-put-failed",
                    "status", putResponse.statusCode(),
                    "body", trimResponseBody(putResponse.body())
                );
            }

            return Map.of(
                "updated", true,
                "uuid", orgUuid,
                "status", putResponse.statusCode()
            );
        } catch (Exception e) {
            return Map.of(
                "updated", false,
                "reason", "egreta-sync-exception",
                "message", e.getMessage() == null ? "unknown" : e.getMessage()
            );
        }
    }

    private ExternalOrganization.UriTerm resolveTypeUriTermFromPayload(
            String typeUri,
            String typeLabel,
            ExternalOrganization.UriTerm current) {
        String uri = typeUri == null ? "" : typeUri.trim();
        if (!uri.isBlank()) {
            ExternalOrganization.UriTerm byUri = resolveTypeUriTermByUri(uri, current);
            if (byUri != null) return byUri;
        }
        String label = typeLabel == null ? "" : typeLabel.trim();
        if (!label.isBlank()) {
            return resolveTypeUriTerm(label, current);
        }
        return null;
    }

    private ExternalOrganization.UriTerm resolveTypeUriTermByUri(String uri, ExternalOrganization.UriTerm current) {
        String safeUri = uri == null ? "" : uri.trim();
        if (safeUri.isBlank()) {
            return current;
        }

        // 1. Intentar resolver desde Classificationschemes de Egreta
        Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes"));
        Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
        if (schemeDoc != null) {
            @SuppressWarnings("unchecked")
            List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
            if (contained != null) {
                for (Document c : contained) {
                    String curi = Objects.toString(c.getString("uri"), "").trim();
                    if (curi.equals(safeUri)) {
                        ExternalOrganization.UriTerm term = new ExternalOrganization.UriTerm();
                        term.setUri(safeUri);
                        Map<String, String> localized = new LinkedHashMap<>();
                        String ca = "", es = "", en = "";
                        Document termDoc = (Document) c.get("term");
                        if (termDoc != null) {
                            @SuppressWarnings("unchecked")
                            List<Document> textList = (List<Document>) termDoc.get("text");
                            if (textList != null) {
                                for (Document t : textList) {
                                    String locale = t.getString("locale");
                                    String val = t.getString("value");
                                    if ("ca_ES".equals(locale)) ca = val;
                                    else if ("es_ES".equals(locale)) es = val;
                                    else if ("en_GB".equals(locale)) en = val;
                                }
                            }
                        }
                        localized.put("ca_ES", ca != null ? ca.trim() : "");
                        localized.put("es_ES", es != null ? es.trim() : "");
                        localized.put("en_GB", en != null ? en.trim() : "");
                        term.setTerm(localized);
                        return term;
                    }
                }
            }
        }

        Query typeQuery = new Query(Criteria.where("type.uri").is(safeUri));
        typeQuery.limit(1);

        ExternalOrganization matched = mongoTemplate.findOne(typeQuery, ExternalOrganization.class);
        if (matched != null && matched.getType() != null) {
            return cloneUriTerm(matched.getType());
        }

        ExternalOrganization.UriTerm term = cloneUriTerm(current);
        if (term == null) term = new ExternalOrganization.UriTerm();
        term.setUri(safeUri);
        if (term.getTerm() == null || term.getTerm().isEmpty()) {
            Map<String, String> localized = new LinkedHashMap<>();
            localized.put("ca_ES", "");
            localized.put("es_ES", "");
            localized.put("en_GB", "");
            term.setTerm(localized);
        }
        return term;
    }

    private Map<String, Object> syncExternalOrganizationTypeToEgreta(
            ExternalOrganization org,
            ExternalOrganization.UriTerm updatedType) {
        return syncExternalOrganizationTypeToEgreta(org, updatedType, null);
    }

    private Map<String, Object> syncExternalOrganizationTypeToEgreta(
            ExternalOrganization org,
            ExternalOrganization.UriTerm updatedType,
            String targetEnv) {
        if (!egretaExternalOrgEnabled) {
            return Map.of("updated", false, "reason", "egreta-sync-disabled");
        }
        if (org == null || org.getUuid() == null || org.getUuid().isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }

        String orgUuid = org.getUuid().trim();
        String getUrl = resolveEgretaUrl(egretaExternalOrgGetUrlTemplate, orgUuid);
        String putUrl = resolveEgretaUrl(egretaExternalOrgPutUrlTemplate, orgUuid);

        if (getUrl.isBlank()) {
            getUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }
        if (putUrl.isBlank()) {
            putUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }

        if ("test".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egreta.uab.cat", "egretat.uab.cat");
            putUrl = putUrl.replace("egreta.uab.cat", "egretat.uab.cat");
        } else if ("prod".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egretat.uab.cat", "egreta.uab.cat");
            putUrl = putUrl.replace("egretat.uab.cat", "egreta.uab.cat");
        }

        if (putUrl.isBlank()) {
            return Map.of("updated", false, "reason", "missing-put-url-template");
        }

        try {
            ObjectNode payloadNode;

            if (!getUrl.isBlank()) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(getUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                    .header("Accept", "application/json")
                    .GET();
                appendEgretaAuth(getBuilder);

                HttpResponse<String> getResponse = httpClient.send(getBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (getResponse.statusCode() < 200 || getResponse.statusCode() >= 300) {
                    return Map.of(
                        "updated", false,
                        "reason", "egreta-get-failed",
                        "status", getResponse.statusCode(),
                        "body", trimResponseBody(getResponse.body())
                    );
                }

                JsonNode root = objectMapper.readTree(getResponse.body());
                if (root == null || !root.isObject()) {
                    return Map.of("updated", false, "reason", "egreta-get-invalid-payload");
                }
                payloadNode = (ObjectNode) root;
            } else {
                payloadNode = objectMapper.valueToTree(org);
            }

            payloadNode.set("type", objectMapper.valueToTree(updatedType));

            HttpRequest.Builder putBuilder = HttpRequest.newBuilder()
                .uri(URI.create(putUrl))
                .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payloadNode)));
            appendEgretaAuth(putBuilder);

            HttpResponse<String> putResponse = httpClient.send(putBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (putResponse.statusCode() < 200 || putResponse.statusCode() >= 300) {
                return Map.of(
                    "updated", false,
                    "reason", "egreta-put-failed",
                    "status", putResponse.statusCode(),
                    "body", trimResponseBody(putResponse.body())
                );
            }

            return Map.of(
                "updated", true,
                "uuid", orgUuid,
                "status", putResponse.statusCode()
            );
        } catch (Exception e) {
            return Map.of(
                "updated", false,
                "reason", "egreta-sync-exception",
                "message", e.getMessage() == null ? "unknown" : e.getMessage()
            );
        }
    }

    private void appendEgretaAuth(HttpRequest.Builder builder) {
        if (egretaExternalOrgApiKey != null && !egretaExternalOrgApiKey.isBlank()) {
            builder.header("api-key", egretaExternalOrgApiKey.trim());
        }
    }

    private String resolveEgretaUrl(String template, String uuid) {
        if (template == null || template.isBlank()) return "";
        String safe = uuid == null ? "" : uuid;
        return template
            .replace("{uuid}", safe)
            .replace("{id}", safe);
    }

    private String trimResponseBody(String body) {
        if (body == null) return "";
        String compact = body.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 320 ? compact.substring(0, 320) + "..." : compact;
    }

    @PostMapping("/stats/apply-suggested-country")
    public Map<String, Object> applySuggestedCountry(@RequestBody Map<String, String> payload) {
        String uuid = payload == null ? "" : Objects.toString(payload.get("uuid"), "").trim();
        String suggestedCountryUri = payload == null ? "" : Objects.toString(payload.get("suggestedCountryUri"), "").trim();
        String suggestedCountry = payload == null ? "" : Objects.toString(payload.get("suggestedCountry"), "").trim();

        if (uuid.isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }
        if (suggestedCountryUri.isBlank() && suggestedCountry.isBlank()) {
            return Map.of("updated", false, "reason", "missing-suggested-country-uri");
        }

        Query q = new Query(Criteria.where("uuid").is(uuid));
        ExternalOrganization org = mongoTemplate.findOne(q, ExternalOrganization.class);
        if (org == null) {
            return Map.of("updated", false, "reason", "organization-not-found", "uuid", uuid);
        }

        ExternalOrganization.UriTerm updatedCountry = resolveCountryUriTermFromPayload(
            suggestedCountryUri,
            suggestedCountry,
            org.getAddress() == null ? null : org.getAddress().getCountry()
        );
        if (updatedCountry == null) {
            return Map.of("updated", false, "reason", "country-not-resolved", "uuid", uuid);
        }

        Map<String, Object> egretaSync = syncExternalOrganizationCountryToEgreta(org, updatedCountry);
        boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
        if (!egretaUpdated) {
            return Map.of(
                "updated", false,
                "reason", Objects.toString(egretaSync.get("reason"), "egreta-sync-failed"),
                "uuid", uuid,
                "details", egretaSync
            );
        }

        if (org.getAddress() == null) {
            org.setAddress(new ExternalOrganization.Address());
        }
        org.getAddress().setCountry(updatedCountry);
        repository.save(org);
        invalidateMetadataCatalogCaches();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated", true);
        response.put("uuid", uuid);
        response.put("appliedCountry", suggestedCountry);
        response.put("appliedCountryUri", Objects.toString(updatedCountry.getUri(), ""));
        response.put("egreta", egretaSync);
        return response;
    }

    @PostMapping("/stats/apply-suggested-funding")
    public Map<String, Object> applySuggestedFunding(@RequestBody Map<String, String> payload) {
        String uuid = payload == null ? "" : Objects.toString(payload.get("uuid"), "").trim();
        String suggestedFundingUri = payload == null ? "" : Objects.toString(payload.get("suggestedFundingUri"), "").trim();
        String suggestedFunding = payload == null ? "" : Objects.toString(payload.get("suggestedFunding"), "").trim();

        if (uuid.isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }
        if (suggestedFundingUri.isBlank() && suggestedFunding.isBlank()) {
            return Map.of("updated", false, "reason", "missing-suggested-funding-uri");
        }

        String normalizedFunding = resolveFundingLabelFromUri(suggestedFundingUri);
        if (normalizedFunding.isBlank()) {
            normalizedFunding = resolveModelFundingLabel(suggestedFunding);
        }
        if (normalizedFunding.isBlank()) {
            return Map.of("updated", false, "reason", "invalid-suggested-funding");
        }

        Query q = new Query(Criteria.where("uuid").is(uuid));
        ExternalOrganization org = mongoTemplate.findOne(q, ExternalOrganization.class);
        if (org == null) {
            return Map.of("updated", false, "reason", "organization-not-found", "uuid", uuid);
        }

        Map<String, Object> egretaSync = syncExternalOrganizationFundingToEgreta(org, normalizedFunding);
        boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
        if (!egretaUpdated) {
            return Map.of(
                "updated", false,
                "reason", Objects.toString(egretaSync.get("reason"), "egreta-sync-failed"),
                "uuid", uuid,
                "details", egretaSync
            );
        }

        applyFundingToKeywordGroups(org, normalizedFunding);
        repository.save(org);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated", true);
        response.put("uuid", uuid);
        response.put("appliedFunding", normalizedFunding);
        response.put("appliedFundingUri", resolveFundingUri(normalizedFunding));
        response.put("egreta", egretaSync);
        return response;
    }

    private Map<String, Object> syncExternalOrganizationFundingToEgreta(
            ExternalOrganization org,
            String normalizedFunding) {
        return syncExternalOrganizationFundingToEgreta(org, normalizedFunding, null);
    }

    private Map<String, Object> syncExternalOrganizationFundingToEgreta(
            ExternalOrganization org,
            String normalizedFunding,
            String targetEnv) {
        if (!egretaExternalOrgEnabled) {
            return Map.of("updated", false, "reason", "egreta-sync-disabled");
        }
        if (org == null || org.getUuid() == null || org.getUuid().isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }

        String orgUuid = org.getUuid().trim();
        String getUrl = resolveEgretaUrl(egretaExternalOrgGetUrlTemplate, orgUuid);
        String putUrl = resolveEgretaUrl(egretaExternalOrgPutUrlTemplate, orgUuid);

        if (getUrl.isBlank()) {
            getUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }
        if (putUrl.isBlank()) {
            putUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }

        if ("test".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egreta.uab.cat", "egretat.uab.cat");
            putUrl = putUrl.replace("egreta.uab.cat", "egretat.uab.cat");
        } else if ("prod".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egretat.uab.cat", "egreta.uab.cat");
            putUrl = putUrl.replace("egretat.uab.cat", "egreta.uab.cat");
        }

        if (putUrl.isBlank()) {
            return Map.of("updated", false, "reason", "missing-put-url-template");
        }

        try {
            ObjectNode payloadNode;

            if (!getUrl.isBlank()) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(getUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                    .header("Accept", "application/json")
                    .GET();
                appendEgretaAuth(getBuilder);

                HttpResponse<String> getResponse = httpClient.send(getBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (getResponse.statusCode() < 200 || getResponse.statusCode() >= 300) {
                    return Map.of(
                        "updated", false,
                        "reason", "egreta-get-failed",
                        "status", getResponse.statusCode(),
                        "body", trimResponseBody(getResponse.body())
                    );
                }

                JsonNode root = objectMapper.readTree(getResponse.body());
                if (root == null || !root.isObject()) {
                    return Map.of("updated", false, "reason", "egreta-get-invalid-payload");
                }
                payloadNode = (ObjectNode) root;
            } else {
                payloadNode = objectMapper.valueToTree(org);
            }

            upsertFundingKeywordGroupInPayload(payloadNode, normalizedFunding);

            HttpRequest.Builder putBuilder = HttpRequest.newBuilder()
                .uri(URI.create(putUrl))
                .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payloadNode)));
            appendEgretaAuth(putBuilder);

            HttpResponse<String> putResponse = httpClient.send(putBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (putResponse.statusCode() < 200 || putResponse.statusCode() >= 300) {
                return Map.of(
                    "updated", false,
                    "reason", "egreta-put-failed",
                    "status", putResponse.statusCode(),
                    "body", trimResponseBody(putResponse.body())
                );
            }

            return Map.of(
                "updated", true,
                "uuid", orgUuid,
                "status", putResponse.statusCode()
            );
        } catch (Exception e) {
            return Map.of(
                "updated", false,
                "reason", "egreta-sync-exception",
                "message", e.getMessage() == null ? "unknown" : e.getMessage()
            );
        }
    }

    private void upsertFundingKeywordGroupInPayload(ObjectNode payloadNode, String normalizedFunding) {
        com.fasterxml.jackson.databind.node.ArrayNode groups;
        JsonNode existingGroups = payloadNode.path("keywordGroups");
        if (existingGroups == null || !existingGroups.isArray()) {
            groups = objectMapper.createArrayNode();
            payloadNode.set("keywordGroups", groups);
        } else {
            groups = (com.fasterxml.jackson.databind.node.ArrayNode) existingGroups;
        }

        ObjectNode targetGroup = null;
        for (JsonNode node : groups) {
            if (!node.isObject()) continue;
            String logicalName = node.path("logicalName").asText("").trim();
            if (FUNDING_LOGICAL_NAME.equals(logicalName)) {
                targetGroup = (ObjectNode) node;
                break;
            }
        }

        if (targetGroup == null) {
            targetGroup = objectMapper.createObjectNode();
            targetGroup.put("typeDiscriminator", "ClassificationsKeywordGroup");
            targetGroup.put("logicalName", FUNDING_LOGICAL_NAME);
            ObjectNode nameNode = objectMapper.createObjectNode();
            nameNode.put("en_GB", "Funding type");
            nameNode.put("es_ES", "Tipo de financiacion");
            nameNode.put("ca_ES", "Tipus de finançament");
            targetGroup.set("name", nameNode);
            groups.add(targetGroup);
        }

        ObjectNode classification = objectMapper.createObjectNode();
        classification.put("uri", resolveFundingUri(normalizedFunding));
        classification.set("term", objectMapper.valueToTree(resolveFundingTerms(normalizedFunding)));
        com.fasterxml.jackson.databind.node.ArrayNode classifications = objectMapper.createArrayNode();
        classifications.add(classification);
        targetGroup.set("classifications", classifications);
    }

    private static final String FUNDING_LOGICAL_NAME = "/uab/externalorganisations/caracter";

    private void applyFundingToKeywordGroups(ExternalOrganization org, String normalizedFunding) {
        if (org.getKeywordGroups() == null) {
            org.setKeywordGroups(new ArrayList<>());
        }

        ExternalOrganization.KeywordGroup fundingGroup = org.getKeywordGroups().stream()
            .filter(kg -> FUNDING_LOGICAL_NAME.equals(Objects.toString(kg.getLogicalName(), "").trim()))
            .findFirst()
            .orElse(null);

        if (fundingGroup == null) {
            fundingGroup = new ExternalOrganization.KeywordGroup();
            fundingGroup.setTypeDiscriminator("ClassificationsKeywordGroup");
            fundingGroup.setLogicalName(FUNDING_LOGICAL_NAME);
            Map<String, String> groupName = new LinkedHashMap<>();
            groupName.put("en_GB", "Funding type");
            groupName.put("es_ES", "Tipo de financiacion");
            groupName.put("ca_ES", "Tipus de finançament");
            fundingGroup.setName(groupName);
            org.getKeywordGroups().add(fundingGroup);
        }

        ExternalOrganization.UriTerm classification = new ExternalOrganization.UriTerm();
        classification.setUri(resolveFundingUri(normalizedFunding));
        classification.setTerm(resolveFundingTerms(normalizedFunding));
        fundingGroup.setClassifications(List.of(classification));
    }

    private String resolveFundingUri(String normalizedFunding) {
        return switch (normalizedFunding) {
            case "Publica" -> "/uab/externalorganisations/caracter/pub";
            case "Privada" -> "/uab/externalorganisations/caracter/prv";
            case "Mixta" -> "/uab/externalorganisations/caracter/mix";
            default -> "/uab/externalorganisations/caracter/unknown";
        };
    }

    private String resolveFundingLabelFromUri(String fundingUri) {
        String safeUri = fundingUri == null ? "" : fundingUri.trim();
        if (safeUri.isBlank()) return "";
        return switch (safeUri) {
            case "/uab/externalorganisations/caracter/pub" -> "Publica";
            case "/uab/externalorganisations/caracter/priv" -> "Privada";
            case "/uab/externalorganisations/caracter/prv" -> "Privada";
            case "/uab/externalorganisations/caracter/mix" -> "Mixta";
            default -> "";
        };
    }

    private Map<String, String> resolveFundingTerms(String normalizedFunding) {
        Map<String, String> terms = new LinkedHashMap<>();
        switch (normalizedFunding) {
            case "Publica" -> {
                terms.put("en_GB", "Public");
                terms.put("es_ES", "Publica");
                terms.put("ca_ES", "Publica");
            }
            case "Privada" -> {
                terms.put("en_GB", "Private");
                terms.put("es_ES", "Privada");
                terms.put("ca_ES", "Privada");
            }
            case "Mixta" -> {
                terms.put("en_GB", "Mixed");
                terms.put("es_ES", "Mixta");
                terms.put("ca_ES", "Mixta");
            }
            default -> {
                terms.put("en_GB", normalizedFunding);
                terms.put("es_ES", normalizedFunding);
                terms.put("ca_ES", normalizedFunding);
            }
        }
        return terms;
    }

    private ExternalOrganization.UriTerm resolveCountryUriTermFromPayload(
            String countryUri,
            String countryLabel,
            ExternalOrganization.UriTerm current) {
        String uri = countryUri == null ? "" : countryUri.trim();
        if (!uri.isBlank()) {
            ExternalOrganization.UriTerm byUri = resolveCountryUriTermByUri(uri, current);
            if (byUri != null) return byUri;
        }
        String label = countryLabel == null ? "" : countryLabel.trim();
        if (!label.isBlank()) {
            return resolveCountryUriTerm(label, current);
        }
        return null;
    }

    private ExternalOrganization.UriTerm resolveCountryUriTermByUri(String uri, ExternalOrganization.UriTerm current) {
        String safeUri = uri == null ? "" : uri.trim();
        if (safeUri.isBlank()) {
            return current;
        }

        Query countryQuery = new Query(Criteria.where("address.country.uri").is(safeUri));
        countryQuery.limit(1);

        ExternalOrganization matched = mongoTemplate.findOne(countryQuery, ExternalOrganization.class);
        if (matched != null && matched.getAddress() != null && matched.getAddress().getCountry() != null) {
            return cloneUriTerm(matched.getAddress().getCountry());
        }

        ExternalOrganization.UriTerm term = cloneUriTerm(current);
        if (term == null) term = new ExternalOrganization.UriTerm();
        term.setUri(safeUri);
        if (term.getTerm() == null || term.getTerm().isEmpty()) {
            Map<String, String> localized = new LinkedHashMap<>();
            localized.put("ca_ES", "");
            localized.put("es_ES", "");
            localized.put("en_GB", "");
            term.setTerm(localized);
        }
        return term;
    }

    private String resolveCountryUriByLabel(String label) {
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.isBlank()) return "";
        ExternalOrganization.UriTerm term = resolveCountryUriTerm(safeLabel, null);
        return term == null ? "" : Objects.toString(term.getUri(), "").trim();
    }

    private Map<String, Object> syncExternalOrganizationCountryToEgreta(
            ExternalOrganization org,
            ExternalOrganization.UriTerm updatedCountry) {
        return syncExternalOrganizationCountryToEgreta(org, updatedCountry, null);
    }

    private Map<String, Object> syncExternalOrganizationCountryToEgreta(
            ExternalOrganization org,
            ExternalOrganization.UriTerm updatedCountry,
            String targetEnv) {
        if (!egretaExternalOrgEnabled) {
            return Map.of("updated", false, "reason", "egreta-sync-disabled");
        }
        if (org == null || org.getUuid() == null || org.getUuid().isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }

        String orgUuid = org.getUuid().trim();
        String getUrl = resolveEgretaUrl(egretaExternalOrgGetUrlTemplate, orgUuid);
        String putUrl = resolveEgretaUrl(egretaExternalOrgPutUrlTemplate, orgUuid);

        if (getUrl.isBlank()) {
            getUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }
        if (putUrl.isBlank()) {
            putUrl = resolveEgretaUrl(egretaExternalOrgBaseUrl + "/{uuid}", orgUuid);
        }

        if ("test".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egreta.uab.cat", "egretat.uab.cat");
            putUrl = putUrl.replace("egreta.uab.cat", "egretat.uab.cat");
        } else if ("prod".equalsIgnoreCase(targetEnv)) {
            getUrl = getUrl.replace("egretat.uab.cat", "egreta.uab.cat");
            putUrl = putUrl.replace("egretat.uab.cat", "egreta.uab.cat");
        } else {
             getUrl = getUrl.replace("egretat.uab.cat", "egreta.uab.cat");
            putUrl = putUrl.replace("egretat.uab.cat", "egreta.uab.cat");
        }

        if (putUrl.isBlank()) {
            return Map.of("updated", false, "reason", "missing-put-url-template");
        }

        try {
            ObjectNode payloadNode;

            if (!getUrl.isBlank()) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(getUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                    .header("Accept", "application/json")
                    .GET();
                appendEgretaAuth(getBuilder);

                HttpResponse<String> getResponse = httpClient.send(getBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (getResponse.statusCode() < 200 || getResponse.statusCode() >= 300) {
                    return Map.of(
                        "updated", false,
                        "reason", "egreta-get-failed",
                        "status", getResponse.statusCode(),
                        "body", trimResponseBody(getResponse.body())
                    );
                }

                JsonNode root = objectMapper.readTree(getResponse.body());
                if (root == null || !root.isObject()) {
                    return Map.of("updated", false, "reason", "egreta-get-invalid-payload");
                }
                payloadNode = (ObjectNode) root;
            } else {
                payloadNode = objectMapper.valueToTree(org);
            }

            JsonNode addressNode = payloadNode.path("address");
            ObjectNode addressObject;
            if (addressNode == null || !addressNode.isObject()) {
                addressObject = objectMapper.createObjectNode();
                payloadNode.set("address", addressObject);
            } else {
                addressObject = (ObjectNode) addressNode;
            }
            addressObject.set("country", objectMapper.valueToTree(updatedCountry));

            HttpRequest.Builder putBuilder = HttpRequest.newBuilder()
                .uri(URI.create(putUrl))
                .timeout(Duration.ofMillis(Math.max(1000, egretaExternalOrgTimeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payloadNode)));
            appendEgretaAuth(putBuilder);

            HttpResponse<String> putResponse = httpClient.send(putBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (putResponse.statusCode() < 200 || putResponse.statusCode() >= 300) {
                return Map.of(
                    "updated", false,
                    "reason", "egreta-put-failed",
                    "status", putResponse.statusCode(),
                    "body", trimResponseBody(putResponse.body())
                );
            }

            return Map.of(
                "updated", true,
                "uuid", orgUuid,
                "status", putResponse.statusCode()
            );
        } catch (Exception e) {
            return Map.of(
                "updated", false,
                "reason", "egreta-sync-exception",
                "message", e.getMessage() == null ? "unknown" : e.getMessage()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Debug endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/debug/references")
    public Map<String, Object> debugReferences(@RequestParam String uuid) {
        String normalizedUuid = uuid.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", normalizedUuid);

        Map<String, List<String>> pathsPerCollection = new LinkedHashMap<>();
        int collectionsScanned = 0;
        Document inFilter = new Document("$in", List.of(normalizedUuid));

        for (String collection : resolveAllCollections()) {
            if (isExtOrgCollection(collection)) continue;
            collectionsScanned++;

            String collectionLower = collection.toLowerCase();
            Set<String> matchingPaths = new LinkedHashSet<>();

            List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
                .filter(e -> collectionLower.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .distinct()
                .collect(Collectors.toList());

            for (String path : knownPaths) {
                try {
                    boolean found = mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, String.class)
                        .into(new ArrayList<>())
                        .stream()
                        .anyMatch(normalizedUuid::equals);
                    if (found) matchingPaths.add(path);
                } catch (Exception ignored) {}
            }

            Set<String> knownPathSet = new HashSet<>(knownPaths);
            List<String> sampledPaths = discoverUuidPaths(collection, 100)
                .stream().filter(p -> !knownPathSet.contains(p)).collect(Collectors.toList());

            for (String path : sampledPaths) {
                Document filter = new Document(path, inFilter);
                try {
                    List<String> values = mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, filter, String.class)
                        .into(new ArrayList<>());
                    if (!values.isEmpty()) matchingPaths.add(path);
                } catch (Exception ignored) {}
            }

            if (!matchingPaths.isEmpty()) {
                pathsPerCollection.put(collection, new ArrayList<>(matchingPaths));
            }
        }

        String extOrgCollection = externalOrganizationCollection;
        collectionsScanned++;
        Set<String> extOrgMatchingPaths = new LinkedHashSet<>();
        List<String> extOrgPaths = discoverUuidPaths(extOrgCollection, 200)
            .stream()
            .filter(p -> !isNonReferenceUuidPath(p))
            .collect(Collectors.toList());

        for (String path : extOrgPaths) {
            Document filter = new Document(path, inFilter);
            try {
                List<String> values = mongoTemplate.getDb()
                    .getCollection(extOrgCollection)
                    .distinct(path, filter, String.class)
                    .into(new ArrayList<>());
                if (!values.isEmpty()) extOrgMatchingPaths.add(path);
            } catch (Exception ignored) {}
        }

        if (!extOrgMatchingPaths.isEmpty()) {
            pathsPerCollection.put(extOrgCollection, new ArrayList<>(extOrgMatchingPaths));
        }

        result.put("referencedIn", pathsPerCollection);
        result.put("collectionsScanned", collectionsScanned);
        return result;
    }

    @GetMapping("/debug/schema")
    public Map<String, Object> debugSchema(@RequestParam String collection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collection", collection);

        String collectionLower = collection.toLowerCase();
        List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
            .filter(e -> collectionLower.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .distinct()
            .collect(Collectors.toList());
        result.put("knownPaths", knownPaths);
        result.put("discoveredPaths", discoverUuidPaths(collection, 200));
        return result;
    }

    @GetMapping("/debug/paths")
    public Map<String, Object> debugPaths(@RequestParam String collection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collection", collection);

        String collectionLower = collection.toLowerCase();
        List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
            .filter(e -> collectionLower.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .distinct()
            .collect(Collectors.toList());
        result.put("knownPaths", knownPaths);
        result.put("discoveredPaths", discoverUuidPaths(collection, 200));
        return result;
    }

    // =========================================================================
    // GET /external-organizations/graph/similarity
    // Returns graph nodes (organizations) and edges (similarity links)
    // Similarity is based on normalized name similarity (threshold 0.9 by default)
    // Limit defaults to first 1000 organizations for faster testing
    // =========================================================================
    @GetMapping("/graph/similarity")
    public Map<String, Object> graphSimilarity(
            @RequestParam(name = "threshold", defaultValue = "0.9") double thresholdParam,
            @RequestParam(name = "limit", defaultValue = "10000") int limitParam) {
        double threshold = Math.max(0.0, Math.min(1.0, thresholdParam));
        int limit = Math.max(1, limitParam);

        String cacheKey = String.format(Locale.ROOT, "%.3f|%d", threshold, limit);
        CachedGraphResult cached = graphSimilarityCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp()) <= GRAPH_SIMILARITY_CACHE_TTL_MS) {
            return cached.payload();
        }

        int totalAvailableOrgs = (int) repository.count();
        List<ExternalOrganization> orgs = findOrganizationsLimited(limit);

        List<OrgSimilarityProfile> profiles = buildSimilarityProfiles(orgs);
        List<Map<String, Object>> nodes = profiles.stream()
            .map(p -> Map.<String, Object>of(
                "id", p.id(),
                "label", p.displayName(),
                "title", p.displayName() + " | " + (p.typeLabel().isBlank() ? "(sin tipo)" : p.typeLabel()) + " | " + (p.countryLabel().isBlank() ? "(sin país)" : p.countryLabel()),
                "type", p.typeLabel().isBlank() ? "(sin tipo)" : p.typeLabel(),
                "country", p.countryLabel().isBlank() ? "(sin país)" : p.countryLabel(),
                "color", p.typeLabel().isBlank() ? "#ccc" : "#4CAF50"
            ))
            .collect(Collectors.toList());

        SimilarityPairResult pairResult = buildSimilarityPairs(profiles, threshold, Integer.MAX_VALUE);
        List<Map<String, Object>> edges = pairResult.pairs().stream()
            .map(p -> Map.of(
                "from", p.get("from"),
                "to", p.get("to"),
                "value", p.get("value"),
                "title", "Similitud: " + Math.round(((Number) p.get("value")).doubleValue() * 100.0) + "%"
            ))
            .collect(Collectors.toList());

        Set<String> connectedNodeIds = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            connectedNodeIds.add(String.valueOf(edge.get("from")));
            connectedNodeIds.add(String.valueOf(edge.get("to")));
        }

        List<Map<String, Object>> connectedNodes = nodes.stream()
            .filter(node -> connectedNodeIds.contains(String.valueOf(node.get("id"))))
            .collect(Collectors.toList());

        Map<String, Object> result = Map.of(
            "nodes", connectedNodes,
            "edges", edges,
            "threshold", threshold,
            "limit", limit,
            "totalOrgs", connectedNodes.size(),
            "totalProcessedOrgs", orgs.size(),
            "totalAvailableOrgs", totalAvailableOrgs,
            "totalNodes", connectedNodes.size(),
            "totalEdges", edges.size()
        );

        graphSimilarityCache.put(cacheKey, new CachedGraphResult(result, now));
        if (graphSimilarityCache.size() > 100) {
            graphSimilarityCache.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > GRAPH_SIMILARITY_CACHE_TTL_MS);
        }

        return result;
    }

    @GetMapping("/similarity/pairs")
    public Map<String, Object> similarityPairs(
            @RequestParam(name = "threshold", defaultValue = "0.9") double thresholdParam,
            @RequestParam(name = "limit", defaultValue = "3000") int limitParam,
            @RequestParam(name = "maxPairs", defaultValue = "2500") int maxPairsParam) {
        double threshold = Math.max(0.0, Math.min(1.0, thresholdParam));
        int limit = Math.max(1, limitParam);
        int maxPairs = Math.max(1, maxPairsParam);

        String cacheKey = String.format(Locale.ROOT, "%.3f|%d|%d", threshold, limit, maxPairs);
        CachedPairsResult cached = pairsSimilarityCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp()) <= GRAPH_SIMILARITY_CACHE_TTL_MS) {
            return cached.payload();
        }

        int totalAvailableOrgs = (int) repository.count();
        List<ExternalOrganization> orgs = findOrganizationsLimited(limit);
        List<OrgSimilarityProfile> profiles = buildSimilarityProfiles(orgs);

        SimilarityPairResult pairResult = buildSimilarityPairs(profiles, threshold, maxPairs);

        Map<String, Object> result = Map.of(
            "pairs", pairResult.pairs(),
            "threshold", threshold,
            "limit", limit,
            "maxPairs", maxPairs,
            "truncated", pairResult.truncated(),
            "totalPairs", pairResult.pairs().size(),
            "totalConnectedOrgs", pairResult.connectedNodeIds().size(),
            "totalProcessedOrgs", profiles.size(),
            "totalAvailableOrgs", totalAvailableOrgs
        );

        pairsSimilarityCache.put(cacheKey, new CachedPairsResult(result, now));
        if (pairsSimilarityCache.size() > 100) {
            pairsSimilarityCache.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > GRAPH_SIMILARITY_CACHE_TTL_MS);
        }

        return result;
    }

    @GetMapping("/similarity/pairs/job/start")
    public Map<String, Object> startSimilarityPairsJob(
            @RequestParam(name = "threshold", defaultValue = "0.9") double thresholdParam,
            @RequestParam(name = "limit", defaultValue = "3000") int limitParam,
            @RequestParam(name = "maxPairs", defaultValue = "2500") int maxPairsParam) {
        double threshold = Math.max(0.0, Math.min(1.0, thresholdParam));
        int limit = Math.max(1, limitParam);
        int maxPairs = Math.max(1, maxPairsParam);

        cleanupOldSimilarityJobs();

        String jobId = UUID.randomUUID().toString();
        SimilarityJobState state = new SimilarityJobState(System.currentTimeMillis());
        state.progress = 1;
        state.message = "Iniciant procés";
        state.status = "running";
        similarityJobs.put(jobId, state);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> payload = similarityPairsWithProgress(threshold, limit, maxPairs, state);
                state.result = payload;
                state.progress = 100;
                state.message = "Completat";
                state.status = "done";
            } catch (Exception ex) {
                state.error = ex.getMessage() == null ? "unknown-error" : ex.getMessage();
                state.message = "Error en el càlcul";
                state.status = "error";
            }
        }, asyncExecutor);

        return Map.of(
            "jobId", jobId,
            "status", state.status,
            "progress", state.progress,
            "message", state.message
        );
    }

    @GetMapping("/similarity/pairs/job/status")
    public Map<String, Object> similarityPairsJobStatus(
            @RequestParam(name = "jobId") String jobId) {
        SimilarityJobState state = similarityJobs.get(jobId);
        if (state == null) {
            return Map.of(
                "jobId", jobId,
                "status", "not-found",
                "progress", 0,
                "message", "Job no trobat"
            );
        }

        if ("done".equals(state.status) && state.result != null) {
            return Map.of(
                "jobId", jobId,
                "status", state.status,
                "progress", state.progress,
                "message", state.message,
                "result", state.result
            );
        }

        if ("error".equals(state.status)) {
            return Map.of(
                "jobId", jobId,
                "status", state.status,
                "progress", state.progress,
                "message", state.message,
                "error", state.error == null ? "unknown-error" : state.error
            );
        }

        return Map.of(
            "jobId", jobId,
            "status", state.status,
            "progress", state.progress,
            "message", state.message
        );
    }

    private Map<String, Object> similarityPairsWithProgress(double threshold, int limit, int maxPairs, SimilarityJobState state) {
        String cacheKey = String.format(Locale.ROOT, "%.3f|%d|%d", threshold, limit, maxPairs);
        CachedPairsResult cached = pairsSimilarityCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp()) <= GRAPH_SIMILARITY_CACHE_TTL_MS) {
            state.progress = 100;
            state.message = "Resultat en caché";
            return cached.payload();
        }

        state.progress = 4;
        state.message = "Llegint organitzacions";

        int totalAvailableOrgs = (int) repository.count();
        List<ExternalOrganization> orgs = findOrganizationsLimited(limit);

        state.progress = 10;
        state.message = "Preparant noms";

        List<OrgSimilarityProfile> profiles = buildSimilarityProfiles(orgs);

        state.progress = 16;
        state.message = "Comparant noms";

        SimilarityPairResult pairResult = buildSimilarityPairs(
            profiles,
            threshold,
            maxPairs,
            value -> {
                state.progress = Math.max(16, Math.min(98, value));
                state.message = "Comparant noms";
            }
        );

        Map<String, Object> result = Map.of(
            "pairs", pairResult.pairs(),
            "threshold", threshold,
            "limit", limit,
            "maxPairs", maxPairs,
            "truncated", pairResult.truncated(),
            "totalPairs", pairResult.pairs().size(),
            "totalConnectedOrgs", pairResult.connectedNodeIds().size(),
            "totalProcessedOrgs", profiles.size(),
            "totalAvailableOrgs", totalAvailableOrgs
        );

        pairsSimilarityCache.put(cacheKey, new CachedPairsResult(result, now));
        if (pairsSimilarityCache.size() > 100) {
            pairsSimilarityCache.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > GRAPH_SIMILARITY_CACHE_TTL_MS);
        }

        return result;
    }

    private void cleanupOldSimilarityJobs() {
        long now = System.currentTimeMillis();
        long ttl = 10 * 60 * 1000;
        similarityJobs.entrySet().removeIf(e -> (now - e.getValue().createdAt) > ttl);
    }

    private record CachedGraphResult(Map<String, Object> payload, long timestamp) {}
    private record CachedPairsResult(Map<String, Object> payload, long timestamp) {}
    private record SimilarityPairResult(List<Map<String, Object>> pairs, Set<String> connectedNodeIds, boolean truncated) {}
    private static class SimilarityJobState {
        private final long createdAt;
        private volatile String status = "pending";
        private volatile int progress = 0;
        private volatile String message = "";
        private volatile String error;
        private volatile Map<String, Object> result;

        private SimilarityJobState(long createdAt) {
            this.createdAt = createdAt;
        }
    }
    private record OrgSimilarityProfile(String id, String displayName, Long pureId, String normalizedName, String[] tokens, String typeLabel, String countryLabel, String workflowStep) {}

    private List<OrgSimilarityProfile> buildSimilarityProfiles(List<ExternalOrganization> orgs) {
        List<OrgSimilarityProfile> profiles = new ArrayList<>(orgs.size());
        for (ExternalOrganization org : orgs) {
            String name = getOrgDisplayName(org);
            if (name.isBlank()) continue;

            String normalized = normalizeText(name);
            if (normalized.isBlank()) continue;

            String[] tokens = normalized.split("\\s+");
            if (tokens.length == 0) continue;

            String id = org.getUuid() != null ? org.getUuid() : org.getId();
            if (id == null || id.isBlank()) continue;

            profiles.add(new OrgSimilarityProfile(
                id,
                name,
                org.getPureId(),
                normalized,
                tokens,
                getOrgTypeLabel(org),
                getOrgCountryLabel(org),
                (org.getWorkflow() == null ? null : org.getWorkflow().getStep())
            ));
        }
        return profiles;
    }

    private List<ExternalOrganization> findOrganizationsLimited(int limit) {
        Query query = new Query().limit(Math.max(1, limit));
        query.fields()
             .include("uuid")
             .include("id")
             .include("displayName")
             .include("name")
             .include("pureId")
             .include("type")
             .include("address")
             .include("workflow");
        return mongoTemplate.find(query, ExternalOrganization.class);
    }

    private SimilarityPairResult buildSimilarityPairs(List<OrgSimilarityProfile> profiles, double threshold, int maxPairs) {
        return buildSimilarityPairs(profiles, threshold, maxPairs, null);
    }

    private SimilarityPairResult buildSimilarityPairs(List<OrgSimilarityProfile> profiles, double threshold, int maxPairs, java.util.function.IntConsumer progressCallback) {
        List<Map<String, Object>> pairs = new ArrayList<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> connectedNodeIds = new HashSet<>();
        boolean truncated = false;

        int n = profiles.size();
        long totalComparisons = ((long) n * (n - 1)) / 2;
        long processedComparisons = 0;
        long progressTick = Math.max(1, totalComparisons / 120);
        for (int i = 0; i < profiles.size(); i++) {
            for (int j = i + 1; j < profiles.size(); j++) {
                processedComparisons++;
                OrgSimilarityProfile p1 = profiles.get(i);
                OrgSimilarityProfile p2 = profiles.get(j);

                if (progressCallback != null && (processedComparisons % progressTick == 0 || processedComparisons == totalComparisons)) {
                    int p = totalComparisons == 0 ? 98 : (int) (16 + (processedComparisons * 82 / totalComparisons));
                    progressCallback.accept(Math.min(98, p));
                }

                int maxTokenLen = Math.max(p1.tokens().length, p2.tokens().length);
                int minTokenLen = Math.min(p1.tokens().length, p2.tokens().length);
                double maxPossible = maxTokenLen == 0 ? 0.0 : (double) minTokenLen / maxTokenLen;
                if (maxPossible < threshold) {
                    continue;
                }

                double similarity = calculateNameSimilarity(p1.tokens(), p2.tokens(), threshold);
                if (similarity < threshold) {
                    continue;
                }

                String id1 = p1.id();
                String id2 = p2.id();
                String edgeId = id1.compareTo(id2) < 0 ? id1 + "|" + id2 : id2 + "|" + id1;
                if (!edgeIds.add(edgeId)) {
                    continue;
                }

                String leftWorkflow = p1.workflowStep();
                String rightWorkflow = p2.workflowStep();

                pairs.add(Map.of(
                    "from", id1,
                    "to", id2,
                    "leftLabel", p1.displayName(),
                    "rightLabel", p2.displayName(),
                    "leftPureId", p1.pureId() == null ? "" : String.valueOf(p1.pureId()),
                    "rightPureId", p2.pureId() == null ? "" : String.valueOf(p2.pureId()),
                    "value", Math.round(similarity * 100.0) / 100.0,
                    "title", "Similitud: " + Math.round(similarity * 100.0) + "%",
                    "leftWorkflow", leftWorkflow,
                    "rightWorkflow", rightWorkflow
                ));
                connectedNodeIds.add(id1);
                connectedNodeIds.add(id2);

                if (pairs.size() >= maxPairs) {
                    truncated = true;
                    if (progressCallback != null) {
                        progressCallback.accept(98);
                    }
                    return new SimilarityPairResult(pairs, connectedNodeIds, truncated);
                }
            }
        }

        return new SimilarityPairResult(pairs, connectedNodeIds, truncated);
    }

    private String getOrgDisplayName(ExternalOrganization org) {
        if (org.getDisplayName() != null && !org.getDisplayName().isBlank()) {
            return org.getDisplayName();
        }
        if (org.getName() != null) {
            return org.getName().getOrDefault("en_GB",
                org.getName().getOrDefault("es_ES",
                    org.getName().getOrDefault("ca_ES", "")));
        }
        return "";
    }

    private String getOrgTypeLabel(ExternalOrganization org) {
        if (org.getType() != null && org.getType().getTerm() != null) {
            return org.getType().getTerm().getOrDefault("en_GB",
                org.getType().getTerm().getOrDefault("es_ES",
                    org.getType().getTerm().getOrDefault("ca_ES", "")));
        }
        return "";
    }

    private String getOrgCountryLabel(ExternalOrganization org) {
        if (org.getAddress() != null && org.getAddress().getCountry() != null && org.getAddress().getCountry().getTerm() != null) {
            return org.getAddress().getCountry().getTerm().getOrDefault("en_GB",
                org.getAddress().getCountry().getTerm().getOrDefault("es_ES",
                    org.getAddress().getCountry().getTerm().getOrDefault("ca_ES", "")));
        }
        return "";
    }

    private ExternalOrganization.UriTerm resolveTypeUriTerm(String label, ExternalOrganization.UriTerm current) {
        String normalizedLabel = normalizeText(label);
        if (normalizedLabel.isBlank()) {
            return current;
        }

        // 1. Intentar resolver desde Classificationschemes de Egreta por coincidencia de texto
        Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes"));
        Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
        if (schemeDoc != null) {
            @SuppressWarnings("unchecked")
            List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
            if (contained != null) {
                for (Document c : contained) {
                    Document termDoc = (Document) c.get("term");
                    if (termDoc != null) {
                        @SuppressWarnings("unchecked")
                        List<Document> textList = (List<Document>) termDoc.get("text");
                        if (textList != null) {
                            String ca = "", es = "", en = "";
                            boolean matched = false;
                            for (Document t : textList) {
                                String locale = t.getString("locale");
                                String val = t.getString("value");
                                if ("ca_ES".equals(locale)) {
                                    ca = val;
                                    if (normalizeText(val).equals(normalizedLabel)) matched = true;
                                } else if ("es_ES".equals(locale)) {
                                    es = val;
                                    if (normalizeText(val).equals(normalizedLabel)) matched = true;
                                } else if ("en_GB".equals(locale)) {
                                    en = val;
                                    if (normalizeText(val).equals(normalizedLabel)) matched = true;
                                }
                            }
                            if (matched) {
                                ExternalOrganization.UriTerm term = new ExternalOrganization.UriTerm();
                                term.setUri(Objects.toString(c.getString("uri"), "").trim());
                                Map<String, String> localized = new LinkedHashMap<>();
                                localized.put("ca_ES", ca != null ? ca.trim() : "");
                                localized.put("es_ES", es != null ? es.trim() : "");
                                localized.put("en_GB", en != null ? en.trim() : "");
                                term.setTerm(localized);
                                return term;
                            }
                        }
                    }
                }
            }
        }

        Query typeQuery = new Query(new Criteria().orOperator(
            Criteria.where("type.term.ca_ES").regex("^" + Pattern.quote(label.trim()) + "$", "i"),
            Criteria.where("type.term.es_ES").regex("^" + Pattern.quote(label.trim()) + "$", "i"),
            Criteria.where("type.term.en_GB").regex("^" + Pattern.quote(label.trim()) + "$", "i")
        ));
        typeQuery.limit(1);

        ExternalOrganization matched = mongoTemplate.findOne(typeQuery, ExternalOrganization.class);
        if (matched != null && matched.getType() != null) {
            return cloneUriTerm(matched.getType());
        }

        return fallbackUriTerm(label, current);
    }

    private ExternalOrganization.UriTerm resolveCountryUriTerm(String label, ExternalOrganization.UriTerm current) {
        String normalizedLabel = normalizeText(label);
        if (normalizedLabel.isBlank()) {
            return current;
        }

        Query countryQuery = new Query(new Criteria().orOperator(
            Criteria.where("address.country.term.ca_ES").regex("^" + Pattern.quote(label.trim()) + "$", "i"),
            Criteria.where("address.country.term.es_ES").regex("^" + Pattern.quote(label.trim()) + "$", "i"),
            Criteria.where("address.country.term.en_GB").regex("^" + Pattern.quote(label.trim()) + "$", "i")
        ));
        countryQuery.limit(1);

        ExternalOrganization matched = mongoTemplate.findOne(countryQuery, ExternalOrganization.class);
        if (matched != null && matched.getAddress() != null && matched.getAddress().getCountry() != null) {
            return cloneUriTerm(matched.getAddress().getCountry());
        }

        return fallbackUriTerm(label, current);
    }

    private ExternalOrganization.UriTerm fallbackUriTerm(String label, ExternalOrganization.UriTerm current) {
        ExternalOrganization.UriTerm term = new ExternalOrganization.UriTerm();
        if (current != null && current.getUri() != null && !current.getUri().isBlank()) {
            term.setUri(current.getUri());
        }
        Map<String, String> localized = new LinkedHashMap<>();
        localized.put("ca_ES", label);
        localized.put("es_ES", label);
        localized.put("en_GB", label);
        term.setTerm(localized);
        return term;
    }

    private ExternalOrganization.UriTerm cloneUriTerm(ExternalOrganization.UriTerm source) {
        if (source == null) return null;
        ExternalOrganization.UriTerm clone = new ExternalOrganization.UriTerm();
        clone.setUri(source.getUri());
        clone.setTerm(source.getTerm() == null ? null : new LinkedHashMap<>(source.getTerm()));
        return clone;
    }

    private void invalidateMetadataCatalogCaches() {
        cachedCountryCatalog.set(null);
        cachedTypeCatalog.set(null);
    }

    private double calculateNameSimilarity(String[] parts1, String[] parts2, double threshold) {
        if (parts1.length == 0 || parts2.length == 0) return 0.0;
        if (parts1.length == parts2.length) {
            boolean allEqual = true;
            for (int i = 0; i < parts1.length; i++) {
                if (!parts1[i].equals(parts2[i])) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) return 1.0;
        }

        String[] shorter = parts1.length <= parts2.length ? parts1 : parts2;
        String[] longer = parts1.length <= parts2.length ? parts2 : parts1;
        int maxLen = longer.length;

        boolean[] usedLong = new boolean[longer.length];
        boolean[] matchedShort = new boolean[shorter.length];

        int exactMatches = 0;
        for (int i = 0; i < shorter.length; i++) {
            String token = shorter[i];
            for (int j = 0; j < longer.length; j++) {
                if (!usedLong[j] && token.equals(longer[j])) {
                    usedLong[j] = true;
                    matchedShort[i] = true;
                    exactMatches++;
                    break;
                }
            }

            int remaining = shorter.length - (i + 1);
            double optimistic = (exactMatches + remaining) / (double) maxLen;
            if (optimistic < threshold) {
                return exactMatches / (double) maxLen;
            }
        }

        int fuzzyMatches = 0;
        for (int i = 0; i < shorter.length; i++) {
            if (matchedShort[i]) {
                continue;
            }

            String token = shorter[i];
            int bestIndex = -1;
            int bestDistance = Integer.MAX_VALUE;

            for (int j = 0; j < longer.length; j++) {
                if (usedLong[j]) {
                    continue;
                }

                String candidate = longer[j];
                if (Math.abs(token.length() - candidate.length()) > 2) {
                    continue;
                }

                int distance = levenshteinDistance(token, candidate);
                if (distance <= 2 && distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = j;
                    if (distance == 0) {
                        break;
                    }
                }
            }

            if (bestIndex >= 0) {
                usedLong[bestIndex] = true;
                fuzzyMatches++;
            }

            int unmatchedRemaining = shorter.length - (i + 1);
            double optimistic = (exactMatches + fuzzyMatches + unmatchedRemaining) / (double) maxLen;
            if (optimistic < threshold) {
                break;
            }
        }

        return (exactMatches + fuzzyMatches) / (double) maxLen;
    }

    private static final ThreadLocal<int[]> LEVENSHTEIN_BUFFER = ThreadLocal.withInitial(() -> new int[128]);

    private int levenshteinDistance(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        if (a.length() < b.length()) {
            String temp = a;
            a = b;
            b = temp;
        }

        int bLen = b.length();
        if (bLen >= 128) {
            int[] dp = new int[bLen + 1];
            return levenshteinDistanceWithArray(a, b, dp);
        }

        int[] dp = LEVENSHTEIN_BUFFER.get();
        return levenshteinDistanceWithArray(a, b, dp);
    }

    private int levenshteinDistanceWithArray(String a, String b, int[] dp) {
        int bLen = b.length();
        for (int i = 0; i <= bLen; i++) {
            dp[i] = i;
        }

        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            char charA = a.charAt(i - 1);
            for (int j = 1; j <= bLen; j++) {
                int temp = dp[j];
                int cost = charA == b.charAt(j - 1) ? 0 : 1;
                dp[j] = Math.min(Math.min(dp[j - 1] + 1, dp[j] + 1), prev + cost);
                prev = temp;
            }
        }
        return dp[bLen];
    }

    // =========================================================================
    // Helpers — suggest payloads
    // Mejora #5: lógica de suggest extraída a un método genérico para eliminar
    // la duplicación entre suggestCountryPayload y suggestTypePayload.
    // =========================================================================

    private Map<String, Object> suggestCountryPayload(String name) {
        String orgName = name == null ? "" : name.trim();
        if (orgName.isBlank()) {
            return Map.of("suggestedCountry", "", "suggestedCountryUri", "", "confidence", 0.0, "reason", "empty-name");
        }

        List<CountryAggregate> catalog = getCachedCountryCatalog();

        InferenceResult result = suggestWithFallback(
            orgName,
            () -> inferCountryWithWebSearch(orgName, catalog),
            r  -> r == null || r.countryLabel.isBlank(),
            () -> inferCountryFromName(orgName, catalog)
        );

        return Map.of(
            "suggestedCountry", result.countryLabel,
            "suggestedCountryUri", resolveCountryUriByLabel(result.countryLabel),
            "confidence",       result.confidence,
            "reason",           result.reason
        );
    }

    private Map<String, Object> suggestTypePayload(String name) {
        String orgName = name == null ? "" : name.trim();
        if (orgName.isBlank()) {
            return Map.of("suggestedType", "", "confidence", 0.0, "reason", "empty-name");
        }

        List<TypeAggregate> catalog = getCachedTypeCatalog();

        TypeInferenceResult result = suggestWithFallback(
            orgName,
            () -> inferTypeWithLocalAi(orgName, catalog),
            r  -> r == null || r.typeLabel.isBlank(),
            () -> inferTypeFromName(orgName, catalog)
        );

        return Map.of(
            "suggestedType", result.typeLabel,
            "confidence",    result.confidence,
            "reason",        result.reason
        );
    }

    /**
     * Mejora #5/#6: plantilla genérica AI-primero → heurística-fallback.
     * Evita duplicar la misma lógica de «si AI no devuelve nada, usa heurística».
     */
    private <R> R suggestWithFallback(
            String orgName,
            java.util.function.Supplier<R> aiInfer,
            java.util.function.Predicate<R> isEmpty,
            java.util.function.Supplier<R> heuristicInfer) {
        R aiResult = aiInfer.get();
        if (!isEmpty.test(aiResult)) return aiResult;
        return heuristicInfer.get();
    }

    // =========================================================================
    // Helpers — TTL catalog cache (mejora #4)
    // =========================================================================

    private List<CountryAggregate> getCachedCountryCatalog() {
        CachedSet<CountryAggregate> cached = cachedCountryCatalog.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp()) < REFERENCE_CACHE_TTL_MS) {
            return new ArrayList<>(cached.data());
        }
        List<CountryAggregate> fresh = buildCountryCatalog();
        cachedCountryCatalog.set(new CachedSet<>(new LinkedHashSet<>(fresh), now));
        return fresh;
    }

    private List<TypeAggregate> getCachedTypeCatalog() {
        CachedSet<TypeAggregate> cached = cachedTypeCatalog.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp()) < REFERENCE_CACHE_TTL_MS) {
            return new ArrayList<>(cached.data());
        }
        List<TypeAggregate> fresh = buildTypeCatalog();
        cachedTypeCatalog.set(new CachedSet<>(new LinkedHashSet<>(fresh), now));
        return fresh;
    }

    // =========================================================================
    // Helpers — two-phase UUID discovery
    // =========================================================================

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    // Mejora #8: Map.ofEntries para soportar más de 10 entradas sin límite
    private static final Map<String, List<String>> KNOWN_PATHS = Map.ofEntries(
        Map.entry("activities", List.of(
            "memberOf.externalOrganization.uuid",
            "hostedBy.externalOrganization.uuid",
            "associatedOrganizations.externalOrganization.uuid"
        )),
        Map.entry("events", List.of(
            "organiser.externalOrganization.uuid",
            "organizers.externalOrganization.uuid",
            "coOrganisers.externalOrganization.uuid",
            "coOrganizers.externalOrganization.uuid",
            "externalOrganizations.uuid",
            "associatedOrganizations.externalOrganization.uuid"
        )),
        Map.entry("applications", List.of(
            "participants.externalOrganization.uuid",
            "collaboratingOrganisations.externalOrganization.uuid",
            "managingOrganisation.uuid"
        )),
        Map.entry("researchoutputs", List.of(
            "contributors.affiliations.externalOrganization.uuid",
            "externalOrganizations.uuid"
        )),
        Map.entry("fundingopportunities", List.of(
            "funders.externalOrganization.uuid",
            "managingOrganisation.uuid"
        ))
    );

    private static final Map<String, List<String>> COUNTRY_ALIASES = Map.ofEntries(
        Map.entry("spain",          List.of("spain", "espana", "espanya", "s l", "sl", "s a", "sa", "barcelona", "catalunya", "catalonia", "ajuntament", "generalitat", "ministerio", "ministeri", "uab", "universitat", "universidad")),
        Map.entry("france",         List.of("france", "francia", "franca", "paris", "bordeaux", "burdeos", "montpellier", "dijon")),
        Map.entry("germany",        List.of("germany", "deutschland", "alemania", "alemanya", "berlin", "munich", "muenchen", "giessen", "muelheim")),
        Map.entry("italy",          List.of("italy", "italia", "roma", "milano", "venezia")),
        Map.entry("portugal",       List.of("portugal", "lisboa", "lisbon")),
        Map.entry("united_kingdom", List.of("united kingdom", "uk", "great britain", "britain", "england", "scotland", "wales", "london", "manchester", "oxford", "cambridge")),
        Map.entry("united_states",  List.of("united states", "usa", "u s a", "eeuu", "estados unidos", "us", "new york", "boston", "bethesda", "austin", "arizona", "california")),
        Map.entry("netherlands",    List.of("netherlands", "holland", "holanda", "paises bajos", "paisos baixos", "amsterdam")),
        Map.entry("belgium",        List.of("belgium", "belgica", "belgique", "brussels", "bruxelles")),
        Map.entry("switzerland",    List.of("switzerland", "swiss", "suiza", "suissa", "suisse", "geneva", "berne")),
        Map.entry("austria",        List.of("austria", "osterreich", "vienna")),
        Map.entry("ireland",        List.of("ireland", "irlanda", "dublin")),
        Map.entry("denmark",        List.of("denmark", "dinamarca", "copenhagen")),
        Map.entry("sweden",         List.of("sweden", "suecia", "sverige", "stockholm")),
        Map.entry("norway",         List.of("norway", "noruega", "norge", "oslo")),
        Map.entry("finland",        List.of("finland", "finlandia", "suomi", "helsinki")),
        Map.entry("poland",         List.of("poland", "polonia", "polska", "warsaw")),
        Map.entry("czech_republic", List.of("czech republic", "czechia", "republica checa", "txec", "prague")),
        Map.entry("china",          List.of("china", "beijing", "shanghai")),
        Map.entry("japan",          List.of("japan", "japon", "japo", "tokyo")),
        Map.entry("south_korea",    List.of("south korea", "korea", "corea", "seoul")),
        Map.entry("india",          List.of("india", "delhi", "mumbai")),
        Map.entry("mexico",         List.of("mexico", "mexic", "mexico city")),
        Map.entry("brazil",         List.of("brazil", "brasil", "sao paulo", "rio de janeiro")),
        Map.entry("argentina",      List.of("argentina", "buenos aires")),
        Map.entry("chile",          List.of("chile", "santiago")),
        Map.entry("colombia",       List.of("colombia", "bogota")),
        Map.entry("canada",         List.of("canada", "toronto", "montreal"))
    );

    private static final Map<String, String> KEYWORD_HINTS = Map.ofEntries(
        Map.entry("espana",    "spain"),
        Map.entry("espanya",   "spain"),
        Map.entry("spanish",   "spain"),
        Map.entry("frances",   "france"),
        Map.entry("french",    "france"),
        Map.entry("deutsch",   "germany"),
        Map.entry("german",    "germany"),
        Map.entry("italian",   "italy"),
        Map.entry("portugues", "portugal"),
        Map.entry("british",   "united_kingdom"),
        Map.entry("english",   "united_kingdom"),
        Map.entry("american",  "united_states"),
        Map.entry("eeuu",      "united_states"),
        Map.entry("dutch",     "netherlands"),
        Map.entry("swiss",     "switzerland"),
        Map.entry("japanese",  "japan"),
        Map.entry("korean",    "south_korea")
    );

    private static final Map<String, String> INSTITUTION_HINTS = Map.ofEntries(
        Map.entry("csic",       "spain"),
        Map.entry("cnrs",       "france"),
        Map.entry("infn",       "italy"),
        Map.entry("cnr",        "italy"),
        Map.entry("dfg",        "germany"),
        Map.entry("max planck", "germany"),
        Map.entry("nih",        "united_states"),
        Map.entry("nsf",        "united_states"),
        Map.entry("ukri",       "united_kingdom"),
        Map.entry("conicet",    "argentina"),
        Map.entry("cnpq",       "brazil"),
        Map.entry("conacyt",    "mexico")
    );

    private static final Map<String, List<String>> TYPE_HINTS = Map.ofEntries(
        Map.entry("university",  List.of("university", "universitat", "universidad", "universite", "universita")),
        Map.entry("hospital",    List.of("hospital", "clinic", "clinica", "sanitari", "sanidad")),
        Map.entry("company",     List.of("company", "empresa", "corporation", "corp", "ltd", "llc", "inc", "sa", "sl", "gmbh", "srl", "spa")),
        Map.entry("institute",   List.of("institute", "institut", "instituto")),
        Map.entry("foundation",  List.of("foundation", "fundacio", "fundacion", "fondation")),
        Map.entry("research",    List.of("research", "recerca", "investigacion", "investigacio", "cientific")),
        Map.entry("government",  List.of("government", "govern", "ministerio", "ministeri", "agency", "agencia", "ajuntament", "diputacion", "generalitat")),
        Map.entry("association", List.of("association", "associacio", "asociacion", "society", "societat"))
    );

    private static final List<String> FUNDING_PUBLIC_HINTS = List.of(
        "universitat", "universidad", "university",
        "ministeri", "ministerio", "ministry",
        "govern", "government", "governo",
        "ajuntament", "ayuntamiento", "city council",
        "diputacio", "diputacion",
        "conselleria", "agencia publica", "agencia estatal",
        "institut public", "instituto publico",
        "hospital public", "servicio de salud", "servei de salut",
        "csic", "cnrs"
    );

    private static final List<String> FUNDING_PRIVATE_HINTS = List.of(
        "s l", "slu", "slp", "s a", "sa",
        "ltd", "llc", "inc", "corp", "corporation",
        "company", "empresa", "startup",
        "holding", "ventures", "capital",
        "gmbh", "bv", "nv", "plc",
        "fundacio privada", "fundacion privada"
    );

    // =========================================================================
    // Records de inferencia
    // =========================================================================

    private record CountryAggregate(String label, String normalizedLabel, int count) {}
    private record TypeAggregate   (String label, String normalizedLabel, int count) {}
    private record InferenceResult    (String countryLabel, double confidence, String reason) {}
    private record TypeInferenceResult(String typeLabel,   double confidence, String reason) {}
    private record FundingInferenceResult(String fundingLabel, double confidence, String reason) {}
    private record MetadataInferenceResult(InferenceResult country, TypeInferenceResult type, FundingInferenceResult funding) {}

    // =========================================================================
    // Inferencia de país
    // =========================================================================

    private InferenceResult inferCountryFromName(String orgName, List<CountryAggregate> catalog) {
        String normalizedName = normalizeText(orgName);
        if (normalizedName.isBlank() || catalog.isEmpty()) {
            return new InferenceResult("", 0.0, "no-signal");
        }

        for (CountryAggregate c : catalog) {
            if (!c.normalizedLabel.isBlank() && normalizedName.contains(c.normalizedLabel)) {
                return new InferenceResult(c.label, confidenceFromCount(0.88, c.count), "direct-country-match");
            }
        }

        for (Map.Entry<String, String> entry : INSTITUTION_HINTS.entrySet()) {
            if (containsWord(normalizedName, entry.getKey())) {
                String resolved = resolveAliasCountry(entry.getValue(), catalog);
                if (!resolved.isBlank()) {
                    return new InferenceResult(resolved, 0.93, "institution-hint:" + entry.getKey());
                }
            }
        }

        List<Map.Entry<String, String>> keywordHints = new ArrayList<>(KEYWORD_HINTS.entrySet());
        keywordHints.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> entry : keywordHints) {
            if (containsWord(normalizedName, entry.getKey())) {
                String resolved = resolveAliasCountry(entry.getValue(), catalog);
                if (!resolved.isBlank()) {
                    int count = countryCountByLabel(resolved, catalog);
                    return new InferenceResult(resolved, confidenceFromCount(0.80, count), "keyword-hint:" + entry.getKey());
                }
            }
        }

        for (Map.Entry<String, List<String>> aliasEntry : COUNTRY_ALIASES.entrySet()) {
            boolean matched = aliasEntry.getValue().stream().anyMatch(alias -> containsWord(normalizedName, alias));
            if (!matched) continue;

            String resolved = resolveAliasCountry(aliasEntry.getKey(), catalog);
            if (!resolved.isBlank()) {
                int count = countryCountByLabel(resolved, catalog);
                return new InferenceResult(resolved, confidenceFromCount(0.75, count), "alias-match:" + aliasEntry.getKey());
            }
        }

        return new InferenceResult("", 0.0, "no-signal");
    }

    private List<CountryAggregate> buildCountryCatalog() {
        // 1. Get counts of already used countries to keep sorting by count
        Map<String, Integer> counts = new HashMap<>();
        try {
            statsByCountry(null).forEach(row -> {
                String label = Objects.toString(row.get("label"), "").trim();
                int count = ((Number) row.getOrDefault("count", 0)).intValue();
                counts.put(normalizeText(label), count);
            });
        } catch (Exception ignored) {}

        List<CountryAggregate> fullCatalog = new ArrayList<>();
        
        // 2. Load all countries from the classification scheme
        try {
            Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/core/countries"));
            Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
            if (schemeDoc != null) {
                @SuppressWarnings("unchecked")
                List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
                if (contained != null) {
                    for (Document c : contained) {
                        Document termDoc = (Document) c.get("term");
                        if (termDoc != null) {
                            // Get Catalan label as primary, fallback to Spanish, then English
                            String label = termDoc.getString("ca_ES");
                            if (label == null || label.isBlank()) label = termDoc.getString("es_ES");
                            if (label == null || label.isBlank()) label = termDoc.getString("en_GB");
                            
                            if (label != null && !label.isBlank() && !"(desconegut)".equalsIgnoreCase(label)) {
                                int count = counts.getOrDefault(normalizeText(label), 0);
                                fullCatalog.add(new CountryAggregate(label, normalizeText(label), count));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // If the classification scheme failed to load or is empty, fallback to the stats-based catalog
        if (fullCatalog.isEmpty()) {
            return statsByCountry(null).stream()
                .map(row -> {
                    String label = Objects.toString(row.get("label"), "").trim();
                    int count = ((Number) row.getOrDefault("count", 0)).intValue();
                    return new CountryAggregate(label, normalizeText(label), count);
                })
                .filter(c -> !c.label.isBlank() && !"(desconegut)".equalsIgnoreCase(c.label))
                .sorted(Comparator.comparingInt(CountryAggregate::count).reversed())
                .collect(Collectors.toList());
        }

        fullCatalog.sort(Comparator.comparingInt(CountryAggregate::count).reversed().thenComparing(CountryAggregate::label));
        return fullCatalog;
    }

    private int countryCountByLabel(String countryLabel, List<CountryAggregate> catalog) {
        String target = normalizeText(countryLabel);
        return catalog.stream()
            .filter(c -> c.normalizedLabel.equals(target))
            .map(CountryAggregate::count)
            .findFirst().orElse(0);
    }

    private String resolveAliasCountry(String aliasKey, List<CountryAggregate> catalog) {
        List<String> aliases = COUNTRY_ALIASES.getOrDefault(aliasKey, List.of());
        if (aliases.isEmpty() || catalog.isEmpty()) return "";

        String normalizedAliasKey = normalizeText(aliasKey.replace('_', ' '));

        return catalog.stream()
            .filter(c -> c.normalizedLabel.equals(normalizedAliasKey)
                || aliases.stream().anyMatch(alias -> {
                    String normalizedAlias = normalizeText(alias);
                    return c.normalizedLabel.contains(normalizedAlias)
                        || normalizedAlias.contains(c.normalizedLabel);
                }))
            .max(Comparator.comparingInt(CountryAggregate::count))
            .map(CountryAggregate::label)
            .orElse("");
    }

    // =========================================================================
    // Inferencia de tipo
    // =========================================================================

    private TypeInferenceResult inferTypeFromName(String orgName, List<TypeAggregate> catalog) {
        String normalizedName = normalizeText(orgName);
        if (normalizedName.isBlank() || catalog.isEmpty()) {
            return new TypeInferenceResult("", 0.0, "no-signal");
        }

        for (TypeAggregate t : catalog) {
            if (!t.normalizedLabel.isBlank() && normalizedName.contains(t.normalizedLabel)) {
                return new TypeInferenceResult(t.label, confidenceFromCount(0.88, t.count), "direct-type-match");
            }
        }

        for (Map.Entry<String, List<String>> hint : TYPE_HINTS.entrySet()) {
            boolean matched = hint.getValue().stream().anyMatch(k -> containsWord(normalizedName, k));
            if (!matched) continue;

            String resolved = resolveTypeByHint(hint.getValue(), catalog);
            if (!resolved.isBlank()) {
                int count = typeCountByLabel(resolved, catalog);
                return new TypeInferenceResult(resolved, confidenceFromCount(0.78, count), "keyword-hint:" + hint.getKey());
            }
        }

        return new TypeInferenceResult("", 0.0, "no-signal");
    }

    private FundingInferenceResult inferFundingFromName(String orgName) {
        String normalized = normalizeText(orgName);
        if (normalized.isBlank()) {
            return new FundingInferenceResult("", 0.0, "no-signal");
        }

        int publicHits = 0;
        int privateHits = 0;
        String publicHit = "";
        String privateHit = "";

        for (String hint : FUNDING_PUBLIC_HINTS) {
            if (containsWord(normalized, hint)) {
                publicHits++;
                if (publicHit.isBlank()) publicHit = hint;
            }
        }
        for (String hint : FUNDING_PRIVATE_HINTS) {
            if (containsWord(normalized, hint)) {
                privateHits++;
                if (privateHit.isBlank()) privateHit = hint;
            }
        }

        if (publicHits == 0 && privateHits == 0) {
            return new FundingInferenceResult("", 0.0, "no-signal");
        }

        if (publicHits == privateHits) {
            String reason = (publicHit.isBlank() ? "" : "public:" + publicHit)
                + (privateHit.isBlank() ? "" : " private:" + privateHit);
            if (reason.isBlank()) reason = "balanced-hints";
            return new FundingInferenceResult("", 0.0, reason.trim());
        }

        boolean isPublic = publicHits > privateHits;
        int major = isPublic ? publicHits : privateHits;
        int minor = isPublic ? privateHits : publicHits;
        int diff = major - minor;
        double confidence = clamp(0.58 + (diff * 0.11) + (major * 0.03));
        confidence = Math.min(0.95, confidence);

        String reason = isPublic ? publicHit : privateHit;
        if (reason == null || reason.isBlank()) reason = "keyword-hint";
        return new FundingInferenceResult(isPublic ? "Publica" : "Privada", confidence, "keyword-hint:" + reason);
    }

    private List<TypeAggregate> buildTypeCatalog() {
        List<Map<String, String>> egretaTypes = typeCatalog();
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : statsByType()) {
            String label = Objects.toString(row.get("label"), "").trim();
            int count = ((Number) row.getOrDefault("count", 0)).intValue();
            counts.put(normalizeText(label), count);
        }

        return egretaTypes.stream()
            .map(t -> {
                String label = t.get("label");
                int count = counts.getOrDefault(normalizeText(label), 0);
                return new TypeAggregate(label, normalizeText(label), count);
            })
            .sorted(Comparator.comparingInt(TypeAggregate::count).reversed())
            .collect(Collectors.toList());
    }

    private String resolveTypeByHint(List<String> hintTerms, List<TypeAggregate> catalog) {
        return catalog.stream()
            .filter(t -> hintTerms.stream().anyMatch(k -> t.normalizedLabel.contains(normalizeText(k))))
            .max(Comparator.comparingInt(TypeAggregate::count))
            .map(TypeAggregate::label)
            .orElse("");
    }

    private int typeCountByLabel(String typeLabel, List<TypeAggregate> catalog) {
        String target = normalizeText(typeLabel);
        return catalog.stream()
            .filter(t -> t.normalizedLabel.equals(target))
            .map(TypeAggregate::count)
            .findFirst().orElse(0);
    }

    // =========================================================================
    // Inferencia asistida por WebSearch para país + AI local para tipo
    // =========================================================================

    private CompletableFuture<String> callWebSearchApiAsync(String orgName) {
        if (!websearchCountrySuggestEnabled || websearchCountrySuggestUrl == null || websearchCountrySuggestUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            String query = "\"" + orgName + "\" organization country";
            String baseSearchUrl = resolveWebSearchUrl(websearchCountrySuggestUrl);
            String separator = baseSearchUrl.contains("?") ? "&" : "?";
            String requestUrl = baseSearchUrl
                + separator
                + "format=json&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofMillis(Math.max(800, websearchCountrySuggestTimeoutMs)))
                .header("Accept", "application/json")
                .GET()
                .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return null;
                    }
                    return response.body();
                })
                .orTimeout(websearchCountrySuggestTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private String resolveWebSearchUrl(String configuredUrl) {
        String safe = configuredUrl == null ? "" : configuredUrl.trim();
        if (safe.endsWith("/sse")) {
            return safe.substring(0, safe.length() - 4) + "/search";
        }
        return safe;
    }

    private InferenceResult inferCountryWithWebSearch(String orgName, List<CountryAggregate> catalog) {
        String cacheKey = "country:websearch:" + orgName;
        CachedInference cached = countryInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            String cachedBody = cached.result();
            if (cachedBody != null) {
                InferenceResult result = parseWebSearchCountryResponse(cachedBody, catalog);
                return (result == null || result.countryLabel.isBlank()) ? null : result;
            }
            return null;
        }

        String body = null;
        try {
            body = callWebSearchApiAsync(orgName)
                .get(websearchCountrySuggestTimeoutMs + 500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            body = null;
        }

        if (body != null) {
            countryInferenceCache.put(cacheKey, new CachedInference(body, System.currentTimeMillis()));
            InferenceResult result = parseWebSearchCountryResponse(body, catalog);
            return (result == null || result.countryLabel.isBlank()) ? null : result;
        }

        return null;
    }

    private InferenceResult parseWebSearchCountryResponse(String rawBody, List<CountryAggregate> catalog) {
        if (rawBody == null || rawBody.isBlank() || catalog == null || catalog.isEmpty()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode resultsNode = root.path("results");
            if (!resultsNode.isArray() || resultsNode.isEmpty()) {
                return null;
            }

            int maxResults = Math.max(1, websearchCountrySuggestMaxResults);
            Map<String, Integer> scores = new HashMap<>();
            int rank = 0;

            for (JsonNode resultNode : resultsNode) {
                if (rank >= maxResults) break;

                String title = resultNode.path("title").asText("");
                String content = resultNode.path("content").asText("");
                String url = resultNode.path("url").asText("");
                String normalizedText = normalizeText(title + " " + content + " " + url);
                if (normalizedText.isBlank()) {
                    rank++;
                    continue;
                }

                int weight = Math.max(1, maxResults - rank);

                for (CountryAggregate candidate : catalog) {
                    if (!candidate.normalizedLabel.isBlank() && containsWord(normalizedText, candidate.normalizedLabel)) {
                        scores.merge(candidate.label, 3 * weight, Integer::sum);
                    }
                }

                for (Map.Entry<String, List<String>> aliasEntry : COUNTRY_ALIASES.entrySet()) {
                    boolean aliasMatched = aliasEntry.getValue().stream().anyMatch(alias -> containsWord(normalizedText, alias));
                    if (!aliasMatched) continue;

                    String resolved = resolveAliasCountry(aliasEntry.getKey(), catalog);
                    if (!resolved.isBlank()) {
                        scores.merge(resolved, 2 * weight, Integer::sum);
                    }
                }

                rank++;
            }

            if (scores.isEmpty()) {
                return null;
            }

            String bestCountry = scores.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .thenComparingInt(entry -> countryCountByLabel(entry.getKey(), catalog)))
                .map(Map.Entry::getKey)
                .orElse("");

            if (bestCountry.isBlank()) {
                return null;
            }

            int score = scores.getOrDefault(bestCountry, 0);
            double confidence = clamp(0.55 + Math.min(0.40, score / 20.0));
            confidence = Math.round(confidence * 100.0) / 100.0;
            return new InferenceResult(bestCountry, confidence, "websearch-match");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Mejora #6/#7: método centralizado con:
     * - CompletableFuture para async (no bloquea thread)
     * - Timeout corto (2 segundos, no 8) para fallback rápido a heurística
     * - Caché por prompt para evitar llamadas repetidas
     */
    private CompletableFuture<String> callLocalAiApiAsync(String systemPrompt, String userPrompt, int maxTokens) {
        if (!aiCountrySuggestEnabled || aiCountrySuggestUrl == null || aiCountrySuggestUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", aiCountrySuggestModel == null || aiCountrySuggestModel.isBlank()
                ? "local-model" : aiCountrySuggestModel);
            payload.put("temperature", 0);
            payload.put("reasoning_effort", "low"); 
            payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userPrompt)
            ));
            payload.put("response_format", Map.of("type", "json_object"));
            payload.put("max_tokens", 800);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(aiCountrySuggestUrl))
                .timeout(Duration.ofMillis(aiCountrySuggestTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

            if (aiCountrySuggestApiKey != null && !aiCountrySuggestApiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + aiCountrySuggestApiKey.trim());
            }

            return aiHttpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return null;
                    }
                    return response.body();
                })
                .orTimeout(aiCountrySuggestTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private InferenceResult inferCountryWithLocalAi(String orgName, List<CountryAggregate> catalog) {
        // Mejora #7: Caché de inferencias por nombre (TTL 10 minutos)
        String cacheKey = "country:" + orgName;
        CachedInference cached = countryInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            String cachedBody = cached.result();
            if (cachedBody != null) {
                InferenceResult result = parseLocalAiResponse(cachedBody, catalog);
                return (result == null || result.countryLabel.isBlank()) ? null : result;
            }
            return null;
        }

       String systemPrompt =
        "You are a data quality assistant specialized in research organizations. "
        + "Given an organization name, first evaluate your internal confidence in inferring its country. "
        + "CRITICAL RULE: you MUST use the web-search tool to research the organization and improve your confidence before generating the final answer. "
         + "Return strict JSON with exactly these keys: \"suggestedCountry\", \"confidence\", \"reason\". "
        + "confidence must be a number between 0 and 1. "
        + "CRITICAL: Output ONLY the raw JSON object. Do NOT wrap the response in markdown blocks like ```json ... ```. "
        + "Your response must start with '{' and end with '}'.\n\n"
        + "Example Output:\n"
        + "{\n"
        + "  \"suggestedCountry\": \"Spain\",\n"
        + "  \"confidence\": 0.95,\n"
        + "  \"reason\": \"Web search confirms the organization is located in Barcelona or associated with UAB.\"\n"
        + "}";

        String userPrompt = "Organization: '" + orgName + "'";

        String body = null;
        try {
            body = callLocalAiApiAsync(systemPrompt, userPrompt,100)
                    .get(aiCountrySuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("Local AI timeout inferring country for org '{}'", orgName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while inferring country for org '{}'", orgName);
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Local AI error inferring country for org '{}': {}", orgName, e.getCause().getMessage());
        }

        if (body != null) {
            countryInferenceCache.put(cacheKey, new CachedInference(body, System.currentTimeMillis()));
            InferenceResult result = parseLocalAiResponse(body, catalog);
            return (result == null || result.countryLabel.isBlank()) ? null : result;
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // inferMetadataWithLocalAiAsync — versión no bloqueante para el batch
    // -------------------------------------------------------------------------
    private CompletableFuture<MetadataInferenceResult> inferMetadataWithLocalAiAsync(
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        String typeCatalogText = typeCatalog.stream()
                .limit(20)
                .map(t -> t.label)
                .collect(Collectors.joining(", "));

        String systemPrompt =
        "You are a data quality assistant for research organization metadata. "
        + "Based solely on your training knowledge, infer the country, organization type, and funding profile. "
         + "CRITICAL RULE: you MUST use the web-search tool to research the organization and improve your confidence before generating the final answer. "
        + "Return ONLY a raw JSON object with exactly these keys: "
        + "\"suggestedCountry\", \"countryConfidence\", \"countryReason\", "
        + "\"suggestedType\", \"typeConfidence\", \"typeReason\", "
        + "\"suggestedFunding\", \"fundingConfidence\", \"fundingReason\". "
        + "Confidence values must be numbers between 0 and 1. "
        + "If uncertain, return empty string and confidence 0. "
        + "No markdown. Start with '{' and end with '}'.";

        String userPrompt = "Organization: '" + orgName + "'. Candidate types: " + typeCatalogText;

        return callLocalAiApiAsync(systemPrompt, userPrompt,150)
                .orTimeout(aiCountrySuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.debug("Local AI timeout/error for org '{}': {}", orgName, e.getMessage());
                    return null;
                })
                .thenApply(body -> {
                    if (body == null) return null;
                    return parseLocalAiMetadataResponse(body, countryCatalog, typeCatalog);
                });
    }

    // -------------------------------------------------------------------------
    // inferMetadataBatch — lotes de AI_BATCH_SIZE, AI_BATCH_CONCURRENCY paralelos
    // -------------------------------------------------------------------------
    private static final int AI_BATCH_SIZE        = 20;
    private static final int AI_BATCH_CONCURRENCY = 10;

    private Map<String, MetadataInferenceResult> inferMetadataBatch(
            List<ExternalOrganization> organizations,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        Map<String, MetadataInferenceResult> results = new ConcurrentHashMap<>();
        Semaphore semaphore = new Semaphore(AI_BATCH_CONCURRENCY);

        for (int i = 0; i < organizations.size(); i += AI_BATCH_SIZE) {
            List<ExternalOrganization> batch = organizations.subList(
                    i, Math.min(i + AI_BATCH_SIZE, organizations.size()));

            List<CompletableFuture<Void>> futures = batch.stream()
                .map(org -> {
                    String orgName = extractOrgName(org);
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return inferMetadataWithLocalAiAsync(orgName, countryCatalog, typeCatalog)
                        .whenComplete((result, ex) -> {
                            semaphore.release();
                            if (result != null && org.getUuid() != null) {
                                results.put(org.getUuid(), result);
                            }
                        })
                        .thenApply(r -> (Void) null);
                })
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.debug("Lote AI procesado: {}/{} organizaciones",
                    Math.min(i + batch.size(), organizations.size()),
                    organizations.size());
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // extractOrgName — nombre preferido en en_GB, fallback es_ES, ca_ES
    // -------------------------------------------------------------------------
    private String extractOrgName(ExternalOrganization org) {
        if (org.getName() == null) return "";
        String name = org.getName().get("en_GB");
        if (name == null || name.isBlank()) name = org.getName().get("es_ES");
        if (name == null || name.isBlank()) name = org.getName().get("ca_ES");
        return name != null ? name : "";
    }

    private TypeInferenceResult inferTypeWithLocalAi(String orgName, List<TypeAggregate> catalog) {
        // Mejora #7: Caché de inferencias por nombre (TTL 10 minutos)
        String cacheKey = "type:" + orgName;
        CachedInference cached = typeInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            String cachedBody = cached.result();
            if (cachedBody != null) {
                TypeInferenceResult result = parseLocalAiTypeResponse(cachedBody, catalog);
                return (result == null || result.typeLabel.isBlank()) ? null : result;
            }
            return null;
        }

        // Mejora #8: Limitar catálogo a top-20 (no 80) para reducir tamaño del prompt
        String catalogText = catalog.stream().limit(20).map(t -> t.label).collect(Collectors.joining(", "));

        String systemPrompt =
                "You are a data quality assistant specialized in research organizations. "
                + "Given an organization name, first evaluate your internal confidence in inferring its organization type. "
               + "CRITICAL RULE: you MUST use the web-search tool to research the organization and improve your confidence before generating the final answer. "
                + "Return strict JSON with exactly these keys: \"suggestedType\", \"confidence\", \"reason\". "
                + "confidence must be a number between 0 and 1. "
                + "CRITICAL: Output ONLY the raw JSON object. Do NOT wrap the response in markdown blocks like ```json ... ```. "
                + "Your final response must start with '{' and end with '}'.";

        String userPrompt = "Organization: '" + orgName + "'. Candidate types: " + catalogText;

        String body = null;
        try {
            body = callLocalAiApiAsync(systemPrompt, userPrompt,100)
                    .get(aiCountrySuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("Local AI timeout inferring type for org '{}'", orgName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while inferring type for org '{}'", orgName);
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Local AI error inferring type for org '{}': {}", orgName, e.getCause().getMessage());
        }

        // Cachear resultado incluso si es null
        if (body != null) {
            typeInferenceCache.put(cacheKey, new CachedInference(body, System.currentTimeMillis()));
            TypeInferenceResult result = parseLocalAiTypeResponse(body, catalog);
            return (result == null || result.typeLabel.isBlank()) ? null : result;
        }

        return null;
    }

    private MetadataInferenceResult inferMetadataWithLocalAi(
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        // Mejora #8: Limitar catálogo a top-20 para reducir tamaño del prompt
        String typeCatalogText = typeCatalog.stream()
                .limit(20)
                .map(t -> t.label)
                .collect(Collectors.joining(", "));

        String systemPrompt =
                "You are a data quality assistant specialized in research organizations. "
                + "Given an organization name, first evaluate your internal confidence for its country, organization type, and funding profile (Publica or Privada). "
                
                + "Return strict JSON with exactly these keys: "
                + "\"suggestedCountry\", \"countryConfidence\", \"countryReason\", "
                + "\"suggestedType\", \"typeConfidence\", \"typeReason\", "
                + "\"suggestedFunding\", \"fundingConfidence\", \"fundingReason\". "
                + "Confidence values must be numbers between 0 and 1. "
                
                + "CRITICAL: Output ONLY the raw JSON object. Do NOT wrap the response in markdown blocks like ```json ... ```. "
                + "Your final response must start with '{' and end with '}'.";

        String userPrompt = "Organization: '" + orgName + "'. Candidate types: " + typeCatalogText;

        String body = null;
        try {
            body = callLocalAiApiAsync(systemPrompt, userPrompt,150)
                    .get(aiCountrySuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("Local AI timeout inferring metadata for org '{}'", orgName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while inferring metadata for org '{}'", orgName);
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Local AI error inferring metadata for org '{}': {}", orgName, e.getCause().getMessage());
        }

        if (body == null) return null;

        return parseLocalAiMetadataResponse(body, countryCatalog, typeCatalog);
    }

    // -------------------------------------------------------------------------
    // Parsers de respuesta AI
    // -------------------------------------------------------------------------

    private InferenceResult parseLocalAiResponse(String rawBody, List<CountryAggregate> catalog) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            InferenceResult direct = readInferenceResultFromJson(root, catalog, "local-ai");
            if (direct != null) return direct;

            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) return null;
            try {
                return readInferenceResultFromJson(objectMapper.readTree(contentNode.asText().trim()), catalog, "local-ai");
            } catch (Exception ex) { return null; }
        } catch (Exception ignored) { return null; }
    }

    private InferenceResult readInferenceResultFromJson(JsonNode node, List<CountryAggregate> catalog, String source) {
        if (node == null || node.isMissingNode()) return null;

        String rawCountry = node.path("suggestedCountry").asText("").trim();
        if (rawCountry.isBlank()) rawCountry = node.path("country").asText("").trim();
        if (rawCountry.isBlank()) return null;

        String resolved = resolveModelCountryToCatalog(rawCountry, catalog);
        if (resolved.isBlank()) return null;

        double confidence = node.path("confidence").isNumber() ? node.path("confidence").asDouble() : 0.75;
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        String reason = node.path("reason").asText("").trim();
        if (reason.isBlank()) reason = "model-inference";
        return new InferenceResult(resolved, confidence, source + ":" + reason);
    }

    private TypeInferenceResult parseLocalAiTypeResponse(String rawBody, List<TypeAggregate> catalog) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            TypeInferenceResult direct = readTypeInferenceResultFromJson(root, catalog, "local-ai");
            if (direct != null) return direct;

            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) return null;
            try {
                return readTypeInferenceResultFromJson(objectMapper.readTree(contentNode.asText().trim()), catalog, "local-ai");
            } catch (Exception ex) { return null; }
        } catch (Exception ignored) { return null; }
    }

    private TypeInferenceResult readTypeInferenceResultFromJson(JsonNode node, List<TypeAggregate> catalog, String source) {
        if (node == null || node.isMissingNode()) return null;

        String rawType = node.path("suggestedType").asText("").trim();
        if (rawType.isBlank()) rawType = node.path("type").asText("").trim();
        if (rawType.isBlank()) return null;

        String resolved = resolveModelTypeToCatalog(rawType, catalog);
        if (resolved.isBlank()) return null;

        double confidence = node.path("confidence").isNumber() ? node.path("confidence").asDouble() : 0.75;
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        String reason = node.path("reason").asText("").trim();
        if (reason.isBlank()) reason = "model-inference";
        return new TypeInferenceResult(resolved, confidence, source + ":" + reason);
    }

    private MetadataInferenceResult parseLocalAiMetadataResponse(
            String rawBody,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            MetadataInferenceResult direct = readMetadataInferenceResultFromJson(root, countryCatalog, typeCatalog, "local-ai");
            if (direct != null) return direct;

            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) return null;
            try {
                return readMetadataInferenceResultFromJson(
                    objectMapper.readTree(contentNode.asText().trim()), countryCatalog, typeCatalog, "local-ai");
            } catch (Exception ex) { return null; }
        } catch (Exception ignored) { return null; }
    }

    private MetadataInferenceResult readMetadataInferenceResultFromJson(
            JsonNode node,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog,
            String source) {
        if (node == null || node.isMissingNode()) return null;

        InferenceResult countryResult = null;
        String resolvedCountry = resolveModelCountryToCatalog(node.path("suggestedCountry").asText("").trim(), countryCatalog);
        if (!resolvedCountry.isBlank()) {
            double confidence = firstNumberNode(node, "countryConfidence", "confidence", 0.75);
            String reason = firstTextNode(node, "countryReason", "reason", "model-inference");
            countryResult = new InferenceResult(resolvedCountry, clamp(confidence), source + ":" + reason);
        }

        TypeInferenceResult typeResult = null;
        String resolvedType = resolveModelTypeToCatalog(node.path("suggestedType").asText("").trim(), typeCatalog);
        if (!resolvedType.isBlank()) {
            double confidence = firstNumberNode(node, "typeConfidence", "confidence", 0.75);
            String reason = firstTextNode(node, "typeReason", "reason", "model-inference");
            typeResult = new TypeInferenceResult(resolvedType, clamp(confidence), source + ":" + reason);
        }

        FundingInferenceResult fundingResult = null;
        String resolvedFunding = resolveModelFundingLabel(node.path("suggestedFunding").asText("").trim());
        if (resolvedFunding.isBlank()) {
            resolvedFunding = resolveModelFundingLabel(node.path("funding").asText("").trim());
        }
        if (!resolvedFunding.isBlank()) {
            double confidence = firstNumberNode(node, "fundingConfidence", "confidence", 0.75);
            String reason = firstTextNode(node, "fundingReason", "reason", "model-inference");
            fundingResult = new FundingInferenceResult(resolvedFunding, clamp(confidence), source + ":" + reason);
        }

        return (countryResult == null && typeResult == null && fundingResult == null) ? null
            : new MetadataInferenceResult(countryResult, typeResult, fundingResult);
    }

    // =========================================================================
    // Helpers — resolución de catálogo
    // =========================================================================

    private String resolveModelCountryToCatalog(String modelCountry, List<CountryAggregate> catalog) {
        String target = normalizeText(modelCountry);
        if (target.isBlank()) return "";

        String direct = catalog.stream()
            .filter(c -> c.normalizedLabel.equals(target))
            .map(CountryAggregate::label)
            .findFirst()
            .orElse("");
        if (!direct.isBlank()) return direct;

        for (Map.Entry<String, List<String>> aliasEntry : COUNTRY_ALIASES.entrySet()) {
            boolean aliasMatch = aliasEntry.getValue().stream()
                .anyMatch(alias -> normalizeText(alias).equals(target));
            if (aliasMatch) return resolveAliasCountry(aliasEntry.getKey(), catalog);
        }
        return "";
    }

    private String resolveModelTypeToCatalog(String modelType, List<TypeAggregate> catalog) {
        String target = normalizeText(modelType);
        if (target.isBlank()) return "";

        // 1. Exact match
        String direct = catalog.stream()
            .filter(t -> t.normalizedLabel.equals(target))
            .map(TypeAggregate::label)
            .findFirst()
            .orElse("");
        if (!direct.isBlank()) return direct;

        // 2. Partial match (if a catalog label is contained in the target, or target in catalog label)
        String partial = catalog.stream()
            .filter(t -> target.contains(t.normalizedLabel) || t.normalizedLabel.contains(target))
            .max(Comparator.comparingInt(TypeAggregate::count))
            .map(TypeAggregate::label)
            .orElse("");
        if (!partial.isBlank()) return partial;

        // 3. Match via TYPE_HINTS
        for (Map.Entry<String, List<String>> hint : TYPE_HINTS.entrySet()) {
            boolean matched = hint.getValue().stream().anyMatch(k -> target.contains(normalizeText(k)));
            if (matched) {
                String resolved = resolveTypeByHint(hint.getValue(), catalog);
                if (!resolved.isBlank()) return resolved;
            }
        }

        return "";
    }

    private String resolveModelFundingLabel(String modelFunding) {
        String target = normalizeText(modelFunding);
        if (target.isBlank()) return "";

        if (target.equals("publica") || target.equals("public") || target.equals("publico") || target.equals("publica publica")) {
            return "Publica";
        }
        if (target.equals("privada") || target.equals("private") || target.equals("privado")) {
            return "Privada";
        }
        return "";
    }

    // =========================================================================
    // Helpers — collectReferencedUuids (mejora #1: thread-safe con AtomicReference)
    // =========================================================================

    private Set<String> collectReferencedUuids() {
        // Mejora #1: doble-check con AtomicReference elimina el race condition
        long now = System.currentTimeMillis();
        CachedSet<String> cached = cachedReferencedExternalOrgUuids.get();
        if (cached != null && (now - cached.timestamp()) < REFERENCE_CACHE_TTL_MS) {
            return cached.data();
        }

        String extOrgCollection = externalOrganizationCollection;
        Set<String> extOrgUuids = loadExternalOrgUuids(extOrgCollection);
        if (extOrgUuids.isEmpty()) return Set.of();

        Set<String> referenced = new HashSet<>();
        List<String> extOrgUuidsList = new ArrayList<>(extOrgUuids);
        Document inFilter = new Document("$in", extOrgUuidsList);

        for (String collection : resolveAllCollections()) {
            if (isExtOrgCollection(collection)) continue;

            String collectionLower = collection.toLowerCase();

            List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
                .filter(e -> collectionLower.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .distinct()
                .collect(Collectors.toList());

            for (String path : knownPaths) {
                try {
                    mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, String.class)
                        .into(new ArrayList<>())
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(v -> !v.isBlank())
                        .filter(extOrgUuids::contains)
                        .forEach(referenced::add);
                } catch (Exception ignored) {}
            }

            Set<String> knownPathSet = new HashSet<>(knownPaths);
            List<String> sampledPaths = discoverUuidPaths(collection, 100)
                .stream()
                .filter(p -> !knownPathSet.contains(p))
                .collect(Collectors.toList());

            for (String path : sampledPaths) {
                Document filter = new Document(path, inFilter);
                try {
                    mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, filter, String.class)
                        .into(new ArrayList<>())
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(v -> !v.isBlank())
                        .forEach(referenced::add);
                } catch (Exception ignored) {}
            }

            if (referenced.size() >= extOrgUuids.size()) break;
        }

        List<String> extOrgPaths = discoverUuidPaths(extOrgCollection, 200)
            .stream()
            .filter(p -> !isNonReferenceUuidPath(p))
            .collect(Collectors.toList());

        for (String path : extOrgPaths) {
            Document filter = new Document(path, inFilter);
            try {
                mongoTemplate.getDb()
                    .getCollection(extOrgCollection)
                    .distinct(path, filter, String.class)
                    .into(new ArrayList<>())
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(v -> !v.isBlank())
                    .forEach(referenced::add);
            } catch (Exception ignored) {}
        }

        CachedSet<String> fresh = new CachedSet<>(Set.copyOf(referenced), System.currentTimeMillis());
        cachedReferencedExternalOrgUuids.compareAndSet(cached, fresh); // atomic, el perdedor descarta su cálculo
        return fresh.data();
    }

    private Set<String> loadExternalOrgUuids(String extOrgCollection) {
        long now = System.currentTimeMillis();
        CachedSet<String> cached = cachedExternalOrgUuids.get();
        if (cached != null && (now - cached.timestamp()) < REFERENCE_CACHE_TTL_MS) {
            return cached.data();
        }

        Set<String> fresh = mongoTemplate.getDb()
            .getCollection(extOrgCollection)
            .distinct("uuid", String.class)
            .into(new ArrayList<>())
            .stream()
            .filter(Objects::nonNull)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());

        CachedSet<String> newCache = new CachedSet<>(Set.copyOf(fresh), System.currentTimeMillis());
        cachedExternalOrgUuids.compareAndSet(cached, newCache);
        return fresh;
    }

    // =========================================================================
    // discoverUuidPaths (mejora #7: itera hasta 3 elementos del array)
    // =========================================================================

    private List<String> discoverUuidPaths(String collection, int sampleSize) {
        Set<String> paths = new LinkedHashSet<>();
        mongoTemplate.getDb()
            .getCollection(collection)
            .aggregate(List.of(new Document("$sample", new Document("size", sampleSize))))
            .forEach(doc -> collectUuidPaths(doc, "", paths));
        return new ArrayList<>(paths);
    }

    /**
     * Mejora #7: en arrays, intenta los primeros MAX_ARRAY_PROBE elementos
     * (no sólo el primero) para evitar perderse paths cuando el elemento 0
     * es null, primitivo o tiene una estructura diferente.
     */
    private static final int MAX_ARRAY_PROBE = 3;

    private void collectUuidPaths(Object node, String prefix, Set<String> paths) {
        if (node instanceof Document doc) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                Object val = entry.getValue();
                if (val instanceof String str && UUID_PATTERN.matcher(str).matches()) {
                    paths.add(path);
                } else if (val instanceof Document || val instanceof List) {
                    collectUuidPaths(val, path, paths);
                }
            }
        } else if (node instanceof List<?> list) {
            int probed = 0;
            for (Object item : list) {
                if (item instanceof Document || item instanceof List) {
                    collectUuidPaths(item, prefix, paths);
                    if (++probed >= MAX_ARRAY_PROBE) break;
                }
            }
        }
    }

    // =========================================================================
    // Helpers — criteria, sort, normalización
    // =========================================================================

    private Sort buildNameSort() {
        return Sort.by(
            Sort.Order.asc("name.ca_ES"),
            Sort.Order.asc("name.es_ES"),
            Sort.Order.asc("name.en_GB")
        );
    }

    private Sort buildSort(String sortBy, String sortDirection) {
        Sort.Direction dir = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
        if (sortBy == null || sortBy.isBlank()) {
            return buildNameSort();
        }
        switch (sortBy.toLowerCase()) {
            case "name":
            case "nom":
                return Sort.by(dir, "name.ca_ES", "name.es_ES", "name.en_GB");
            case "type":
            case "tipus":
                return Sort.by(dir, "type.term.ca_ES", "type.term.es_ES", "type.term.en_GB");
            case "funding":
            case "financiacio":
            case "financiacion":
                return Sort.by(dir, "keywordGroups.classifications.term.ca_ES", "keywordGroups.classifications.term.es_ES", "keywordGroups.classifications.term.en_GB");
            case "country":
            case "pais":
                return Sort.by(dir, "address.country.term.ca_ES", "address.country.term.es_ES", "address.country.term.en_GB");
            case "workflow":
                return Sort.by(dir, "workflow.step");
            case "uuid":
                return Sort.by(dir, "uuid");
            default:
                return buildNameSort();
        }
    }

    /**
     * Mejora #3: un orOperator entre los tres idiomas — si falta el país en
     * CUALQUIERA de ellos, la organización aparece en el listado "sin país".
     */
    private Criteria missingCountryCriteria() {
        Criteria noCa = new Criteria().orOperator(
            Criteria.where("address.country.term.ca_ES").exists(false),
            Criteria.where("address.country.term.ca_ES").is(null),
            Criteria.where("address.country.term.ca_ES").is("")
        );
        Criteria noEs = new Criteria().orOperator(
            Criteria.where("address.country.term.es_ES").exists(false),
            Criteria.where("address.country.term.es_ES").is(null),
            Criteria.where("address.country.term.es_ES").is("")
        );
        Criteria noEn = new Criteria().orOperator(
            Criteria.where("address.country.term.en_GB").exists(false),
            Criteria.where("address.country.term.en_GB").is(null),
            Criteria.where("address.country.term.en_GB").is("")
        );
        // OR: basta con que falte en un idioma para considerarla incompleta
        return new Criteria().orOperator(noCa, noEs, noEn);
    }

    private Criteria missingTypeCriteria() {
        Criteria noCa = new Criteria().orOperator(
            Criteria.where("type.term.ca_ES").exists(false),
            Criteria.where("type.term.ca_ES").is(null),
            Criteria.where("type.term.ca_ES").is(""),
            Criteria.where("type.term.ca_ES").regex("^(unknown|desconegut)$", "i")
        );
        Criteria noEs = new Criteria().orOperator(
            Criteria.where("type.term.es_ES").exists(false),
            Criteria.where("type.term.es_ES").is(null),
            Criteria.where("type.term.es_ES").is(""),
            Criteria.where("type.term.es_ES").regex("^(unknown|desconegut)$", "i")
        );
        Criteria noEn = new Criteria().orOperator(
            Criteria.where("type.term.en_GB").exists(false),
            Criteria.where("type.term.en_GB").is(null),
            Criteria.where("type.term.en_GB").is(""),
            Criteria.where("type.term.en_GB").regex("^(unknown|desconegut)$", "i")
        );
        return new Criteria().orOperator(noCa, noEs, noEn);
    }

    private Criteria missingFundingCriteria() {
        return new Criteria().orOperator(
            Criteria.where("keywordGroups").exists(false),
            Criteria.where("keywordGroups").is(null),
            Criteria.where("keywordGroups").size(0),
            Criteria.where("keywordGroups").not().elemMatch(
                Criteria.where("logicalName").is("/uab/externalorganisations/caracter")
                    .and("classifications").exists(true).not().size(0)
                    .and("classifications.uri").exists(true).ne("").ne("/uab/externalorganisations/caracter/unknown")
            )
        );
    }

    /**
     * Mejora #10: containsWord precompila el Pattern sólo si el término
     * normalizado cambia. En la práctica los términos son constantes de
     * catálogo, así que el cache inline evita recompilar en cada llamada
     * dentro de los bucles de inferencia.
     */
    private final Map<String, Pattern> containsWordCache = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean containsWord(String text, String term) {
        if (text == null || term == null) return false;
        String normalizedTerm = normalizeText(term);
        if (normalizedTerm.isBlank()) return false;
        Pattern p = containsWordCache.computeIfAbsent(
            normalizedTerm,
            k -> Pattern.compile("(^|\\s)" + Pattern.quote(k) + "($|\\s)", Pattern.CASE_INSENSITIVE)
        );
        return p.matcher(text).find();
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private double confidenceFromCount(double base, int count) {
        double bonus = Math.min(0.10, Math.log10(Math.max(1, count) + 1) / 20.0);
        return Math.round(Math.min(0.98, base + bonus) * 100.0) / 100.0;
    }

    private double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private double firstNumberNode(JsonNode node, String primary, String fallback, double def) {
        JsonNode n = node.path(primary);
        if (n.isNumber()) return n.asDouble();
        n = node.path(fallback);
        return n.isNumber() ? n.asDouble() : def;
    }

    private String firstTextNode(JsonNode node, String primary, String fallback, String def) {
        String v = node.path(primary).asText("").trim();
        if (!v.isBlank()) return v;
        v = node.path(fallback).asText("").trim();
        return v.isBlank() ? def : v;
    }

    private boolean isNonReferenceUuidPath(String path) {
        if (path == null) return true;
        return "uuid".equals(path) || "_id".equals(path) || path.startsWith("_id.");
    }

    private boolean isExtOrgCollection(String name) {
        return name.toLowerCase().contains("externalorg");
    }

    private List<String> resolveAllCollections() {
        return mongoTemplate.getDb()
            .listCollectionNames()
            .into(new ArrayList<>())
            .stream()
            .filter(n -> !n.startsWith("system."))
            .collect(Collectors.toList());
    }

    // =========================================================================
    // Auto-apply Country suggest & apply feature
    // =========================================================================

    private void startAutoApplyRun(String env, double confidence) {
        synchronized(autoApplyLock) {
            autoApplyRunning = true;
            autoApplyTotal = 0;
            autoApplyProcessed = 0;
            autoApplyApplied = 0;
            autoApplyConfidenceThreshold = confidence;
            autoApplyLogs.clear();
        }
        
        try {
            java.io.File file = new java.io.File(autoApplyLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                "=== INICI DE PROCES AUTO-APLICAR PAIS (" + (env == null ? "DEFAULT" : env.toUpperCase()) + ", Confiança >= " + Math.round(confidence * 100) + "%) === " + java.time.LocalDateTime.now() + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            System.err.println("Error initializing auto-apply log file: " + e.getMessage());
        }
    }

    private synchronized void writeLogToFile(String line) {
        try {
            java.io.File file = new java.io.File(autoApplyLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                line + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.err.println("Error writing to auto-apply log file: " + e.getMessage());
        }
    }

    private void logMessage(String message) {
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyLogs.add(formatted);
        writeLogToFile(formatted);
    }

    private void executeAutoApplyCountry(String env, double confidenceThreshold) {
        asyncExecutor.submit(() -> {
            try {
                Query query = new Query(missingCountryCriteria());
                query.fields().include("uuid").include("name");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                
                synchronized(autoApplyLock) {
                    autoApplyTotal = orgs.size();
                }
                
                logMessage("S'han trobat " + autoApplyTotal + " organitzacions sense pais. Processant en paral·lel (Confiança >= " + Math.round(confidenceThreshold * 100) + "%)...");
                
                List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
                
                // Concurrencia de 16 peticiones paralelas (óptimo para la LS40)
                int concurrencyLimit = 16;
                java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrencyLimit);
                
                List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
                
                for (ExternalOrganization shortOrg : orgs) {
                    String uuid = shortOrg.getUuid();
                    String name = extractOrgName(shortOrg);
                    
                    synchronized(autoApplyLock) {
                        if (!autoApplyRunning) {
                            break;
                        }
                    }
                    
                    sem.acquire(); // Adquirir antes de enviar la tarea al pool, bloqueando el bucle de envío si no hay hueco
                    
                    synchronized(autoApplyLock) {
                        if (!autoApplyRunning) {
                            sem.release();
                            break;
                        }
                    }
                    
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            synchronized(autoApplyLock) {
                                if (!autoApplyRunning) {
                                    return;
                                }
                            }
                            
                            ExternalOrganization org = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(uuid)), ExternalOrganization.class);
                            if (org == null) {
                                logMessage("[ERROR] Organitzacio no trobada: " + name + " (UUID: " + uuid + ")");
                                return;
                            }
                            
                            // 1. Coincidencia IA (solo prompt de país)
                            InferenceResult countryResult = inferCountryWithLocalAi(name, countryCatalog);

                            // 2. WebSearch si la IA tampoco encuentra nada
                            if (countryResult == null || countryResult.countryLabel.isBlank()) {
                                countryResult = inferCountryWithWebSearch(name, countryCatalog);
                            }
                            
                            if (countryResult == null || countryResult.countryLabel.isBlank()) {
                                logMessage("[OMES] " + name + " (UUID: " + uuid + ") - Sense suggeriment de pais.");
                                return;
                            }
                            
                            String suggestedCountry = countryResult.countryLabel;
                            double confidence = countryResult.confidence;
                            
                            if (confidence >= confidenceThreshold) {
                                String suggestedCountryUri = resolveCountryUriByLabel(suggestedCountry);
                                ExternalOrganization.UriTerm updatedCountry = resolveCountryUriTermFromPayload(
                                    suggestedCountryUri,
                                    suggestedCountry,
                                    org.getAddress() == null ? null : org.getAddress().getCountry()
                                );
                                if (updatedCountry == null) {
                                    logMessage("[ERROR] No s'ha pogut resoldre el pais per a: " + name + " (Pais suggerit: " + suggestedCountry + ")");
                                } else {
                                    Map<String, Object> egretaSync = syncExternalOrganizationCountryToEgreta(org, updatedCountry, env);
                                    boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
                                    if (!egretaUpdated) {
                                        logMessage("[ERROR] " + name + " - Error de sincronitzacio amb Egreta: " + egretaSync.get("reason"));
                                    } else {
                                        if (org.getAddress() == null) {
                                            org.setAddress(new ExternalOrganization.Address());
                                        }
                                        org.getAddress().setCountry(updatedCountry);
                                        repository.save(org);
                                        
                                        synchronized(autoApplyLock) {
                                            autoApplyApplied++;
                                        }
                                        logMessage("[APLICAT] " + name + " -> " + suggestedCountry + " (Confiança: " + Math.round(confidence * 100) + "%)");
                                    }
                                }
                            } else {
                                logMessage("[OMES] " + name + " - Suggeriment: " + suggestedCountry + " (Confiança: " + Math.round(confidence * 100) + "% - baixa).");
                            }
                        } catch (Exception e) {
                            logMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyLock) {
                                autoApplyProcessed++;
                            }
                        }
                    }, aiExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                logMessage("Proces finalitzat. Total processades: " + autoApplyProcessed + ", Aplicades: " + autoApplyApplied);
                invalidateMetadataCatalogCaches();
            } catch (Exception e) {
                logMessage("[FATAL ERROR] " + e.getMessage());
            } finally {
                synchronized(autoApplyLock) {
                    autoApplyRunning = false;
                }
            }
        });
    }

    @PostMapping("/stats/auto-apply-country/start")
    public Map<String, Object> startAutoApplyCountry(
            @RequestParam(defaultValue = "test") String env,
            @RequestParam(defaultValue = "0.90") double confidence) {
        synchronized(autoApplyLock) {
            if (autoApplyRunning) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyRun(env, confidence);
            executeAutoApplyCountry(env, confidence);
            return Map.of("started", true);
        }
    }

    @PostMapping("/stats/auto-apply-country/stop")
    public Map<String, Object> stopAutoApplyCountry() {
        synchronized(autoApplyLock) {
            if (!autoApplyRunning) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyRunning = false;
            logMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
    }

    @GetMapping("/stats/auto-apply-country/status")
    public Map<String, Object> getAutoApplyCountryStatus() {
        synchronized(autoApplyLock) {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("running", autoApplyRunning);
            status.put("total", autoApplyTotal);
            status.put("processed", autoApplyProcessed);
            status.put("applied", autoApplyApplied);
            status.put("confidenceThreshold", autoApplyConfidenceThreshold);
            status.put("logs", String.join("\n", autoApplyLogs));
            return status;
        }
    }

    @GetMapping("/stats/auto-apply-country/download-log")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadAutoApplyLog() {
        try {
            java.io.File file = new java.io.File(autoApplyLogFilePath);
            if (!file.exists()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toURI());
            return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auto-apply-country.log\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(resource);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    private void startAutoApplyTypeRun(String env, double confidence) {
        synchronized(autoApplyTypeLock) {
            autoApplyTypeRunning = true;
            autoApplyTypeTotal = 0;
            autoApplyTypeProcessed = 0;
            autoApplyTypeApplied = 0;
            autoApplyTypeConfidenceThreshold = confidence;
            autoApplyTypeLogs.clear();
        }
        
        try {
            java.io.File file = new java.io.File(autoApplyTypeLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                "=== INICI DE PROCES AUTO-APLICAR TIPUS (" + (env == null ? "DEFAULT" : env.toUpperCase()) + ", Confiança >= " + Math.round(confidence * 100) + "%) === " + java.time.LocalDateTime.now() + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            System.err.println("Error initializing auto-apply type log file: " + e.getMessage());
        }
    }

    private synchronized void writeTypeLogToFile(String line) {
        try {
            java.io.File file = new java.io.File(autoApplyTypeLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                line + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.err.println("Error writing to auto-apply type log file: " + e.getMessage());
        }
    }

    private void logTypeMessage(String message) {
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyTypeLogs.add(formatted);
        writeTypeLogToFile(formatted);
    }

    private void executeAutoApplyType(String env, double confidenceThreshold) {
        asyncExecutor.submit(() -> {
            try {
                Query query = new Query(missingTypeCriteria());
                query.fields().include("uuid").include("name");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                
                synchronized(autoApplyTypeLock) {
                    autoApplyTypeTotal = orgs.size();
                }
                
                logTypeMessage("S'han trobat " + autoApplyTypeTotal + " organitzacions sense tipus. Processant en paral·lel (Confiança >= " + Math.round(confidenceThreshold * 100) + "%)...");
                
                List<TypeAggregate> typeCatalog = getCachedTypeCatalog();
                
                int concurrencyLimit = 16;
                java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrencyLimit);
                
                List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
                
                for (ExternalOrganization shortOrg : orgs) {
                    String uuid = shortOrg.getUuid();
                    String name = extractOrgName(shortOrg);
                    
                    synchronized(autoApplyTypeLock) {
                        if (!autoApplyTypeRunning) {
                            break;
                        }
                    }
                    
                    sem.acquire();
                    
                    synchronized(autoApplyTypeLock) {
                        if (!autoApplyTypeRunning) {
                            sem.release();
                            break;
                        }
                    }
                    
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            synchronized(autoApplyTypeLock) {
                                if (!autoApplyTypeRunning) {
                                    return;
                                }
                            }
                            
                            ExternalOrganization org = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(uuid)), ExternalOrganization.class);
                            if (org == null) {
                                logTypeMessage("[ERROR] Organitzacio no trobada: " + name + " (UUID: " + uuid + ")");
                                return;
                            }
                            
                            TypeInferenceResult typeResult = inferTypeWithLocalAi(name, typeCatalog);
                            if (typeResult == null || typeResult.typeLabel.isBlank()) {
                                typeResult = inferTypeFromName(name, typeCatalog);
                            }
                            
                            if (typeResult == null || typeResult.typeLabel.isBlank()) {
                                logTypeMessage("[OMES] " + name + " (UUID: " + uuid + ") - Sense suggeriment de tipus.");
                                return;
                            }
                            
                            String suggestedType = typeResult.typeLabel;
                            double confidence = typeResult.confidence;
                            
                            if (confidence >= confidenceThreshold) {
                                ExternalOrganization.UriTerm updatedType = resolveTypeUriTermFromPayload(null, suggestedType, org.getType());
                                if (updatedType == null) {
                                    logTypeMessage("[ERROR] No s'ha pogut resoldre el tipus per a: " + name + " (Tipus suggerit: " + suggestedType + ")");
                                } else {
                                    Map<String, Object> egretaSync = syncExternalOrganizationTypeToEgreta(org, updatedType, env);
                                    boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
                                    if (!egretaUpdated) {
                                        logTypeMessage("[ERROR] " + name + " - Error de sincronitzacio amb Egreta: " + egretaSync.get("reason"));
                                    } else {
                                        org.setType(updatedType);
                                        repository.save(org);
                                        
                                        synchronized(autoApplyTypeLock) {
                                            autoApplyTypeApplied++;
                                        }
                                        logTypeMessage("[APLICAT] " + name + " -> " + suggestedType + " (Confiança: " + Math.round(confidence * 100) + "%)");
                                    }
                                }
                            } else {
                                logTypeMessage("[OMES] " + name + " - Suggeriment: " + suggestedType + " (Confiança: " + Math.round(confidence * 100) + "% - baixa).");
                            }
                        } catch (Exception e) {
                            logTypeMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyTypeLock) {
                                autoApplyTypeProcessed++;
                            }
                        }
                    }, aiExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                logTypeMessage("Proces finalitzat. Total processades: " + autoApplyTypeProcessed + ", Aplicades: " + autoApplyTypeApplied);
                invalidateMetadataCatalogCaches();
            } catch (Exception e) {
                logTypeMessage("[FATAL ERROR] " + e.getMessage());
            } finally {
                synchronized(autoApplyTypeLock) {
                    autoApplyTypeRunning = false;
                }
            }
        });
    }

    @PostMapping("/stats/auto-apply-type/start")
    public Map<String, Object> startAutoApplyType(
            @RequestParam(defaultValue = "test") String env,
            @RequestParam(defaultValue = "0.90") double confidence) {
        synchronized(autoApplyTypeLock) {
            if (autoApplyTypeRunning) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyTypeRun(env, confidence);
            executeAutoApplyType(env, confidence);
            return Map.of("started", true);
        }
    }

    @PostMapping("/stats/auto-apply-type/stop")
    public Map<String, Object> stopAutoApplyType() {
        synchronized(autoApplyTypeLock) {
            if (!autoApplyTypeRunning) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyTypeRunning = false;
            logTypeMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
    }

    @GetMapping("/stats/auto-apply-type/status")
    public Map<String, Object> getAutoApplyTypeStatus() {
        synchronized(autoApplyTypeLock) {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("running", autoApplyTypeRunning);
            status.put("total", autoApplyTypeTotal);
            status.put("processed", autoApplyTypeProcessed);
            status.put("applied", autoApplyTypeApplied);
            status.put("confidenceThreshold", autoApplyTypeConfidenceThreshold);
            status.put("logs", String.join("\n", autoApplyTypeLogs));
            return status;
        }
    }

    @GetMapping("/stats/auto-apply-type/download-log")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadAutoApplyTypeLog() {
        try {
            java.io.File file = new java.io.File(autoApplyTypeLogFilePath);
            if (!file.exists()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toURI());
            return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auto-apply-type.log\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(resource);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    private void startAutoApplyFundingRun(String env, double confidence) {
        synchronized(autoApplyFundingLock) {
            autoApplyFundingRunning = true;
            autoApplyFundingTotal = 0;
            autoApplyFundingProcessed = 0;
            autoApplyFundingApplied = 0;
            autoApplyFundingConfidenceThreshold = confidence;
            autoApplyFundingLogs.clear();
        }
        
        try {
            java.io.File file = new java.io.File(autoApplyFundingLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                "=== INICI DE PROCES AUTO-APLICAR FINANÇAMENT (" + (env == null ? "DEFAULT" : env.toUpperCase()) + ", Confiança >= " + Math.round(confidence * 100) + "%) === " + java.time.LocalDateTime.now() + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            System.err.println("Error initializing auto-apply funding log file: " + e.getMessage());
        }
    }

    private synchronized void writeFundingLogToFile(String line) {
        try {
            java.io.File file = new java.io.File(autoApplyFundingLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                line + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.err.println("Error writing to auto-apply funding log file: " + e.getMessage());
        }
    }

    private void logFundingMessage(String message) {
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyFundingLogs.add(formatted);
        writeFundingLogToFile(formatted);
    }

    private void executeAutoApplyFunding(String env, double confidenceThreshold) {
        asyncExecutor.submit(() -> {
            try {
                Query query = new Query(missingFundingCriteria());
                query.fields().include("uuid").include("name");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                
                synchronized(autoApplyFundingLock) {
                    autoApplyFundingTotal = orgs.size();
                }
                
                logFundingMessage("S'han trobat " + autoApplyFundingTotal + " organitzacions sense finançament. Processant en paral·lel (Confiança >= " + Math.round(confidenceThreshold * 100) + "%)...");
                
                List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
                List<TypeAggregate> typeCatalog = getCachedTypeCatalog();
                
                int concurrencyLimit = 16;
                java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrencyLimit);
                
                List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
                
                for (ExternalOrganization shortOrg : orgs) {
                    String uuid = shortOrg.getUuid();
                    String name = extractOrgName(shortOrg);
                    
                    synchronized(autoApplyFundingLock) {
                        if (!autoApplyFundingRunning) {
                            break;
                        }
                    }
                    
                    sem.acquire();
                    
                    synchronized(autoApplyFundingLock) {
                        if (!autoApplyFundingRunning) {
                            sem.release();
                            break;
                        }
                    }
                    
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            synchronized(autoApplyFundingLock) {
                                if (!autoApplyFundingRunning) {
                                    return;
                                }
                            }
                            
                            ExternalOrganization org = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(uuid)), ExternalOrganization.class);
                            if (org == null) {
                                logFundingMessage("[ERROR] Organitzacio no trobada: " + name + " (UUID: " + uuid + ")");
                                return;
                            }
                            
                            FundingInferenceResult fundingResult = inferFundingFromName(name);
                            if (fundingResult == null || fundingResult.fundingLabel.isBlank()) {
                                MetadataInferenceResult aiResult = inferMetadataWithLocalAi(name, countryCatalog, typeCatalog);
                                if (aiResult != null && aiResult.funding() != null) {
                                    fundingResult = aiResult.funding();
                                }
                            }
                            
                            if (fundingResult == null || fundingResult.fundingLabel.isBlank()) {
                                logFundingMessage("[OMES] " + name + " (UUID: " + uuid + ") - Sense suggeriment de finançament.");
                                return;
                            }
                            
                            String suggestedFunding = fundingResult.fundingLabel;
                            double confidence = fundingResult.confidence;
                            
                            if (confidence >= confidenceThreshold) {
                                String normalizedFunding = resolveModelFundingLabel(suggestedFunding);
                                if (normalizedFunding.isBlank()) {
                                    logFundingMessage("[ERROR] No s'ha pogut resoldre el finançament per a: " + name + " (Finançament suggerit: " + suggestedFunding + ")");
                                } else {
                                    Map<String, Object> egretaSync = syncExternalOrganizationFundingToEgreta(org, normalizedFunding, env);
                                    boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
                                    if (!egretaUpdated) {
                                        logFundingMessage("[ERROR] " + name + " - Error de sincronitzacio amb Egreta: " + egretaSync.get("reason"));
                                    } else {
                                        applyFundingToKeywordGroups(org, normalizedFunding);
                                        repository.save(org);
                                        
                                        synchronized(autoApplyFundingLock) {
                                            autoApplyFundingApplied++;
                                        }
                                        logFundingMessage("[APLICAT] " + name + " -> " + suggestedFunding + " (Confiança: " + Math.round(confidence * 100) + "%)");
                                    }
                                }
                            } else {
                                logFundingMessage("[OMES] " + name + " - Suggeriment: " + suggestedFunding + " (Confiança: " + Math.round(confidence * 100) + "% - baixa).");
                            }
                        } catch (Exception e) {
                            logFundingMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyFundingLock) {
                                autoApplyFundingProcessed++;
                            }
                        }
                    }, aiExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                logFundingMessage("Proces finalitzat. Total processades: " + autoApplyFundingProcessed + ", Aplicades: " + autoApplyFundingApplied);
            } catch (Exception e) {
                logFundingMessage("[FATAL ERROR] " + e.getMessage());
            } finally {
                synchronized(autoApplyFundingLock) {
                    autoApplyFundingRunning = false;
                }
            }
        });
    }

    @PostMapping("/stats/auto-apply-funding/start")
    public Map<String, Object> startAutoApplyFunding(
            @RequestParam(defaultValue = "test") String env,
            @RequestParam(defaultValue = "0.90") double confidence) {
        synchronized(autoApplyFundingLock) {
            if (autoApplyFundingRunning) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyFundingRun(env, confidence);
            executeAutoApplyFunding(env, confidence);
            return Map.of("started", true);
        }
    }

    @PostMapping("/stats/auto-apply-funding/stop")
    public Map<String, Object> stopAutoApplyFunding() {
        synchronized(autoApplyFundingLock) {
            if (!autoApplyFundingRunning) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyFundingRunning = false;
            logFundingMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
    }

    @GetMapping("/stats/auto-apply-funding/status")
    public Map<String, Object> getAutoApplyFundingStatus() {
        synchronized(autoApplyFundingLock) {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("running", autoApplyFundingRunning);
            status.put("total", autoApplyFundingTotal);
            status.put("processed", autoApplyFundingProcessed);
            status.put("applied", autoApplyFundingApplied);
            status.put("confidenceThreshold", autoApplyFundingConfidenceThreshold);
            status.put("logs", String.join("\n", autoApplyFundingLogs));
            return status;
        }
    }

    @GetMapping("/stats/auto-apply-funding/download-log")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadAutoApplyFundingLog() {
        try {
            java.io.File file = new java.io.File(autoApplyFundingLogFilePath);
            if (!file.exists()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toURI());
            return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auto-apply-funding.log\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(resource);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/stats/auto-validate/start")
    public Map<String, Object> startAutoValidate(
            @RequestParam(defaultValue = "test") String env) {
        synchronized(autoValidateLock) {
            if (autoValidateRunning) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoValidateRun(env);
            executeAutoValidate(env);
            return Map.of("started", true);
        }
    }

    @PostMapping("/stats/auto-validate/stop")
    public Map<String, Object> stopAutoValidate() {
        synchronized(autoValidateLock) {
            if (!autoValidateRunning) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoValidateRunning = false;
            logValidateMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
    }

    @GetMapping("/stats/auto-validate/status")
    public Map<String, Object> getAutoValidateStatus() {
        synchronized(autoValidateLock) {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("running", autoValidateRunning);
            status.put("total", autoValidateTotal);
            status.put("processed", autoValidateProcessed);
            status.put("applied", autoValidateApplied);
            status.put("logs", String.join("\n", autoValidateLogs));
            return status;
        }
    }

    @GetMapping("/stats/auto-validate/download-log")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadAutoValidateLog() {
        try {
            java.io.File file = new java.io.File(autoValidateLogFilePath);
            if (!file.exists()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toURI());
            return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auto-validate.log\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(resource);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    private void startAutoValidateRun(String env) {
        synchronized(autoValidateLock) {
            autoValidateRunning = true;
            autoValidateTotal = 0;
            autoValidateProcessed = 0;
            autoValidateApplied = 0;
            autoValidateLogs.clear();
        }
        
        try {
            java.io.File file = new java.io.File(autoValidateLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                "=== INICI DE PROCÉS VALIDACIÓ AUTOMÀTICA (" + (env == null ? "DEFAULT" : env.toUpperCase()) + ") === " + java.time.LocalDateTime.now() + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            System.err.println("Error initializing auto-validate log file: " + e.getMessage());
        }
    }

    private synchronized void writeValidateLogToFile(String line) {
        try {
            java.io.File file = new java.io.File(autoValidateLogFilePath);
            java.nio.file.Files.writeString(
                file.toPath(), 
                line + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.err.println("Error writing to auto-validate log file: " + e.getMessage());
        }
    }

    private void logValidateMessage(String message) {
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoValidateLogs.add(formatted);
        writeValidateLogToFile(formatted);
    }

    private void executeAutoValidate(String env) {
        asyncExecutor.submit(() -> {
            try {
                Criteria notApproved = new Criteria().orOperator(
                    Criteria.where("workflow.step").nin("approved", "validated", "Approved", "Validated")
                );
                Criteria hasType = new Criteria().norOperator(missingTypeCriteria());
                Criteria hasCountry = new Criteria().norOperator(missingCountryCriteria());
                Criteria hasFunding = new Criteria().norOperator(missingFundingCriteria());

                Query query = new Query(new Criteria().andOperator(notApproved, hasType, hasCountry, hasFunding));
                query.fields().include("uuid").include("name").include("workflow");
                
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                
                synchronized(autoValidateLock) {
                    autoValidateTotal = orgs.size();
                }
                
                logValidateMessage("S'han trobat " + autoValidateTotal + " organitzacions per validar automàticament.");
                
                int concurrencyLimit = 16;
                java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrencyLimit);
                
                List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
                
                for (ExternalOrganization shortOrg : orgs) {
                    String uuid = shortOrg.getUuid();
                    String name = getOrgDisplayName(shortOrg);
                    
                    synchronized(autoValidateLock) {
                        if (!autoValidateRunning) {
                            break;
                        }
                    }
                    
                    sem.acquire();
                    
                    synchronized(autoValidateLock) {
                        if (!autoValidateRunning) {
                            sem.release();
                            break;
                        }
                    }
                    
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            synchronized(autoValidateLock) {
                                if (!autoValidateRunning) {
                                    return;
                                }
                            }
                            
                            ExternalOrganization org = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(uuid)), ExternalOrganization.class);
                            if (org == null) {
                                logValidateMessage("[ERROR] Organització no trobada: " + name + " (UUID: " + uuid + ")");
                                return;
                            }
                            
                            ExternalOrganization.WorkflowStatus updatedWorkflow = org.getWorkflow();
                            if (updatedWorkflow == null) {
                                updatedWorkflow = new ExternalOrganization.WorkflowStatus();
                            }
                            updatedWorkflow.setStep("approved");
                            
                            Map<String, Object> egretaSync = syncExternalOrganizationWorkflowToEgreta(org, updatedWorkflow, env);
                            boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
                            if (!egretaUpdated) {
                                logValidateMessage("[ERROR] " + name + " - Error de sincronització amb Egreta: " + egretaSync.get("reason"));
                            } else {
                                org.setWorkflow(updatedWorkflow);
                                repository.save(org);
                                
                                synchronized(autoValidateLock) {
                                    autoValidateApplied++;
                                }
                                logValidateMessage("[VALIDAT] " + name + " (UUID: " + uuid + ")");
                            }
                        } catch (Exception e) {
                            logValidateMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoValidateLock) {
                                autoValidateProcessed++;
                            }
                        }
                    }, asyncExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                logValidateMessage("Procés de validació automàtica finalitzat. Total processades: " + autoValidateProcessed + ", Validada/es: " + autoValidateApplied);
                invalidateMetadataCatalogCaches();
            } catch (Exception e) {
                logValidateMessage("[FATAL ERROR] " + e.getMessage());
            } finally {
                synchronized(autoValidateLock) {
                    autoValidateRunning = false;
                }
            }
        });
    }
}