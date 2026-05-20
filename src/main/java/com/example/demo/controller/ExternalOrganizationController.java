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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/external-organizations")
@CrossOrigin(origins = "*")
public class ExternalOrganizationController {

    // -------------------------------------------------------------------------
    // TTL cache — thread-safe via AtomicReference + double-check (mejora #1)
    // -------------------------------------------------------------------------
    private record CachedSet<T>(Set<T> data, long timestamp) {}
    private record CachedInference(String result, long timestamp) {}

    private static final long REFERENCE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final long GRAPH_SIMILARITY_CACHE_TTL_MS = 2 * 60 * 1000;
    private static final long INFERENCE_CACHE_TTL_MS = 10 * 60 * 1000;  // 10 minutos para inferencias

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

    @Autowired
    public ExternalOrganizationController(
            ExternalOrganizationRepository repository,
            MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
            @RequestParam(defaultValue = "20") int size) {

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

        PageRequest pageable = PageRequest.of(page, size, buildNameSort());
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
            @RequestParam(defaultValue = "20") int size) {

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

        PageRequest pageable = PageRequest.of(page, size, buildNameSort());
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
            return Map.of(
                "suggestedCountry", "", "suggestedCountryUri", "", "countryConfidence", 0.0, "countryReason", "empty-name",
                "suggestedType",   "",   "typeConfidence",   0.0, "typeReason",   "empty-name"
            );
        }

        // Mejora #4: catálogos cacheados
        List<CountryAggregate> countryCatalog = getCachedCountryCatalog();
        List<TypeAggregate>    typeCatalog    = getCachedTypeCatalog();

        InferenceResult     countryResult = inferCountryFromName(orgName, countryCatalog);
        TypeInferenceResult typeResult    = inferTypeFromName(orgName, typeCatalog);

        if (countryResult.countryLabel.isBlank() || typeResult.typeLabel.isBlank()) {
            MetadataInferenceResult aiResult = inferMetadataWithLocalAi(orgName, countryCatalog, typeCatalog);
            if (aiResult != null) {
                if (countryResult.countryLabel.isBlank()
                        && aiResult.country() != null && !aiResult.country().countryLabel.isBlank()) {
                    countryResult = aiResult.country();
                }
                if (typeResult.typeLabel.isBlank()
                        && aiResult.type() != null && !aiResult.type().typeLabel.isBlank()) {
                    typeResult = aiResult.type();
                }
            }
        }

        String suggestedCountryUri = resolveCountryUriByLabel(countryResult.countryLabel);

        return Map.of(
            "suggestedCountry", countryResult.countryLabel,
            "suggestedCountryUri", suggestedCountryUri,
            "countryConfidence", countryResult.confidence,
            "countryReason", countryResult.reason,
            "suggestedType", typeResult.typeLabel,
            "typeConfidence", typeResult.confidence,
            "typeReason", typeResult.reason
        );
    }

    @GetMapping("/stats/type-catalog")
    public List<Map<String, String>> typeCatalog() {
        return repository.findAll().stream()
            .map(ExternalOrganization::getType)
            .filter(Objects::nonNull)
            .map(t -> Map.of(
                "uri", Objects.toString(t.getUri(), "").trim(),
                "label", Objects.toString(
                    t.getTerm() == null ? "" :
                        t.getTerm().getOrDefault("ca_ES",
                            t.getTerm().getOrDefault("es_ES",
                                t.getTerm().getOrDefault("en_GB", ""))),
                    "").trim()))
            .filter(row -> !Objects.toString(row.get("uri"), "").isBlank()
                && !Objects.toString(row.get("label"), "").isBlank())
            .distinct()
            .sorted(Comparator.comparing(r -> Objects.toString(r.get("label"), ""), String.CASE_INSENSITIVE_ORDER))
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

        List<ExternalOrganization> allOrgs = repository.findAll();
        int totalAvailableOrgs = allOrgs.size();
        List<ExternalOrganization> orgs = allOrgs.stream().limit(limit).toList();

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

        List<ExternalOrganization> allOrgs = repository.findAll();
        int totalAvailableOrgs = allOrgs.size();
        List<ExternalOrganization> orgs = allOrgs.stream().limit(limit).toList();
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
        });

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

        List<ExternalOrganization> allOrgs = repository.findAll();
        int totalAvailableOrgs = allOrgs.size();
        List<ExternalOrganization> orgs = allOrgs.stream().limit(limit).toList();

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
    private record OrgSimilarityProfile(String id, String displayName, Long pureId, String normalizedName, String[] tokens, String typeLabel, String countryLabel) {}

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
                getOrgCountryLabel(org)
            ));
        }
        return profiles;
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

                pairs.add(Map.of(
                    "from", id1,
                    "to", id2,
                    "leftLabel", p1.displayName(),
                    "rightLabel", p2.displayName(),
                    "leftPureId", p1.pureId() == null ? "" : String.valueOf(p1.pureId()),
                    "rightPureId", p2.pureId() == null ? "" : String.valueOf(p2.pureId()),
                    "value", Math.round(similarity * 100.0) / 100.0,
                    "title", "Similitud: " + Math.round(similarity * 100.0) + "%"
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

    private int levenshteinDistance(String a, String b) {
        int[] x = new int[b.length() + 1];
        int[] y = new int[b.length() + 1];
        for (int i = 1; i <= b.length(); i++) x[i] = i;

        for (int i = 1; i <= a.length(); i++) {
            y[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                y[j] = Math.min(Math.min(y[j - 1] + 1, x[j] + 1), x[j - 1] + cost);
            }
            int[] t = x; x = y; y = t;
        }
        return x[b.length()];
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
            () -> inferCountryWithLocalAi(orgName, catalog),
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
        Map.entry("spain",          List.of("spain", "espana", "espanya")),
        Map.entry("france",         List.of("france", "francia", "franca")),
        Map.entry("germany",        List.of("germany", "deutschland", "alemania", "alemanya")),
        Map.entry("italy",          List.of("italy", "italia")),
        Map.entry("portugal",       List.of("portugal")),
        Map.entry("united_kingdom", List.of("united kingdom", "uk", "great britain", "britain", "england", "scotland", "wales")),
        Map.entry("united_states",  List.of("united states", "usa", "u s a", "eeuu", "estados unidos", "us")),
        Map.entry("netherlands",    List.of("netherlands", "holland", "holanda", "paises bajos", "paisos baixos")),
        Map.entry("belgium",        List.of("belgium", "belgica", "belgique")),
        Map.entry("switzerland",    List.of("switzerland", "swiss", "suiza", "suissa", "suisse")),
        Map.entry("austria",        List.of("austria", "osterreich")),
        Map.entry("ireland",        List.of("ireland", "irlanda")),
        Map.entry("denmark",        List.of("denmark", "dinamarca")),
        Map.entry("sweden",         List.of("sweden", "suecia", "sverige")),
        Map.entry("norway",         List.of("norway", "noruega", "norge")),
        Map.entry("finland",        List.of("finland", "finlandia", "suomi")),
        Map.entry("poland",         List.of("poland", "polonia", "polska")),
        Map.entry("czech_republic", List.of("czech republic", "czechia", "republica checa", "txec")),
        Map.entry("china",          List.of("china")),
        Map.entry("japan",          List.of("japan", "japon", "japo")),
        Map.entry("south_korea",    List.of("south korea", "korea", "corea")),
        Map.entry("india",          List.of("india")),
        Map.entry("mexico",         List.of("mexico", "mexic")),
        Map.entry("brazil",         List.of("brazil", "brasil")),
        Map.entry("argentina",      List.of("argentina")),
        Map.entry("chile",          List.of("chile")),
        Map.entry("colombia",       List.of("colombia")),
        Map.entry("canada",         List.of("canada"))
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

    // =========================================================================
    // Records de inferencia
    // =========================================================================

    private record CountryAggregate(String label, String normalizedLabel, int count) {}
    private record TypeAggregate   (String label, String normalizedLabel, int count) {}
    private record InferenceResult    (String countryLabel, double confidence, String reason) {}
    private record TypeInferenceResult(String typeLabel,   double confidence, String reason) {}
    private record MetadataInferenceResult(InferenceResult country, TypeInferenceResult type) {}

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

        return catalog.stream()
            .filter(c -> aliases.stream().anyMatch(alias ->
                c.normalizedLabel.contains(alias) || alias.contains(c.normalizedLabel)))
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

    private List<TypeAggregate> buildTypeCatalog() {
        return statsByType().stream()
            .map(row -> {
                String label = Objects.toString(row.get("label"), "").trim();
                int count = ((Number) row.getOrDefault("count", 0)).intValue();
                return new TypeAggregate(label, normalizeText(label), count);
            })
            .filter(t -> !t.label.isBlank() && !"(desconegut)".equalsIgnoreCase(t.label))
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
    // Inferencia con AI local (mejora #6/#7/#8: async, caché, timeout corto)
    // =========================================================================

    /**
     * Mejora #6/#7: método centralizado con:
     * - CompletableFuture para async (no bloquea thread)
     * - Timeout corto (2 segundos, no 8) para fallback rápido a heurística
     * - Caché por prompt para evitar llamadas repetidas
     */
    private CompletableFuture<String> callLocalAiApiAsync(String prompt) {
        if (!aiCountrySuggestEnabled || aiCountrySuggestUrl == null || aiCountrySuggestUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", aiCountrySuggestModel == null || aiCountrySuggestModel.isBlank()
                    ? "local-model" : aiCountrySuggestModel);
                payload.put("temperature", 0);
                payload.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a data quality assistant."),
                    Map.of("role", "user",   "content", prompt)
                ));
                payload.put("response_format", Map.of("type", "json_object"));

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(aiCountrySuggestUrl))
                    .timeout(Duration.ofMillis(aiCountrySuggestTimeoutMs))  // Usa timeout más corto (2000ms por defecto)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

                if (aiCountrySuggestApiKey != null && !aiCountrySuggestApiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + aiCountrySuggestApiKey.trim());
                }

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
                return response.body();
            } catch (Exception ignored) {
                return null;
            }
        }).orTimeout(aiCountrySuggestTimeoutMs, TimeUnit.MILLISECONDS)
          .exceptionally(e -> null);
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

        // Mejora #8: Limitar catálogo a top-20 (no 120) para reducir tamaño del prompt
        String catalogText = catalog.stream().limit(20).map(c -> c.label).collect(Collectors.joining(", "));
        String prompt = "Given an organization name, infer the most likely country. "
            + "Return strict JSON with keys suggestedCountry, confidence, reason. "
            + "confidence must be between 0 and 1. "
            + "If uncertain return suggestedCountry as empty string and confidence 0. "
            + "Organization: '" + orgName + "'. "
            + "Candidate countries: " + catalogText;

        // Mejora #7: Usar async con await y timeout corto
        String body = null;
        try {
            body = callLocalAiApiAsync(prompt)
                .get(aiCountrySuggestTimeoutMs + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout o error - continuará con null
        }
        
        // Cachear resultado incluso si es null
        if (body != null) {
            countryInferenceCache.put(cacheKey, new CachedInference(body, System.currentTimeMillis()));
            InferenceResult result = parseLocalAiResponse(body, catalog);
            return (result == null || result.countryLabel.isBlank()) ? null : result;
        }
        
        return null;
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
        String prompt = "Given an organization name, infer the most likely organization type. "
            + "Return strict JSON with keys suggestedType, confidence, reason. "
            + "confidence must be between 0 and 1. "
            + "If uncertain return suggestedType as empty string and confidence 0. "
            + "Organization: '" + orgName + "'. "
            + "Candidate types: " + catalogText;

        // Mejora #7: Usar async con await y timeout corto
        String body = null;
        try {
            body = callLocalAiApiAsync(prompt)
                .get(aiCountrySuggestTimeoutMs + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout o error - continuará con null
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

        // Mejora #8: Limitar catálogo a top-20 (no 120/80) para reducir tamaño del prompt
        String countryCatalogText = countryCatalog.stream().limit(20).map(c -> c.label).collect(Collectors.joining(", "));
        String typeCatalogText    = typeCatalog.stream().limit(20).map(t -> t.label).collect(Collectors.joining(", "));
        String prompt = "Given an organization name, infer the most likely country and organization type. "
            + "Return strict JSON with keys suggestedCountry, countryConfidence, countryReason, suggestedType, typeConfidence, typeReason. "
            + "Confidence values must be between 0 and 1. "
            + "If uncertain, return the corresponding suggested field as empty string and its confidence as 0. "
            + "Organization: '" + orgName + "'. "
            + "Candidate countries: " + countryCatalogText + ". "
            + "Candidate types: " + typeCatalogText;

        // Mejora #7: Usar async con await y timeout corto
        String body = null;
        try {
            body = callLocalAiApiAsync(prompt)
                .get(aiCountrySuggestTimeoutMs + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout o error - continuará con null
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

        return (countryResult == null && typeResult == null) ? null
            : new MetadataInferenceResult(countryResult, typeResult);
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

        return catalog.stream()
            .filter(t -> t.normalizedLabel.equals(target))
            .map(TypeAggregate::label)
            .findFirst()
            .orElse("");
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
            Sort.Order.asc("name.en_GB"),
            Sort.Order.asc("displayName")
        );
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
}