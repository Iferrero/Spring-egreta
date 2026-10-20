
package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.demo.model.ExternalOrganization;
import com.example.demo.model.ExternalOrganizationDifferent;
import com.example.demo.repository.ExternalOrganizationRepository;
import com.example.demo.repository.ExternalOrganizationDifferentRepository;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.ReconciliationType;
import com.example.demo.curation.reconciler.ReconciliationJobRegistry;
import com.example.demo.curation.service.MetadataEnrichmentEngine;
import com.example.demo.curation.util.CurationTextNormalizer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private final Map<String, CachedInference> associatedPersonCache = new ConcurrentHashMap<>();

    @Value("${app.ror.suggest.enabled:true}")
    private boolean rorSuggestEnabled;

    @Value("${app.ror.mongodb.database:Ror}")
    private String rorDatabase;

    @Value("${app.ror.mongodb.collection:ror_data}")
    private String rorCollectionName;

    @Value("${app.ror.suggest.url:https://api.ror.org/v2/organizations}")
    private String rorSuggestUrl;

    @Value("${app.ror.suggest.timeout-ms:3500}")
    private int rorSuggestTimeoutMs;

    @Value("${app.openalex.author-suggest.enabled:true}")
    private boolean openAlexAuthorSuggestEnabled;

    @Value("${app.openalex.author-suggest.url:https://api.openalex.org/authors}")
    private String openAlexAuthorSuggestUrl;

    @Value("${app.openalex.author-suggest.timeout-ms:3500}")
    private int openAlexAuthorSuggestTimeoutMs;

    @Value("${app.openalex.api-key:}")
    private String openAlexApiKey;

    @Value("${app.crossref.suggest.enabled:true}")
    private boolean crossrefSuggestEnabled;

    @Value("${app.crossref.suggest.url:https://api.crossref.org/works}")
    private String crossrefSuggestUrl;

    @Value("${app.crossref.suggest.timeout-ms:3500}")
    private int crossrefSuggestTimeoutMs;

    @Value("${app.crossref.mailto:egreta@uab.cat}")
    private String crossrefMailto;

    private record CachedMetadataInference(MetadataInferenceResult result, long timestamp) {}
    private final Map<String, CachedMetadataInference> rorInferenceCache = new ConcurrentHashMap<>();
    private final Map<String, CachedMetadataInference> openAlexInferenceCache = new ConcurrentHashMap<>();
    private final Map<String, CachedMetadataInference> crossrefInferenceCache = new ConcurrentHashMap<>();

    private final ExternalOrganizationRepository repository;
    private final ExternalOrganizationDifferentRepository differentRepository;
    private final MongoTemplate mongoTemplate;
    private final MongoTemplate rorMongoTemplate;
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

    // State variables for auto-apply name process
    private final Object autoApplyNameLock = new Object();
    private boolean autoApplyNameRunning = false;
    private int autoApplyNameTotal = 0;
    private int autoApplyNameProcessed = 0;
    private int autoApplyNameApplied = 0;
    private double autoApplyNameSimilarityThreshold = 0.90;
    private final List<String> autoApplyNameLogs = new java.util.Vector<>();
    private final String autoApplyNameLogFilePath = "logs/auto-apply-name.log";

    // State variables for auto-validate process
    private final Object autoValidateLock = new Object();
    private boolean autoValidateRunning = false;
    private int autoValidateTotal = 0;
    private int autoValidateProcessed = 0;
    private int autoValidateApplied = 0;
    private final List<String> autoValidateLogs = new java.util.Vector<>();
    private final String autoValidateLogFilePath = "logs/auto-validate.log";

    private final Map<String, CachedInference> rorRawCache = new ConcurrentHashMap<>();

    private final ExecutorService aiExecutor;

    @Autowired(required = false)
    private com.example.demo.service.VllmAffiliationService vllmAffiliationService;

    @Autowired(required = false)
    private MetadataEnrichmentEngine metadataEnrichmentEngine;

    @Autowired(required = false)
    private ReconciliationJobRegistry reconciliationJobRegistry;

    @Autowired(required = false)
    private com.example.demo.curation.service.SimilarityMatchingEngine similarityMatchingEngine;

    public ExternalOrganizationController(
            ExternalOrganizationRepository repository,
            ExternalOrganizationDifferentRepository differentRepository,
            MongoTemplate mongoTemplate) {
        this(repository, differentRepository, mongoTemplate, null);
    }

    @Autowired
    public ExternalOrganizationController(
            ExternalOrganizationRepository repository,
            ExternalOrganizationDifferentRepository differentRepository,
            MongoTemplate mongoTemplate,
            @Autowired(required = false) @Qualifier("rorMongoTemplate") MongoTemplate rorMongoTemplate) {
        this.repository = repository;
        this.differentRepository = differentRepository;
        this.mongoTemplate = mongoTemplate;
        this.rorMongoTemplate = rorMongoTemplate != null ? rorMongoTemplate : mongoTemplate;
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

        if (useEgreta) {
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

                // Egreta ha fusionado con éxito. Actualizar MongoDB local:
                // 1) Combinar propiedades locales de fuentes en el target si existe en Mongo
                mergeLocalSourcesIntoTarget(targetId, sourceIds);

                // 2) Eliminar las organizaciones fuente de MongoDB para que no queden huérfanas
                mongoTemplate.remove(new Query(Criteria.where("uuid").in(sourceIds)), ExternalOrganization.class);

                invalidateMetadataCatalogCaches();

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
        }

        // Fusión local MongoDB (cuando egreta = false)
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

        mergeProperties(target, sources);

        // Guardar la organización fusionada
        repository.save(target);

        // Eliminar las fuentes de MongoDB
        mongoTemplate.remove(new Query(Criteria.where("uuid").in(sourceIds)), ExternalOrganization.class);

        invalidateMetadataCatalogCaches();

        return Map.of(
            "merged", true,
            "targetId", targetId,
            "removed", sourceIds,
            "target", target
        );
    }

    private void mergeLocalSourcesIntoTarget(String targetId, List<String> sourceIds) {
        try {
            ExternalOrganization target = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(targetId)), ExternalOrganization.class);
            List<ExternalOrganization> sources = mongoTemplate.find(new Query(Criteria.where("uuid").in(sourceIds)), ExternalOrganization.class);
            if (target != null && !sources.isEmpty()) {
                mergeProperties(target, sources);
                repository.save(target);
            }
        } catch (Exception e) {
            log.warn("Error combinando propiedades locales tras fusión en Egreta: {}", e.getMessage());
        }
    }

    private void mergeProperties(ExternalOrganization target, List<ExternalOrganization> sources) {
        for (ExternalOrganization source : sources) {
            // Fusionar nombres (añadir los que falten)
            if (source.getName() != null) {
                if (target.getName() == null) target.setName(new java.util.HashMap<>());
                for (Map.Entry<String, String> entry : source.getName().entrySet()) {
                    target.getName().putIfAbsent(entry.getKey(), entry.getValue());
                }
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

    private InferenceResult pickBestCountry(List<InferenceResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new InferenceResult("", 0.0, "no-signal");
        }
        return candidates.stream()
            .filter(Objects::nonNull)
            .filter(c -> c.countryLabel() != null && !c.countryLabel().isBlank())
            .max(Comparator.comparingDouble(InferenceResult::confidence))
            .orElse(new InferenceResult("", 0.0, "no-signal"));
    }

    private TypeInferenceResult pickBestType(List<TypeInferenceResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new TypeInferenceResult("", 0.0, "no-signal");
        }
        return candidates.stream()
            .filter(Objects::nonNull)
            .filter(c -> c.typeLabel() != null && !c.typeLabel().isBlank())
            .max(Comparator.comparingDouble(TypeInferenceResult::confidence))
            .orElse(new TypeInferenceResult("", 0.0, "no-signal"));
    }

    private FundingInferenceResult pickBestFunding(List<FundingInferenceResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new FundingInferenceResult("", 0.0, "no-signal");
        }
        return candidates.stream()
            .filter(Objects::nonNull)
            .filter(c -> c.fundingLabel() != null && !c.fundingLabel().isBlank())
            .max(Comparator.comparingDouble(FundingInferenceResult::confidence))
            .orElse(new FundingInferenceResult("", 0.0, "no-signal"));
    }

    public MetadataInferenceResult inferBestMetadata(
            String orgUuid,
            String orgName,
            String explicitAssociatedPerson,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        if (orgName == null || orgName.isBlank()) {
            return new MetadataInferenceResult(
                new InferenceResult("", 0.0, "empty-name"),
                new TypeInferenceResult("", 0.0, "empty-name"),
                new FundingInferenceResult("", 0.0, "empty-name")
            );
        }

        if (metadataEnrichmentEngine != null) {
            String associatedPerson = (explicitAssociatedPerson != null && !explicitAssociatedPerson.isBlank())
                ? explicitAssociatedPerson.trim()
                : findAssociatedPersonForOrg(orgUuid, orgName);
            RelatedPublicationInfo pubInfo = findRelatedPublicationInfoForOrg(orgUuid, orgName);
            String pubTitle = pubInfo != null ? pubInfo.title() : null;
            String doi = pubInfo != null ? pubInfo.doi() : null;

            List<com.example.demo.curation.model.CountryAggregate> modelCountries = (countryCatalog != null)
                ? countryCatalog.stream().map(c -> new com.example.demo.curation.model.CountryAggregate(c.label, c.normalizedLabel, c.count)).toList()
                : List.of();
            List<com.example.demo.curation.model.TypeAggregate> modelTypes = (typeCatalog != null)
                ? typeCatalog.stream().map(t -> new com.example.demo.curation.model.TypeAggregate(t.label, t.normalizedLabel, t.count)).toList()
                : List.of();

            ProviderQueryContext ctx = new ProviderQueryContext(associatedPerson, pubTitle, doi, modelCountries, modelTypes);
            com.example.demo.curation.model.MetadataInferenceResult engineRes = metadataEnrichmentEngine.inferMetadata(orgName, ctx);
            if (engineRes != null && engineRes.hasAnySignal()) {
                return new MetadataInferenceResult(
                    new InferenceResult(engineRes.country().countryLabel(), engineRes.country().confidence(), engineRes.country().reason()),
                    new TypeInferenceResult(engineRes.type().typeLabel(), engineRes.type().confidence(), engineRes.type().reason()),
                    new FundingInferenceResult(engineRes.funding().fundingLabel(), engineRes.funding().confidence(), engineRes.funding().reason())
                );
            }
        }

        List<InferenceResult> countryCandidates = new ArrayList<>();
        List<TypeInferenceResult> typeCandidates = new ArrayList<>();
        List<FundingInferenceResult> fundingCandidates = new ArrayList<>();

        // 1. Fast-path: ROR (Instantani, d'alta precisió i sense cost)
        MetadataInferenceResult rorResult = null;
        if (rorSuggestEnabled) {
            rorResult = inferMetadataWithRor(orgName, countryCatalog, typeCatalog);
            if (rorResult != null) {
                if (rorResult.country() != null && !rorResult.country().countryLabel().isBlank()) {
                    countryCandidates.add(rorResult.country());
                }
                if (rorResult.type() != null && !rorResult.type().typeLabel().isBlank()) {
                    typeCandidates.add(rorResult.type());
                }
                if (rorResult.funding() != null && !rorResult.funding().fundingLabel().isBlank()) {
                    fundingCandidates.add(rorResult.funding());
                }
            }
        }

        // Si ROR ja ha resolt amb alta confiança (>= 85%) tots els camps, retornem immediatament
        boolean countryResolved = rorResult != null && rorResult.country() != null && rorResult.country().confidence() >= 0.85 && !rorResult.country().countryLabel().isBlank();
        boolean typeResolved    = rorResult != null && rorResult.type() != null && rorResult.type().confidence() >= 0.85 && !rorResult.type().typeLabel().isBlank();
        boolean fundingResolved = rorResult != null && rorResult.funding() != null && rorResult.funding().confidence() >= 0.85 && !rorResult.funding().fundingLabel().isBlank();

        if (countryResolved && typeResolved && fundingResolved) {
            return new MetadataInferenceResult(rorResult.country(), rorResult.type(), rorResult.funding());
        }

        // 2. Si algun camp falta o té confiança baixa/mitjana, executar en paral·lel OpenAlex (sobre l'article d'Egreta) i Local AI
        String associatedPerson = (explicitAssociatedPerson != null && !explicitAssociatedPerson.isBlank())
            ? explicitAssociatedPerson.trim()
            : findAssociatedPersonForOrg(orgUuid, orgName);

        List<CompletableFuture<MetadataInferenceResult>> slowFutures = new ArrayList<>();

        // Publicació vinculada a l'organització externa a Egreta (Researchoutputs a Mongo)
        RelatedPublicationInfo pubInfo = findRelatedPublicationInfoForOrg(orgUuid, orgName);
        if (pubInfo != null) {
            // OpenAlex Publication (mira exclusivament l'article recollit a Egreta i l'afiliació de l'autor en aquest article)
            if (openAlexAuthorSuggestEnabled) {
                slowFutures.add(inferMetadataWithOpenAlexPublicationAsync(orgName, pubInfo, countryCatalog, typeCatalog));
            }

            // Crossref Publication (mira l'article recollit a Egreta)
            if (crossrefSuggestEnabled) {
                slowFutures.add(inferMetadataWithCrossrefPublicationAsync(orgName, pubInfo, countryCatalog, typeCatalog));
            }
        }

        // Local AI
        if (aiCountrySuggestEnabled) {
            slowFutures.add(inferMetadataWithLocalAiAsync(orgName, associatedPerson, countryCatalog, typeCatalog));
        }

        if (!slowFutures.isEmpty()) {
            try {
                long maxTimeout = Math.max(
                    Math.max(openAlexAuthorSuggestTimeoutMs, crossrefSuggestTimeoutMs),
                    aiCountrySuggestTimeoutMs
                ) + AI_TIMEOUT_BUFFER_MS;
                CompletableFuture.allOf(slowFutures.toArray(new CompletableFuture[0]))
                    .get(maxTimeout, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Les fonts finalitzades a temps es processaran avall
            }
        }

        for (CompletableFuture<MetadataInferenceResult> future : slowFutures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    MetadataInferenceResult res = future.getNow(null);
                    if (res != null) {
                        if (res.country() != null && !res.country().countryLabel().isBlank()) {
                            countryCandidates.add(res.country());
                        }
                        if (res.type() != null && !res.type().typeLabel().isBlank()) {
                            typeCandidates.add(res.type());
                        }
                        if (res.funding() != null && !res.funding().fundingLabel().isBlank()) {
                            fundingCandidates.add(res.funding());
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Fallback WebSearch (si país té confiança <= 50% o no hi ha candidats)
        InferenceResult currentBestCountry = pickBestCountry(countryCandidates);
        if (currentBestCountry.confidence() <= 0.50) {
            try {
                InferenceResult webRes = inferCountryWithWebSearch(orgName, associatedPerson, countryCatalog);
                if (webRes != null && !webRes.countryLabel().isBlank()) {
                    countryCandidates.add(webRes);
                }
            } catch (Exception ignored) {}
        }

        // Baselines per heurística local de nom
        try {
            InferenceResult hCountry = inferCountryFromName(orgName, countryCatalog);
            if (hCountry != null && !hCountry.countryLabel().isBlank()) {
                countryCandidates.add(hCountry);
            }
        } catch (Exception ignored) {}

        try {
            TypeInferenceResult hType = inferTypeFromName(orgName, typeCatalog);
            if (hType != null && !hType.typeLabel().isBlank()) {
                typeCandidates.add(hType);
            }
        } catch (Exception ignored) {}

        try {
            FundingInferenceResult hFunding = inferFundingFromName(orgName);
            if (hFunding != null && !hFunding.fundingLabel().isBlank()) {
                fundingCandidates.add(hFunding);
            }
        } catch (Exception ignored) {}

        InferenceResult bestCountry = pickBestCountry(countryCandidates);
        TypeInferenceResult bestType = pickBestType(typeCandidates);
        FundingInferenceResult bestFunding = pickBestFunding(fundingCandidates);

        return new MetadataInferenceResult(bestCountry, bestType, bestFunding);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/suggest-metadata?name=<orgName>&uuid=<orgUuid>
    // -------------------------------------------------------------------------
    @GetMapping("/stats/suggest-metadata")
    public Map<String, Object> suggestMetadataByName(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String uuid,
            @RequestParam(required = false) String associatedPerson) {
        String orgName = name == null ? "" : name.trim();
        String orgUuid = uuid == null ? "" : uuid.trim();

        if (orgName.isBlank() && !orgUuid.isBlank()) {
            ExternalOrganization found = mongoTemplate.findOne(
                new Query(Criteria.where("uuid").is(orgUuid)), ExternalOrganization.class);
            if (found != null) {
                orgName = extractOrgName(found);
            }
        }

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
            empty.put("associatedPerson", "");
            empty.put("suggestedName", "");
            empty.put("nameSimilarity", 0.0);
            empty.put("nameRorId", "");
            empty.put("nameReason", "empty-name");
            return empty;
        }

        List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
        List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();

        RorNameProposal nameProposal = null;
        try {
            Long pureId = null;
            if (!orgUuid.isBlank()) {
                ExternalOrganization found = mongoTemplate.findOne(
                    new Query(Criteria.where("uuid").is(orgUuid)), ExternalOrganization.class);
                if (found != null) {
                    pureId = found.getPureId();
                }
            }
            nameProposal = findRorNameProposal(orgUuid, pureId, orgName);
        } catch (Exception e) {
            log.debug("Error generating name proposal for '{}': {}", orgName, e.getMessage());
        }

        // Si tenim un nom net suggerit per vLLM / ROR / OpenAlex, utilitzem aquest nom net per inferir les metadades
        String effectiveName = (nameProposal != null && nameProposal.suggestedName() != null && !nameProposal.suggestedName().isBlank())
            ? nameProposal.suggestedName().trim()
            : orgName;

        MetadataInferenceResult best = inferBestMetadata(orgUuid, effectiveName, associatedPerson, countryCatalog, typeCatalog);

        InferenceResult countryResult = best.country();
        TypeInferenceResult typeResult = best.type();
        FundingInferenceResult fundingResult = best.funding();

        // Si nameProposal té país o tipus deduït amb alta confiança, prioritzar-lo
        if (nameProposal != null) {
            if (nameProposal.country() != null && !nameProposal.country().isBlank() && (countryResult.countryLabel().isBlank() || countryResult.confidence() < 0.85)) {
                countryResult = new InferenceResult(nameProposal.country(), Math.max(countryResult.confidence(), nameProposal.similarity()), nameProposal.reason());
            }
            if (nameProposal.type() != null && !nameProposal.type().isBlank() && (typeResult.typeLabel().isBlank() || typeResult.confidence() < 0.85)) {
                typeResult = new TypeInferenceResult(nameProposal.type(), Math.max(typeResult.confidence(), nameProposal.similarity()), nameProposal.reason());
            }
        }

        // Si cal millorar la inferència de finançament a partir del nom net o del tipus
        if (fundingResult == null || fundingResult.fundingLabel().isBlank() || fundingResult.confidence() < 0.85) {
            FundingInferenceResult fRes = inferFundingFromName(effectiveName);
            if (fRes != null && !fRes.fundingLabel().isBlank() && (fundingResult == null || fRes.confidence() >= fundingResult.confidence())) {
                fundingResult = fRes;
            }
        }

        String resolvedPerson = (associatedPerson != null && !associatedPerson.isBlank())
            ? associatedPerson.trim()
            : findAssociatedPersonForOrg(orgUuid, orgName);

        String suggestedCountryUri = resolveCountryUriByLabel(countryResult.countryLabel());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestedCountry", countryResult.countryLabel());
        out.put("suggestedCountryUri", suggestedCountryUri);
        out.put("countryConfidence", countryResult.confidence());
        out.put("countryReason", countryResult.reason());
        out.put("suggestedType", typeResult.typeLabel());
        out.put("typeConfidence", typeResult.confidence());
        out.put("typeReason", typeResult.reason());
        out.put("suggestedFunding", fundingResult != null ? fundingResult.fundingLabel() : "");
        out.put("suggestedFundingUri", resolveFundingUri(fundingResult != null ? fundingResult.fundingLabel() : ""));
        out.put("fundingConfidence", fundingResult != null ? fundingResult.confidence() : 0.0);
        out.put("fundingReason", fundingResult != null ? fundingResult.reason() : "no-signal");
        out.put("associatedPerson", resolvedPerson != null ? resolvedPerson : "");
        if (nameProposal != null && nameProposal.suggestedName() != null && !nameProposal.suggestedName().isBlank()) {
            out.put("suggestedName", nameProposal.suggestedName());
            out.put("nameSimilarity", nameProposal.similarity());
            out.put("nameRorId", nameProposal.rorId() != null ? nameProposal.rorId() : "");
            out.put("nameReason", nameProposal.reason() != null ? nameProposal.reason() : "");
            out.put("nameRawAffiliation", nameProposal.rawAffiliation() != null ? nameProposal.rawAffiliation() : "");
            out.put("proposals", nameProposal.candidates() != null ? nameProposal.candidates() : List.of());
        } else {
            out.put("suggestedName", "");
            out.put("nameSimilarity", 0.0);
            out.put("nameRorId", "");
            out.put("nameReason", "no-proposal");
            out.put("nameRawAffiliation", "");
            out.put("proposals", List.of());
        }
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

        List<ExternalOrganization> organizations = mongoTemplate.find(
            new Query(Criteria.where("uuid").in(uuids)),
            ExternalOrganization.class
        );
        if (organizations.isEmpty()) return List.of();

        List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
        List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();

        Map<String, MetadataInferenceResult> bestResults = new ConcurrentHashMap<>();
        List<ExternalOrganization> needsDeepInference = new ArrayList<>();

        if (rorSuggestEnabled) {
            Map<String, CompletableFuture<MetadataInferenceResult>> rorFutures = new HashMap<>();
            for (ExternalOrganization org : organizations) {
                rorFutures.put(org.getUuid(), inferMetadataWithRorAsync(extractOrgName(org), countryCatalog, typeCatalog));
            }
            rorFutures.forEach((uuid, future) -> {
                try {
                    MetadataInferenceResult rorRes = future.get(rorSuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
                    if (rorRes != null 
                            && rorRes.country() != null && rorRes.country().confidence() >= 0.85 && !rorRes.country().countryLabel().isBlank()
                            && rorRes.type() != null && rorRes.type().confidence() >= 0.85 && !rorRes.type().typeLabel().isBlank()
                            && rorRes.funding() != null && rorRes.funding().confidence() >= 0.85 && !rorRes.funding().fundingLabel().isBlank()) {
                        bestResults.put(uuid, rorRes);
                    }
                } catch (Exception ignored) {}
            });
        }

        for (ExternalOrganization org : organizations) {
            if (!bestResults.containsKey(org.getUuid())) {
                needsDeepInference.add(org);
            }
        }

        if (!needsDeepInference.isEmpty()) {
            List<CompletableFuture<Void>> batchFutures = needsDeepInference.stream()
                .map(org -> CompletableFuture.runAsync(() -> {
                    String uuid = org.getUuid();
                    String orgName = extractOrgName(org);
                    MetadataInferenceResult best = inferBestMetadata(uuid, orgName, null, countryCatalog, typeCatalog);
                    bestResults.put(uuid, best);
                }, asyncExecutor))
                .collect(Collectors.toList());

            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();
        }

        return organizations.stream().map(org -> {
            String uuid = org.getUuid();
            MetadataInferenceResult best = bestResults.getOrDefault(uuid,
                new MetadataInferenceResult(
                    new InferenceResult("", 0.0, "no-signal"),
                    new TypeInferenceResult("", 0.0, "no-signal"),
                    new FundingInferenceResult("", 0.0, "no-signal")
                )
            );
            InferenceResult cr = best.country();
            TypeInferenceResult tr = best.type();
            FundingInferenceResult fr = best.funding();
            String countryUri = resolveCountryUriByLabel(cr.countryLabel());
            String associatedPerson = findAssociatedPersonForOrg(uuid, extractOrgName(org));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuid);
            row.put("name", extractOrgName(org));
            row.put("suggestedCountry", cr.countryLabel());
            row.put("suggestedCountryUri", countryUri);
            row.put("countryConfidence", cr.confidence());
            row.put("countryReason", cr.reason());
            row.put("suggestedType", tr.typeLabel());
            row.put("typeConfidence", tr.confidence());
            row.put("typeReason", tr.reason());
            row.put("suggestedFunding", fr.fundingLabel());
            row.put("suggestedFundingUri", resolveFundingUri(fr.fundingLabel()));
            row.put("fundingConfidence", fr.confidence());
            row.put("fundingReason", fr.reason());
            row.put("associatedPerson", associatedPerson != null ? associatedPerson : "");
            String workflowStep = null;
            if (org.getWorkflow() != null) {
                workflowStep = org.getWorkflow().getStep();
            }
            row.put("workflow", workflowStep);
            return row;
        }).collect(Collectors.toList());
    }

    @PostMapping("/stats/clear-cache")
    public Map<String, Object> clearCache() {
        int rorCount = rorInferenceCache.size();
        int openAlexCount = openAlexInferenceCache.size();
        int crossrefCount = crossrefInferenceCache.size();
        int relatedPubCount = relatedPublicationCache.size();
        int countryCount = countryInferenceCache.size();
        int typeCount = typeInferenceCache.size();
        int personCount = associatedPersonCache.size();
        int graphCount = graphSimilarityCache.size() + pairsSimilarityCache.size();

        rorInferenceCache.clear();
        openAlexInferenceCache.clear();
        crossrefInferenceCache.clear();
        relatedPublicationCache.clear();
        countryInferenceCache.clear();
        typeInferenceCache.clear();
        associatedPersonCache.clear();
        graphSimilarityCache.clear();
        pairsSimilarityCache.clear();
        cachedCountryCatalog.set(null);
        cachedTypeCatalog.set(null);
        cachedExternalOrgUuids.set(null);
        cachedReferencedExternalOrgUuids.set(null);
        containsWordCache.clear();

        log.info("Caches de ExternalOrganization netejades manualment (ROR: {}, OpenAlex: {}, Crossref: {}, Pubs: {}, País: {}, Tipus: {}, Persones: {}, Grafs: {})",
                rorCount, openAlexCount, crossrefCount, relatedPubCount, countryCount, typeCount, personCount, graphCount);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "S'han buidat totes les memòries cau correctament");
        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put("rorInferences", rorCount);
        cleared.put("openAlexInferences", openAlexCount);
        cleared.put("crossrefInferences", crossrefCount);
        cleared.put("relatedPublications", relatedPubCount);
        cleared.put("countryInferences", countryCount);
        cleared.put("typeInferences", typeCount);
        cleared.put("associatedPersons", personCount);
        cleared.put("graphs", graphCount);
        resp.put("clearedEntries", cleared);
        resp.put("timestamp", System.currentTimeMillis());

        return resp;
    }

    @GetMapping("/stats/country-catalog")
    public List<Map<String, String>> countryCatalog() {
        try {
            Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/core/countries"));
            Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
            if (schemeDoc != null) {
                @SuppressWarnings("unchecked")
                List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
                if (contained != null && !contained.isEmpty()) {
                    List<Map<String, String>> countries = new ArrayList<>();
                    for (Document c : contained) {
                        Boolean disabled = c.getBoolean("disabled");
                        if (disabled != null && disabled) continue;
                        String uri = Objects.toString(c.getString("uri"), "").trim();
                        String label = "";
                        Document termDoc = (Document) c.get("term");
                        if (termDoc != null) {
                            label = termDoc.getString("ca_ES");
                            if (label == null || label.isBlank()) label = termDoc.getString("es_ES");
                            if (label == null || label.isBlank()) label = termDoc.getString("en_GB");
                        }
                        label = label == null ? "" : label.trim();
                        if (!label.isBlank() && !"(desconegut)".equalsIgnoreCase(label)) {
                            countries.add(Map.of("uri", uri, "label", label));
                        }
                    }
                    if (!countries.isEmpty()) {
                        return countries.stream()
                            .sorted(Comparator.comparing(
                                r -> r.get("label"),
                                String.CASE_INSENSITIVE_ORDER))
                            .collect(Collectors.toList());
                    }
                }
            }
        } catch (Exception ignored) {}

        List<CountryAggregate> catalog = getCachedCountryCatalog();
        return catalog.stream()
            .map(c -> {
                String label = c.label;
                String uri = resolveCountryUriByLabel(label);
                return Map.of("uri", uri, "label", label);
            })
            .sorted(Comparator.comparing(r -> r.get("label"), String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
    }

    @GetMapping("/stats/funding-catalog")
    public List<Map<String, String>> fundingCatalog() {
        return List.of(
            Map.of("uri", "/uab/externalorganisations/caracter/pub", "label", "Publica"),
            Map.of("uri", "/uab/externalorganisations/caracter/prv", "label", "Privada"),
            Map.of("uri", "/uab/externalorganisations/caracter/mix", "label", "Mixta")
        );
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

    private Map<String, Object> syncExternalOrganizationRorIdentifierToEgreta(
            ExternalOrganization org,
            String rorId,
            String targetEnv) {
        return syncExternalOrganizationRorIdentifierToEgreta(org, rorId, null, null, targetEnv);
    }

    private Map<String, Object> syncExternalOrganizationRorIdentifierToEgreta(
            ExternalOrganization org,
            String rorId,
            ExternalOrganization.UriTerm updatedCountry,
            String targetEnv) {
        return syncExternalOrganizationRorIdentifierToEgreta(org, rorId, updatedCountry, null, targetEnv);
    }

    private Map<String, Object> syncExternalOrganizationRorIdentifierToEgreta(
            ExternalOrganization org,
            String rorId,
            ExternalOrganization.UriTerm updatedCountry,
            String newName,
            String targetEnv) {
        if (!egretaExternalOrgEnabled) {
            return Map.of("updated", false, "reason", "egreta-sync-disabled");
        }
        if (org == null || org.getUuid() == null || org.getUuid().isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }
        boolean hasRor = rorId != null && !rorId.isBlank();
        boolean hasName = newName != null && !newName.isBlank();
        boolean hasCountry = updatedCountry != null;

        if (!hasRor && !hasName && !hasCountry) {
            return Map.of("updated", false, "reason", "missing-update-payload");
        }

        String normalizedRor = null;
        if (hasRor) {
            normalizedRor = rorId.trim();
            if (!normalizedRor.startsWith("http://") && !normalizedRor.startsWith("https://")) {
                normalizedRor = "https://ror.org/" + normalizedRor;
            }
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

            if (hasRor) {
                upsertRorIdentifierInPayload(payloadNode, normalizedRor);
            }

            if (hasName) {
                JsonNode nameNode = payloadNode.path("name");
                ObjectNode nameObject;
                if (nameNode == null || !nameNode.isObject()) {
                    nameObject = objectMapper.createObjectNode();
                    payloadNode.set("name", nameObject);
                } else {
                    nameObject = (ObjectNode) nameNode;
                }
                String cleanNewName = newName.trim();
                nameObject.put("en_GB", cleanNewName);
                nameObject.put("es_ES", cleanNewName);
                nameObject.put("ca_ES", cleanNewName);
                nameObject.put("value", cleanNewName);
            }

            if (updatedCountry != null) {
                JsonNode addressNode = payloadNode.path("address");
                ObjectNode addressObject;
                if (addressNode == null || !addressNode.isObject()) {
                    addressObject = objectMapper.createObjectNode();
                    payloadNode.set("address", addressObject);
                } else {
                    addressObject = (ObjectNode) addressNode;
                }
                addressObject.set("country", objectMapper.valueToTree(updatedCountry));
            }

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

    private void upsertRorIdentifierInPayload(ObjectNode payloadNode, String rorUrl) {
        com.fasterxml.jackson.databind.node.ArrayNode idArray;
        JsonNode existingIds = payloadNode.path("identifiers");
        if (existingIds == null || !existingIds.isArray()) {
            idArray = objectMapper.createArrayNode();
            payloadNode.set("identifiers", idArray);
        } else {
            idArray = (com.fasterxml.jackson.databind.node.ArrayNode) existingIds;
        }

        ObjectNode rorNode = null;
        for (JsonNode node : idArray) {
            if (!node.isObject()) continue;
            String typeUri = node.path("type").path("uri").asText("").toLowerCase();
            String idSource = node.path("idSource").asText("").toLowerCase();
            String idVal = node.path("id").asText("").toLowerCase();
            String valVal = node.path("value").asText("").toLowerCase();
            if (typeUri.contains("ror") || idSource.contains("ror") || idVal.contains("ror.org") || valVal.contains("ror.org")) {
                rorNode = (ObjectNode) node;
                break;
            }
        }

        if (rorNode == null) {
            rorNode = objectMapper.createObjectNode();
            idArray.add(rorNode);
        }

        rorNode.put("typeDiscriminator", "ClassifiedId");
        rorNode.put("id", rorUrl);
        rorNode.put("value", rorUrl);
        rorNode.put("idSource", "ror");

        ObjectNode typeNode = objectMapper.createObjectNode();
        typeNode.put("uri", "/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationsources/ror");
        ObjectNode termNode = objectMapper.createObjectNode();
        termNode.put("ca_ES", "ROR");
        termNode.put("es_ES", "ROR");
        termNode.put("en_GB", "ROR");
        typeNode.set("term", termNode);
        rorNode.set("type", typeNode);
    }

    private void applyRorIdentifierToOrganization(ExternalOrganization org, String rorId) {
        applyRorIdentifierToOrganization(org, rorId, null);
    }

    private void applyRorIdentifierToOrganization(ExternalOrganization org, String rorId, ExternalOrganization.UriTerm updatedCountry) {
        if (org == null || rorId == null || rorId.isBlank()) return;
        String normalizedRor = rorId.trim();
        if (!normalizedRor.startsWith("http://") && !normalizedRor.startsWith("https://")) {
            normalizedRor = "https://ror.org/" + normalizedRor;
        }

        List<ExternalOrganization.Identifier> identifiers = org.getIdentifiers();
        if (identifiers == null) {
            identifiers = new ArrayList<>();
            org.setIdentifiers(identifiers);
        }

        ExternalOrganization.Identifier rorIdent = null;
        for (ExternalOrganization.Identifier id : identifiers) {
            if (id == null) continue;
            if (id.getIdSource() != null && id.getIdSource().toLowerCase().contains("ror")) {
                rorIdent = id;
                break;
            }
            if (id.getType() != null && id.getType().getUri() != null && id.getType().getUri().toLowerCase().contains("ror")) {
                rorIdent = id;
                break;
            }
            String val = id.getEffectiveValue();
            if (val != null && val.toLowerCase().contains("ror.org")) {
                rorIdent = id;
                break;
            }
        }

        if (rorIdent == null) {
            rorIdent = new ExternalOrganization.Identifier();
            identifiers.add(rorIdent);
        }

        rorIdent.setTypeDiscriminator("ClassifiedId");
        rorIdent.setId(normalizedRor);
        rorIdent.setValue(normalizedRor);
        rorIdent.setIdSource("ror");

        ExternalOrganization.UriTerm uriTerm = rorIdent.getType();
        if (uriTerm == null) {
            uriTerm = new ExternalOrganization.UriTerm();
            rorIdent.setType(uriTerm);
        }
        uriTerm.setUri("/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationsources/ror");
        Map<String, String> termMap = uriTerm.getTerm();
        if (termMap == null) {
            termMap = new LinkedHashMap<>();
            uriTerm.setTerm(termMap);
        }
        termMap.put("ca_ES", "ROR");
        termMap.put("es_ES", "ROR");
        termMap.put("en_GB", "ROR");

        if (updatedCountry != null) {
            if (org.getAddress() == null) {
                org.setAddress(new ExternalOrganization.Address());
            }
            org.getAddress().setCountry(updatedCountry);
        }
    }

    @PostMapping(value = {"/stats/apply-suggested-name", "/stats/apply-suggested-ror"})
    public Map<String, Object> applySuggestedName(@RequestBody Map<String, Object> payload) {
        String uuid = payload == null ? "" : Objects.toString(payload.get("uuid"), "").trim();
        String rorId = payload == null ? "" : Objects.toString(payload.get("rorId"), "").trim();
        String suggestedName = payload == null ? "" : Objects.toString(payload.get("suggestedName"), "").trim();
        boolean applyName = false;
        if (payload != null) {
            Object applyNameObj = payload.get("applyName");
            if (applyNameObj instanceof Boolean b) {
                applyName = b;
            } else if (applyNameObj != null) {
                applyName = "true".equalsIgnoreCase(applyNameObj.toString().trim());
            } else if (payload.containsKey("changeName")) {
                applyName = "true".equalsIgnoreCase(String.valueOf(payload.get("changeName")).trim());
            }
        }
        String env = payload == null ? "test" : Objects.toString(payload.get("env"), "test").trim();

        if (uuid.isBlank()) {
            return Map.of("updated", false, "reason", "missing-uuid");
        }

        Query q = new Query(Criteria.where("uuid").is(uuid));
        ExternalOrganization org = mongoTemplate.findOne(q, ExternalOrganization.class);
        if (org == null) {
            return Map.of("updated", false, "reason", "organization-not-found", "uuid", uuid);
        }

        RorNameProposal proposal = null;
        if (rorId.isBlank() || isMissingCountry(org) || (applyName && suggestedName.isBlank())) {
            String searchName = suggestedName.isBlank() ? extractOrgName(org) : suggestedName;
            proposal = findRorNameProposal(uuid, searchName);
            if (rorId.isBlank() && proposal != null && proposal.rorId() != null && !proposal.rorId().isBlank()) {
                rorId = proposal.rorId();
            }
            if (suggestedName.isBlank() && proposal != null && proposal.suggestedName() != null && !proposal.suggestedName().isBlank()) {
                suggestedName = proposal.suggestedName();
            }
        }

        if (rorId.isBlank() && (!applyName || suggestedName.isBlank())) {
            return Map.of("updated", false, "reason", "missing-ror-id");
        }

        ExternalOrganization.UriTerm updatedCountry = null;
        String appliedCountry = null;
        if (isMissingCountry(org)) {
            String countryCandidate = payload == null ? "" : Objects.toString(payload.get("suggestedCountry"), "").trim();
            String countryUriCandidate = payload == null ? "" : Objects.toString(payload.get("suggestedCountryUri"), "").trim();
            if (countryCandidate.isBlank() && countryUriCandidate.isBlank() && proposal != null && proposal.country() != null && !proposal.country().isBlank()) {
                countryCandidate = proposal.country();
            }
            if (!countryCandidate.isBlank() || !countryUriCandidate.isBlank()) {
                updatedCountry = resolveCountryUriTermFromPayload(countryUriCandidate, countryCandidate, null);
                if (updatedCountry != null) {
                    appliedCountry = !countryCandidate.isBlank() ? countryCandidate : updatedCountry.getEffectiveTerm();
                }
            }
        }

        String nameToApply = (applyName && !suggestedName.isBlank()) ? suggestedName.trim() : null;

        Map<String, Object> egretaSync = syncExternalOrganizationRorIdentifierToEgreta(org, rorId, updatedCountry, nameToApply, env);
        boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
        if (!egretaUpdated) {
            return Map.of(
                "updated", false,
                "reason", Objects.toString(egretaSync.get("reason"), "egreta-sync-failed"),
                "uuid", uuid,
                "details", egretaSync
            );
        }

        if (nameToApply != null) {
            if (org.getName() == null) {
                org.setName(new java.util.HashMap<>());
            }
            org.getName().put("ca_ES", nameToApply);
            org.getName().put("es_ES", nameToApply);
            org.getName().put("en_GB", nameToApply);
        }

        if (!rorId.isBlank()) {
            applyRorIdentifierToOrganization(org, rorId, updatedCountry);
        } else if (updatedCountry != null) {
            if (org.getAddress() == null) {
                org.setAddress(new ExternalOrganization.Address());
            }
            org.getAddress().setCountry(updatedCountry);
        }

        repository.save(org);
        if (updatedCountry != null || nameToApply != null) {
            invalidateMetadataCatalogCaches();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated", true);
        response.put("uuid", uuid);
        if (nameToApply != null) {
            response.put("appliedName", nameToApply);
        }
        if (!rorId.isBlank()) {
            response.put("appliedRor", rorId);
        }
        if (appliedCountry != null) {
            response.put("appliedCountry", appliedCountry);
            response.put("appliedCountryUri", updatedCountry != null ? Objects.toString(updatedCountry.getUri(), "") : "");
        }
        response.put("egreta", egretaSync);
        return response;
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

    private Set<String> getIgnoredPairKeys() {
        try {
            List<ExternalOrganizationDifferent> all = differentRepository.findAll();
            Set<String> keys = new HashSet<>(all.size());
            for (ExternalOrganizationDifferent diff : all) {
                String u1 = diff.getOrg1Uuid();
                String u2 = diff.getOrg2Uuid();
                if (u1 != null && u2 != null) {
                    String key = u1.compareTo(u2) < 0 ? u1 + "|" + u2 : u2 + "|" + u1;
                    keys.add(key);
                }
            }
            return keys;
        } catch (Exception ex) {
            log.warn("Error llegint organitzacions marcades com a diferents: {}", ex.getMessage());
            return Collections.emptySet();
        }
    }

    @PostMapping("/similarity/different")
    public Map<String, Object> markDifferentOrganizations(@RequestBody Map<String, Object> payload) {
        List<ExternalOrganizationDifferent> toSave = new ArrayList<>();

        // 1. Single pair { "from": "uuid1", "to": "uuid2", "fromName": "...", "toName": "..." } or { "org1": "...", "org2": "..." }
        String from = Objects.toString(payload.get("from") != null ? payload.get("from") : payload.get("org1"), "").trim();
        String to = Objects.toString(payload.get("to") != null ? payload.get("to") : payload.get("org2"), "").trim();
        String fromName = Objects.toString(payload.get("fromName") != null ? payload.get("fromName") : payload.get("org1Name"), "").trim();
        String toName = Objects.toString(payload.get("toName") != null ? payload.get("toName") : payload.get("org2Name"), "").trim();

        if (!from.isBlank() && !to.isBlank() && !from.equals(to)) {
            toSave.add(new ExternalOrganizationDifferent(from, to, fromName, toName));
        }

        // 2. Cluster UUIDs { "clusterUuids": ["u1", "u2", "u3"] }
        if (payload.get("clusterUuids") instanceof List<?> clusterList) {
            List<String> uuids = clusterList.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

            for (int i = 0; i < uuids.size(); i++) {
                for (int j = i + 1; j < uuids.size(); j++) {
                    toSave.add(new ExternalOrganizationDifferent(uuids.get(i), uuids.get(j), null, null));
                }
            }
        }

        // 3. List of pairs { "pairs": [ {"from": "u1", "to": "u2"}, ... ] }
        if (payload.get("pairs") instanceof List<?> pairsList) {
            for (Object item : pairsList) {
                if (item instanceof Map<?, ?> pairMap) {
                    String pFrom = Objects.toString(pairMap.get("from") != null ? pairMap.get("from") : pairMap.get("org1"), "").trim();
                    String pTo = Objects.toString(pairMap.get("to") != null ? pairMap.get("to") : pairMap.get("org2"), "").trim();
                    String pFromName = Objects.toString(pairMap.get("fromName") != null ? pairMap.get("fromName") : pairMap.get("org1Name"), "").trim();
                    String pToName = Objects.toString(pairMap.get("toName") != null ? pairMap.get("toName") : pairMap.get("org2Name"), "").trim();
                    if (!pFrom.isBlank() && !pTo.isBlank() && !pFrom.equals(pTo)) {
                        toSave.add(new ExternalOrganizationDifferent(pFrom, pTo, pFromName, pToName));
                    }
                }
            }
        }

        if (toSave.isEmpty()) {
            return Map.of("success", false, "savedCount", 0, "message", "No s'han rebut parelles vàlides");
        }

        // Resolve any missing organization names from ExternalOrganizations collection
        Set<String> missingUuids = new HashSet<>();
        for (ExternalOrganizationDifferent diff : toSave) {
            if (diff.getOrg1Name() == null || diff.getOrg1Name().isBlank()) {
                if (diff.getOrg1Uuid() != null && !diff.getOrg1Uuid().isBlank()) {
                    missingUuids.add(diff.getOrg1Uuid());
                }
            }
            if (diff.getOrg2Name() == null || diff.getOrg2Name().isBlank()) {
                if (diff.getOrg2Uuid() != null && !diff.getOrg2Uuid().isBlank()) {
                    missingUuids.add(diff.getOrg2Uuid());
                }
            }
        }

        if (!missingUuids.isEmpty()) {
            Query query = new Query(new Criteria().orOperator(
                Criteria.where("uuid").in(missingUuids),
                Criteria.where("id").in(missingUuids)
            ));
            query.fields().include("uuid").include("id").include("name");
            List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
            Map<String, String> nameMap = new HashMap<>();
            for (ExternalOrganization org : orgs) {
                String name = getOrgDisplayName(org);
                if (org.getUuid() != null && !org.getUuid().isBlank()) nameMap.put(org.getUuid(), name);
                if (org.getId() != null && !org.getId().isBlank()) nameMap.put(org.getId(), name);
            }

            for (ExternalOrganizationDifferent diff : toSave) {
                if ((diff.getOrg1Name() == null || diff.getOrg1Name().isBlank()) && nameMap.containsKey(diff.getOrg1Uuid())) {
                    diff.setOrg1Name(nameMap.get(diff.getOrg1Uuid()));
                }
                if ((diff.getOrg2Name() == null || diff.getOrg2Name().isBlank()) && nameMap.containsKey(diff.getOrg2Uuid())) {
                    diff.setOrg2Name(nameMap.get(diff.getOrg2Uuid()));
                }
            }
        }

        differentRepository.saveAll(toSave);

        // Clear cached results
        pairsSimilarityCache.clear();
        graphSimilarityCache.clear();

        return Map.of("success", true, "savedCount", toSave.size());
    }

    private void backfillMissingDifferentNames() {
        try {
            Query query = new Query(new Criteria().orOperator(
                Criteria.where("org1Name").is(null),
                Criteria.where("org1Name").is(""),
                Criteria.where("org2Name").is(null),
                Criteria.where("org2Name").is("")
            )).limit(500);

            List<ExternalOrganizationDifferent> list = mongoTemplate.find(query, ExternalOrganizationDifferent.class);
            if (list.isEmpty()) return;

            Set<String> uuids = new HashSet<>();
            for (ExternalOrganizationDifferent diff : list) {
                if (diff.getOrg1Uuid() != null && !diff.getOrg1Uuid().isBlank()) uuids.add(diff.getOrg1Uuid());
                if (diff.getOrg2Uuid() != null && !diff.getOrg2Uuid().isBlank()) uuids.add(diff.getOrg2Uuid());
            }

            Query orgQuery = new Query(new Criteria().orOperator(
                Criteria.where("uuid").in(uuids),
                Criteria.where("id").in(uuids)
            ));
            orgQuery.fields().include("uuid").include("id").include("name");
            List<ExternalOrganization> orgs = mongoTemplate.find(orgQuery, ExternalOrganization.class);
            Map<String, String> nameMap = new HashMap<>();
            for (ExternalOrganization org : orgs) {
                String name = getOrgDisplayName(org);
                if (org.getUuid() != null && !org.getUuid().isBlank()) nameMap.put(org.getUuid(), name);
                if (org.getId() != null && !org.getId().isBlank()) nameMap.put(org.getId(), name);
            }

            for (ExternalOrganizationDifferent diff : list) {
                boolean changed = false;
                if ((diff.getOrg1Name() == null || diff.getOrg1Name().isBlank()) && nameMap.containsKey(diff.getOrg1Uuid())) {
                    diff.setOrg1Name(nameMap.get(diff.getOrg1Uuid()));
                    changed = true;
                }
                if ((diff.getOrg2Name() == null || diff.getOrg2Name().isBlank()) && nameMap.containsKey(diff.getOrg2Uuid())) {
                    diff.setOrg2Name(nameMap.get(diff.getOrg2Uuid()));
                    changed = true;
                }
                if (changed) {
                    differentRepository.save(diff);
                }
            }
        } catch (Exception ex) {
            log.warn("Error rellenant noms d'organitzacions diferents: {}", ex.getMessage());
        }
    }

    @GetMapping("/similarity/different")
    public Map<String, Object> listDifferentOrganizations(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        backfillMissingDifferentNames();
        Page<ExternalOrganizationDifferent> resultPage = differentRepository.findAll(PageRequest.of(Math.max(0, page), Math.max(1, size)));
        return Map.of(
            "total", resultPage.getTotalElements(),
            "totalPages", resultPage.getTotalPages(),
            "currentPage", resultPage.getNumber(),
            "items", resultPage.getContent()
        );
    }

    @DeleteMapping("/similarity/different")
    public Map<String, Object> removeDifferentPair(
            @RequestParam(name = "org1") String org1,
            @RequestParam(name = "org2") String org2) {
        String u1 = org1.trim();
        String u2 = org2.trim();
        String id = u1.compareTo(u2) < 0 ? u1 + ":" + u2 : u2 + ":" + u1;
        differentRepository.deleteById(id);
        pairsSimilarityCache.clear();
        graphSimilarityCache.clear();
        return Map.of("success", true, "deletedId", id);
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

        if (similarityMatchingEngine != null) {
            int totalAvailableOrgs = (int) repository.count();
            List<ExternalOrganization> orgs = findOrganizationsLimited(limit);
            List<com.example.demo.curation.model.OrgSimilarityProfile> profiles = similarityMatchingEngine.buildSimilarityProfiles(orgs);
            Set<String> ignoredKeys = getIgnoredPairKeys();
            Map<String, Object> result = similarityMatchingEngine.buildGraphSimilarity(profiles, threshold, limit, totalAvailableOrgs, ignoredKeys);
            graphSimilarityCache.put(cacheKey, new CachedGraphResult(result, now));
            if (graphSimilarityCache.size() > 100) {
                graphSimilarityCache.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > GRAPH_SIMILARITY_CACHE_TTL_MS);
            }
            return result;
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

        if (similarityMatchingEngine != null) {
            int totalAvailableOrgs = (int) repository.count();
            List<ExternalOrganization> orgs = findOrganizationsLimited(limit);
            List<com.example.demo.curation.model.OrgSimilarityProfile> profiles = similarityMatchingEngine.buildSimilarityProfiles(orgs);
            Set<String> ignoredKeys = getIgnoredPairKeys();
            Map<String, Object> result = similarityMatchingEngine.buildPairsSimilarityResult(profiles, threshold, limit, maxPairs, totalAvailableOrgs, ignoredKeys, null);
            pairsSimilarityCache.put(cacheKey, new CachedPairsResult(result, now));
            if (pairsSimilarityCache.size() > 100) {
                pairsSimilarityCache.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > GRAPH_SIMILARITY_CACHE_TTL_MS);
            }
            return result;
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

        if (similarityMatchingEngine != null) {
            state.progress = 4;
            state.message = "Llegint organitzacions";
            int totalAvailableOrgs = (int) repository.count();
            List<ExternalOrganization> orgs = findOrganizationsLimited(limit);

            state.progress = 10;
            state.message = "Preparant noms";
            List<com.example.demo.curation.model.OrgSimilarityProfile> profiles = similarityMatchingEngine.buildSimilarityProfiles(orgs);

            state.progress = 16;
            state.message = "Comparant noms";
            Set<String> ignoredKeys = getIgnoredPairKeys();

            Map<String, Object> result = similarityMatchingEngine.buildPairsSimilarityResult(
                profiles, threshold, limit, maxPairs, totalAvailableOrgs, ignoredKeys,
                value -> {
                    state.progress = Math.max(16, Math.min(98, value));
                    state.message = "Comparant noms";
                }
            );

            pairsSimilarityCache.put(cacheKey, new CachedPairsResult(result, now));
            if (pairsSimilarityCache.size() > 100) {
                pairsSimilarityCache.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > GRAPH_SIMILARITY_CACHE_TTL_MS);
            }
            return result;
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

        Set<String> ignoredKeys = getIgnoredPairKeys();

        SimilarityPairResult pairResult = buildSimilarityPairs(
            profiles,
            threshold,
            maxPairs,
            value -> {
                state.progress = Math.max(16, Math.min(98, value));
                state.message = "Comparant noms";
            },
            ignoredKeys
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
             .include("name")
             .include("pureId")
             .include("type")
             .include("address")
             .include("workflow");
        return mongoTemplate.find(query, ExternalOrganization.class);
    }

    private SimilarityPairResult buildSimilarityPairs(List<OrgSimilarityProfile> profiles, double threshold, int maxPairs) {
        return buildSimilarityPairs(profiles, threshold, maxPairs, null, getIgnoredPairKeys());
    }

    private SimilarityPairResult buildSimilarityPairs(List<OrgSimilarityProfile> profiles, double threshold, int maxPairs, java.util.function.IntConsumer progressCallback, Set<String> ignoredKeys) {
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
                if (ignoredKeys != null && ignoredKeys.contains(edgeId)) {
                    continue;
                }
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
        return CurationTextNormalizer.calculateNameSimilarity(parts1, parts2, threshold);
    }

    private int levenshteinDistance(String a, String b) {
        return CurationTextNormalizer.levenshteinDistance(a, b);
    }

    private double calculateStringSimilarity(String s1, String s2) {
        return CurationTextNormalizer.calculateStringSimilarity(s1, s2);
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
            "fundingOrganizations.uuid",
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
        Map.entry("espana",         "spain"),
        Map.entry("espanya",        "spain"),
        Map.entry("spanish",        "spain"),
        Map.entry("frances",        "france"),
        Map.entry("french",         "france"),
        Map.entry("deutsch",        "germany"),
        Map.entry("german",         "germany"),
        Map.entry("deutschland",    "germany"),
        Map.entry("wirtschaft",     "germany"),
        Map.entry("wirstschaft",    "germany"),
        Map.entry("gesellschaft",   "germany"),
        Map.entry("bundes",         "germany"),
        Map.entry("stiftung",       "germany"),
        Map.entry("hochschule",     "germany"),
        Map.entry("fachhochschule", "germany"),
        Map.entry("universitaet",   "germany"),
        Map.entry("italian",        "italy"),
        Map.entry("portugues",      "portugal"),
        Map.entry("british",        "united_kingdom"),
        Map.entry("english",        "united_kingdom"),
        Map.entry("american",       "united_states"),
        Map.entry("eeuu",           "united_states"),
        Map.entry("dutch",          "netherlands"),
        Map.entry("swiss",          "switzerland"),
        Map.entry("japanese",       "japan"),
        Map.entry("korean",         "south_korea")
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

    private static final List<String> FUNDING_STRONG_PUBLIC_HINTS = List.of(
        "ministeri", "ministerio", "ministry",
        "govern", "government", "governo", "gouvernement",
        "generalitat", "ajuntament", "ayuntamiento", "city council",
        "diputacio", "diputacion", "conselleria", "consejeria",
        "csic", "cnrs", "conicet", "inserm", "ciber", "isciii",
        "instituto nacional", "institut nacional", "national institute",
        "agencia publica", "agencia estatal"
    );

    private static final List<String> FUNDING_PUBLIC_HINTS = List.of(
        "universitat", "universidad", "university", "universite", "universita",
        "institut public", "instituto publico", "institut d'investigacio", "instituto de investigacion", "research institute", "institute", "institut", "center for research", "centre de recerca", "centro de investigacion",
        "hospital universitari", "hospital universitario", "hospital clinic", "hospital general",
        "servei de salut", "servicio de salud", "catsalut", "sergas", "sacyl", "sescam", "osakidetza",
        "irb", "idibaps", "vhio", "iisc", "max planck", "helmholtz", "fraunhofer", "leibniz", "inria", "inrae"
    );

    private static final List<String> FUNDING_STRONG_PRIVATE_HINTS = List.of(
        "s l", "slu", "slp", "s a", "sa", "s l u", "s a u",
        "ltd", "llc", "inc", "corp", "corporation",
        "gmbh", "bv", "nv", "plc", "srl", "spa", "kgaa", "sas", "sarl",
        "fundacio privada", "fundacion privada"
    );

    private static final List<String> FUNDING_PRIVATE_HINTS = List.of(
        "company", "empresa", "startup", "holding", "ventures", "capital",
        "pharma", "pharmaceuticals", "biotech", "laboratories", "laboratorios",
        "therapeutics", "technologies", "consulting", "solutions", "software"
    );

    // =========================================================================
    // Records de inferencia (interns al controller)
    // =========================================================================

    public static record CountryAggregate(String label, String normalizedLabel, int count) {}
    public static record TypeAggregate   (String label, String normalizedLabel, int count) {}
    public static record InferenceResult    (String countryLabel, double confidence, String reason) {}
    public static record TypeInferenceResult(String typeLabel,   double confidence, String reason) {}
    public static record FundingInferenceResult(String fundingLabel, double confidence, String reason) {}
    public static record MetadataInferenceResult(InferenceResult country, TypeInferenceResult type, FundingInferenceResult funding) {}

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

    private String resolveCountryFromCode(String countryCode, List<CountryAggregate> catalog) {
        if (countryCode == null || countryCode.isBlank() || catalog == null || catalog.isEmpty()) return "";
        String code = countryCode.trim().toUpperCase();

        // 1. Direct standard ISO2 map to COUNTRY_ALIASES canonical key
        String canonicalKey = switch (code) {
            case "ES" -> "spain";
            case "FR" -> "france";
            case "DE" -> "germany";
            case "IT" -> "italy";
            case "PT" -> "portugal";
            case "GB", "UK" -> "united_kingdom";
            case "US", "USA" -> "united_states";
            case "NL" -> "netherlands";
            case "BE" -> "belgium";
            case "CH" -> "switzerland";
            case "AT" -> "austria";
            case "IE" -> "ireland";
            case "DK" -> "denmark";
            case "SE" -> "sweden";
            case "NO" -> "norway";
            case "FI" -> "finland";
            case "PL" -> "poland";
            case "CZ" -> "czech_republic";
            case "CN" -> "china";
            case "JP" -> "japan";
            case "KR" -> "south_korea";
            case "IN" -> "india";
            case "MX" -> "mexico";
            case "BR" -> "brazil";
            case "AR" -> "argentina";
            case "CL" -> "chile";
            case "CO" -> "colombia";
            case "CA" -> "canada";
            case "AU" -> "australia";
            case "NZ" -> "new_zealand";
            case "IL" -> "israel";
            case "SG" -> "singapore";
            case "GR" -> "greece";
            case "TR" -> "turkey";
            case "ZA" -> "south_africa";
            case "RU" -> "russia";
            case "UA" -> "ukraine";
            case "RO" -> "romania";
            case "HU" -> "hungary";
            case "BG" -> "bulgaria";
            case "HR" -> "croatia";
            case "SI" -> "slovenia";
            case "SK" -> "slovakia";
            case "EE" -> "estonia";
            case "LV" -> "latvia";
            case "LT" -> "lithuania";
            case "LU" -> "luxembourg";
            case "IS" -> "iceland";
            case "UY" -> "uruguay";
            case "PE" -> "peru";
            case "EC" -> "ecuador";
            case "VE" -> "venezuela";
            case "CU" -> "cuba";
            case "CR" -> "costa_rica";
            case "PA" -> "panama";
            case "DO" -> "dominican_republic";
            case "PR" -> "puerto_rico";
            case "TW" -> "taiwan";
            case "HK" -> "hong_kong";
            case "SA" -> "saudi_arabia";
            case "AE" -> "united_arab_emirates";
            case "EG" -> "egypt";
            case "MA" -> "morocco";
            case "TN" -> "tunisia";
            case "DZ" -> "algeria";
            case "NG" -> "nigeria";
            case "KE" -> "kenya";
            case "TH" -> "thailand";
            case "VN" -> "vietnam";
            case "ID" -> "indonesia";
            case "MY" -> "malaysia";
            case "PH" -> "philippines";
            case "PK" -> "pakistan";
            case "BD" -> "bangladesh";
            case "IR" -> "iran";
            case "IQ" -> "iraq";
            case "LB" -> "lebanon";
            case "JO" -> "jordan";
            default -> null;
        };

        if (canonicalKey != null) {
            String resolved = resolveAliasCountryDirect(canonicalKey, catalog);
            if (!resolved.isBlank()) return resolved;
        }

        // 2. Locale display names in Catalan, Spanish, English
        try {
            Locale loc = Locale.of("", code);
            String caName = loc.getDisplayCountry(Locale.forLanguageTag("ca"));
            String esName = loc.getDisplayCountry(Locale.forLanguageTag("es"));
            String enName = loc.getDisplayCountry(Locale.ENGLISH);

            for (String candidateName : List.of(caName, esName, enName)) {
                if (candidateName != null && !candidateName.isBlank()) {
                    String normCand = normalizeText(candidateName);
                    for (CountryAggregate c : catalog) {
                        if (c.normalizedLabel.equals(normCand)) {
                            return c.label;
                        }
                    }
                }
            }
            for (String candidateName : List.of(caName, esName, enName)) {
                if (candidateName != null && !candidateName.isBlank()) {
                    String normCand = normalizeText(candidateName);
                    for (CountryAggregate c : catalog) {
                        if (c.normalizedLabel.contains(normCand) || normCand.contains(c.normalizedLabel)) {
                            return c.label;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "";
    }

    private String resolveAliasCountryDirect(String aliasKey, List<CountryAggregate> catalog) {
        List<String> aliases = COUNTRY_ALIASES.getOrDefault(aliasKey.trim().toLowerCase(), List.of());
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

    private String resolveAliasCountry(String aliasKey, List<CountryAggregate> catalog) {
        if (aliasKey == null || aliasKey.isBlank() || catalog == null || catalog.isEmpty()) return "";
        String trimmed = aliasKey.trim();
        if (trimmed.length() == 2) {
            String fromCode = resolveCountryFromCode(trimmed, catalog);
            if (!fromCode.isBlank()) return fromCode;
        }

        return resolveAliasCountryDirect(trimmed, catalog);
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
                return new TypeInferenceResult(t.label, confidenceFromCount(0.90, t.count), "direct-type-match");
            }
        }

        return resolvePreciseEgretaType(orgName, "", "", catalog, 0.88, "keyword-hint");
    }

    private FundingInferenceResult inferFundingFromName(String orgName) {
        String normalized = normalizeText(orgName);
        if (normalized.isBlank()) {
            return new FundingInferenceResult("", 0.0, "no-signal");
        }

        int strongPublicHits = 0;
        int strongPrivateHits = 0;
        int publicHits = 0;
        int privateHits = 0;
        String publicHit = "";
        String privateHit = "";

        for (String hint : FUNDING_STRONG_PUBLIC_HINTS) {
            if (containsWord(normalized, hint)) {
                strongPublicHits++;
                publicHits += 2;
                if (publicHit.isBlank()) publicHit = hint;
            }
        }
        for (String hint : FUNDING_PUBLIC_HINTS) {
            if (containsWord(normalized, hint)) {
                publicHits++;
                if (publicHit.isBlank()) publicHit = hint;
            }
        }
        for (String hint : FUNDING_STRONG_PRIVATE_HINTS) {
            if (containsWord(normalized, hint)) {
                strongPrivateHits++;
                privateHits += 2;
                if (privateHit.isBlank()) privateHit = hint;
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
        boolean hasStrongSignal = isPublic ? (strongPublicHits > 0) : (strongPrivateHits > 0);

        double baseConfidence = hasStrongSignal ? 0.92 : 0.85;
        double confidence = clamp(baseConfidence + (diff * 0.03) + (major * 0.01));
        confidence = Math.min(0.97, confidence);

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
    // Inferencia ROR (Research Organization Registry)
    // =========================================================================

    public record RorCandidateProposal(
        String suggestedName,
        double similarity,
        String rorId,
        String country,
        String type,
        String reason,
        String rawAffiliation
    ) {}

    public record RorNameProposal(
        String uuid,
        Long pureId,
        String currentName,
        String suggestedName,
        double similarity,
        String rorId,
        String country,
        String type,
        String reason,
        String rawAffiliation,
        List<RorCandidateProposal> candidates
    ) {
        public RorNameProposal(String uuid, Long pureId, String currentName, String suggestedName, double similarity, String rorId, String country, String type, String reason, String rawAffiliation) {
            this(uuid, pureId, currentName, suggestedName, similarity, rorId, country, type, reason, rawAffiliation, List.of());
        }

        public RorNameProposal(String uuid, Long pureId, String currentName, String suggestedName, double similarity, String rorId, String country, String type, String reason) {
            this(uuid, pureId, currentName, suggestedName, similarity, rorId, country, type, reason, "", List.of());
        }
    }

    private static final Set<String> ROR_GENERIC_WORDS = Set.of(
        "universitat", "universidad", "university", "universite", "universitaet", "universitad",
        "institut", "instituto", "institute", "institution", "fondation", "fundacion", "fundacio", "foundation",
        "centre", "centro", "center", "hospital", "facultat", "facultad", "faculty",
        "departament", "departamento", "department", "de", "del", "la", "el", "les", "los", "las",
        "d", "l", "i", "y", "and", "of", "the", "for", "in", "a", "at", "on", "to", "und", "et", "du", "des",
        "fur", "der", "die", "das", "sa", "sl", "gmbh", "inc", "llc", "corp", "ltd"
    );

    public String fetchRorRawJson(String orgName) {
        if (!rorSuggestEnabled || orgName == null || orgName.isBlank()) return null;
        String cacheKey = normalizeText(orgName);
        CachedInference cached = rorRawCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return cached.result();
        }

        String result = queryRorFromMongo(orgName);
        if ((result == null || !hasRorItems(result)) && rorSuggestUrl != null && !rorSuggestUrl.isBlank() && rorSuggestUrl.startsWith("http")) {
            String cleaned = cleanQueryForRor(orgName);
            if (!cleaned.isBlank()) {
                result = executeRorQuery(cleaned);
            }
            if (result == null || !hasRorItems(result)) {
                result = executeRorQuery(orgName.trim());
            }
        }

        if (result != null && hasRorItems(result)) {
            rorRawCache.put(cacheKey, new CachedInference(result, System.currentTimeMillis()));
            return result;
        }

        if (result != null) {
            rorRawCache.put(cacheKey, new CachedInference(result, System.currentTimeMillis()));
            return result;
        }
        return null;
    }

    public String queryRorFromMongo(String orgName) {
        if (orgName == null || orgName.isBlank()) return null;
        MongoTemplate template = rorMongoTemplate != null ? rorMongoTemplate : mongoTemplate;
        if (template == null) return null;

        String colName = (rorCollectionName == null || rorCollectionName.isBlank()) ? "ror_data" : rorCollectionName.trim();
        com.mongodb.client.MongoCollection<Document> collection;
        try {
            collection = template.getCollection(colName);
        } catch (Exception e) {
            log.debug("Error accessing ROR collection '{}': {}", colName, e.getMessage());
            return null;
        }

        Map<String, Document> candidates = new LinkedHashMap<>();
        String norm = normalizeText(orgName);
        String[] rawTokens = norm.split("\\s+");
        List<String> tokens = Arrays.stream(rawTokens).filter(t -> !t.isBlank()).toList();
        if (tokens.isEmpty()) return null;

        String cleanedRaw = cleanQueryForRor(orgName);
        if (cleanedRaw.isBlank()) cleanedRaw = orgName.trim();

        int maxCandidates = 25;

        // 1. Exact case-insensitive match on names.value
        try {
            Document query = new Document("names.value", new Document("$regex", "^" + Pattern.quote(cleanedRaw) + "$").append("$options", "i"));
            for (Document doc : collection.find(query).limit(maxCandidates)) {
                String id = doc.getString("id");
                if (id != null && !candidates.containsKey(id)) {
                    candidates.put(id, doc);
                }
            }
        } catch (Exception e) {
            log.debug("ROR mongo exact match query error: {}", e.getMessage());
        }

        List<String> specificTokens = tokens.stream()
                .filter(t -> !ROR_GENERIC_WORDS.contains(t) && t.length() >= 3)
                .toList();

        // 2. MongoDB Text search with quoted specific tokens (requires exact term matching)
        if (specificTokens.size() >= 1) {
            String quotedSearch = specificTokens.stream().limit(3)
                    .map(t -> "\"" + t.replace("\"", "") + "\"")
                    .collect(Collectors.joining(" "));
            try {
                Document textQuery = new Document("$text", new Document("$search", quotedSearch));
                Document projection = new Document("score", new Document("$meta", "textScore"));
                Document sort = new Document("score", new Document("$meta", "textScore"));
                for (Document doc : collection.find(textQuery).projection(projection).sort(sort).limit(maxCandidates)) {
                    String id = doc.getString("id");
                    if (id != null && !candidates.containsKey(id)) {
                        candidates.put(id, doc);
                    }
                }
            } catch (Exception e) {
                log.debug("ROR mongo text search (quoted) error: {}", e.getMessage());
            }
        }

        // 3. Fallback: unquoted text search if we have few candidates
        if (candidates.size() < 12) {
            String searchWords = tokens.stream().filter(t -> t.length() > 2).collect(Collectors.joining(" "));
            if (!searchWords.isBlank()) {
                try {
                    Document textQuery = new Document("$text", new Document("$search", searchWords));
                    Document projection = new Document("score", new Document("$meta", "textScore"));
                    Document sort = new Document("score", new Document("$meta", "textScore"));
                    for (Document doc : collection.find(textQuery).projection(projection).sort(sort).limit(maxCandidates)) {
                        String id = doc.getString("id");
                        if (id != null && !candidates.containsKey(id)) {
                            candidates.put(id, doc);
                        }
                    }
                } catch (Exception e) {
                    log.debug("ROR mongo text search (unquoted) error: {}", e.getMessage());
                }
            }
        }

        // 4. Fallback: regex search on longest specific token
        if (candidates.size() < 5 && !specificTokens.isEmpty()) {
            String longest = specificTokens.stream().max(Comparator.comparingInt(String::length)).orElse("");
            if (longest.length() >= 4) {
                try {
                    Document regexQuery = new Document("names.value", new Document("$regex", "\\b" + Pattern.quote(longest) + "\\b").append("$options", "i"));
                    for (Document doc : collection.find(regexQuery).limit(10)) {
                        String id = doc.getString("id");
                        if (id != null && !candidates.containsKey(id)) {
                            candidates.put(id, doc);
                        }
                    }
                } catch (Exception e) {
                    log.debug("ROR mongo regex token query error: {}", e.getMessage());
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        List<Document> resultList = candidates.values().stream().limit(maxCandidates).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document d : resultList) {
            Document copy = new Document(d);
            copy.remove("_id");
            items.add(copy);
        }

        try {
            Map<String, Object> root = Map.of("items", items);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Error serializing ROR items to JSON: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasRorItems(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.path("items");
            return items.isArray() && !items.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String cleanQueryForRor(String name) {
        if (name == null || name.isBlank()) return "";
        return name.replaceAll("(?i)[,\\.;/\\-_]+", " ")
                   .replaceAll("(?i)\\b(e\\.?w\\.?|s\\.?l\\.?|s\\.?a\\.?|gmbh|inc\\.?|llc|corp\\.?|ltd\\.?)\\b", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    private static final Set<String> KNOWN_RAW_COUNTRY_TOKENS = Set.of(
        "spain", "espana", "espanya", "france", "francia", "franca", "germany", "deutschland", "alemania", "alemanya",
        "italy", "italia", "portugal", "united kingdom", "uk", "great britain", "england", "scotland", "wales",
        "united states", "usa", "us", "u s a", "eeuu", "estados unidos", "switzerland", "suiza", "suissa", "suisse",
        "netherlands", "holland", "holanda", "paises bajos", "paisos baixos", "belgium", "belgica", "belgique",
        "austria", "osterreich", "ireland", "irlanda", "denmark", "dinamarca", "sweden", "suecia", "sverige",
        "norway", "noruega", "norge", "finland", "finlandia", "suomi", "poland", "polonia", "polska",
        "czech republic", "czechia", "republica checa", "greece", "grecia", "japan", "japon", "japo",
        "china", "south korea", "korea", "corea", "australia", "canada",
        "brazil", "brasil", "mexico", "mexic", "argentina", "chile", "colombia", "peru", "india", "israel", "turkey", "turquia",
        "es", "esp", "fr", "fra", "de", "deu", "it", "ita", "pt", "prt", "gb", "gbr", "nl", "nld", "be", "bel", "ch", "che", "at", "aut", "se", "swe", "no", "nor", "fi", "fin", "dk", "dnk", "pl", "pol"
    );

    private static final Set<String> KNOWN_RAW_CITY_TOKENS = Set.of(
        "barcelona", "madrid", "bellaterra", "cerdanyola del valles", "cerdanyola", "sabadell", "terrassa", "badalona",
        "girona", "lleida", "tarragona", "manresa", "vic", "reus", "sant cugat del valles", "sant cugat", "mataro",
        "santa coloma de gramenet", "l'hospitalet de llobregat", "hospitalet de llobregat", "hospitalet", "granollers",
        "castelldefels", "viladecans", "el prat de llobregat", "rubi", "gava", "cornella de llobregat", "esplugues de llobregat",
        "sant boi de llobregat", "mollet del valles", "valencia", "sevilla", "zaragoza", "malaga", "murcia",
        "palma", "palma de mallorca", "bilbao", "alicante", "alacant", "cordoba", "valladolid", "vigo", "gijon", "vitoria",
        "vitoria gasteiz", "a coruna", "la coruna", "granada", "elche", "elx", "oviedo", "cartagena", "jerez de la frontera",
        "pamplona", "irunea", "almeria", "san sebastian", "donostia", "santander", "castellon", "castello", "burgos",
        "salamanca", "albacete", "huelva", "logrono", "badajoz", "huesca", "segovia", "cuenca", "soria", "teruel",
        "avila", "zamora", "palencia", "ciudad real", "toledo", "caceres", "lugo", "ourense", "pontevedra", "jaen",
        "cadiz", "paris", "rome", "roma", "london", "berlin", "munich", "muenchen", "frankfurt", "amsterdam",
        "brussels", "bruxelles", "geneva", "geneve", "zurich", "zuerich", "vienna", "wien", "milan", "milano",
        "florence", "firenze", "naples", "napoli", "turin", "torino", "lisbon", "lisboa", "porto", "athens", "dublin",
        "stockholm", "oslo", "copenhagen", "helsinki", "warsaw", "prague", "budapest", "tokyo", "beijing", "shanghai",
        "seoul", "singapore", "sydney", "melbourne", "new york", "boston", "chicago", "los angeles", "san francisco",
        "washington", "cambridge", "oxford", "toronto", "montreal", "vancouver", "buenos aires", "santiago", "bogota", "mexico city"
    );

    public String cleanRawAffiliationWithoutCityCountry(String rawAff, List<CountryAggregate> countryCatalog) {
        if (rawAff == null || rawAff.isBlank()) return "";

        // 1. Intentar extreure mitjançant el servidor local vLLM (IA / Model d'idioma)
        if (vllmAffiliationService != null && vllmAffiliationService.isEnabled()) {
            try {
                String vllmParsed = vllmAffiliationService.cleanOrganizationName(rawAff);
                if (vllmParsed != null && !vllmParsed.isBlank()) {
                    return vllmParsed;
                }
            } catch (Exception e) {
                log.debug("Fallback to heuristic cleaner, vLLM error: {}", e.getMessage());
            }
        }

        // 2. Fallback heurístic / Address & Location Parser
        String text = rawAff.trim();
        text = text.replaceAll("(?i)\\bhttps?://\\S+", "")
                   .replaceAll("(?i)\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", "")
                   .trim();

        String[] rawSegments = text.split("\\s*[,;]\\s*");
        if (rawSegments.length == 0) return text;

        List<String> validSegments = new ArrayList<>();
        for (String seg : rawSegments) {
            String s = seg.trim();
            if (!s.isBlank()) {
                validSegments.add(s);
            }
        }

        while (validSegments.size() > 1) {
            String last = validSegments.get(validSegments.size() - 1);
            if (isAffiliationLocationSegment(last, countryCatalog)) {
                validSegments.remove(validSegments.size() - 1);
            } else {
                break;
            }
        }

        String result = String.join(", ", validSegments).trim();
        result = cleanInlineTrailingLocation(result, countryCatalog);
        result = result.replaceAll("^[,;.\\s]+|[,;.\\s]+$", "").trim();
        return result.isBlank() ? text : result;
    }

    private boolean isAffiliationLocationSegment(String segment, List<CountryAggregate> countryCatalog) {
        if (segment == null || segment.isBlank()) return true;
        String s = segment.trim();
        String norm = normalizeText(s);

        // 1. Pure postal code: 3 to 8 digits (e.g. 639798, 08193, 28040, 75005, 100000)
        if (s.matches("^\\d{3,8}$")) return true;

        // 2. Postal code with prefix/suffix, county or city (e.g. "639798", "Essex CM1 7ET", "CM1 7ET", "08193 Bellaterra", "D-80333 Munich", "EC1A 1BB", "CA 94305")
        if (s.matches("(?i).*(?:\\b[A-Z]{1,2}\\d{1,2}[A-Z]?\\s*\\d[A-Z]{2}\\b|\\b[A-Z]\\d[A-Z]\\s*\\d[A-Z]\\d\\b).*")) return true;
        if (s.matches("^(?:[A-Z]{1,3}[-\\s]?)?\\d{3,8}(?:\\s+[A-Za-zÀ-ÿ\\s'.-]+)?$")) return true;
        if (s.matches("(?i).*(?:\\b[A-Z]{2}\\s+\\d{4,6}(?:-\\d{4})?\\b).*")) return true;
        if (s.matches("^(?:CP|C\\.P\\.|BP|Cedex|Apartado|P\\.?O\\.?\\s*Box|Postal Code|Zip Code|Zip|Casilla|Boite Postale)\\b.*$")) return true;
        if (s.matches("^[A-Z0-9]{2,4}\\s+[A-Z0-9]{2,4}(?:\\s+[A-Za-zÀ-ÿ\\s'-]+)?$")) return true;

        // 3. Street address:
        // 3a. Starts with street keyword (e.g. "Carrer de...", "Avenida...", "Passeig...", "Court Road...")
        if (s.matches("(?i)^(?:c\\/|carrer|calle|avda\\.?|avenida|avinguda|passeig|paseo|pza\\.?|plaça|plaza|rúa|via|rue|street|st\\.?|road|rd\\.?|ave\\.?|avenue|boulevard|blvd\\.?|drive|dr\\.?|lane|ln\\.?|way|place|pl\\.?|court|ct\\.?|square|sq\\.?|building|edifici|edificio|campus\\b|apartado\\b|p\\.?o\\.?\\s*box\\b|bp\\s*\\d+|cp\\s*\\d+|casilla\\b|dr\\.\\s+[a-z]+|doctor\\s+[a-z]+|gran\\s+via|bloque|blq\\.?|nau|nave|piso|planta)\\b.*")) {
            return true;
        }

        // 3b. Starts with street number, e.g. "50 Nanyang Avenue", "10 Downing Street", "353 Serra Mall", "1 Oxford St"
        if (s.matches("(?i)^\\d+[-A-Za-z0-9/\\s,.-]*\\b(?:avenue|ave\\.?|street|st\\.?|road|rd\\.?|drive|dr\\.?|boulevard|blvd\\.?|lane|ln\\.?|way|place|pl\\.?|court|ct\\.?|square|sq\\.?|circle|cir\\.?|terrace|ter\\.?|parkway|pkwy\\.?|highway|hwy\\.?|close|cres\\.?|crescent|walk|row|alley|path|mall|campus|building|edifici|edificio|block|tower|weg|str\\.?|strasse|straße|allee|platz)\\b.*")) {
            return true;
        }

        // 3c. Ends with street keyword / number, e.g. "Court Road", "Nanyang Avenue 50", "Ackermannweg 10", "Piazzale Aldo Moro 5", "Martí i Franquès 1"
        if (s.matches("(?i).*\\b(?:avenue|ave\\.?|street|st\\.?|road|rd\\.?|drive|dr\\.?|boulevard|blvd\\.?|lane|ln\\.?|way|place|court|carrer|calle|avinguda|avenida|passeig|paseo|via|rue|str\\.?|strasse|straße|platz|allee|weg|mall|walk)\\s*(?:\\d+[-/\\d\\w]*)?$")) {
            return true;
        }

        // 4. Country check
        if (KNOWN_RAW_COUNTRY_TOKENS.contains(norm)) return true;
        if (countryCatalog != null) {
            boolean matchesCatalogCountry = countryCatalog.stream()
                .anyMatch(c -> c.normalizedLabel.equals(norm));
            if (matchesCatalogCountry) return true;
        }

        // 5. City / Province check
        if (KNOWN_RAW_CITY_TOKENS.contains(norm)) return true;

        String withoutParens = norm.replaceAll("\\([^)]+\\)", "").trim();
        if (KNOWN_RAW_CITY_TOKENS.contains(withoutParens) || KNOWN_RAW_COUNTRY_TOKENS.contains(withoutParens)) {
            return true;
        }

        // 6. Generic trailing location without organization keywords (e.g. single city/region/county name like "Chelmsford", "Essex")
        boolean hasOrgKeyword = s.matches("(?i).*\\b(?:hospital|clinic|universit|college|school|faculty|facultat|facultad|institut|departm|centre|center|laborat|unit|service|servei|servicio|fundaci|foundat|associat|societ|agenc|organi|group|grup|grupo|consejo|consell|council|consort|board|govern|minister).*");
        if (!hasOrgKeyword && (s.split("\\s+").length <= 2 || s.matches("(?i).*(?:shire|ford|borough|burg|ville|city|town|region|district|county|province)\\b.*"))) {
            return true;
        }

        return false;
    }

    private String cleanInlineTrailingLocation(String text, List<CountryAggregate> countryCatalog) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text.trim();
        cleaned = cleaned.replaceAll("(?i)\\s*\\((?:Spain|Espanya|España|France|Germany|Italy|UK|USA|Singapore|Barcelona|Madrid|Bellaterra)\\)\\s*$", "");
        cleaned = cleaned.replaceAll("(?i),?\\s*\\d+[-A-Za-z0-9/\\s,.-]*\\b(?:Avenue|Ave|Street|St|Road|Rd|Drive|Dr|Boulevard|Blvd|Lane|Ln|Way|Carrer|Calle|Avenida|Avinguda|Passeig|Paseo|Rue|Via|Strasse|Weg)\\b(?:\\s*\\d+)?.*$", "");
        cleaned = cleaned.replaceAll("(?i),?\\s*(?:\\b\\d{3,8}\\b|[A-Z]{1,2}-\\d{3,8})\\s+[A-Za-zÀ-ÿ\\s'.-]+$", "");
        cleaned = cleaned.replaceAll("(?i),?\\s*\\b\\d{3,8}\\b$", "");
        return cleaned.trim();
    }

    private String executeRorQuery(String queryText) {
        try {
            String baseUrl = (rorSuggestUrl == null || rorSuggestUrl.isBlank())
                    ? "https://api.ror.org/v2/organizations"
                    : rorSuggestUrl.trim();
            String encodedName = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
            String targetUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "query=" + encodedName;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, rorSuggestTimeoutMs)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Spring-egreta/1.0 (UAB)")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception e) {
            log.debug("ROR API lookup error for '{}': {}", queryText, e.getMessage());
        }
        return null;
    }

    public RorNameProposal findRorNameProposal(String orgUuid, String orgName) {
        return findRorNameProposal(orgUuid, null, orgName);
    }

    public RorNameProposal findRorNameProposal(String orgUuid, Long pureId, String orgName) {
        if ((!rorSuggestEnabled && !openAlexAuthorSuggestEnabled) || orgName == null || orgName.isBlank()) return null;

        List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
        List<TypeAggregate> typeCatalog = getCachedTypeCatalog();

        RorNameProposal rorProposal = null;
        if (rorSuggestEnabled) {
            rorProposal = executeRorNameProposalLookup(orgUuid, pureId, orgName, countryCatalog, typeCatalog);
        }

        RorNameProposal bestProposal = rorProposal;

        if (openAlexAuthorSuggestEnabled && (bestProposal == null || bestProposal.similarity() < 0.88)) {
            // 1. Proposta a partir de publicació relacionada a Egreta
            RorNameProposal pubProposal = findOpenAlexPublicationNameProposal(orgUuid, pureId, orgName, countryCatalog, typeCatalog);
            if (pubProposal != null && (bestProposal == null || pubProposal.similarity() > bestProposal.similarity())) {
                bestProposal = pubProposal;
            }

            // 2. Proposta a partir de cerca d'institucions a OpenAlex
            if (bestProposal == null || bestProposal.similarity() < 0.85) {
                RorNameProposal instProposal = findOpenAlexInstitutionNameProposal(orgUuid, pureId, orgName, countryCatalog, typeCatalog);
                if (instProposal != null && (bestProposal == null || instProposal.similarity() > bestProposal.similarity())) {
                    bestProposal = instProposal;
                }
            }
        }

        // Si tenim proposta però li falta país o tipus, complementar amb catàleg
        if (bestProposal != null) {
            String country = bestProposal.country();
            if ((country == null || country.isBlank()) && countryCatalog != null && !countryCatalog.isEmpty()) {
                InferenceResult cRes = inferCountryFromName(bestProposal.suggestedName(), countryCatalog);
                if (cRes != null && !cRes.countryLabel().isBlank()) {
                    country = cRes.countryLabel();
                }
            }
            String type = bestProposal.type();
            if ((type == null || type.isBlank()) && typeCatalog != null && !typeCatalog.isEmpty()) {
                TypeInferenceResult tRes = resolvePreciseEgretaType(bestProposal.currentName(), bestProposal.suggestedName(), "", typeCatalog, bestProposal.similarity(), "type-enrich");
                if (tRes != null && !tRes.typeLabel().isBlank()) {
                    type = tRes.typeLabel();
                }
            }
            if (!Objects.equals(country, bestProposal.country()) || !Objects.equals(type, bestProposal.type())) {
                bestProposal = new RorNameProposal(
                    bestProposal.uuid(),
                    bestProposal.pureId(),
                    bestProposal.currentName(),
                    bestProposal.suggestedName(),
                    bestProposal.similarity(),
                    bestProposal.rorId(),
                    country != null ? country : "",
                    type != null ? type : "",
                    bestProposal.reason(),
                    bestProposal.rawAffiliation()
                );
            }
        }

        return bestProposal;
    }

    private RorNameProposal executeRorNameProposalLookup(
            String orgUuid,
            Long pureId,
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!rorSuggestEnabled || orgName == null || orgName.isBlank()) return null;
        String rawJson = fetchRorRawJson(orgName);
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return null;

            String queryNorm = normalizeText(orgName);
            if (queryNorm.isBlank()) return null;

            JsonNode bestItem = null;
            String bestCanonicalName = "";
            double bestSimilarity = 0.0;
            String bestRorId = "";
            String bestCountry = "";
            String rawRorType = "";

            int maxInspect = Math.min(items.size(), 20);
            for (int i = 0; i < maxInspect; i++) {
                JsonNode item = items.get(i);
                String rorId = item.path("id").asText("");

                String canonicalName = "";
                JsonNode namesNode = item.path("names");
                if (namesNode.isArray()) {
                    for (JsonNode nameObj : namesNode) {
                        String val = nameObj.path("value").asText("").trim();
                        if (val.isBlank()) continue;
                        JsonNode typesNode = nameObj.path("types");
                        boolean isRorDisplay = false;
                        boolean isLabel = false;
                        if (typesNode.isArray()) {
                            for (JsonNode t : typesNode) {
                                if ("ror_display".equalsIgnoreCase(t.asText())) isRorDisplay = true;
                                if ("label".equalsIgnoreCase(t.asText())) isLabel = true;
                            }
                        }
                        if (isRorDisplay) {
                            canonicalName = val;
                            break;
                        } else if (isLabel && canonicalName.isBlank()) {
                            canonicalName = val;
                        } else if (canonicalName.isBlank()) {
                            canonicalName = val;
                        }
                    }
                }

                if (canonicalName.isBlank()) continue;

                double itemMaxScore = 0.0;
                double stringSim = calculateStringSimilarity(orgName, canonicalName);
                if (namesNode.isArray()) {
                    for (JsonNode nameObj : namesNode) {
                        String val = nameObj.path("value").asText("").trim();
                        if (val.isBlank()) continue;
                        boolean isAcronym = false;
                        JsonNode typesNode = nameObj.path("types");
                        if (typesNode.isArray()) {
                            for (JsonNode t : typesNode) {
                                if ("acronym".equalsIgnoreCase(t.asText())) isAcronym = true;
                            }
                        }
                        double score = calculateNameMatchScore(queryNorm, normalizeText(val), isAcronym);
                        double sSim = calculateStringSimilarity(orgName, val);
                        if (score > itemMaxScore) {
                            itemMaxScore = score;
                        }
                        if (sSim > stringSim) {
                            stringSim = sSim;
                        }
                    }
                }

                String[] qTokens = queryNorm.split("\\s+");
                String[] cTokens = normalizeText(canonicalName).split("\\s+");
                double tokenSim = calculateNameSimilarity(qTokens, cTokens, 0.0);
                double combinedSimilarity = Math.max(itemMaxScore, Math.max(tokenSim, stringSim));

                if (combinedSimilarity > bestSimilarity) {
                    bestSimilarity = combinedSimilarity;
                    bestItem = item;
                    bestCanonicalName = canonicalName;
                    bestRorId = rorId;

                    JsonNode locs = item.path("locations");
                    if (locs.isArray() && !locs.isEmpty()) {
                        String rawCountryName = locs.get(0).path("geonames_details").path("country_name").asText("");
                        String rawCountryCode = locs.get(0).path("geonames_details").path("country_code").asText("");
                        String candidateCountry = !rawCountryName.isBlank() ? rawCountryName : rawCountryCode;
                        if (!candidateCountry.isBlank() && countryCatalog != null) {
                            String catalogCountry = resolveModelCountryToCatalog(candidateCountry, countryCatalog);
                            if (catalogCountry.isBlank() && !rawCountryCode.isBlank()) {
                                catalogCountry = resolveAliasCountry(rawCountryCode, countryCatalog);
                            }
                            bestCountry = !catalogCountry.isBlank() ? catalogCountry : candidateCountry;
                        }
                    }
                    JsonNode types = item.path("types");
                    if (types.isArray() && !types.isEmpty()) {
                        rawRorType = types.get(0).asText("");
                    }
                }
            }

            if (bestItem == null || bestCanonicalName.isBlank() || bestSimilarity < 0.70) {
                return null;
            }

            String bestType = "";
            if (typeCatalog != null) {
                TypeInferenceResult typeRes = resolvePreciseEgretaType(orgName, bestCanonicalName, rawRorType, typeCatalog, bestSimilarity, "ror-type:" + bestRorId);
                if (typeRes != null && !typeRes.typeLabel().isBlank()) {
                    bestType = typeRes.typeLabel();
                }
            }
            if (bestType.isBlank() && !rawRorType.isBlank()) {
                bestType = rawRorType;
            }

            double roundedSim = Math.round(bestSimilarity * 100.0) / 100.0;
            return new RorNameProposal(
                orgUuid,
                pureId,
                orgName,
                bestCanonicalName,
                roundedSim,
                bestRorId,
                bestCountry,
                bestType,
                "ror-name-similarity (" + Math.round(roundedSim * 100) + "%)"
            );
        } catch (Exception e) {
            log.debug("Error generating ROR name proposal for '{}': {}", orgName, e.getMessage());
            return null;
        }
    }

    private RorNameProposal findOpenAlexPublicationNameProposal(
            String orgUuid,
            Long pureId,
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!openAlexAuthorSuggestEnabled || orgName == null || orgName.isBlank()) return null;

        RelatedPublicationInfo pubInfo = findRelatedPublicationInfoForOrg(orgUuid, orgName);
        if (pubInfo == null) return null;

        String targetUrl = null;
        String cleanDoi = pubInfo.doi() != null ? pubInfo.doi().trim().replaceAll("^https?://doi\\.org/", "").replaceAll("^doi:", "").trim() : "";
        if (!cleanDoi.isBlank()) {
            targetUrl = "https://api.openalex.org/works/doi:" + URLEncoder.encode(cleanDoi, StandardCharsets.UTF_8);
        } else if (pubInfo.title() != null && !pubInfo.title().isBlank()) {
            String cleanTitle = cleanPublicationTitleForSearch(pubInfo.title());
            if (!cleanTitle.isBlank()) {
                targetUrl = "https://api.openalex.org/works?search=" + URLEncoder.encode(cleanTitle, StandardCharsets.UTF_8) + "&per-page=3";
            }
        }
        if (targetUrl == null) return null;

        if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
            targetUrl += (targetUrl.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(openAlexApiKey.trim(), StandardCharsets.UTF_8);
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, openAlexAuthorSuggestTimeoutMs)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Spring-egreta/1.0 (mailto:egreta@uab.cat)")
                    .GET();

            if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + openAlexApiKey.trim());
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode workNode = root.has("authorships") ? root : root.path("results").path(0);
                if (workNode != null && !workNode.isMissingNode() && workNode.has("authorships")) {
                    JsonNode authorships = workNode.path("authorships");
                    if (authorships.isArray() && !authorships.isEmpty()) {
                        String queryNorm = normalizeText(orgName);
                        String authorNorm = pubInfo.authorName() != null ? normalizeText(pubInfo.authorName()) : "";

                        Map<String, RorCandidateProposal> distinctCandidates = new LinkedHashMap<>();
                        JsonNode bestInst = null;
                        double bestSim = 0.0;
                        String matchedRawAff = "";

                        // Filtrar exclusivament les autories rellevants (l'investigador objectiu o l'afiliació que coincideixi)
                        List<JsonNode> relevantAuthorships = new ArrayList<>();
                        for (JsonNode authorship : authorships) {
                            JsonNode authorObj = authorship.path("author");
                            String authDisplayName = authorObj.path("display_name").asText("");
                            boolean authorMatch = !authorNorm.isBlank() && calculateNameMatchScore(authorNorm, normalizeText(authDisplayName), false) >= 0.60;
                            if (authorMatch) {
                                relevantAuthorships.add(authorship);
                            }
                        }

                        if (relevantAuthorships.isEmpty()) {
                            for (JsonNode authorship : authorships) {
                                boolean hasMatch = false;
                                JsonNode rawAffStrings = authorship.path("raw_affiliation_strings");
                                if (rawAffStrings.isArray()) {
                                    for (JsonNode rNode : rawAffStrings) {
                                        String r = rNode.asText("");
                                        if (!r.isBlank() && (calculateNameMatchScore(queryNorm, normalizeText(r), false) >= 0.55 || calculateStringSimilarity(orgName, r) >= 0.55)) {
                                            hasMatch = true;
                                            break;
                                        }
                                    }
                                }
                                String singleRaw = authorship.path("raw_affiliation_string").asText("");
                                if (!hasMatch && !singleRaw.isBlank() && (calculateNameMatchScore(queryNorm, normalizeText(singleRaw), false) >= 0.55 || calculateStringSimilarity(orgName, singleRaw) >= 0.55)) {
                                    hasMatch = true;
                                }
                                if (!hasMatch) {
                                    JsonNode insts = authorship.path("institutions");
                                    if (insts.isArray()) {
                                        for (JsonNode inst : insts) {
                                            String instName = inst.path("display_name").asText("");
                                            if (!instName.isBlank() && (calculateNameMatchScore(queryNorm, normalizeText(instName), false) >= 0.55 || calculateStringSimilarity(orgName, instName) >= 0.55)) {
                                                hasMatch = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (hasMatch) {
                                    relevantAuthorships.add(authorship);
                                    if (relevantAuthorships.size() >= 2) break;
                                }
                            }
                        }

                        for (JsonNode authorship : relevantAuthorships) {
                            JsonNode authorObj = authorship.path("author");
                            String authDisplayName = authorObj.path("display_name").asText("");
                            boolean authorMatch = !authorNorm.isBlank() && calculateNameMatchScore(authorNorm, normalizeText(authDisplayName), false) >= 0.60;

                            // Recollir totes les cadenes d'afiliació de l'autor
                            List<String> rawAffsForAuthor = new ArrayList<>();
                            JsonNode rawAffStrings = authorship.path("raw_affiliation_strings");
                            if (rawAffStrings.isArray() && !rawAffStrings.isEmpty()) {
                                for (JsonNode rNode : rawAffStrings) {
                                    String rText = rNode.asText("").trim();
                                    if (!rText.isBlank()) rawAffsForAuthor.add(rText);
                                }
                            }
                            String singleRaw = authorship.path("raw_affiliation_string").asText("").trim();
                            if (!singleRaw.isBlank() && !rawAffsForAuthor.contains(singleRaw)) {
                                rawAffsForAuthor.add(singleRaw);
                            }

                            // Processar només les cadenes d'afiliació en brut d'aquest autor (màxim 3)
                            int rawCount = 0;
                            for (String rText : rawAffsForAuthor) {
                                if (rawCount >= 3) break;
                                rawCount++;

                                double rawScore = calculateNameMatchScore(queryNorm, normalizeText(rText), false);
                                double rawSim = calculateStringSimilarity(orgName, rText);
                                double sim = Math.max(rawScore, rawSim);
                                if (authorMatch) sim = Math.max(sim, 0.85);

                                String cleaned = cleanRawAffiliationWithoutCityCountry(rText, countryCatalog);
                                String candidateName = (!cleaned.isBlank()) ? cleaned : rText;
                                String normKey = normalizeText(candidateName);

                                if (!normKey.isBlank() && (!distinctCandidates.containsKey(normKey) || sim > distinctCandidates.get(normKey).similarity())) {
                                    String cCountry = inferCountryFromName(candidateName, countryCatalog).countryLabel();
                                    TypeInferenceResult tRes = resolvePreciseEgretaType(queryNorm, candidateName, "", typeCatalog, sim, "openalex-pub");
                                    String cType = tRes != null ? tRes.typeLabel() : "";
                                    distinctCandidates.put(normKey, new RorCandidateProposal(
                                        candidateName,
                                        Math.round(sim * 100.0) / 100.0,
                                        "",
                                        cCountry,
                                        cType,
                                        "openalex-raw-affiliation",
                                        rText
                                    ));
                                }

                                if (sim > bestSim) {
                                    bestSim = sim;
                                    matchedRawAff = rText;
                                }
                            }

                            // Processar institucions canòniques d'OpenAlex d'aquest autor
                            JsonNode insts = authorship.path("institutions");
                            if (insts.isArray()) {
                                for (JsonNode inst : insts) {
                                    String instDisplayName = inst.path("display_name").asText("").trim();
                                    if (instDisplayName.isBlank()) continue;
                                    String rorId = inst.path("ror").asText("").trim();
                                    String countryCode = inst.path("country_code").asText("").trim();
                                    String instType = inst.path("type").asText("").trim();

                                    double score = calculateNameMatchScore(queryNorm, normalizeText(instDisplayName), false);
                                    double strSim = calculateStringSimilarity(orgName, instDisplayName);
                                    double sim = Math.max(score, strSim);
                                    if (authorMatch) sim = Math.max(sim, 0.85);

                                    String normKey = normalizeText(instDisplayName);
                                    String resolvedCountry = "";
                                    if (!countryCode.isBlank()) {
                                        resolvedCountry = resolveAliasCountry(countryCode.toLowerCase(), countryCatalog);
                                    }
                                    if (resolvedCountry.isBlank()) {
                                        resolvedCountry = inferCountryFromName(instDisplayName, countryCatalog).countryLabel();
                                    }
                                    TypeInferenceResult tRes = resolvePreciseEgretaType(queryNorm, instDisplayName, instType, typeCatalog, sim, "openalex-pub");
                                    String resolvedType = tRes != null ? tRes.typeLabel() : "";

                                    if (!normKey.isBlank() && (!distinctCandidates.containsKey(normKey) || sim > distinctCandidates.get(normKey).similarity())) {
                                        distinctCandidates.put(normKey, new RorCandidateProposal(
                                            instDisplayName,
                                            Math.round(sim * 100.0) / 100.0,
                                            rorId,
                                            resolvedCountry,
                                            resolvedType,
                                            "openalex-institution",
                                            !rawAffsForAuthor.isEmpty() ? rawAffsForAuthor.get(0) : ""
                                        ));
                                    }

                                    if (sim > bestSim) {
                                        bestSim = sim;
                                        bestInst = inst;
                                        if (!rawAffsForAuthor.isEmpty()) {
                                            matchedRawAff = rawAffsForAuthor.get(0);
                                        }
                                    }
                                }
                            }
                        }

                        if ((bestInst != null || !matchedRawAff.isBlank() || !distinctCandidates.isEmpty()) && (bestSim >= 0.65 || !distinctCandidates.isEmpty())) {
                            List<RorCandidateProposal> candidateList = new ArrayList<>(distinctCandidates.values());
                            candidateList.sort(Comparator.comparingDouble(RorCandidateProposal::similarity).reversed());

                            RorCandidateProposal topCandidate = candidateList.isEmpty() ? null : candidateList.get(0);

                            String canonicalName = bestInst != null ? bestInst.path("display_name").asText("") : "";
                            String rorId = topCandidate != null && !topCandidate.rorId().isBlank() ? topCandidate.rorId() : (bestInst != null ? bestInst.path("ror").asText("") : "");
                            String resolvedCountry = topCandidate != null && !topCandidate.country().isBlank() ? topCandidate.country() : "";
                            String resolvedType = topCandidate != null && !topCandidate.type().isBlank() ? topCandidate.type() : "";

                            String rawProposedName = cleanRawAffiliationWithoutCityCountry(matchedRawAff, countryCatalog);
                            String finalSuggestedName = topCandidate != null ? topCandidate.suggestedName() : ((!rawProposedName.isBlank()) ? rawProposedName : canonicalName);

                            double finalSim = topCandidate != null ? topCandidate.similarity() : bestSim;
                            double roundedSim = Math.round(finalSim * 100.0) / 100.0;
                            String workId = workNode.path("id").asText("");
                            String reason = (!workId.isBlank() ? workId : "openalex-publication") + " (" + Math.round(roundedSim * 100) + "%)";
                            return new RorNameProposal(
                                orgUuid,
                                pureId,
                                orgName,
                                finalSuggestedName,
                                roundedSim,
                                rorId,
                                resolvedCountry,
                                resolvedType,
                                reason,
                                matchedRawAff,
                                candidateList
                            );
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error querying OpenAlex publication for name proposal '{}': {}", orgName, e.getMessage());
        }
        return null;
    }

    private RorNameProposal findOpenAlexInstitutionNameProposal(
            String orgUuid,
            Long pureId,
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!openAlexAuthorSuggestEnabled || orgName == null || orgName.isBlank()) return null;

        String cleanName = cleanQueryForRor(orgName);
        if (cleanName.isBlank()) cleanName = orgName.trim();

        try {
            String targetUrl = "https://api.openalex.org/institutions?search=" + URLEncoder.encode(cleanName, StandardCharsets.UTF_8) + "&per-page=5";
            if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
                targetUrl += "&api_key=" + URLEncoder.encode(openAlexApiKey.trim(), StandardCharsets.UTF_8);
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, openAlexAuthorSuggestTimeoutMs)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Spring-egreta/1.0 (mailto:egreta@uab.cat)")
                    .GET();

            if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + openAlexApiKey.trim());
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) return null;

            String queryNorm = normalizeText(orgName);
            String[] qTokens = queryNorm.split("\\s+");

            JsonNode bestInst = null;
            String bestCanonicalName = "";
            double bestSim = 0.0;
            String bestRorId = "";
            String bestCountry = "";
            String bestType = "";

            int maxCheck = Math.min(results.size(), 5);
            for (int i = 0; i < maxCheck; i++) {
                JsonNode inst = results.get(i);
                String displayName = inst.path("display_name").asText("").trim();
                if (displayName.isBlank()) continue;

                String rorId = inst.path("ror").asText("").trim();
                String countryCode = inst.path("country_code").asText("").trim();
                String instType = inst.path("type").asText("").trim();

                double score = calculateNameMatchScore(queryNorm, normalizeText(displayName), false);
                double strSim = calculateStringSimilarity(orgName, displayName);
                String[] cTokens = normalizeText(displayName).split("\\s+");
                double tokSim = calculateNameSimilarity(qTokens, cTokens, 0.0);
                double itemSim = Math.max(score, Math.max(strSim, tokSim));

                // Check alternatives & acronyms
                JsonNode alts = inst.path("display_name_alternatives");
                if (alts.isArray()) {
                    for (JsonNode alt : alts) {
                        String aVal = alt.asText("").trim();
                        if (!aVal.isBlank()) {
                            double aScore = calculateNameMatchScore(queryNorm, normalizeText(aVal), false);
                            double aStrSim = calculateStringSimilarity(orgName, aVal);
                            if (Math.max(aScore, aStrSim) > itemSim) {
                                itemSim = Math.max(aScore, aStrSim);
                            }
                        }
                    }
                }
                JsonNode acrs = inst.path("display_name_acronyms");
                if (acrs.isArray()) {
                    for (JsonNode acr : acrs) {
                        String aVal = acr.asText("").trim();
                        if (!aVal.isBlank()) {
                            double aScore = calculateNameMatchScore(queryNorm, normalizeText(aVal), true);
                            if (aScore > itemSim) {
                                itemSim = aScore;
                            }
                        }
                    }
                }

                if (itemSim > bestSim) {
                    bestSim = itemSim;
                    bestInst = inst;
                    bestCanonicalName = displayName;
                    bestRorId = rorId;

                    String resolvedCountry = "";
                    if (!countryCode.isBlank()) {
                        resolvedCountry = resolveAliasCountry(countryCode.toLowerCase(), countryCatalog);
                    }
                    if (resolvedCountry.isBlank() && !displayName.isBlank()) {
                        resolvedCountry = inferCountryFromName(displayName, countryCatalog).countryLabel();
                    }
                    bestCountry = resolvedCountry;

                    TypeInferenceResult typeRes = resolvePreciseEgretaType(queryNorm, displayName, instType, typeCatalog, itemSim, "openalex-inst");
                    bestType = typeRes != null ? typeRes.typeLabel() : "";
                }
            }

            if (bestInst != null && !bestCanonicalName.isBlank() && bestSim >= 0.70) {
                double roundedSim = Math.round(bestSim * 100.0) / 100.0;
                return new RorNameProposal(
                    orgUuid,
                    pureId,
                    orgName,
                    bestCanonicalName,
                    roundedSim,
                    bestRorId,
                    bestCountry,
                    bestType,
                    "openalex-institution (" + Math.round(roundedSim * 100) + "%)"
                );
            }
        } catch (Exception e) {
            log.debug("Error querying OpenAlex institutions for name proposal '{}': {}", orgName, e.getMessage());
        }

        return null;
    }

    public CompletableFuture<MetadataInferenceResult> inferMetadataWithRorAsync(
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!rorSuggestEnabled || orgName == null || orgName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String cacheKey = normalizeText(orgName);
        CachedMetadataInference cached = rorInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached.result());
        }

        return CompletableFuture.supplyAsync(() -> {
            String body = fetchRorRawJson(orgName);
            if (body != null) {
                MetadataInferenceResult res = parseRorResponse(orgName, body, countryCatalog, typeCatalog);
                if (res != null) {
                    rorInferenceCache.put(cacheKey, new CachedMetadataInference(res, System.currentTimeMillis()));
                }
                return res;
            }
            return null;
        }, aiExecutor != null ? aiExecutor : asyncExecutor);
    }

    public MetadataInferenceResult inferMetadataWithRor(
            String orgName,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!rorSuggestEnabled || orgName == null || orgName.isBlank()) return null;
        try {
            return inferMetadataWithRorAsync(orgName, countryCatalog, typeCatalog)
                    .get(rorSuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("ROR synchronous lookup failed for '{}': {}", orgName, e.getMessage());
            return null;
        }
    }

    private MetadataInferenceResult parseRorResponse(
            String queryOrgName,
            String jsonBody,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (jsonBody == null || jsonBody.isBlank()) return null;

        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return null;

            String queryNorm = normalizeText(queryOrgName);
            if (queryNorm.isBlank()) return null;

            JsonNode bestItem = null;
            double bestScore = 0.0;

            // Evaluate top items from ROR
            int maxInspect = Math.min(items.size(), 5);
            for (int i = 0; i < maxInspect; i++) {
                JsonNode item = items.get(i);
                JsonNode namesNode = item.path("names");
                if (namesNode.isArray()) {
                    for (JsonNode nameObj : namesNode) {
                        String val = nameObj.path("value").asText("");
                        if (val.isBlank()) continue;
                        boolean isAcronym = false;
                        JsonNode typesNode = nameObj.path("types");
                        if (typesNode.isArray()) {
                            for (JsonNode t : typesNode) {
                                if ("acronym".equalsIgnoreCase(t.asText())) {
                                    isAcronym = true;
                                    break;
                                }
                            }
                        }
                        double score = calculateNameMatchScore(queryNorm, normalizeText(val), isAcronym);
                        double strScore = calculateStringSimilarity(queryNorm, normalizeText(val));
                        score = Math.max(score, strScore);
                        if (score > bestScore) {
                            bestScore = score;
                            bestItem = item;
                        }
                    }
                }
            }

            // If best score is below confidence threshold (0.78), do not trust ROR result
            if (bestItem == null || bestScore < 0.78) {
                return null;
            }

            String rorId = bestItem.path("id").asText("");

            // 1. Resolve Country
            InferenceResult countryResult = resolveCountryFromRorItem(bestItem, countryCatalog, bestScore, rorId);

            // 2. Resolve Type
            TypeInferenceResult typeResult = resolveTypeFromRorItem(bestItem, queryOrgName, typeCatalog, bestScore, rorId);

            // 3. Resolve Funding (Public / Private)
            FundingInferenceResult fundingResult = resolveFundingFromRorItem(bestItem, queryOrgName, bestScore, rorId);

            return new MetadataInferenceResult(countryResult, typeResult, fundingResult);
        } catch (Exception e) {
            log.warn("Error parsing ROR response for '{}': {}", queryOrgName, e.getMessage());
            return null;
        }
    }

    private double calculateNameMatchScore(String queryNorm, String candidateNorm, boolean isAcronym) {
        if (queryNorm.isBlank() || candidateNorm.isBlank()) return 0.0;
        if (queryNorm.equals(candidateNorm)) {
            return 1.0;
        }
        if (isAcronym && queryNorm.equalsIgnoreCase(candidateNorm)) {
            return 0.95;
        }

        // Clean punctuation & compare words
        String[] queryTokens = queryNorm.split("\\s+");
        String[] candTokens = candidateNorm.split("\\s+");
        Set<String> querySet = new HashSet<>(Arrays.asList(queryTokens));
        Set<String> candSet = new HashSet<>(Arrays.asList(candTokens));

        // Remove stop words
        Set<String> stopWords = Set.of(
            "de", "del", "la", "el", "les", "los", "las", "d", "l", "i", "y", "and", "of", "the",
            "for", "in", "a", "at", "on", "to", "und", "et", "du", "des", "von", "van", "der", "die", "das"
        );
        querySet.removeAll(stopWords);
        candSet.removeAll(stopWords);

        if (querySet.isEmpty() || candSet.isEmpty()) {
            return 0.0;
        }

        int intersection = 0;
        for (String q : querySet) {
            if (candSet.contains(q)) intersection++;
        }

        if (intersection == 0) return 0.0;

        double precision = (double) intersection / candSet.size();
        double recall = (double) intersection / querySet.size();
        double f1 = (2.0 * precision * recall) / (precision + recall);

        // Substring check only if high token coverage and significant length ratio
        int maxLen = Math.max(queryNorm.length(), candidateNorm.length());
        double lenRatio = (double) Math.min(queryNorm.length(), candidateNorm.length()) / maxLen;

        if ((candidateNorm.contains(queryNorm) || queryNorm.contains(candidateNorm)) && lenRatio >= 0.65 && f1 >= 0.70) {
            return Math.max(f1, 0.90);
        }

        return f1;
    }

    private InferenceResult resolveCountryFromRorItem(
            JsonNode item,
            List<CountryAggregate> countryCatalog,
            double matchScore,
            String rorId) {
        JsonNode locations = item.path("locations");
        if (locations.isArray() && !locations.isEmpty()) {
            JsonNode loc = locations.get(0).path("geonames_details");
            String countryName = loc.path("country_name").asText("").trim();
            String countryCode = loc.path("country_code").asText("").trim();

            String resolved = "";
            if (!countryCode.isBlank()) {
                resolved = resolveAliasCountry(countryCode.toLowerCase(), countryCatalog);
            }
            if (resolved.isBlank() && !countryName.isBlank()) {
                resolved = resolveAliasCountry(countryName.toLowerCase(), countryCatalog);
            }
            if (resolved.isBlank() && !countryName.isBlank()) {
                String target = normalizeText(countryName);
                resolved = countryCatalog.stream()
                        .filter(c -> c.normalizedLabel.equals(target))
                        .map(CountryAggregate::label)
                        .findFirst()
                        .orElse("");
            }

            if (!resolved.isBlank()) {
                double confidence = Math.min(0.99, matchScore * 0.98);
                String reason = "ror-match:" + (rorId.isBlank() ? "found" : rorId);
                return new InferenceResult(resolved, confidence, reason);
            }
        }
        return new InferenceResult("", 0.0, "no-ror-country");
    }

    private TypeInferenceResult resolveTypeFromRorItem(
            JsonNode item,
            String queryOrgName,
            List<TypeAggregate> typeCatalog,
            double matchScore,
            String rorId) {
        JsonNode typesNode = item.path("types");
        String rorType = "";
        if (typesNode.isArray() && !typesNode.isEmpty()) {
            rorType = typesNode.get(0).asText("").toLowerCase().trim();
        }

        String canonicalName = "";
        JsonNode namesNode = item.path("names");
        if (namesNode.isArray() && !namesNode.isEmpty()) {
            canonicalName = namesNode.get(0).path("value").asText("");
        }
        if (canonicalName.isBlank()) {
            canonicalName = item.path("name").asText("");
        }

        return resolvePreciseEgretaType(queryOrgName, canonicalName, rorType, typeCatalog, matchScore, "ror-type:" + (rorId.isBlank() ? "found" : rorId));
    }

    private TypeInferenceResult resolvePreciseEgretaType(
            String orgName,
            String canonicalName,
            String broadType,
            List<TypeAggregate> typeCatalog,
            double baseConfidence,
            String reasonPrefix) {

        String combined = (normalizeText(orgName) + " " + normalizeText(canonicalName)).trim();
        if (combined.isBlank() || typeCatalog.isEmpty()) {
            return new TypeInferenceResult("", 0.0, "no-signal");
        }

        // 1. Departament universitari
        if (matchesAnyKeyword(combined, "department", "departament", "departamento", "dept", "seccio", "seccion", "section", "division", "catedra", "chair", "faculty", "facultat", "facultad", "scola", "school of", "college of", "escola")) {
            String resolved = resolveTypeByHint(List.of("departament universitari", "departament", "departamento"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":department-structure");
            }
        }

        // 2. Institut Universitari d'investigació
        if (matchesAnyKeyword(combined, "institut universitari", "instituto universitario", "university institute", "instituts universitaris de recerca")) {
            String resolved = resolveTypeByHint(List.of("institut universitari d'investigacio", "institut universitari", "instituto universitario"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":university-institute");
            }
        }

        // 3. CIBER
        if (matchesAnyKeyword(combined, "ciber", "ciber-bbn", "ciberehd", "ciberes", "ciberned", "ciberobn", "ciberon", "ciberer", "ciberesp", "cibermed", "cibersam")) {
            String resolved = resolveTypeByHint(List.of("ciber"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.99, Math.max(0.95, baseConfidence * 0.99)), reasonPrefix + ":ciber");
            }
        }

        // 4. Entitat gestora del sistema nacional de salut
        if (matchesAnyKeyword(combined, "catsalut", "servei catala de la salut", "servicio de salud", "sergas", "sacyl", "sescam", "osakidetza", "sms", "sas", "ib-salut", "ibsalut", "sistema nacional de salud", "servei de salut")) {
            String resolved = resolveTypeByHint(List.of("entitat gestora del sistema nacional de salut", "gestora del sistema nacional de salut", "sistema nacional de salut"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":health-system-admin");
            }
        }

        // 5. Institucions sanitàries (Hospitals, clíniques, etc.)
        if (matchesAnyKeyword(combined, "hospital", "clinic", "clinica", "sanitari", "sanitaria", "healthcare", "medical center", "centre medic", "assistencial", "policlinica", "hospital universitari", "nhs trust", "centre d'atencio primaria", "cap", "infirmary", "dispensari", "mutua")
                || "healthcare".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("institucions sanitaries", "instituciones sanitarias", "sanitari", "hospital"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":healthcare");
            }
        }

        // 6. Organisme públic d'investigació (OPI)
        if (matchesAnyKeyword(combined, "csic", "cnrs", "max planck", "inserm", "inria", "inrae", "helmholtz", "fraunhofer", "conicet", "cnpq", "conacyt", "iisc", "organisme public d'investigacio", "organismo publico de investigacion", "national research council", "consejo superior de investigaciones cientificas", "institut nacional d'investigacio", "instituto nacional de investigacion")) {
            String resolved = resolveTypeByHint(List.of("organisme public d'investigacio", "organismo publico de investigacion", "recerca"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":opi");
            }
        }

        // 7. Centre tecnològic / Centres d'innovació i tecnologia
        if (matchesAnyKeyword(combined, "centre tecnologic", "centro tecnologico", "technology center", "technological centre", "eurecat", "leitat", "tecnalia", "aimen", "itene", "cidaut", "parc tecnologic", "parc cientific", "research park", "innovacio i tecnologia")) {
            String resolved = resolveTypeByHint(List.of("centre tecnologic", "centres d'innovacio i tecnologia", "centro tecnologico"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":tech-center");
            }
        }

        // 8. Centre R+D
        if (matchesAnyKeyword(combined, "centre de recerca", "centro de investigacion", "research centre", "research center", "centre r+d", "centro i+d", "r&d centre", "r&d center", "laboratori de recerca", "laboratorio de investigacion")
                || "facility".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("centre r+d", "centre de recerca", "organismes publics d'investigacio", "recerca"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.95, Math.max(0.88, baseConfidence * 0.95)), reasonPrefix + ":rd-center");
            }
        }

        // 9. Fundació
        if (matchesAnyKeyword(combined, "fundacio", "fundacion", "foundation", "stiftung", "fondation", "fondazione")) {
            String resolved = resolveTypeByHint(List.of("fundacio", "fundacion", "foundation"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":foundation");
            }
        }

        // 10. Associacions i agrupacions
        if (matchesAnyKeyword(combined, "associacio", "asociacion", "association", "societat", "sociedad", "society", "agrupacio", "agrupacion", "col legi", "colegio oficial", "collegi oficial", "federacio", "federacion", "federation", "e.v.", "e v", "eingetragener verein", "verein", "gremi", "gremio", "guild", "consell", "council", "academy", "academia", "akademie")
                || "nonprofit".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("associacions i agrupacions", "associacio", "asociaciones y agrupaciones", "fundacio"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.95, Math.max(0.88, baseConfidence * 0.95)), reasonPrefix + ":association");
            }
        }

        // 11. Administració internacional
        if (matchesAnyKeyword(combined, "onu", "unesco", "who", "oms", "comissio europea", "european commission", "world bank", "banc mundial", "unicef", "oecd", "ocde", "nato", "otant", "united nations", "naciones unidas", "nacions unides", "fao", "ilo", "oit")) {
            String resolved = resolveTypeByHint(List.of("administracio internacional", "administracion internacional", "international administration"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.98, Math.max(0.92, baseConfidence * 0.98)), reasonPrefix + ":intl-admin");
            }
        }

        // 12. Agència estatal
        if (matchesAnyKeyword(combined, "agencia estatal", "agencia de", "state agency", "aemet", "aemps", "aei", "agencia nacional")) {
            String resolved = resolveTypeByHint(List.of("agencia estatal", "state agency"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":state-agency");
            }
        }

        // 13. Sector administració pública
        if (matchesAnyKeyword(combined, "ministeri", "ministerio", "ministry", "generalitat", "govern", "gobierno", "government", "ajuntament", "ayuntamiento", "diputacio", "diputacion", "conselleria", "consejeria", "consell comarcal", "alcaldia", "secretaria d'estat", "secretaria de estado", "department of state", "departament de la generalitat")
                || "government".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("sector administracio publica", "administracio publica", "administracion publica"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":gov-admin");
            }
        }

        // 14. Entitat empresarial (Empreses privades)
        if (matchesAnyKeyword(combined, "s l", "s a", "slu", "sau", "slp", "ltd", "llc", "inc", "corp", "corporation", "gmbh", "srl", "spa", "kgaa", "sas", "sarl", "bv", "nv", "plc", "company", "empresa", "pharma", "pharmaceuticals", "laboratories", "laboratorios", "therapeutics", "biotech", "consulting", "solutions", "s.l.", "s.a.")
                || "company".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("entitat empresarial", "empresa", "enterprise"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":company");
            }
        }

        // 15. Universitat (només si no era departament o institut universitari)
        if (matchesAnyKeyword(combined, "universitat", "universidad", "university", "universite", "universitaet", "universita", "universitad", "college", "hochschule", "faculdade", "polytechnic", "politecnica")
                || "education".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("universitat", "universidad", "university"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":university");
            }
        }

        return new TypeInferenceResult("", 0.0, "no-signal");
    }

    private boolean matchesAnyKeyword(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null) return false;
        String norm = normalizeText(text);
        for (String kw : keywords) {
            if (containsWord(norm, normalizeText(kw))) {
                return true;
            }
        }
        return false;
    }

    private FundingInferenceResult resolveFundingFromRorItem(
            JsonNode item,
            String orgName,
            double matchScore,
            String rorId) {
        JsonNode typesNode = item.path("types");
        List<String> rorTypes = new ArrayList<>();
        if (typesNode.isArray()) {
            for (JsonNode t : typesNode) {
                rorTypes.add(t.asText().toLowerCase().trim());
            }
        }

        String reasonSuffix = rorId.isBlank() ? "" : ":" + rorId;

        if (rorTypes.contains("government")) {
            return new FundingInferenceResult("Publica", Math.min(0.98, Math.max(0.92, matchScore * 0.98)), "ror-government" + reasonSuffix);
        }
        if (rorTypes.contains("company")) {
            return new FundingInferenceResult("Privada", Math.min(0.98, Math.max(0.92, matchScore * 0.98)), "ror-company" + reasonSuffix);
        }
        if (rorTypes.contains("nonprofit")) {
            return new FundingInferenceResult("Privada", Math.min(0.92, Math.max(0.85, matchScore * 0.92)), "ror-nonprofit" + reasonSuffix);
        }
        if (rorTypes.contains("healthcare")) {
            String norm = normalizeText(orgName);
            if (norm.contains("privat") || norm.contains("privado") || norm.contains("private")
                    || norm.contains("clinica") || norm.contains("quiron") || norm.contains("dexeus")) {
                return new FundingInferenceResult("Privada", Math.min(0.95, Math.max(0.88, matchScore * 0.95)), "ror-healthcare-private" + reasonSuffix);
            }
            return new FundingInferenceResult("Publica", Math.min(0.95, Math.max(0.90, matchScore * 0.95)), "ror-healthcare-public" + reasonSuffix);
        }
        if (rorTypes.contains("facility") || rorTypes.contains("archive")) {
            return new FundingInferenceResult("Publica", Math.min(0.95, Math.max(0.90, matchScore * 0.95)), "ror-facility" + reasonSuffix);
        }
        if (rorTypes.contains("education")) {
            String norm = normalizeText(orgName);
            if (norm.contains("publica") || norm.contains("nacional") || norm.contains("autonoma")
                    || norm.contains("estatal") || norm.contains("state") || norm.contains("politecnica")
                    || norm.contains("ub") || norm.contains("uab") || norm.contains("upc") || norm.contains("upf")) {
                return new FundingInferenceResult("Publica", Math.min(0.98, Math.max(0.94, matchScore * 0.98)), "ror-education-public" + reasonSuffix);
            }
            if (norm.contains("privada") || norm.contains("private") || norm.contains("catholic")
                    || norm.contains("catolica") || norm.contains("esade") || norm.contains("ie university")
                    || norm.contains("uoc") || norm.contains("ramon llull") || norm.contains("vic")) {
                return new FundingInferenceResult("Privada", Math.min(0.96, Math.max(0.92, matchScore * 0.96)), "ror-education-private" + reasonSuffix);
            }
            // Default for universities/education recognized by ROR
            return new FundingInferenceResult("Publica", Math.min(0.94, Math.max(0.90, matchScore * 0.94)), "ror-education" + reasonSuffix);
        }

        // Fallback to name heuristic
        return inferFundingFromName(orgName);
    }

    // =========================================================================
    // OpenAlex Author Affiliation & Institution Metadata Inference
    // =========================================================================

    private String cleanAuthorName(String rawAuthor) {
        if (rawAuthor == null || rawAuthor.isBlank()) return "";
        return rawAuthor.replaceAll("\\s*\\(Persona\\s+externa\\)\\s*", "")
                        .replaceAll("\\s*\\(Persona\\s+interna\\)\\s*", "")
                        .replaceAll("\\s*\\(Investigador/a\\)\\s*", "")
                        .replaceAll("\\s*\\([^)]*\\)\\s*", "")
                        .trim();
    }

    public CompletableFuture<MetadataInferenceResult> inferMetadataWithOpenAlexAuthorAsync(
            String orgName,
            String associatedPerson,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        if (!openAlexAuthorSuggestEnabled || associatedPerson == null || associatedPerson.isBlank() || orgName == null || orgName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String authorName = cleanAuthorName(associatedPerson);
        if (authorName.isBlank() || authorName.length() < 3) {
            return CompletableFuture.completedFuture(null);
        }

        String cacheKey = "openalex:" + normalizeText(orgName) + ":" + normalizeText(authorName);
        CachedMetadataInference cached = openAlexInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached.result());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String encoded = URLEncoder.encode(authorName, StandardCharsets.UTF_8);
                String targetUrl = openAlexAuthorSuggestUrl + "?search=" + encoded;

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofMillis(Math.max(1000, openAlexAuthorSuggestTimeoutMs)))
                        .header("Accept", "application/json")
                        .header("User-Agent", "Spring-egreta/1.0 (mailto:egreta@uab.cat)")
                        .GET();

                if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + openAlexApiKey.trim());
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    MetadataInferenceResult res = parseOpenAlexAuthorResponse(orgName, authorName, resp.body(), countryCatalog, typeCatalog);
                    if (res != null) {
                        openAlexInferenceCache.put(cacheKey, new CachedMetadataInference(res, System.currentTimeMillis()));
                    }
                    return res;
                } else if (resp.statusCode() == 429) {
                    log.warn("OpenAlex Author API quota exhaurida o límit assolit (HTTP 429 Too Many Requests). Configureu 'app.openalex.api-key' per obtenir quota de 100k/dia.");
                }
            } catch (Exception e) {
                log.debug("OpenAlex Author API lookup error for '{}' (author '{}'): {}", orgName, authorName, e.getMessage());
            }
            return null;
        }, aiExecutor != null ? aiExecutor : asyncExecutor);
    }

    public MetadataInferenceResult inferMetadataWithOpenAlexAuthor(
            String orgName,
            String associatedPerson,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!openAlexAuthorSuggestEnabled || associatedPerson == null || associatedPerson.isBlank() || orgName == null || orgName.isBlank()) return null;
        try {
            return inferMetadataWithOpenAlexAuthorAsync(orgName, associatedPerson, countryCatalog, typeCatalog)
                    .get(openAlexAuthorSuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("OpenAlex synchronous lookup failed for '{}': {}", orgName, e.getMessage());
            return null;
        }
    }

    private MetadataInferenceResult parseOpenAlexAuthorResponse(
            String queryOrgName,
            String authorName,
            String jsonBody,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (jsonBody == null || jsonBody.isBlank()) return null;

        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) return null;

            String queryNorm = normalizeText(queryOrgName);
            if (queryNorm.isBlank()) return null;

            JsonNode bestInstitution = null;
            String bestAuthorId = null;
            double bestScore = 0.0;

            // Inspect top authors returned (up to 3)
            int maxAuthors = Math.min(results.size(), 3);
            for (int i = 0; i < maxAuthors; i++) {
                JsonNode authorNode = results.get(i);
                String authorId = authorNode.path("id").asText("");

                List<JsonNode> candidateInsts = new ArrayList<>();
                // Check last_known_institutions
                JsonNode lastKnown = authorNode.path("last_known_institutions");
                if (lastKnown.isArray()) {
                    for (JsonNode inst : lastKnown) candidateInsts.add(inst);
                }
                // Check affiliations[].institution
                JsonNode affiliations = authorNode.path("affiliations");
                if (affiliations.isArray()) {
                    for (JsonNode aff : affiliations) {
                        JsonNode inst = aff.path("institution");
                        if (!inst.isMissingNode() && !inst.isNull()) {
                            candidateInsts.add(inst);
                        }
                    }
                }

                for (JsonNode inst : candidateInsts) {
                    String instDisplayName = inst.path("display_name").asText("");
                    if (instDisplayName.isBlank()) continue;

                    double score = calculateNameMatchScore(queryNorm, normalizeText(instDisplayName), false);
                    if (score > bestScore) {
                        bestScore = score;
                        bestInstitution = inst;
                        bestAuthorId = authorId;
                    }
                }
            }

            // Name assimilation verification: must satisfy threshold >= 0.65
            if (bestInstitution == null || bestScore < 0.65) {
                // If not matched at top institution level, check recent works of top author for raw affiliation string matching
                for (int i = 0; i < maxAuthors; i++) {
                    String aId = results.get(i).path("id").asText("");
                    if (!aId.isBlank()) {
                        MetadataInferenceResult workRes = inferMetadataFromAuthorWorks(queryOrgName, aId, countryCatalog, typeCatalog);
                        if (workRes != null) {
                            return workRes;
                        }
                    }
                }
                return null;
            }

            String instId = bestInstitution.path("id").asText("");
            String instRor = bestInstitution.path("ror").asText("");
            String instDisplayName = bestInstitution.path("display_name").asText("");
            String instCountryCode = bestInstitution.path("country_code").asText("");
            String instType = bestInstitution.path("type").asText("");

            String reasonRef = (!instId.isBlank() ? instId : (bestAuthorId != null ? bestAuthorId : "openalex"));

            // 1. Country resolution
            String resolvedCountry = "";
            if (!instCountryCode.isBlank()) {
                resolvedCountry = resolveAliasCountry(instCountryCode.toLowerCase(), countryCatalog);
            }
            if (resolvedCountry.isBlank() && !instDisplayName.isBlank()) {
                resolvedCountry = inferCountryFromName(instDisplayName, countryCatalog).countryLabel();
            }
            InferenceResult countryResult = null;
            if (!resolvedCountry.isBlank()) {
                double conf = Math.min(0.95, Math.max(0.88, bestScore * 0.95));
                countryResult = new InferenceResult(resolvedCountry, conf, "openalex-author:" + reasonRef);
            }

            // 2. Type resolution
            String resolvedType = "";
            if (!instType.isBlank()) {
                List<String> hints = switch (instType.toLowerCase()) {
                    case "education" -> List.of("universitat", "universidad", "ensenyament superior", "educacio", "academic");
                    case "facility" -> List.of("centre de recerca", "instituts universitaris de recerca", "investigacio", "cientific", "recerca");
                    case "company" -> List.of("empresa", "sector empresarial", "companyia", "corporacio");
                    case "government" -> List.of("administracio publica", "organisme public", "entitats publiques", "govern");
                    case "nonprofit" -> List.of("fundacio", "associacio", "entitats sense anim de lucre", "ong");
                    case "healthcare" -> List.of("hospital", "institucions sanitaries", "sanitari", "salut");
                    case "funder" -> List.of("agencia de financament", "entitat financadora");
                    case "archive" -> List.of("arxiu", "biblioteca", "museu");
                    default -> List.of();
                };
                if (!hints.isEmpty()) {
                    resolvedType = resolveTypeByHint(hints, typeCatalog);
                }
            }
            if (resolvedType.isBlank() && !instDisplayName.isBlank()) {
                TypeInferenceResult inferredType = inferTypeFromName(instDisplayName, typeCatalog);
                if (inferredType != null && !inferredType.typeLabel().isBlank()) {
                    resolvedType = inferredType.typeLabel();
                }
            }
            TypeInferenceResult typeResult = null;
            if (!resolvedType.isBlank()) {
                double conf = Math.min(0.95, Math.max(0.88, bestScore * 0.95));
                typeResult = new TypeInferenceResult(resolvedType, conf, "openalex-author:" + reasonRef);
            }

            // 3. Funding resolution
            FundingInferenceResult fundingResult = null;
            String typeLower = instType.toLowerCase();
            if (typeLower.equals("education") || typeLower.equals("government")) {
                fundingResult = new FundingInferenceResult("Publica", Math.min(0.95, Math.max(0.90, bestScore * 0.95)), "openalex-author:" + reasonRef);
            } else if (typeLower.equals("company")) {
                fundingResult = new FundingInferenceResult("Privada", Math.min(0.95, Math.max(0.90, bestScore * 0.95)), "openalex-author:" + reasonRef);
            } else if (typeLower.equals("nonprofit")) {
                fundingResult = new FundingInferenceResult("Privada", Math.min(0.90, Math.max(0.85, bestScore * 0.90)), "openalex-author:" + reasonRef);
            } else if (typeLower.equals("healthcare")) {
                String norm = normalizeText(instDisplayName);
                if (norm.contains("privat") || norm.contains("privado") || norm.contains("private")
                        || norm.contains("clinica") || norm.contains("quiron") || norm.contains("dexeus")) {
                    fundingResult = new FundingInferenceResult("Privada", 0.90, "openalex-healthcare-private:" + reasonRef);
                } else {
                    fundingResult = new FundingInferenceResult("Publica", 0.90, "openalex-healthcare-public:" + reasonRef);
                }
            } else {
                fundingResult = inferFundingFromName(instDisplayName);
                if (fundingResult != null && !fundingResult.fundingLabel().isBlank()) {
                    fundingResult = new FundingInferenceResult(fundingResult.fundingLabel(), Math.min(0.90, Math.max(0.85, bestScore * 0.90)), "openalex-author:" + reasonRef);
                }
            }

            return (countryResult == null && typeResult == null && fundingResult == null) ? null
                    : new MetadataInferenceResult(countryResult, typeResult, fundingResult);

        } catch (Exception e) {
            log.warn("Error parsing OpenAlex response for '{}': {}", queryOrgName, e.getMessage());
            return null;
        }
    }

    private MetadataInferenceResult inferMetadataFromAuthorWorks(
            String queryOrgName,
            String authorId,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (authorId == null || authorId.isBlank() || queryOrgName == null || queryOrgName.isBlank()) return null;

        String cleanId = authorId.replaceAll("^https?://openalex\\.org/", "").trim();
        if (cleanId.isBlank()) return null;

        try {
            String worksUrl = "https://api.openalex.org/works?filter=author.id:" + cleanId + "&sort=publication_year:desc&per_page=10";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(worksUrl))
                    .timeout(Duration.ofMillis(Math.max(1000, openAlexAuthorSuggestTimeoutMs)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Spring-egreta/1.0 (mailto:egreta@uab.cat)")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) return null;

            String queryNorm = normalizeText(queryOrgName);
            String[] rawTokens = queryNorm.split("\\s+");
            Set<String> stopWords = Set.of(
                "de", "del", "la", "el", "les", "los", "las", "d", "l", "i", "y", "and", "of", "the",
                "for", "in", "a", "at", "on", "to", "department", "dept", "departament", "departamento", "platform", "unit", "service", "center", "centre"
            );
            List<String> contentTokens = Arrays.stream(rawTokens)
                    .filter(t -> !stopWords.contains(t) && t.length() > 2)
                    .toList();

            if (contentTokens.isEmpty()) return null;

            for (JsonNode work : results) {
                String workId = work.path("id").asText("");
                JsonNode authorships = work.path("authorships");
                if (!authorships.isArray()) continue;

                for (JsonNode authorship : authorships) {
                    JsonNode authorObj = authorship.path("author");
                    String authId = authorObj.path("id").asText("");
                    if (!authId.endsWith(cleanId)) continue;

                    JsonNode rawAffStrings = authorship.path("raw_affiliation_strings");
                    if (!rawAffStrings.isArray()) continue;

                    for (JsonNode rawNode : rawAffStrings) {
                        String rawAff = rawNode.asText("");
                        if (rawAff.isBlank()) continue;

                        String rawNorm = normalizeText(rawAff);
                        String[] rawWords = rawNorm.split("\\s+");
                        Set<String> rawWordSet = new HashSet<>(Arrays.asList(rawWords));

                        int matchedTokens = 0;
                        for (String ct : contentTokens) {
                            if (rawWordSet.contains(ct)) {
                                matchedTokens++;
                            } else {
                                boolean partial = rawWordSet.stream().anyMatch(rw -> 
                                    rw.startsWith(ct) || ct.startsWith(rw) || (ct.length() > 4 && rw.contains(ct))
                                );
                                if (partial) matchedTokens++;
                            }
                        }

                        double matchRatio = (double) matchedTokens / contentTokens.size();
                        if (matchRatio >= 0.70) {
                            // Match found in work affiliation!
                            JsonNode insts = authorship.path("institutions");
                            JsonNode bestInst = (insts.isArray() && !insts.isEmpty()) ? insts.get(0) : null;

                            String instDisplayName = bestInst != null ? bestInst.path("display_name").asText("") : "";
                            String instCountryCode = bestInst != null ? bestInst.path("country_code").asText("") : "";
                            String instType = bestInst != null ? bestInst.path("type").asText("") : "";
                            String instId = bestInst != null ? bestInst.path("id").asText("") : "";

                            String reasonRef = (!workId.isBlank() ? workId : (!instId.isBlank() ? instId : "openalex-work"));

                            // 1. Resolve country
                            String resolvedCountry = "";
                            if (!instCountryCode.isBlank()) {
                                resolvedCountry = resolveAliasCountry(instCountryCode.toLowerCase(), countryCatalog);
                            }
                            if (resolvedCountry.isBlank() && !instDisplayName.isBlank()) {
                                resolvedCountry = inferCountryFromName(instDisplayName, countryCatalog).countryLabel();
                            }
                            if (resolvedCountry.isBlank()) {
                                resolvedCountry = inferCountryFromName(rawAff, countryCatalog).countryLabel();
                            }
                            InferenceResult countryResult = null;
                            if (!resolvedCountry.isBlank()) {
                                countryResult = new InferenceResult(resolvedCountry, 0.92, "openalex-work:" + reasonRef);
                            }

                            // 2. Resolve type
                            String resolvedType = "";
                            if (!instType.isBlank()) {
                                List<String> hints = switch (instType.toLowerCase()) {
                                    case "education" -> List.of("universitat", "universidad", "ensenyament superior", "educacio", "academic");
                                    case "facility" -> List.of("centre de recerca", "instituts universitaris de recerca", "investigacio", "cientific", "recerca");
                                    case "company" -> List.of("empresa", "sector empresarial", "companyia", "corporacio");
                                    case "government" -> List.of("administracio publica", "organisme public", "entitats publiques", "govern");
                                    case "nonprofit" -> List.of("fundacio", "associacio", "entitats sense anim de lucre", "ong");
                                    case "healthcare" -> List.of("hospital", "institucions sanitaries", "sanitari", "salut");
                                    case "funder" -> List.of("agencia de financament", "entitat financadora");
                                    case "archive" -> List.of("arxiu", "biblioteca", "museu");
                                    default -> List.of();
                                };
                                if (!hints.isEmpty()) {
                                    resolvedType = resolveTypeByHint(hints, typeCatalog);
                                }
                            }
                            if (resolvedType.isBlank() && !instDisplayName.isBlank()) {
                                TypeInferenceResult inferredType = inferTypeFromName(instDisplayName, typeCatalog);
                                if (inferredType != null && !inferredType.typeLabel().isBlank()) {
                                    resolvedType = inferredType.typeLabel();
                                }
                            }
                            TypeInferenceResult typeResult = null;
                            if (!resolvedType.isBlank()) {
                                typeResult = new TypeInferenceResult(resolvedType, 0.92, "openalex-work:" + reasonRef);
                            }

                            // 3. Resolve funding
                            FundingInferenceResult fundingResult = null;
                            String typeLower = instType.toLowerCase();
                            if (typeLower.equals("education") || typeLower.equals("government")) {
                                fundingResult = new FundingInferenceResult("Publica", 0.92, "openalex-work:" + reasonRef);
                            } else if (typeLower.equals("company")) {
                                fundingResult = new FundingInferenceResult("Privada", 0.92, "openalex-work:" + reasonRef);
                            } else if (typeLower.equals("nonprofit")) {
                                fundingResult = new FundingInferenceResult("Privada", 0.90, "openalex-work:" + reasonRef);
                            } else if (typeLower.equals("healthcare")) {
                                String norm = normalizeText(instDisplayName + " " + rawAff);
                                if (norm.contains("privat") || norm.contains("privado") || norm.contains("private")
                                        || norm.contains("clinica") || norm.contains("quiron") || norm.contains("dexeus")) {
                                    fundingResult = new FundingInferenceResult("Privada", 0.90, "openalex-healthcare-private:" + reasonRef);
                                } else {
                                    fundingResult = new FundingInferenceResult("Publica", 0.90, "openalex-healthcare-public:" + reasonRef);
                                }
                            } else if (!instDisplayName.isBlank()) {
                                fundingResult = inferFundingFromName(instDisplayName);
                                if (fundingResult != null && !fundingResult.fundingLabel().isBlank()) {
                                    fundingResult = new FundingInferenceResult(fundingResult.fundingLabel(), 0.90, "openalex-work:" + reasonRef);
                                }
                            }

                            if (countryResult != null || typeResult != null || fundingResult != null) {
                                return new MetadataInferenceResult(countryResult, typeResult, fundingResult);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.debug("Error inspecting author works on OpenAlex for '{}': {}", authorId, e.getMessage());
        }

        return null;
    }

    // =========================================================================
    // OpenAlex Related Publication (DOI / Title) Metadata Inference
    // =========================================================================

    public record RelatedPublicationInfo(String doi, String title, String authorName) {}
    private final Map<String, RelatedPublicationInfo> relatedPublicationCache = new ConcurrentHashMap<>();

    private RelatedPublicationInfo findRelatedPublicationInfoForOrg(String orgUuid, String orgName) {
        String cacheKey = (orgUuid != null && !orgUuid.isBlank()) ? orgUuid.trim() : (orgName != null ? orgName.trim() : "");
        if (cacheKey.isBlank()) return null;

        RelatedPublicationInfo cached = relatedPublicationCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String targetUuid = (orgUuid != null && !orgUuid.isBlank()) ? orgUuid.trim() : null;
            if (targetUuid == null && orgName != null && !orgName.isBlank()) {
                String escaped = Pattern.quote(orgName.trim());
                Criteria nameCriteria = new Criteria().orOperator(
                    Criteria.where("name.ca_ES").regex("^" + escaped + "$", "i"),
                    Criteria.where("name.es_ES").regex("^" + escaped + "$", "i"),
                    Criteria.where("name.en_GB").regex("^" + escaped + "$", "i")
                );
                Query q = new Query(nameCriteria);
                q.fields().include("uuid");
                ExternalOrganization found = mongoTemplate.findOne(q, ExternalOrganization.class);
                if (found != null && found.getUuid() != null) {
                    targetUuid = found.getUuid();
                }
            }

            if (targetUuid != null && !targetUuid.isBlank() && mongoTemplate.collectionExists("Researchoutputs")) {
                Query q = new Query(new Criteria().orOperator(
                    Criteria.where("contributors.externalOrganizations.uuid").is(targetUuid),
                    Criteria.where("contributors.affiliations.externalOrganization.uuid").is(targetUuid),
                    Criteria.where("contributors.affiliations.uuid").is(targetUuid),
                    Criteria.where("contributors.externalOrganization.uuid").is(targetUuid),
                    Criteria.where("contributors.externalPositions.externalOrganization.uuid").is(targetUuid),
                    Criteria.where("externalOrganizations.uuid").is(targetUuid)
                ));
                q.fields().include("title", "electronicVersions", "contributors");
                q.limit(1);
                Document doc = mongoTemplate.findOne(q, Document.class, "Researchoutputs");
                if (doc != null) {
                    String title = "";
                    Object titleObj = doc.get("title");
                    if (titleObj instanceof Document tDoc) {
                        title = tDoc.getString("value");
                        if (title == null || title.isBlank()) title = tDoc.getString("en_GB");
                        if (title == null || title.isBlank()) title = tDoc.getString("es_ES");
                        if (title == null || title.isBlank()) title = tDoc.getString("ca_ES");
                    } else if (titleObj instanceof String s) {
                        title = s;
                    }

                    String doi = null;
                    Object evRaw = doc.get("electronicVersions");
                    if (evRaw instanceof List<?> evList) {
                        for (Object item : evList) {
                            if (item instanceof Document evDoc) {
                                String d = evDoc.getString("doi");
                                if (d != null && !d.isBlank()) { doi = d.trim(); break; }
                            }
                        }
                    }

                    String authorName = null;
                    List<?> contributors = doc.get("contributors", List.class);
                    if (contributors != null) {
                        for (Object item : contributors) {
                            if (item instanceof Document cDoc) {
                                if (contributorMatchesOrgUuid(cDoc, targetUuid)) {
                                    Document nameDoc = cDoc.get("name", Document.class);
                                    if (nameDoc != null) {
                                        String first = nameDoc.getString("firstName");
                                        String last = nameDoc.getString("lastName");
                                        authorName = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
                                        if (!authorName.isBlank()) break;
                                    }
                                }
                            }
                        }
                    }

                    if (authorName == null || authorName.isBlank()) {
                        authorName = findAssociatedPersonForOrg(targetUuid, orgName);
                    }

                    if ((doi != null && !doi.isBlank()) || (title != null && !title.isBlank())) {
                        RelatedPublicationInfo info = new RelatedPublicationInfo(doi, title, authorName);
                        relatedPublicationCache.put(cacheKey, info);
                        return info;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error finding related publication for org '{}': {}", orgUuid, e.getMessage());
        }

        return null;
    }

    private String cleanPublicationTitleForSearch(String title) {
        if (title == null || title.isBlank()) return "";
        // Treure caràcters especials, símbols i puntuació que bloquegen Elasticsearch d'OpenAlex
        String cleaned = title.replaceAll("[®™©\"'„“”«»\\[\\]{}()<>:;,/\\\\|*+~?!#^]", " ")
                              .replaceAll("\\s+", " ").trim();
        // Limitar a les primeres 8 paraules per evitar queries massa àmplies / gateway timeout
        String[] words = cleaned.split("\\s+");
        if (words.length > 8) {
            cleaned = Arrays.stream(words).limit(8).collect(Collectors.joining(" "));
        }
        return cleaned;
    }

    public CompletableFuture<MetadataInferenceResult> inferMetadataWithOpenAlexPublicationAsync(
            String orgName,
            RelatedPublicationInfo pubInfo,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        if (!openAlexAuthorSuggestEnabled || pubInfo == null || orgName == null || orgName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String targetUrl = null;
        String cleanDoi = pubInfo.doi() != null ? pubInfo.doi().trim().replaceAll("^https?://doi\\.org/", "").replaceAll("^doi:", "").trim() : "";
        if (!cleanDoi.isBlank()) {
            targetUrl = "https://api.openalex.org/works/doi:" + URLEncoder.encode(cleanDoi, StandardCharsets.UTF_8);
        } else if (pubInfo.title() != null && !pubInfo.title().isBlank()) {
            String cleanTitle = cleanPublicationTitleForSearch(pubInfo.title());
            if (!cleanTitle.isBlank()) {
                targetUrl = "https://api.openalex.org/works?search=" + URLEncoder.encode(cleanTitle, StandardCharsets.UTF_8) + "&per-page=3";
            }
        }

        if (targetUrl == null) return CompletableFuture.completedFuture(null);

        if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
            targetUrl += (targetUrl.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(openAlexApiKey.trim(), StandardCharsets.UTF_8);
        }

        String cacheKey = "openalex-pub:" + normalizeText(orgName) + ":" + (pubInfo.doi() != null ? pubInfo.doi() : normalizeText(pubInfo.title()));
        CachedMetadataInference cached = openAlexInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached.result());
        }

        final String finalTargetUrl = targetUrl;
        log.info("[OpenAlex] Iniciant consulta per org '{}' | DOI: '{}' | Títol: '{}' | Autor objectiu: '{}' | URL: {}",
                orgName, pubInfo.doi(), pubInfo.title(), pubInfo.authorName(), finalTargetUrl);

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(finalTargetUrl))
                        .timeout(Duration.ofMillis(Math.max(1000, openAlexAuthorSuggestTimeoutMs)))
                        .header("Accept", "application/json")
                        .header("User-Agent", "Spring-egreta/1.0 (mailto:egreta@uab.cat)")
                        .GET();

                if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + openAlexApiKey.trim());
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                log.info("[OpenAlex] Resposta HTTP {} per org '{}' (URL: {})", resp.statusCode(), orgName, finalTargetUrl);

                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    JsonNode workNode = root.has("authorships") ? root : root.path("results").path(0);
                    if (workNode != null && !workNode.isMissingNode() && workNode.has("authorships")) {
                        String workId = workNode.path("id").asText("");
                        log.info("[OpenAlex] Article trobat (ID: '{}' | Títol: '{}'). Analitzant autories...",
                                workId, workNode.path("title").asText(""));
                        MetadataInferenceResult res = parseOpenAlexWorkAuthorships(orgName, pubInfo.authorName(), workNode, countryCatalog, typeCatalog);
                        if (res != null) {
                            log.info("[OpenAlex] Metadades deduïdes per '{}': País='{}', Tipus='{}'",
                                    orgName,
                                    res.country() != null ? res.country().countryLabel() : "-",
                                    res.type() != null ? res.type().typeLabel() : "-");
                            openAlexInferenceCache.put(cacheKey, new CachedMetadataInference(res, System.currentTimeMillis()));
                            return res;
                        } else {
                            log.info("[OpenAlex] No s'ha trobat filiació coincident per org '{}' / autor '{}' a l'article '{}'",
                                    orgName, pubInfo.authorName(), workId);
                        }
                    } else {
                        log.info("[OpenAlex] No s'ha trobat cap publicació amb autories a OpenAlex per '{}'", orgName);
                    }
                } else if (resp.statusCode() == 429) {
                    log.warn("OpenAlex Works API quota exhaurida o límit assolit (HTTP 429 Too Many Requests). Configureu 'app.openalex.api-key' per obtenir quota de 100k/dia.");
                } else if (resp.statusCode() == 504 || resp.statusCode() == 408) {
                    log.warn("[OpenAlex] Timeout a OpenAlex (HTTP {}) per org '{}'. La cerca a OpenAlex ha estat cancel·lada.", resp.statusCode(), orgName);
                } else {
                    log.warn("[OpenAlex] HTTP {} al consultar '{}' a URL: {}", resp.statusCode(), orgName, finalTargetUrl);
                }
            } catch (Exception e) {
                log.debug("Error querying OpenAlex publication for '{}': {}", orgName, e.getMessage());
            }
            return null;
        }, aiExecutor != null ? aiExecutor : asyncExecutor);
    }

    public MetadataInferenceResult inferMetadataWithOpenAlexPublication(
            String orgName,
            RelatedPublicationInfo pubInfo,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!openAlexAuthorSuggestEnabled || pubInfo == null || orgName == null || orgName.isBlank()) return null;
        try {
            return inferMetadataWithOpenAlexPublicationAsync(orgName, pubInfo, countryCatalog, typeCatalog)
                    .get(openAlexAuthorSuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("OpenAlex synchronous publication lookup failed for '{}': {}", orgName, e.getMessage());
            return null;
        }
    }

    private MetadataInferenceResult parseOpenAlexWorkAuthorships(
            String queryOrgName,
            String authorName,
            JsonNode workNode,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (workNode == null || workNode.isMissingNode()) return null;

        String workId = workNode.path("id").asText("");
        JsonNode authorships = workNode.path("authorships");
        if (!authorships.isArray() || authorships.isEmpty()) return null;

        String queryNorm = normalizeText(queryOrgName);
        String[] rawTokens = queryNorm.split("\\s+");
        Set<String> stopWords = Set.of(
            "de", "del", "la", "el", "les", "los", "las", "d", "l", "i", "y", "and", "of", "the",
            "for", "in", "a", "at", "on", "to", "department", "dept", "departament", "departamento", "platform", "unit", "service", "center", "centre"
        );
        List<String> contentTokens = Arrays.stream(rawTokens)
                .filter(t -> !stopWords.contains(t) && t.length() > 2)
                .toList();

        String authorNorm = authorName != null ? normalizeText(authorName) : "";

        // Pass 1: Si tenim un autor associat de la BD, buscar PRIMER la seva autoria específica a l'article
        if (!authorNorm.isBlank()) {
            for (JsonNode authorship : authorships) {
                JsonNode authorObj = authorship.path("author");
                String authDisplayName = authorObj.path("display_name").asText("");
                if (calculateNameMatchScore(authorNorm, normalizeText(authDisplayName), false) >= 0.60) {
                    log.info("[OpenAlex] Autor objectiu '{}' localitzat a l'article com '{}'. Analitzant la seva filiació...",
                            authorName, authDisplayName);
                    MetadataInferenceResult res = extractMetadataFromAuthorship(authorship, queryNorm, contentTokens, workId, countryCatalog, typeCatalog, true);
                    if (res != null) {
                        return res;
                    }
                }
            }
        }

        // Pass 2: Si no hi ha autor associat o no tenia institució, cercar per coincidència de text d'afiliació/institució
        for (JsonNode authorship : authorships) {
            MetadataInferenceResult res = extractMetadataFromAuthorship(authorship, queryNorm, contentTokens, workId, countryCatalog, typeCatalog, false);
            if (res != null) {
                return res;
            }
        }

        return null;
    }

    private MetadataInferenceResult extractMetadataFromAuthorship(
            JsonNode authorship,
            String queryNorm,
            List<String> contentTokens,
            String workId,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog,
            boolean isTargetAuthor) {

        JsonNode rawAffStrings = authorship.path("raw_affiliation_strings");
        boolean affMatched = false;
        String matchedRawAff = "";

        if (rawAffStrings.isArray()) {
            for (JsonNode rawNode : rawAffStrings) {
                String rawAff = rawNode.asText("");
                if (rawAff.isBlank()) continue;

                String rawNorm = normalizeText(rawAff);
                String[] rawWords = rawNorm.split("\\s+");
                Set<String> rawWordSet = new HashSet<>(Arrays.asList(rawWords));

                int matchedCount = 0;
                for (String ct : contentTokens) {
                    if (rawWordSet.contains(ct)) {
                        matchedCount++;
                    } else {
                        boolean partial = rawWordSet.stream().anyMatch(rw ->
                            rw.startsWith(ct) || ct.startsWith(rw) || (ct.length() > 4 && rw.contains(ct))
                        );
                        if (partial) matchedCount++;
                    }
                }

                if (!contentTokens.isEmpty() && (double) matchedCount / contentTokens.size() >= 0.60) {
                    affMatched = true;
                    matchedRawAff = rawAff;
                    break;
                }
            }
        }

        // Also check institutions array inside authorship
        JsonNode insts = authorship.path("institutions");
        JsonNode bestInst = (insts.isArray() && !insts.isEmpty()) ? insts.get(0) : null;
        if (!affMatched && bestInst != null) {
            String instName = bestInst.path("display_name").asText("");
            if (calculateNameMatchScore(queryNorm, normalizeText(instName), false) >= 0.65) {
                affMatched = true;
            }
        }

        if (affMatched || (isTargetAuthor && bestInst != null)) {
            String instDisplayName = bestInst != null ? bestInst.path("display_name").asText("") : "";
            String instCountryCode = bestInst != null ? bestInst.path("country_code").asText("") : "";
            String instType = bestInst != null ? bestInst.path("type").asText("") : "";
            String instId = bestInst != null ? bestInst.path("id").asText("") : "";

            String reasonRef = (!workId.isBlank() ? workId : (!instId.isBlank() ? instId : "openalex-pub"));

            // 1. Resolve country
            String resolvedCountry = "";
            if (!instCountryCode.isBlank()) {
                resolvedCountry = resolveAliasCountry(instCountryCode.toLowerCase(), countryCatalog);
            }
            if (resolvedCountry.isBlank() && !instDisplayName.isBlank()) {
                resolvedCountry = inferCountryFromName(instDisplayName, countryCatalog).countryLabel();
            }
            if (resolvedCountry.isBlank() && !matchedRawAff.isBlank()) {
                resolvedCountry = inferCountryFromName(matchedRawAff, countryCatalog).countryLabel();
            }
            InferenceResult countryResult = null;
            if (!resolvedCountry.isBlank()) {
                countryResult = new InferenceResult(resolvedCountry, 0.95, "openalex-pub:" + reasonRef);
            }

            // 2. Resolve type with refined multi-level Egreta categorization
            TypeInferenceResult typeResult = resolvePreciseEgretaType(
                queryNorm,
                instDisplayName + " " + matchedRawAff,
                instType,
                typeCatalog,
                0.95,
                "openalex-pub:" + reasonRef
            );

            // 3. Resolve funding
            FundingInferenceResult fundingResult = null;
            String typeLower = instType.toLowerCase();
            if (typeLower.equals("education") || typeLower.equals("government")) {
                fundingResult = new FundingInferenceResult("Publica", 0.95, "openalex-pub:" + reasonRef);
            } else if (typeLower.equals("company")) {
                fundingResult = new FundingInferenceResult("Privada", 0.95, "openalex-pub:" + reasonRef);
            } else if (typeLower.equals("nonprofit")) {
                fundingResult = new FundingInferenceResult("Privada", 0.92, "openalex-pub:" + reasonRef);
            } else if (typeLower.equals("healthcare")) {
                String norm = normalizeText(instDisplayName + " " + matchedRawAff);
                if (norm.contains("privat") || norm.contains("privado") || norm.contains("private")
                        || norm.contains("clinica") || norm.contains("quiron") || norm.contains("dexeus")) {
                    fundingResult = new FundingInferenceResult("Privada", 0.92, "openalex-healthcare-private:" + reasonRef);
                } else {
                    fundingResult = new FundingInferenceResult("Publica", 0.92, "openalex-healthcare-public:" + reasonRef);
                }
            } else if (!instDisplayName.isBlank()) {
                fundingResult = inferFundingFromName(instDisplayName);
                if (fundingResult != null && !fundingResult.fundingLabel().isBlank()) {
                    fundingResult = new FundingInferenceResult(fundingResult.fundingLabel(), 0.92, "openalex-pub:" + reasonRef);
                }
            }

            if (countryResult != null || typeResult != null || fundingResult != null) {
                return new MetadataInferenceResult(countryResult, typeResult, fundingResult);
            }
        }

        return null;
    }

    // =========================================================================
    // Crossref Publication & Author Metadata Inference
    // =========================================================================

    public CompletableFuture<MetadataInferenceResult> inferMetadataWithCrossrefPublicationAsync(
            String orgName,
            RelatedPublicationInfo pubInfo,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        if (!crossrefSuggestEnabled || pubInfo == null || orgName == null || orgName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String targetUrl = null;
        if (pubInfo.doi() != null && !pubInfo.doi().isBlank()) {
            targetUrl = crossrefSuggestUrl + "/" + URLEncoder.encode(pubInfo.doi().trim(), StandardCharsets.UTF_8);
        } else if (pubInfo.title() != null && !pubInfo.title().isBlank()) {
            targetUrl = crossrefSuggestUrl + "?query.title=" + URLEncoder.encode(pubInfo.title().trim(), StandardCharsets.UTF_8) + "&rows=3";
        }

        if (targetUrl == null) return CompletableFuture.completedFuture(null);

        String cacheKey = "crossref-pub:" + normalizeText(orgName) + ":" + (pubInfo.doi() != null ? pubInfo.doi() : normalizeText(pubInfo.title()));
        CachedMetadataInference cached = crossrefInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached.result());
        }

        final String finalTargetUrl = targetUrl;
        return CompletableFuture.supplyAsync(() -> {
            try {
                String mailto = (crossrefMailto != null && !crossrefMailto.isBlank()) ? crossrefMailto.trim() : "egreta@uab.cat";
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(finalTargetUrl))
                        .timeout(Duration.ofMillis(Math.max(1000, crossrefSuggestTimeoutMs)))
                        .header("Accept", "application/json")
                        .header("User-Agent", "Spring-egreta/1.0 (mailto:" + mailto + ")")
                        .GET();

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    JsonNode messageNode = root.path("message");
                    if (messageNode.has("author")) {
                        MetadataInferenceResult res = parseCrossrefWorkAuthorships(orgName, pubInfo.authorName(), messageNode, countryCatalog, typeCatalog);
                        if (res != null) {
                            crossrefInferenceCache.put(cacheKey, new CachedMetadataInference(res, System.currentTimeMillis()));
                            return res;
                        }
                    } else {
                        JsonNode items = messageNode.path("items");
                        if (items.isArray()) {
                            for (JsonNode item : items) {
                                MetadataInferenceResult res = parseCrossrefWorkAuthorships(orgName, pubInfo.authorName(), item, countryCatalog, typeCatalog);
                                if (res != null) {
                                    crossrefInferenceCache.put(cacheKey, new CachedMetadataInference(res, System.currentTimeMillis()));
                                    return res;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error querying Crossref publication for '{}': {}", orgName, e.getMessage());
            }
            return null;
        }, aiExecutor != null ? aiExecutor : asyncExecutor);
    }

    public MetadataInferenceResult inferMetadataWithCrossrefPublication(
            String orgName,
            RelatedPublicationInfo pubInfo,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!crossrefSuggestEnabled || pubInfo == null || orgName == null || orgName.isBlank()) return null;
        try {
            return inferMetadataWithCrossrefPublicationAsync(orgName, pubInfo, countryCatalog, typeCatalog)
                    .get(crossrefSuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Crossref synchronous publication lookup failed for '{}': {}", orgName, e.getMessage());
            return null;
        }
    }

    public CompletableFuture<MetadataInferenceResult> inferMetadataWithCrossrefAuthorAsync(
            String orgName,
            String associatedPerson,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        if (!crossrefSuggestEnabled || associatedPerson == null || associatedPerson.isBlank() || orgName == null || orgName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String authorName = cleanAuthorName(associatedPerson);
        if (authorName.isBlank() || authorName.length() < 3) {
            return CompletableFuture.completedFuture(null);
        }

        String cacheKey = "crossref-author:" + normalizeText(orgName) + ":" + normalizeText(authorName);
        CachedMetadataInference cached = crossrefInferenceCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached.result());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String encoded = URLEncoder.encode(authorName, StandardCharsets.UTF_8);
                String targetUrl = crossrefSuggestUrl + "?query.author=" + encoded + "&rows=5";
                String mailto = (crossrefMailto != null && !crossrefMailto.isBlank()) ? crossrefMailto.trim() : "egreta@uab.cat";

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofMillis(Math.max(1000, crossrefSuggestTimeoutMs)))
                        .header("Accept", "application/json")
                        .header("User-Agent", "Spring-egreta/1.0 (mailto:" + mailto + ")")
                        .GET();

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    JsonNode items = root.path("message").path("items");
                    if (items.isArray()) {
                        for (JsonNode item : items) {
                            MetadataInferenceResult res = parseCrossrefWorkAuthorships(orgName, authorName, item, countryCatalog, typeCatalog);
                            if (res != null) {
                                crossrefInferenceCache.put(cacheKey, new CachedMetadataInference(res, System.currentTimeMillis()));
                                return res;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error querying Crossref author for '{}' (author '{}'): {}", orgName, authorName, e.getMessage());
            }
            return null;
        }, aiExecutor != null ? aiExecutor : asyncExecutor);
    }

    public MetadataInferenceResult inferMetadataWithCrossrefAuthor(
            String orgName,
            String associatedPerson,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (!crossrefSuggestEnabled || associatedPerson == null || associatedPerson.isBlank() || orgName == null || orgName.isBlank()) return null;
        try {
            return inferMetadataWithCrossrefAuthorAsync(orgName, associatedPerson, countryCatalog, typeCatalog)
                    .get(crossrefSuggestTimeoutMs + AI_TIMEOUT_BUFFER_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Crossref synchronous author lookup failed for '{}': {}", orgName, e.getMessage());
            return null;
        }
    }

    private MetadataInferenceResult parseCrossrefWorkAuthorships(
            String queryOrgName,
            String targetAuthorName,
            JsonNode workNode,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (workNode == null || workNode.isMissingNode()) return null;

        String doi = workNode.path("DOI").asText("").trim();
        JsonNode authors = workNode.path("author");
        if (!authors.isArray() || authors.isEmpty()) return null;

        String queryNorm = normalizeText(queryOrgName);
        String[] rawTokens = queryNorm.split("\\s+");
        Set<String> stopWords = Set.of(
            "de", "del", "la", "el", "les", "los", "las", "d", "l", "i", "y", "and", "of", "the",
            "for", "in", "a", "at", "on", "to", "department", "dept", "departament", "departamento", "platform", "unit", "service", "center", "centre"
        );
        List<String> contentTokens = Arrays.stream(rawTokens)
                .filter(t -> !stopWords.contains(t) && t.length() > 2)
                .toList();

        String authorNorm = targetAuthorName != null ? normalizeText(targetAuthorName) : "";

        // Pass 1: If target author name is present, check author match first
        if (!authorNorm.isBlank()) {
            for (JsonNode author : authors) {
                String given = author.path("given").asText("");
                String family = author.path("family").asText("");
                String name = author.path("name").asText("");
                String fullAuthor = (!name.isBlank() ? name : (given + " " + family)).trim();

                if (!fullAuthor.isBlank() && calculateNameMatchScore(authorNorm, normalizeText(fullAuthor), false) >= 0.60) {
                    MetadataInferenceResult res = extractMetadataFromCrossrefAuthor(author, queryNorm, contentTokens, doi, countryCatalog, typeCatalog, true);
                    if (res != null) return res;
                }
            }
        }

        // Pass 2: Check all authors' affiliations for org match
        for (JsonNode author : authors) {
            MetadataInferenceResult res = extractMetadataFromCrossrefAuthor(author, queryNorm, contentTokens, doi, countryCatalog, typeCatalog, false);
            if (res != null) return res;
        }

        return null;
    }

    private MetadataInferenceResult extractMetadataFromCrossrefAuthor(
            JsonNode authorNode,
            String queryNorm,
            List<String> contentTokens,
            String doi,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog,
            boolean isTargetAuthor) {

        JsonNode affs = authorNode.path("affiliation");
        if (!affs.isArray() || affs.isEmpty()) return null;

        String matchedRawAff = "";
        String matchedRorId = "";
        boolean affMatched = false;

        for (JsonNode affNode : affs) {
            String rawAff = "";
            if (affNode.isTextual()) {
                rawAff = affNode.asText("");
            } else if (affNode.isObject()) {
                rawAff = affNode.path("name").asText("");
                JsonNode ids = affNode.path("id");
                if (ids.isArray()) {
                    for (JsonNode idNode : ids) {
                        String idType = idNode.path("id-type").asText("");
                        String idVal = idNode.path("id").asText("");
                        if ("ror".equalsIgnoreCase(idType) || idVal.contains("ror.org")) {
                            matchedRorId = idVal;
                        }
                    }
                }
            }

            if (matchedRorId.isBlank()) {
                String authorRor = authorNode.path("ROR").asText("");
                if (!authorRor.isBlank()) matchedRorId = authorRor;
            }

            if (rawAff.isBlank()) continue;

            String rawNorm = normalizeText(rawAff);
            String[] rawWords = rawNorm.split("\\s+");
            Set<String> rawWordSet = new HashSet<>(Arrays.asList(rawWords));

            int matchedCount = 0;
            for (String ct : contentTokens) {
                if (rawWordSet.contains(ct)) {
                    matchedCount++;
                } else {
                    boolean partial = rawWordSet.stream().anyMatch(rw ->
                        rw.startsWith(ct) || ct.startsWith(rw) || (ct.length() > 4 && rw.contains(ct))
                    );
                    if (partial) matchedCount++;
                }
            }

            if (!contentTokens.isEmpty() && (double) matchedCount / contentTokens.size() >= 0.60) {
                affMatched = true;
                matchedRawAff = rawAff;
                break;
            }

            if (calculateNameMatchScore(queryNorm, rawNorm, false) >= 0.65) {
                affMatched = true;
                matchedRawAff = rawAff;
                break;
            }
        }

        if (affMatched || (isTargetAuthor && !matchedRawAff.isBlank())) {
            String reasonRef = (!doi.isBlank() ? doi : "crossref-pub");

            // 1. Resolve country
            String resolvedCountry = "";
            if (!matchedRorId.isBlank()) {
                RorNameProposal rorProp = findRorNameProposal(null, matchedRawAff);
                if (rorProp != null && rorProp.country() != null && !rorProp.country().isBlank()) {
                    resolvedCountry = rorProp.country();
                }
            }
            if (resolvedCountry.isBlank() && !matchedRawAff.isBlank()) {
                InferenceResult cRes = inferCountryFromName(matchedRawAff, countryCatalog);
                if (cRes != null && !cRes.countryLabel().isBlank()) {
                    resolvedCountry = cRes.countryLabel();
                }
            }
            InferenceResult countryResult = null;
            if (!resolvedCountry.isBlank()) {
                countryResult = new InferenceResult(resolvedCountry, 0.95, "crossref-pub:" + reasonRef);
            }

            // 2. Resolve type with refined multi-level Egreta categorization
            TypeInferenceResult typeResult = resolvePreciseEgretaType(
                queryNorm,
                matchedRawAff,
                "",
                typeCatalog,
                0.95,
                "crossref-pub:" + reasonRef
            );

            // 3. Resolve funding
            FundingInferenceResult fundingResult = null;
            if (!matchedRawAff.isBlank()) {
                FundingInferenceResult fRes = inferFundingFromName(matchedRawAff);
                if (fRes != null && !fRes.fundingLabel().isBlank()) {
                    fundingResult = new FundingInferenceResult(fRes.fundingLabel(), 0.92, "crossref-pub:" + reasonRef);
                }
            }

            if (countryResult != null || typeResult != null || fundingResult != null) {
                return new MetadataInferenceResult(countryResult, typeResult, fundingResult);
            }
        }

        return null;
    }

    // =========================================================================
    // Inferencia asistida por WebSearch para país + AI local para tipo
    // =========================================================================

    private CompletableFuture<String> callWebSearchApiAsync(String orgName) {
        return callWebSearchApiAsync(orgName, null);
    }

    private CompletableFuture<String> callWebSearchApiAsync(String orgName, String associatedPerson) {
        if (!websearchCountrySuggestEnabled || websearchCountrySuggestUrl == null || websearchCountrySuggestUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            String personClean = (associatedPerson != null) ? associatedPerson.replaceAll("\\s*\\([^)]*\\)", "").trim() : "";
            String query = (personClean.isBlank())
                ? ("\"" + orgName + "\" organization country")
                : ("\"" + orgName + "\" \"" + personClean + "\" organization country");
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
        return inferCountryWithWebSearch(orgName, null, catalog);
    }

    private InferenceResult inferCountryWithWebSearch(String orgName, String associatedPerson, List<CountryAggregate> catalog) {
        String cacheKey = "country:websearch:" + orgName + (associatedPerson != null ? ":" + associatedPerson : "");
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
            body = callWebSearchApiAsync(orgName, associatedPerson)
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
            payload.put("user", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

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
        return inferCountryWithLocalAi(orgName, null, catalog);
    }

    private InferenceResult inferCountryWithLocalAi(String orgName, String associatedPerson, List<CountryAggregate> catalog) {
        // Mejora #7: Caché de inferencias por nombre (TTL 10 minutos)
        String cacheKey = "country:" + orgName + (associatedPerson != null ? ":" + associatedPerson : "");
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
        + "Given an organization name, infer its country using your training knowledge. "
        + "You may perform web searches or use browser tools ONLY if uncertain, and you MUST limit your search to a maximum of 2 or 3 sources/webpages to keep it fast. "
        + "If you cannot find reliable evidence, return a LOW confidence value (below 0.5) rather than guessing based on the name alone. "
        + "Return strict JSON with exactly these keys: \"suggestedCountry\", \"confidence\", \"reason\". "
        + "confidence must be a number between 0 and 1, and must honestly reflect how much evidence you actually have. "
        + "The \"reason\" field must only describe evidence you actually observed and must be extremely brief (at most 10-15 words). "
        + "CRITICAL: Output ONLY the raw JSON object. Do NOT wrap the response in markdown blocks like ```json ... ```. "
        + "Your response must start with '{' and end with '}'.\n\n"
        + "Example Output:\n"
        + "{\n"
        + "  \"suggestedCountry\": \"Spain\",\n"
        + "  \"confidence\": 0.95,\n"
        + "  \"reason\": \"Name references Barcelona/UAB.\"\n"
        + "}";

        String personContext = (associatedPerson != null && !associatedPerson.isBlank())
            ? (" (Associated researcher: '" + associatedPerson + "')")
            : "";
        String userPrompt = "Organization: '" + orgName + "'" + personContext;

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
        return inferMetadataWithLocalAiAsync(orgName, null, countryCatalog, typeCatalog);
    }

    private CompletableFuture<MetadataInferenceResult> inferMetadataWithLocalAiAsync(
            String orgName,
            String associatedPerson,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        String typeCatalogText = typeCatalog.stream()
                .limit(20)
                .map(t -> t.label)
                .collect(Collectors.joining(", "));

        String systemPrompt =
        "You are a data quality assistant for research organization metadata. "
        + "Based on your training knowledge, infer the country, organization type, and funding profile. "
        + "You may perform web searches or use browser tools ONLY if uncertain, and you MUST limit your search to a maximum of 2 or 3 sources/webpages to keep it fast. "
        + "Return ONLY a raw JSON object with exactly these keys: "
        + "\"suggestedCountry\", \"countryConfidence\", \"countryReason\", "
        + "\"suggestedType\", \"typeConfidence\", \"typeReason\", "
        + "\"suggestedFunding\", \"fundingConfidence\", \"fundingReason\". "
        + "Confidence values must be numbers between 0 and 1, and must honestly reflect the evidence you actually have. "
        + "If uncertain or if no evidence was found, return LOW confidence (below 0.5) instead of guessing from the name alone. "
        + "Reason fields must only describe evidence observed and must be extremely brief (at most 10-15 words). "
        + "No markdown. Start with '{' and end with '}'.";

        String personContext = (associatedPerson != null && !associatedPerson.isBlank())
            ? (" (Associated researcher: '" + associatedPerson + "')")
            : "";
        String userPrompt = "Organization: '" + orgName + "'" + personContext + ". Candidate types: " + typeCatalogText;

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
                    String associatedPerson = findAssociatedPersonForOrg(org.getUuid(), orgName);
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return inferMetadataWithLocalAiAsync(orgName, associatedPerson, countryCatalog, typeCatalog)
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
        if (org == null) return "";
        if (org.getDisplayName() != null && !org.getDisplayName().isBlank()) {
            return org.getDisplayName().trim();
        }
        if (org.getName() == null || org.getName().isEmpty()) return "";
        String name = org.getName().get("en_GB");
        if (name == null || name.isBlank()) name = org.getName().get("es_ES");
        if (name == null || name.isBlank()) name = org.getName().get("ca_ES");
        if (name == null || name.isBlank()) {
            for (String v : org.getName().values()) {
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return name != null ? name.trim() : "";
    }

    private TypeInferenceResult inferTypeWithLocalAi(String orgName, List<TypeAggregate> catalog) {
        return inferTypeWithLocalAi(orgName, null, catalog);
    }

    private TypeInferenceResult inferTypeWithLocalAi(String orgName, String associatedPerson, List<TypeAggregate> catalog) {
        // Mejora #7: Caché de inferencias por nombre (TTL 10 minutos)
        String cacheKey = "type:" + orgName + (associatedPerson != null ? ":" + associatedPerson : "");
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
        + "Given an organization name, infer its organization type using your training knowledge. "
        + "You may perform web searches or use browser tools ONLY if uncertain, and you MUST limit your search to a maximum of 2 or 3 sources/webpages to keep it fast. "
        + "If you cannot find reliable evidence, return a LOW confidence value (below 0.5) rather than guessing. "
        + "Return strict JSON with exactly these keys: \"suggestedType\", \"confidence\", \"reason\". "
        + "confidence must be a number between 0 and 1, honestly reflecting the evidence actually found. "
        + "The reason must only describe evidence observed and must be extremely brief (at most 10-15 words). "
        + "CRITICAL: Output ONLY the raw JSON object. Do NOT wrap the response in markdown blocks like ```json ... ```. "
        + "Your final response must start with '{' and end with '}'.";

        String personContext = (associatedPerson != null && !associatedPerson.isBlank())
            ? (" (Associated researcher: '" + associatedPerson + "')")
            : "";
        String userPrompt = "Organization: '" + orgName + "'" + personContext + ". Candidate types: " + catalogText;

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
        return inferMetadataWithLocalAi(orgName, null, countryCatalog, typeCatalog);
    }

    private MetadataInferenceResult inferMetadataWithLocalAi(
            String orgName,
            String associatedPerson,
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
                + "You may perform web searches or use browser tools ONLY if uncertain, and you MUST limit your search to a maximum of 2 or 3 sources/webpages to keep it fast. "
                + "Return strict JSON with exactly these keys: "
                + "\"suggestedCountry\", \"countryConfidence\", \"countryReason\", "
                + "\"suggestedType\", \"typeConfidence\", \"typeReason\", "
                + "\"suggestedFunding\", \"fundingConfidence\", \"fundingReason\". "
                + "Confidence values must be numbers between 0 and 1. "
                + "Reason fields must be extremely brief (at most 10-15 words). "
                
                + "CRITICAL: Output ONLY the raw JSON object. Do NOT wrap the response in markdown blocks like ```json ... ```. "
                + "Your final response must start with '{' and end with '}'.";

        String personContext = (associatedPerson != null && !associatedPerson.isBlank())
            ? (" (Associated researcher: '" + associatedPerson + "')")
            : "";
        String userPrompt = "Organization: '" + orgName + "'" + personContext + ". Candidate types: " + typeCatalogText;

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
    // Búsqueda de persona externa / investigadora asociada a la organización
    // -------------------------------------------------------------------------
    private String findAssociatedPersonForOrg(String orgUuid, String orgName) {
        if ((orgUuid == null || orgUuid.isBlank()) && (orgName == null || orgName.isBlank())) {
            return null;
        }

        String cacheKey = (orgUuid != null && !orgUuid.isBlank()) ? ("uuid:" + orgUuid.trim()) : ("name:" + orgName.trim().toLowerCase());
        CachedInference cached = associatedPersonCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < INFERENCE_CACHE_TTL_MS) {
            return cached.result();
        }

        String result = null;

        try {
            String targetUuid = (orgUuid != null && !orgUuid.isBlank()) ? orgUuid.trim() : null;
            if (targetUuid == null && orgName != null && !orgName.isBlank()) {
                String escaped = Pattern.quote(orgName.trim());
                Criteria nameCriteria = new Criteria().orOperator(
                    Criteria.where("name.ca_ES").regex("^" + escaped + "$", "i"),
                    Criteria.where("name.es_ES").regex("^" + escaped + "$", "i"),
                    Criteria.where("name.en_GB").regex("^" + escaped + "$", "i")
                );
                Query q = new Query(nameCriteria);
                q.fields().include("uuid");
                ExternalOrganization found = mongoTemplate.findOne(q, ExternalOrganization.class);
                if (found != null && found.getUuid() != null) {
                    targetUuid = found.getUuid();
                }
            }

            if (targetUuid != null && !targetUuid.isBlank()) {
                // 1. Buscar en StudentTheses (únicamente supervisores externos)
                if (result == null && mongoTemplate.collectionExists("StudentTheses")) {
                    Query q = new Query(new Criteria().orOperator(
                        Criteria.where("supervisors.externalOrganizations.uuid").is(targetUuid),
                        Criteria.where("supervisors.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("supervisors.affiliations.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("supervisors.externalOrganizationRef.uuid").is(targetUuid),
                        Criteria.where("externalOrganizationRef.uuid").is(targetUuid)
                    ));
                    q.fields().include("supervisors");
                    q.limit(1);
                    Document doc = mongoTemplate.findOne(q, Document.class, "StudentTheses");
                    if (doc != null) {
                        List<?> supervisors = doc.get("supervisors", List.class);
                        if (supervisors != null) {
                            for (Object item : supervisors) {
                                if (item instanceof Document sDoc) {
                                    if (contributorMatchesOrgUuid(sDoc, targetUuid)) {
                                        boolean isExt = "ExternalSupervisorAssociation".equals(sDoc.getString("typeDiscriminator"))
                                                || sDoc.containsKey("externalPerson");
                                        if (isExt) {
                                            Document nameDoc = sDoc.get("name", Document.class);
                                            if (nameDoc != null) {
                                                String first = nameDoc.getString("firstName");
                                                String last = nameDoc.getString("lastName");
                                                String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
                                                if (!full.isBlank()) {
                                                    result = full + " (Persona externa)";
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Buscar en Activities (únicamente personas externas)
                if (result == null && mongoTemplate.collectionExists("Activities")) {
                    Query q = new Query(new Criteria().orOperator(
                        Criteria.where("consultancyFor.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("memberOf.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("editorialWorkFor.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("hostOrganisations.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("externalOrganisations.uuid").is(targetUuid),
                        Criteria.where("collaboratingOrganisations.externalOrganization.uuid").is(targetUuid)
                    ));
                    q.fields().include("externalPersons");
                    q.limit(1);
                    Document doc = mongoTemplate.findOne(q, Document.class, "Activities");
                    if (doc != null) {
                        List<?> extPersons = doc.get("externalPersons", List.class);
                        if (extPersons != null && !extPersons.isEmpty()) {
                            for (Object item : extPersons) {
                                if (item instanceof Document pDoc) {
                                    Document nameDoc = pDoc.get("name", Document.class);
                                    if (nameDoc != null) {
                                        String first = nameDoc.getString("firstName");
                                        String last = nameDoc.getString("lastName");
                                        String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
                                        if (!full.isBlank()) {
                                            result = full + " (Persona externa)";
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Buscar en Researchoutputs (únicamente coautores externos)
                if (result == null && mongoTemplate.collectionExists("Researchoutputs")) {
                    Query q = new Query(new Criteria().orOperator(
                        Criteria.where("contributors.externalOrganizations.uuid").is(targetUuid),
                        Criteria.where("contributors.affiliations.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("contributors.affiliations.uuid").is(targetUuid),
                        Criteria.where("contributors.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("contributors.externalPositions.externalOrganization.uuid").is(targetUuid),
                        Criteria.where("externalOrganizations.uuid").is(targetUuid)
                    ));
                    q.fields().include("contributors");
                    q.limit(1);
                    Document doc = mongoTemplate.findOne(q, Document.class, "Researchoutputs");
                    if (doc != null) {
                        List<?> contributors = doc.get("contributors", List.class);
                        if (contributors != null) {
                            for (Object item : contributors) {
                                if (item instanceof Document cDoc) {
                                    if (contributorMatchesOrgUuid(cDoc, targetUuid)) {
                                        boolean isExt = "ExternalContributorAssociation".equals(cDoc.getString("typeDiscriminator"))
                                                || cDoc.containsKey("externalPerson");
                                        if (isExt) {
                                            Document nameDoc = cDoc.get("name", Document.class);
                                            if (nameDoc != null) {
                                                String first = nameDoc.getString("firstName");
                                                String last = nameDoc.getString("lastName");
                                                String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
                                                if (!full.isBlank()) {
                                                    result = full + " (Persona externa)";
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error cercant persona associada per a organitzacio UUID '{}': {}", orgUuid, e.getMessage());
        }

        associatedPersonCache.put(cacheKey, new CachedInference(result, System.currentTimeMillis()));
        return result;
    }

    private boolean contributorMatchesOrgUuid(Document cDoc, String targetUuid) {
        if (cDoc == null || targetUuid == null || targetUuid.isBlank()) return false;
        List<String> fields = List.of("externalOrganizations", "affiliations", "externalOrganization", "externalPositions", "organisations", "organisationalUnits", "externalOrganizationRef");
        for (String field : fields) {
            Object val = cDoc.get(field);
            if (docContainsUuid(val, targetUuid)) {
                return true;
            }
        }
        return false;
    }

    private boolean docContainsUuid(Object obj, String targetUuid) {
        if (obj == null || targetUuid == null) return false;
        if (obj instanceof String s) {
            return targetUuid.equalsIgnoreCase(s.trim());
        }
        if (obj instanceof Document d) {
            String uuid = d.getString("uuid");
            if (uuid != null && targetUuid.equalsIgnoreCase(uuid.trim())) {
                return true;
            }
            for (String key : d.keySet()) {
                if (docContainsUuid(d.get(key), targetUuid)) {
                    return true;
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (docContainsUuid(item, targetUuid)) {
                    return true;
                }
            }
        }
        return false;
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
            double confidence = firstNumberNode(node, "fundingConfidence", "confidence", 0.85);
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

    private Criteria missingRorCriteria() {
        return new Criteria().orOperator(
            Criteria.where("identifiers").exists(false),
            Criteria.where("identifiers").is(null),
            Criteria.where("identifiers").size(0),
            Criteria.where("identifiers").not().elemMatch(
                new Criteria().orOperator(
                    Criteria.where("type.uri").regex("ror", "i"),
                    Criteria.where("type.term.ca_ES").regex("ror", "i"),
                    Criteria.where("type.term.es_ES").regex("ror", "i"),
                    Criteria.where("type.term.en_GB").regex("ror", "i"),
                    Criteria.where("idSource").regex("ror", "i"),
                    Criteria.where("value").regex("ror\\.org", "i"),
                    Criteria.where("id").regex("ror\\.org", "i")
                )
            )
        );
    }

    private boolean hasRorIdentifier(ExternalOrganization org) {
        if (org == null || org.getIdentifiers() == null || org.getIdentifiers().isEmpty()) {
            return false;
        }
        for (ExternalOrganization.Identifier id : org.getIdentifiers()) {
            if (id == null) continue;
            if (id.getIdSource() != null && id.getIdSource().toLowerCase().contains("ror")) return true;
            if (id.getType() != null && id.getType().getUri() != null && id.getType().getUri().toLowerCase().contains("ror")) return true;
            if (id.getType() != null && id.getType().getTerm() != null) {
                for (String t : id.getType().getTerm().values()) {
                    if (t != null && t.toLowerCase().contains("ror")) return true;
                }
            }
            String val = id.getEffectiveValue();
            if (val != null && (val.toLowerCase().contains("ror.org") || (val.startsWith("0") && val.length() == 9))) return true;
        }
        return false;
    }

    private boolean hasExistingCountry(ExternalOrganization org) {
        if (org == null) return false;
        String countryName = org.getCountryName();
        if (countryName != null && !countryName.isBlank()) {
            return true;
        }
        if (org.getAddress() != null && org.getAddress().getCountry() != null) {
            ExternalOrganization.UriTerm c = org.getAddress().getCountry();
            if (c.getUri() != null && !c.getUri().isBlank()) {
                return true;
            }
            if (c.getTerm() != null) {
                for (String val : c.getTerm().values()) {
                    if (val != null && !val.isBlank()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isMissingCountry(ExternalOrganization org) {
        return !hasExistingCountry(org);
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
        if (reconciliationJobRegistry != null) {
            reconciliationJobRegistry.logMessage(ReconciliationType.COUNTRY, message);
        }
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyLogs.add(formatted);
        writeLogToFile(formatted);
    }

    private void executeAutoApplyCountry(String env, double confidenceThreshold) {
        asyncExecutor.submit(() -> {
            try {
                Query query = new Query(missingCountryCriteria()).with(buildNameSort());
                query.fields().include("uuid").include("name").include("displayName");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                orgs.sort(Comparator.comparing(this::extractOrgName, String.CASE_INSENSITIVE_ORDER));
                
                synchronized(autoApplyLock) {
                    autoApplyTotal = orgs.size();
                }
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.setTotal(ReconciliationType.COUNTRY, orgs.size());
                }
                
                logMessage("S'han trobat " + autoApplyTotal + " organitzacions sense pais. Processant en paral·lel (Confiança >= " + Math.round(confidenceThreshold * 100) + "%)...");
                
                List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
                List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();
                
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
                            
                            InferenceResult countryResult = null;
                            if (rorSuggestEnabled) {
                                MetadataInferenceResult rorRes = inferMetadataWithRor(name, countryCatalog, typeCatalog);
                                if (rorRes != null && rorRes.country() != null && !rorRes.country().countryLabel().isBlank() && rorRes.country().confidence() >= 0.85) {
                                    countryResult = rorRes.country();
                                }
                            }
                            if (countryResult == null) {
                                MetadataInferenceResult best = inferBestMetadata(uuid, name, null, countryCatalog, typeCatalog);
                                countryResult = best.country();
                            }
                            
                            if (countryResult == null || countryResult.countryLabel().isBlank()) {
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
                                        if (reconciliationJobRegistry != null) {
                                            reconciliationJobRegistry.incrementApplied(ReconciliationType.COUNTRY);
                                        }
                                        logMessage("[APLICAT] " + name + " -> " + suggestedCountry + " (Font: " + countryResult.reason() + ", Confiança: " + Math.round(confidence * 100) + "%)");
                                    }
                                }
                            } else {
                                logMessage("[OMES] " + name + " - Suggeriment: " + suggestedCountry + " (Font: " + countryResult.reason() + ", Confiança: " + Math.round(confidence * 100) + "% - baixa).");
                            }
                        } catch (Exception e) {
                            logMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyLock) {
                                autoApplyProcessed++;
                            }
                            if (reconciliationJobRegistry != null) {
                                reconciliationJobRegistry.incrementProcessed(ReconciliationType.COUNTRY);
                            }
                        }
                    }, aiExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                String summary = "Proces finalitzat. Total processades: " + autoApplyProcessed + ", Aplicades: " + autoApplyApplied;
                logMessage(summary);
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.finishRun(ReconciliationType.COUNTRY, summary);
                }
                invalidateMetadataCatalogCaches();
            } catch (Exception e) {
                logMessage("[FATAL ERROR] " + e.getMessage());
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.failRun(ReconciliationType.COUNTRY, e.getMessage());
                }
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
        if (reconciliationJobRegistry != null) {
            boolean started = reconciliationJobRegistry.startRun(ReconciliationType.COUNTRY, env, confidence, "PROCES NORMALITZACIO DE PAISOS");
            if (!started) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyRun(env, confidence);
            executeAutoApplyCountry(env, confidence);
            return Map.of("started", true);
        }
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
        if (reconciliationJobRegistry != null) {
            boolean stopped = reconciliationJobRegistry.requestStop(ReconciliationType.COUNTRY);
            if (!stopped) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyRunning = false;
            logMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
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
        if (reconciliationJobRegistry != null) {
            return reconciliationJobRegistry.getProgress(ReconciliationType.COUNTRY).toMap();
        }
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
        if (reconciliationJobRegistry != null) {
            reconciliationJobRegistry.logMessage(ReconciliationType.TYPE, message);
        }
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyTypeLogs.add(formatted);
        writeTypeLogToFile(formatted);
    }

    private void executeAutoApplyType(String env, double confidenceThreshold) {
        asyncExecutor.submit(() -> {
            try {
                Query query = new Query(missingTypeCriteria()).with(buildNameSort());
                query.fields().include("uuid").include("name").include("displayName");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                orgs.sort(Comparator.comparing(this::extractOrgName, String.CASE_INSENSITIVE_ORDER));
                
                synchronized(autoApplyTypeLock) {
                    autoApplyTypeTotal = orgs.size();
                }
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.setTotal(ReconciliationType.TYPE, orgs.size());
                }
                
                logTypeMessage("S'han trobat " + autoApplyTypeTotal + " organitzacions sense tipus. Processant en paral·lel (Confiança >= " + Math.round(confidenceThreshold * 100) + "%)...");
                
                List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
                List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();
                
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
                            
                            TypeInferenceResult typeResult = null;
                            if (rorSuggestEnabled) {
                                MetadataInferenceResult rorRes = inferMetadataWithRor(name, countryCatalog, typeCatalog);
                                if (rorRes != null && rorRes.type() != null && !rorRes.type().typeLabel().isBlank() && rorRes.type().confidence() >= 0.85) {
                                    typeResult = rorRes.type();
                                }
                            }
                            if (typeResult == null) {
                                MetadataInferenceResult best = inferBestMetadata(uuid, name, null, countryCatalog, typeCatalog);
                                typeResult = best.type();
                            }
                            
                            if (typeResult == null || typeResult.typeLabel().isBlank()) {
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
                                        if (reconciliationJobRegistry != null) {
                                            reconciliationJobRegistry.incrementApplied(ReconciliationType.TYPE);
                                        }
                                        logTypeMessage("[APLICAT] " + name + " -> " + suggestedType + " (Font: " + typeResult.reason() + ", Confiança: " + Math.round(confidence * 100) + "%)");
                                    }
                                }
                            } else {
                                logTypeMessage("[OMES] " + name + " - Suggeriment: " + suggestedType + " (Font: " + typeResult.reason() + ", Confiança: " + Math.round(confidence * 100) + "% - baixa).");
                            }
                        } catch (Exception e) {
                            logTypeMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyTypeLock) {
                                autoApplyTypeProcessed++;
                            }
                            if (reconciliationJobRegistry != null) {
                                reconciliationJobRegistry.incrementProcessed(ReconciliationType.TYPE);
                            }
                        }
                    }, aiExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                String summary = "Proces finalitzat. Total processades: " + autoApplyTypeProcessed + ", Aplicades: " + autoApplyTypeApplied;
                logTypeMessage(summary);
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.finishRun(ReconciliationType.TYPE, summary);
                }
                invalidateMetadataCatalogCaches();
            } catch (Exception e) {
                logTypeMessage("[FATAL ERROR] " + e.getMessage());
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.failRun(ReconciliationType.TYPE, e.getMessage());
                }
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
        if (reconciliationJobRegistry != null) {
            boolean started = reconciliationJobRegistry.startRun(ReconciliationType.TYPE, env, confidence, "PROCES NORMALITZACIO DE TIPOLOGIES");
            if (!started) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyTypeRun(env, confidence);
            executeAutoApplyType(env, confidence);
            return Map.of("started", true);
        }
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
        if (reconciliationJobRegistry != null) {
            boolean stopped = reconciliationJobRegistry.requestStop(ReconciliationType.TYPE);
            if (!stopped) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyTypeRunning = false;
            logTypeMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
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
        if (reconciliationJobRegistry != null) {
            return reconciliationJobRegistry.getProgress(ReconciliationType.TYPE).toMap();
        }
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
        if (reconciliationJobRegistry != null) {
            reconciliationJobRegistry.logMessage(ReconciliationType.FUNDING, message);
        }
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyFundingLogs.add(formatted);
        writeFundingLogToFile(formatted);
    }

    private void executeAutoApplyFunding(String env, double confidenceThreshold) {
        asyncExecutor.submit(() -> {
            try {
                Query query = new Query(missingFundingCriteria()).with(buildNameSort());
                query.fields().include("uuid").include("name").include("displayName");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                orgs.sort(Comparator.comparing(this::extractOrgName, String.CASE_INSENSITIVE_ORDER));
                
                synchronized(autoApplyFundingLock) {
                    autoApplyFundingTotal = orgs.size();
                }
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.setTotal(ReconciliationType.FUNDING, orgs.size());
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
                            
                            FundingInferenceResult fundingResult = null;
                            if (rorSuggestEnabled) {
                                MetadataInferenceResult rorRes = inferMetadataWithRor(name, countryCatalog, typeCatalog);
                                if (rorRes != null && rorRes.funding() != null && !rorRes.funding().fundingLabel().isBlank() && rorRes.funding().confidence() >= 0.85) {
                                    fundingResult = rorRes.funding();
                                }
                            }
                            if (fundingResult == null) {
                                MetadataInferenceResult best = inferBestMetadata(uuid, name, null, countryCatalog, typeCatalog);
                                fundingResult = best.funding();
                            }
                            
                            if (fundingResult == null || fundingResult.fundingLabel().isBlank()) {
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
                                        if (reconciliationJobRegistry != null) {
                                            reconciliationJobRegistry.incrementApplied(ReconciliationType.FUNDING);
                                        }
                                        logFundingMessage("[APLICAT] " + name + " -> " + suggestedFunding + " (Font: " + fundingResult.reason() + ", Confiança: " + Math.round(confidence * 100) + "%)");
                                    }
                                }
                            } else {
                                logFundingMessage("[OMES] " + name + " - Suggeriment: " + suggestedFunding + " (Font: " + fundingResult.reason() + ", Confiança: " + Math.round(confidence * 100) + "% - baixa).");
                            }
                        } catch (Exception e) {
                            logFundingMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyFundingLock) {
                                autoApplyFundingProcessed++;
                            }
                            if (reconciliationJobRegistry != null) {
                                reconciliationJobRegistry.incrementProcessed(ReconciliationType.FUNDING);
                            }
                        }
                    }, aiExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                String summary = "Proces finalitzat. Total processades: " + autoApplyFundingProcessed + ", Aplicades: " + autoApplyFundingApplied;
                logFundingMessage(summary);
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.finishRun(ReconciliationType.FUNDING, summary);
                }
            } catch (Exception e) {
                logFundingMessage("[FATAL ERROR] " + e.getMessage());
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.failRun(ReconciliationType.FUNDING, e.getMessage());
                }
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
        if (reconciliationJobRegistry != null) {
            boolean started = reconciliationJobRegistry.startRun(ReconciliationType.FUNDING, env, confidence, "PROCES NORMALITZACIO DE FINANÇAMENT");
            if (!started) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyFundingRun(env, confidence);
            executeAutoApplyFunding(env, confidence);
            return Map.of("started", true);
        }
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
        if (reconciliationJobRegistry != null) {
            boolean stopped = reconciliationJobRegistry.requestStop(ReconciliationType.FUNDING);
            if (!stopped) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyFundingRunning = false;
            logFundingMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
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
        if (reconciliationJobRegistry != null) {
            return reconciliationJobRegistry.getProgress(ReconciliationType.FUNDING).toMap();
        }
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
            java.io.File file = (reconciliationJobRegistry != null)
                ? reconciliationJobRegistry.getLogFile(ReconciliationType.FUNDING)
                : new java.io.File(autoApplyFundingLogFilePath);
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

    @GetMapping("/stats/name-proposals")
    public Map<String, Object> getNameProposals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "0.75") double minSimilarity,
            @RequestParam(defaultValue = "0.9999") double maxSimilarity,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "300") int limit,
            @RequestParam(defaultValue = "true") boolean onlyWithoutRor,
            @RequestParam(defaultValue = "false") boolean onlyWithoutCountry) {
        
        List<Criteria> andCriteria = new ArrayList<>();
        if (onlyWithoutRor) {
            andCriteria.add(missingRorCriteria());
        }
        if (onlyWithoutCountry) {
            andCriteria.add(missingCountryCriteria());
        }
        if (search != null && !search.isBlank()) {
            String escaped = Pattern.quote(search.trim());
            andCriteria.add(new Criteria().orOperator(
                Criteria.where("name.ca_ES").regex(escaped, "i"),
                Criteria.where("name.es_ES").regex(escaped, "i"),
                Criteria.where("name.en_GB").regex(escaped, "i")
            ));
        }
        
        Query query = new Query();
        if (!andCriteria.isEmpty()) {
            if (andCriteria.size() == 1) {
                query.addCriteria(andCriteria.get(0));
            } else {
                query.addCriteria(new Criteria().andOperator(andCriteria.toArray(new Criteria[0])));
            }
        }
        query.fields().include("uuid").include("pureId").include("name").include("address").include("type").include("identifiers");
        if (limit > 0) {
            query.limit(limit);
        }
        
        List<ExternalOrganization> orgList = mongoTemplate.find(query, ExternalOrganization.class);
        
        List<RorNameProposal> proposals = new java.util.concurrent.CopyOnWriteArrayList<>();
        int concurrencyLimit = 16;
        java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrencyLimit);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (ExternalOrganization org : orgList) {
            if (onlyWithoutRor && hasRorIdentifier(org)) {
                continue;
            }
            if (onlyWithoutCountry && !isMissingCountry(org)) {
                continue;
            }
            String uuid = org.getUuid();
            Long pureId = org.getPureId();
            String name = extractOrgName(org);
            if (name.isBlank()) continue;
            
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            CompletableFuture<Void> fut = CompletableFuture.runAsync(() -> {
                try {
                    RorNameProposal p = findRorNameProposal(uuid, pureId, name);
                    if (p != null && p.similarity() >= minSimilarity && p.similarity() <= maxSimilarity) {
                        proposals.add(p);
                    }
                } finally {
                    sem.release();
                }
            }, aiExecutor != null ? aiExecutor : asyncExecutor);
            futures.add(fut);
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        proposals.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        
        int total = proposals.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<RorNameProposal> paged = proposals.subList(fromIndex, toIndex);
        
        return Map.of(
            "total", total,
            "page", page,
            "size", size,
            "items", paged
        );
    }

    @GetMapping("/stats/suggest-name")
    public Map<String, Object> suggestName(
            @RequestParam(required = false) String uuid,
            @RequestParam(required = false) String name) {
        String orgName = name == null ? "" : name.trim();
        String orgUuid = uuid == null ? "" : uuid.trim();
        Long orgPureId = null;
        if (!orgUuid.isBlank()) {
            ExternalOrganization org = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(orgUuid)), ExternalOrganization.class);
            if (org != null) {
                orgPureId = org.getPureId();
                if (orgName.isBlank()) {
                    orgName = extractOrgName(org);
                }
            }
        }
        if (orgName.isBlank()) {
            return Map.of("suggestedName", "", "similarity", 0.0, "reason", "empty-name");
        }
        
        RorNameProposal proposal = findRorNameProposal(orgUuid, orgPureId, orgName);
        if (proposal == null) {
            return Map.of("currentName", orgName, "suggestedName", "", "similarity", 0.0, "reason", "no-proposal");
        }
        
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("uuid", proposal.uuid() == null ? "" : proposal.uuid());
        if (proposal.pureId() != null) {
            resp.put("pureId", proposal.pureId());
        }
        resp.put("currentName", proposal.currentName());
        resp.put("suggestedName", proposal.suggestedName());
        resp.put("similarity", proposal.similarity());
        resp.put("rorId", proposal.rorId());
        resp.put("country", proposal.country());
        resp.put("type", proposal.type());
        resp.put("reason", proposal.reason());
        resp.put("rawAffiliation", proposal.rawAffiliation() != null ? proposal.rawAffiliation() : "");
        return resp;
    }

    private void startAutoApplyNameRun(String env, double similarity) {
        synchronized(autoApplyNameLock) {
            autoApplyNameRunning = true;
            autoApplyNameTotal = 0;
            autoApplyNameProcessed = 0;
            autoApplyNameApplied = 0;
            autoApplyNameSimilarityThreshold = similarity;
            autoApplyNameLogs.clear();
        }
        
        try {
            java.io.File file = new java.io.File(autoApplyNameLogFilePath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.nio.file.Files.writeString(
                file.toPath(), 
                "=== INICI DE PROCES NORMALITZACIO DE NOMS ROR (" + (env == null ? "DEFAULT" : env.toUpperCase()) + ", Similitud >= " + Math.round(similarity * 100) + "%) === " + java.time.LocalDateTime.now() + System.lineSeparator(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            System.err.println("Error initializing auto-apply name log file: " + e.getMessage());
        }
    }

    private synchronized void writeNameLogToFile(String line) {
        try {
            java.io.File file = new java.io.File(autoApplyNameLogFilePath);
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
            System.err.println("Error writing to auto-apply name log file: " + e.getMessage());
        }
    }

    private void logNameMessage(String message) {
        if (reconciliationJobRegistry != null) {
            reconciliationJobRegistry.logMessage(ReconciliationType.NAME, message);
        }
        String formatted = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message;
        autoApplyNameLogs.add(formatted);
        writeNameLogToFile(formatted);
    }

    private void executeAutoApplyName(String env, double similarityThreshold, boolean onlyWithoutRor, boolean onlyWithoutCountry, boolean applyName) {
        asyncExecutor.submit(() -> {
            try {
                List<Criteria> andCriteria = new ArrayList<>();
                if (onlyWithoutRor) {
                    andCriteria.add(missingRorCriteria());
                }
                if (onlyWithoutCountry) {
                    andCriteria.add(missingCountryCriteria());
                }
                Query query = new Query();
                if (!andCriteria.isEmpty()) {
                    if (andCriteria.size() == 1) {
                        query.addCriteria(andCriteria.get(0));
                    } else {
                        query.addCriteria(new Criteria().andOperator(andCriteria.toArray(new Criteria[0])));
                    }
                }
                query.with(buildNameSort());
                query.fields().include("uuid").include("name").include("displayName").include("address").include("identifiers");
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                orgs.sort(Comparator.comparing(this::extractOrgName, String.CASE_INSENSITIVE_ORDER));
                
                synchronized(autoApplyNameLock) {
                    autoApplyNameTotal = orgs.size();
                }
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.setTotal(ReconciliationType.NAME, orgs.size());
                }
                
                logNameMessage("S'han trobat " + autoApplyNameTotal + " organitzacions (Sense ROR: " + onlyWithoutRor + ", Sense País: " + onlyWithoutCountry + ", Canviar Nom: " + applyName + "). Analitzant propostes de noms ROR/OpenAlex (Similitud >= " + Math.round(similarityThreshold * 100) + "%)...");
                
                int concurrencyLimit = 16;
                java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(concurrencyLimit);
                
                List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
                
                for (ExternalOrganization shortOrg : orgs) {
                    if (onlyWithoutRor && hasRorIdentifier(shortOrg)) {
                        continue;
                    }
                    if (onlyWithoutCountry && !isMissingCountry(shortOrg)) {
                        continue;
                    }
                    String uuid = shortOrg.getUuid();
                    String name = extractOrgName(shortOrg);
                    
                    synchronized(autoApplyNameLock) {
                        if (!autoApplyNameRunning) {
                            break;
                        }
                    }
                    
                    sem.acquire();
                    
                    synchronized(autoApplyNameLock) {
                        if (!autoApplyNameRunning) {
                            sem.release();
                            break;
                        }
                    }
                    
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            synchronized(autoApplyNameLock) {
                                if (!autoApplyNameRunning) {
                                    return;
                                }
                            }
                            
                            ExternalOrganization org = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(uuid)), ExternalOrganization.class);
                            if (org == null) {
                                logNameMessage("[ERROR] Organitzacio no trobada: " + name + " (UUID: " + uuid + ")");
                                return;
                            }
                            
                            RorNameProposal proposal = findRorNameProposal(uuid, name);
                            if (proposal == null || proposal.suggestedName() == null || proposal.suggestedName().isBlank()) {
                                return;
                            }
                            
                            if (proposal.similarity() >= similarityThreshold) {
                                ExternalOrganization.UriTerm updatedCountry = null;
                                if (isMissingCountry(org) && proposal.country() != null && !proposal.country().isBlank()) {
                                    updatedCountry = resolveCountryUriTermFromPayload(null, proposal.country(), null);
                                }
                                String nameToApply = (applyName && proposal.suggestedName() != null && !proposal.suggestedName().isBlank()) ? proposal.suggestedName().trim() : null;

                                Map<String, Object> egretaSync = syncExternalOrganizationRorIdentifierToEgreta(org, proposal.rorId(), updatedCountry, nameToApply, env);
                                boolean egretaUpdated = Boolean.TRUE.equals(egretaSync.get("updated"));
                                if (!egretaUpdated) {
                                    logNameMessage("[ERROR] " + name + " - Error de sincronitzacio amb Egreta: " + egretaSync.get("reason"));
                                } else {
                                    if (nameToApply != null) {
                                        if (org.getName() == null) {
                                            org.setName(new java.util.HashMap<>());
                                        }
                                        org.getName().put("ca_ES", nameToApply);
                                        org.getName().put("es_ES", nameToApply);
                                        org.getName().put("en_GB", nameToApply);
                                    }
                                    if (proposal.rorId() != null && !proposal.rorId().isBlank()) {
                                        applyRorIdentifierToOrganization(org, proposal.rorId(), updatedCountry);
                                    } else if (updatedCountry != null) {
                                        if (org.getAddress() == null) {
                                            org.setAddress(new ExternalOrganization.Address());
                                        }
                                        org.getAddress().setCountry(updatedCountry);
                                    }
                                    repository.save(org);
                                    if (updatedCountry != null || nameToApply != null) {
                                        invalidateMetadataCatalogCaches();
                                    }
                                    
                                    synchronized(autoApplyNameLock) {
                                        autoApplyNameApplied++;
                                    }
                                    if (reconciliationJobRegistry != null) {
                                        reconciliationJobRegistry.incrementApplied(ReconciliationType.NAME);
                                    }
                                    String countryLog = (updatedCountry != null) ? " + País: \"" + proposal.country() + "\"" : "";
                                    String nameLog = (nameToApply != null) ? " -> Nou Nom: \"" + nameToApply + "\"" : "";
                                    logNameMessage("[ROR/NOM ASSIGNAT] \"" + name + "\"" + nameLog + " (ROR: " + proposal.rorId() + ", Similitud: " + Math.round(proposal.similarity() * 100) + "%, Font: " + proposal.reason() + ")" + countryLog);
                                }
                            }
                        } catch (Exception e) {
                            logNameMessage("[ERROR] " + name + " - " + e.getMessage());
                        } finally {
                            sem.release();
                            synchronized(autoApplyNameLock) {
                                autoApplyNameProcessed++;
                            }
                            if (reconciliationJobRegistry != null) {
                                reconciliationJobRegistry.incrementProcessed(ReconciliationType.NAME);
                            }
                        }
                    }, aiExecutor != null ? aiExecutor : asyncExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                String summary = "Proces d'assignacio de noms i identificadors finalitzat. Processades: " + autoApplyNameProcessed + ", Assignacions: " + autoApplyNameApplied;
                logNameMessage(summary);
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.finishRun(ReconciliationType.NAME, summary);
                }
            } catch (Exception e) {
                logNameMessage("[FATAL ERROR] " + e.getMessage());
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.failRun(ReconciliationType.NAME, e.getMessage());
                }
            } finally {
                synchronized(autoApplyNameLock) {
                    autoApplyNameRunning = false;
                }
            }
        });
    }

    @PostMapping("/stats/auto-apply-name/start")
    public Map<String, Object> startAutoApplyName(
            @RequestParam(defaultValue = "test") String env,
            @RequestParam(defaultValue = "0.90") double similarity,
            @RequestParam(defaultValue = "true") boolean onlyWithoutRor,
            @RequestParam(defaultValue = "false") boolean onlyWithoutCountry,
            @RequestParam(defaultValue = "false") boolean applyName) {
        if (reconciliationJobRegistry != null) {
            boolean started = reconciliationJobRegistry.startRun(ReconciliationType.NAME, env, similarity, "PROCES NORMALITZACIO DE NOMS ROR");
            if (!started) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyNameRun(env, similarity);
            executeAutoApplyName(env, similarity, onlyWithoutRor, onlyWithoutCountry, applyName);
            return Map.of("started", true);
        }
        synchronized(autoApplyNameLock) {
            if (autoApplyNameRunning) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoApplyNameRun(env, similarity);
            executeAutoApplyName(env, similarity, onlyWithoutRor, onlyWithoutCountry, applyName);
            return Map.of("started", true);
        }
    }

    @PostMapping("/stats/auto-apply-name/stop")
    public Map<String, Object> stopAutoApplyName() {
        if (reconciliationJobRegistry != null) {
            boolean stopped = reconciliationJobRegistry.requestStop(ReconciliationType.NAME);
            if (!stopped) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyNameRunning = false;
            logNameMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
        synchronized(autoApplyNameLock) {
            if (!autoApplyNameRunning) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoApplyNameRunning = false;
            logNameMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
    }

    @GetMapping("/stats/auto-apply-name/status")
    public Map<String, Object> getAutoApplyNameStatus() {
        if (reconciliationJobRegistry != null) {
            return reconciliationJobRegistry.getProgress(ReconciliationType.NAME).toMap();
        }
        synchronized(autoApplyNameLock) {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("running", autoApplyNameRunning);
            status.put("total", autoApplyNameTotal);
            status.put("processed", autoApplyNameProcessed);
            status.put("applied", autoApplyNameApplied);
            status.put("similarityThreshold", autoApplyNameSimilarityThreshold);
            status.put("logs", String.join("\n", autoApplyNameLogs));
            return status;
        }
    }

    @GetMapping("/stats/auto-apply-name/download-log")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadAutoApplyNameLog() {
        try {
            java.io.File file = (reconciliationJobRegistry != null)
                ? reconciliationJobRegistry.getLogFile(ReconciliationType.NAME)
                : new java.io.File(autoApplyNameLogFilePath);
            if (!file.exists()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toURI());
            return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auto-apply-name.log\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(resource);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/stats/auto-validate/start")
    public Map<String, Object> startAutoValidate(
            @RequestParam(defaultValue = "test") String env) {
        if (reconciliationJobRegistry != null) {
            boolean started = reconciliationJobRegistry.startRun(ReconciliationType.VALIDATE, env, 1.0, "PROCES VALIDACIO AUTOMATICA");
            if (!started) {
                return Map.of("started", false, "reason", "already-running");
            }
            startAutoValidateRun(env);
            executeAutoValidate(env);
            return Map.of("started", true);
        }
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
        if (reconciliationJobRegistry != null) {
            boolean stopped = reconciliationJobRegistry.requestStop(ReconciliationType.VALIDATE);
            if (!stopped) {
                return Map.of("stopped", false, "reason", "not-running");
            }
            autoValidateRunning = false;
            logValidateMessage("Petició d'aturar el procés rebuda.");
            return Map.of("stopped", true);
        }
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
        if (reconciliationJobRegistry != null) {
            return reconciliationJobRegistry.getProgress(ReconciliationType.VALIDATE).toMap();
        }
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
            java.io.File file = (reconciliationJobRegistry != null)
                ? reconciliationJobRegistry.getLogFile(ReconciliationType.VALIDATE)
                : new java.io.File(autoValidateLogFilePath);
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
        if (reconciliationJobRegistry != null) {
            reconciliationJobRegistry.logMessage(ReconciliationType.VALIDATE, message);
        }
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

                Query query = new Query(new Criteria().andOperator(notApproved, hasType, hasCountry, hasFunding)).with(buildNameSort());
                query.fields().include("uuid").include("name").include("displayName").include("workflow");
                
                List<ExternalOrganization> orgs = mongoTemplate.find(query, ExternalOrganization.class);
                orgs.sort(Comparator.comparing(this::extractOrgName, String.CASE_INSENSITIVE_ORDER));
                
                synchronized(autoValidateLock) {
                    autoValidateTotal = orgs.size();
                }
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.setTotal(ReconciliationType.VALIDATE, orgs.size());
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
                                if (reconciliationJobRegistry != null) {
                                    reconciliationJobRegistry.incrementApplied(ReconciliationType.VALIDATE);
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
                            if (reconciliationJobRegistry != null) {
                                reconciliationJobRegistry.incrementProcessed(ReconciliationType.VALIDATE);
                            }
                        }
                    }, asyncExecutor);
                    
                    futures.add(future);
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
                String summary = "Procés de validació automàtica finalitzat. Total processades: " + autoValidateProcessed + ", Validada/es: " + autoValidateApplied;
                logValidateMessage(summary);
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.finishRun(ReconciliationType.VALIDATE, summary);
                }
                invalidateMetadataCatalogCaches();
            } catch (Exception e) {
                logValidateMessage("[FATAL ERROR] " + e.getMessage());
                if (reconciliationJobRegistry != null) {
                    reconciliationJobRegistry.failRun(ReconciliationType.VALIDATE, e.getMessage());
                }
            } finally {
                synchronized(autoValidateLock) {
                    autoValidateRunning = false;
                }
            }
        });
    }
}