package com.example.demo.controller;

// 1. Modelos y Repositorios propios
import com.example.demo.model.Publicacion;
import com.example.demo.model.PublicacionCorreccion;
import com.example.demo.repository.PublicacionRepository;
import com.example.demo.repository.PublicacionCorreccionRepository;
import com.example.demo.service.ResearchOutputJournalLinkService;


// 2. Spring Web (Anotaciones del Controlador)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// 3. Spring Data - Paginación y Ordenación
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;

// 4. Spring Data MongoDB - Motor de consultas y Agregaciones (Gráficos)
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.http.ResponseEntity;

// 5. Utilidades de Java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/pure")
@CrossOrigin(origins = "*")
public class PublicacionController {

    @Value("${app.scopus.api-key:}")
    private String scopusApiKey;

    private final PublicacionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;
    private final PublicacionCorreccionRepository publicacionCorreccionRepository;

    // Cache for apa-list: key → (result, timestamp)
    private record ApaListCacheEntry(List<Map<String, Object>> data, long timestamp) {}
    private record StatsCacheEntry(List<Map> data, long timestamp) {}
    private final Map<String, ApaListCacheEntry> apaListCache = new ConcurrentHashMap<>();
    private final Map<String, StatsCacheEntry> tiposPorAnioCache = new ConcurrentHashMap<>();
    private final Map<String, StatsCacheEntry> personaResumenCache = new ConcurrentHashMap<>();
    private static final long APA_LIST_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final long STATS_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    @Autowired
    public PublicacionController(
            PublicacionRepository repository,
            MongoTemplate mongoTemplate,
            ResearchOutputJournalLinkService researchOutputJournalLinkService,
            PublicacionCorreccionRepository publicacionCorreccionRepository) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
        this.publicacionCorreccionRepository = publicacionCorreccionRepository;
    }

    private String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid) {
        return buildStatsCacheKey(desde, hasta, deptUuid, null, false);
    }

    private String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid, String filtrePersonal) {
        return buildStatsCacheKey(desde, hasta, deptUuid, filtrePersonal, false);
    }

    private String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid, String filtrePersonal, boolean managedByDepartment) {
        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();
        String fp = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal;
        return String.valueOf(desde) + "_" + String.valueOf(hasta) + "_" + String.join(",", deptFilter) + "_" + fp + "_" + managedByDepartment;
    }

    private Document buildPersonAssocCriteria(String filtrePersonal, Integer desde, Integer hasta) {
        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        if (usePeriode && (desde != null || hasta != null)) {
            List<Document> conditions = new ArrayList<>();
            if (desde != null) {
                String desdeIso = desde + "-01-01";
                Date desdeDate = Date.from(LocalDate.of(desde, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant());
                conditions.add(new Document("$or", List.of(
                    new Document("period.endDate", null),
                    new Document("period.endDate", new Document("$exists", false)),
                    new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 9)),
                        new Document("period.endDate", new Document("$gte", desdeDate))
                    )),
                    new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 2)),
                        new Document("period.endDate", new Document("$gte", desdeIso))
                    ))
                )));
            }
            if (hasta != null) {
                String hastaIso = hasta + "-12-31";
                Date hastaDate = Date.from(LocalDate.of(hasta, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant());
                conditions.add(new Document("$or", List.of(
                    new Document("period.startDate", null),
                    new Document("period.startDate", new Document("$exists", false)),
                    new Document("$and", List.of(
                        new Document("period.startDate", new Document("$type", 9)),
                        new Document("period.startDate", new Document("$lte", hastaDate))
                    )),
                    new Document("$and", List.of(
                        new Document("period.startDate", new Document("$type", 2)),
                        new Document("period.startDate", new Document("$lte", hastaIso))
                    ))
                )));
            }
            return conditions.size() == 1 ? conditions.get(0) : new Document("$and", conditions);
        } else {
            LocalDate today = LocalDate.now();
            Date todayDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
            String todayIso = today.toString();
            return new Document("$or", Arrays.asList(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", Arrays.asList(
                    new Document("period.endDate", new Document("$type", 9)),
                    new Document("period.endDate", new Document("$gt", todayDate))
                )),
                new Document("$and", Arrays.asList(
                    new Document("period.endDate", new Document("$type", 2)),
                    new Document("period.endDate", new Document("$gt", todayIso))
                ))
            ));
        }
    }

    @GetMapping
    public Page<Publicacion> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        PageRequest pageable = PageRequest.of(page, size, Sort.by("submissionYear").descending());
        
        if (buscar.isEmpty()) {
            return repository.findAll(pageable);
        } else {
            return repository.findByTitleValueContainingIgnoreCase(buscar, pageable);
        }
    }

    @GetMapping("/search")
    public Page<Publicacion> buscarPorAnio(
            @RequestParam(required = false) Integer anio,
            @RequestParam(defaultValue = "0") int page) {
        
        // Configuramos para traer 10 resultados, ordenados por los más nuevos
        Pageable pageable = PageRequest.of(page, 10, Sort.by("modifiedDate").descending());

        if (anio != null) {
            return repository.findBySubmissionYear(anio, pageable);
        } else {
            return repository.findAll(pageable);
        }
    }

    @GetMapping("/{publicationUuid}/raw")
    public ResponseEntity<Object> getRawDocument(@PathVariable String publicationUuid) {
        Document doc = mongoTemplate.findOne(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(publicationUuid)),
                Document.class, "Researchoutputs");
        if (doc == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(doc.toJson());
    }

    @GetMapping("/{publicationUuid}/journal-jcr")
    public ResponseEntity<Map<String, Object>> getJournalAndJcrByPublication(@PathVariable String publicationUuid) {
        return researchOutputJournalLinkService.linkByPublicationUuid(publicationUuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/journal-jcr/resumen")
    public Map<String, Object> getJournalJcrSummary(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("submissionYear").descending());
        Page<Publicacion> publications;

        if (buscar == null || buscar.isBlank()) {
            publications = repository.findAll(pageable);
        } else {
            publications = repository.findByTitleValueContainingIgnoreCase(buscar, pageable);
        }

        List<Map<String, Object>> items = publications.getContent().stream()
                .map(publicacion -> researchOutputJournalLinkService
                        .summarizeByPublicationUuid(publicacion.getId())
                        .orElseGet(() -> {
                            Map<String, Object> fallback = new LinkedHashMap<>();
                            fallback.put("publicationUuid", publicacion.getId());
                            fallback.put("publicationTitle", publicacion.getFullTitle());
                            fallback.put("publicationYear", publicacion.getSubmissionYear());
                            fallback.put("journalFound", false);
                            fallback.put("jcrCount", 0);
                            return fallback;
                        }))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", publications.getNumber());
        response.put("size", publications.getSize());
        response.put("totalElements", publications.getTotalElements());
        response.put("totalPages", publications.getTotalPages());
        response.put("items", items);
        return response;
    }

    @GetMapping("/stats/quartiles")
    public List<Map<String, Object>> getQuartilesByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartileDistributionByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/quartiles/articles")
    public List<Map<String, Object>> getQuartileArticlesByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartileArticlesByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/quartiles/evolution")
    public List<Map<String, Object>> getQuartileEvolutionByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartileEvolutionByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/quartiles/dashboard")
    public Map<String, Object> getQuartilesDashboardByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartilesDashboardByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }
    
    // Estadísticas para Gráfico de Líneas (Años)
@GetMapping("/stats/years")
public List<Map> statsAnios() {
    try {
        Aggregation agg = Aggregation.newAggregation(
            // Cambia esto por el nombre exacto del campo en tu JSON de Mongo
            Aggregation.group("publicationDate.year").count().as("total"),
            Aggregation.project("total").and("_id").as("anio"),
            Aggregation.sort(Sort.Direction.ASC, "anio")
        );

        // MUY IMPORTANTE: Cambia "researchoutputs" por el nombre real de tu colección
        // que ves en MongoDB Compass. A veces es "Researchoutputs" (con R mayúscula)
        AggregationResults<Map> results = mongoTemplate.aggregate(agg, "Researchoutputs", Map.class);
        return results.getMappedResults();
            } catch (Exception e) {
            // Esto imprimirá el error real en tu consola de Java
            e.printStackTrace();
            return List.of(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats/scopus")
    public Map<String, Object> statsScopus() {
        Map<String, Object> response = new LinkedHashMap<>();
        if (scopusApiKey == null || scopusApiKey.isBlank()) {
            response.put("error", "API Key not configured");
            return response;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            for (int year = 2020; year <= 2026; year++) {
                String query = "AF-ID(60012977) AND PUBYEAR(" + year + ")";
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                String urlString = "https://api.elsevier.com/content/search/scopus?query=" + encodedQuery;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .header("X-ELS-APIKey", scopusApiKey.trim())
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(8))
                        .GET()
                        .build();

                HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (httpResponse.statusCode() == 200) {
                    Document doc = Document.parse(httpResponse.body());
                    Document searchResults = (Document) doc.get("search-results");
                    if (searchResults != null) {
                        String totalResultsStr = searchResults.getString("opensearch:totalResults");
                        if (totalResultsStr != null) {
                            try {
                                int count = Integer.parseInt(totalResultsStr);
                                response.put(String.valueOf(year), count);
                            } catch (NumberFormatException e) {
                                response.put(String.valueOf(year), 0);
                            }
                        }
                    }
                } else {
                    response.put("error", "HTTP " + httpResponse.statusCode() + " from Scopus API");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("error", e.getMessage());
        }

        return response;
    }

    // Estadísticas para Gráfico de Donut (Tipos)
    @GetMapping("/stats/types")
    public List<Map> statsTipos() {
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.group("type.term.ca_ES").count().as("total"),
            Aggregation.project("total").and("_id").as("tipo")
        );
        return mongoTemplate.aggregate(agg, "Researchoutputs", Map.class).getMappedResults();
    }

    @GetMapping("/stats/types-by-year")
    public List<Map> statsTiposPorAnio(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid,
            @RequestParam(required = false, defaultValue = "vigent") String filtrePersonal,
            @RequestParam(required = false, defaultValue = "false") boolean managedByDepartment) {

        String cacheKey = buildStatsCacheKey(desde, hasta, deptUuid, filtrePersonal, managedByDepartment);
        StatsCacheEntry cached = tiposPorAnioCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < STATS_CACHE_TTL_MS) {
            return cached.data();
        }

        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();

        List<Document> pipeline = new ArrayList<>();

        if (managedByDepartment && !deptFilter.isEmpty()) {
            List<Document> andFilters = new ArrayList<>();
            andFilters.add(new Document("workflow.step", "approved"));
            andFilters.add(new Document("managingOrganization.uuid", new Document("$in", deptFilter)));

            pipeline.add(new Document("$match", new Document("$and", andFilters)));

            pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList(
                    new Document("$arrayElemAt", Arrays.asList("$publicationStatuses.publicationDate.year", 0)),
                    new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear"))
                )))
                .append("tipoPublicacion", new Document("$ifNull", Arrays.asList(
                    "$type.term.ca_ES",
                    new Document("$ifNull", Arrays.asList("$type.term.ca_ES", "Sense tipus"))
                )))));

            List<Document> yearFilters = new ArrayList<>();
            yearFilters.add(new Document("$expr", new Document("$gt", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$publicationYear", 0)), 0
            ))));
            if (desde != null) {
            yearFilters.add(new Document("publicationYear", new Document("$gte", desde)));
            }
            if (hasta != null) {
            yearFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
            }
            pipeline.add(new Document("$match", new Document("$and", yearFilters)));

            // Equivalente a count(distinct r.uuid) por any/tipus
            pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationYear", "$publicationYear")
                .append("tipoPublicacion", "$tipoPublicacion")
                .append("publicationUuid", "$publicationUuid"))));

            pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

            pipeline.add(new Document("$project", new Document("_id", 0)
                .append("anio", "$_id.publicationYear")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("num_publicaciones", "$totalPublicaciones")
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

            pipeline.add(new Document("$sort", new Document("anio", -1)
                .append("tipo_publicacion", 1)));

            List<Map> result = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

            tiposPorAnioCache.put(cacheKey, new StatsCacheEntry(result, System.currentTimeMillis()));
            return result;
        }

        pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

        if (managedByDepartment && !deptFilter.isEmpty()) {
            pipeline.add(new Document("$match", new Document("managingOrganization.uuid", new Document("$in", deptFilter))));
        }

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList(
                    new Document("$arrayElemAt", Arrays.asList("$publicationStatuses.publicationDate.year", 0)),
                    new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear"))
                )))
                .append("tipoPublicacion", new Document("$ifNull", Arrays.asList(
                    "$type.term.ca_ES",
                    new Document("$ifNull", Arrays.asList("$type.term.ca_ES", "Sense tipus"))
                )))
                .append("managingOrgUuid", "$managingOrganization.uuid")
                .append("contributors", new Document("$ifNull", Arrays.asList("$contributors", List.of())))));

        List<Document> andFilters = new ArrayList<>();
        if (desde != null) {
            andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
        }
        if (hasta != null) {
            andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
        }
        if (!andFilters.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", andFilters)));
        }

        pipeline.add(new Document("$unwind", "$contributors"));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", 1)
                .append("publicationYear", 1)
                .append("tipoPublicacion", 1)
            .append("managingOrgUuid", 1)
                .append("personUuid", new Document("$trim", new Document("input",
                        new Document("$ifNull", Arrays.asList(
                                "$contributors.person.uuid",
                                new Document("$ifNull", Arrays.asList("$contributors.externalPerson.uuid", "")))))))));

        pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(
                new Document("$strLenCP", "$personUuid"), 0
        )))));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Persons")
                .append("localField", "personUuid")
                .append("foreignField", "uuid")
                .append("as", "persona_info")));

        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$persona_info")
                .append("preserveNullAndEmptyArrays", false)));

        Document assocCriteria = buildPersonAssocCriteria(filtrePersonal, desde, hasta);

        if (!deptFilter.isEmpty()) {
            Document assocMatch = new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", new Document("$and", Arrays.asList(
                    new Document("organization.uuid", new Document("$in", deptFilter)),
                    assocCriteria
                )))
            );
            Document managingMatch = new Document("managingOrgUuid", new Document("$in", deptFilter));
            pipeline.add(new Document("$match", new Document("$or", Arrays.asList(assocMatch, managingMatch))));
        } else {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", assocCriteria)
            )));
        }

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationUuid", "$publicationUuid")
                .append("publicationYear", "$publicationYear")
                .append("tipoPublicacion", "$tipoPublicacion"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

        pipeline.add(new Document("$project", new Document("_id", 0)
                .append("anio", "$_id.publicationYear")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("num_publicaciones", "$totalPublicaciones")
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

        pipeline.add(new Document("$sort", new Document("anio", 1)
                .append("tipo_publicacion", 1)));

        List<Map> result = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        tiposPorAnioCache.put(cacheKey, new StatsCacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    @GetMapping("/stats/persona-resumen")
    public List<Map> statsPersonaResumen(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid) {

        String cacheKey = buildStatsCacheKey(desde, hasta, deptUuid);
        StatsCacheEntry cached = personaResumenCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < STATS_CACHE_TTL_MS) {
            return cached.data();
        }

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))
                .append("tipoPublicacion", new Document("$ifNull", Arrays.asList("$type.term.ca_ES", "Sense tipus")))
                .append("contributors", new Document("$ifNull", Arrays.asList("$contributors", List.of())))));

        List<Document> andFilters = new ArrayList<>();
        if (desde != null) {
            andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
        }
        if (hasta != null) {
            andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
        }
        if (!andFilters.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", andFilters)));
        }

        pipeline.add(new Document("$unwind", "$contributors"));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", 1)
                .append("tipoPublicacion", 1)
                .append("personUuid", new Document("$trim", new Document("input",
                        new Document("$ifNull", Arrays.asList(
                                "$contributors.person.uuid",
                                new Document("$ifNull", Arrays.asList("$contributors.externalPerson.uuid", "")))))))
                .append("personaContributor", new Document("$trim", new Document("input",
                        new Document("$concat", Arrays.asList(
                                new Document("$ifNull", Arrays.asList("$contributors.name.lastName", "")),
                                new Document("$cond", Arrays.asList(
                                    new Document("$gt", Arrays.asList(new Document("$strLenCP", new Document("$ifNull", Arrays.asList("$contributors.name.firstName", ""))), 0)),
                                    ", ",
                                    ""
                                )),
                                new Document("$ifNull", Arrays.asList("$contributors.name.firstName", ""))
                        ))).append("chars", " ")))));

        pipeline.add(new Document("$match", new Document("$expr", new Document("$or", Arrays.asList(
                new Document("$gt", Arrays.asList(new Document("$strLenCP", "$personUuid"), 0)),
                new Document("$gt", Arrays.asList(new Document("$strLenCP", "$personaContributor"), 0))
        )))));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Persons")
                .append("localField", "personUuid")
                .append("foreignField", "uuid")
                .append("as", "persona_info")));

        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$persona_info")
                .append("preserveNullAndEmptyArrays", true)));

        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();

        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();
        Date hoyDate = Date.from(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant());

        Document activeAssociationCriteria = new Document("$or", Arrays.asList(
            new Document("period.endDate", null),
            new Document("period.endDate", new Document("$exists", false)),
            new Document("$and", Arrays.asList(
                new Document("period.endDate", new Document("$type", 9)),
                new Document("period.endDate", new Document("$gt", hoyDate))
            )),
            new Document("$and", Arrays.asList(
                new Document("period.endDate", new Document("$type", 2)),
                new Document("period.endDate", new Document("$gt", hoyIso))
            ))
        ));

        if (!deptFilter.isEmpty()) {
            Document assocCriteria = new Document("$and", Arrays.asList(
                new Document("organization.uuid", new Document("$in", deptFilter)),
                activeAssociationCriteria
            ));

            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", assocCriteria)
            )));
        } else {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", activeAssociationCriteria)
            )));
        }

        Document nomPersona = new Document("$trim", new Document("input",
            new Document("$concat", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$persona_info.name.lastName", "")),
                new Document("$cond", Arrays.asList(
                    new Document("$gt", Arrays.asList(new Document("$strLenCP", new Document("$ifNull", Arrays.asList("$persona_info.name.firstName", ""))), 0)),
                    ", ",
                    ""
                )),
                new Document("$ifNull", Arrays.asList("$persona_info.name.firstName", ""))
            ))).append("chars", " "));

        Document nomContributor = new Document("$trim", new Document("input",
            new Document("$ifNull", Arrays.asList("$personaContributor", ""))).append("chars", " "));

        Document personaExpr = new Document("$cond", Arrays.asList(
            new Document("$gt", Arrays.asList(new Document("$strLenCP", nomPersona), 0)),
            nomPersona,
            new Document("$cond", Arrays.asList(
                new Document("$gt", Arrays.asList(new Document("$strLenCP", nomContributor), 0)),
                nomContributor,
                "$personUuid"
            ))
        ));

        pipeline.add(new Document("$project", new Document()
            .append("publicationUuid", 1)
            .append("publicationYear", 1)
            .append("personUuid", 1)
            .append("tipoPublicacion", 1)
            .append("persona", personaExpr)));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationUuid", "$publicationUuid")
            .append("publicationYear", "$publicationYear")
                .append("personUuid", "$personUuid")
                .append("tipoPublicacion", "$tipoPublicacion")
                .append("persona", "$persona"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
            .append("publicationYear", "$_id.publicationYear")
                .append("personUuid", "$_id.personUuid")
                .append("persona", "$_id.persona")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

        pipeline.add(new Document("$project", new Document("_id", 0)
            .append("anio", "$_id.publicationYear")
                .append("person_uuid", "$_id.personUuid")
                .append("nombre", "$_id.persona")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("num_publicaciones", "$totalPublicaciones")
                // Alias para el frontend actual
            .append("publicationYear", "$_id.publicationYear")
                .append("personaUuid", "$_id.personUuid")
                .append("persona", "$_id.persona")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

        pipeline.add(new Document("$sort", new Document("nombre", 1)
                .append("tipo_publicacion", 1)));
        // "nombre" is now "Cognom, Nom" so sort is alphabetical by surname

        List<Map> result = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        personaResumenCache.put(cacheKey, new StatsCacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    @GetMapping("/stats/apa")
    public List<Map<String, Object>> statsApaList(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid,
            @RequestParam(required = false, defaultValue = "vigent") String filtrePersonal,
            @RequestParam(required = false, defaultValue = "false") boolean managedByDepartment) {

        // Check cache first
        String fp = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal;
        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
            .filter(v -> v != null && !v.isBlank())
            .toList();
        String cacheKey = desde + "_" + hasta + "_" + String.join(",", deptFilter) + "_" + fp + "_" + managedByDepartment;
        ApaListCacheEntry cached = apaListCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < APA_LIST_CACHE_TTL_MS) {
            return cached.data();
        }

        // Step 1: Get unique publication UUIDs with year
        List<Document> rows;

        if (managedByDepartment && !deptFilter.isEmpty()) {
            List<Document> pipeline = new ArrayList<>();
            pipeline.add(new Document("$match", new Document("$and", Arrays.asList(
                    new Document("workflow.step", "approved"),
                    new Document("managingOrganization.uuid", new Document("$in", deptFilter))
            ))));

            pipeline.add(new Document("$project", new Document()
                    .append("publicationUuid", "$uuid")
                    .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))));

            List<Document> yearFilters = new ArrayList<>();
            yearFilters.add(new Document("$expr", new Document("$gt", Arrays.asList(
                    new Document("$ifNull", Arrays.asList("$publicationYear", 0)), 0
            ))));
            if (desde != null) {
                yearFilters.add(new Document("publicationYear", new Document("$gte", desde)));
            }
            if (hasta != null) {
                yearFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
            }
            pipeline.add(new Document("$match", new Document("$and", yearFilters)));

            // Equivalente a distinct uuid con anyo
            pipeline.add(new Document("$group", new Document("_id", "$publicationUuid")
                    .append("publicationYear", new Document("$first", "$publicationYear"))));

            rows = mongoTemplate
                    .getCollection("Researchoutputs")
                    .aggregate(pipeline)
                    .into(new ArrayList<>());
        } else {
            List<Document> pipeline = new ArrayList<>();

            pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

            pipeline.add(new Document("$project", new Document()
                    .append("publicationUuid", "$uuid")
                    .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))
                    .append("managingOrgUuid", "$managingOrganization.uuid")
                    .append("contributors", new Document("$ifNull", Arrays.asList("$contributors", List.of())))));

            List<Document> andFilters = new ArrayList<>();
            if (desde != null) {
                andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
            }
            if (hasta != null) {
                andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
            }
            if (!andFilters.isEmpty()) {
                pipeline.add(new Document("$match", new Document("$and", andFilters)));
            }

            pipeline.add(new Document("$unwind", "$contributors"));

            pipeline.add(new Document("$project", new Document()
                    .append("publicationUuid", 1)
                    .append("publicationYear", 1)
                    .append("managingOrgUuid", 1)
                    .append("personUuid", new Document("$trim", new Document("input",
                            new Document("$ifNull", Arrays.asList(
                                    "$contributors.person.uuid",
                                    new Document("$ifNull", Arrays.asList("$contributors.externalPerson.uuid", "")))))))));

            pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(
                    new Document("$strLenCP", "$personUuid"), 0
            )))));

            pipeline.add(new Document("$lookup", new Document()
                    .append("from", "Persons")
                    .append("localField", "personUuid")
                    .append("foreignField", "uuid")
                    .append("as", "persona_info")));

            pipeline.add(new Document("$unwind", new Document()
                    .append("path", "$persona_info")
                    .append("preserveNullAndEmptyArrays", false)));

            Document assocCriteriaApa = buildPersonAssocCriteria(filtrePersonal, desde, hasta);

            if (!deptFilter.isEmpty()) {
                Document assocMatchApa = new Document(
                    "persona_info.staffOrganizationAssociations",
                    new Document("$elemMatch", new Document("$and", Arrays.asList(
                        new Document("organization.uuid", new Document("$in", deptFilter)),
                        assocCriteriaApa
                    )))
                );
                Document managingMatchApa = new Document("managingOrgUuid", new Document("$in", deptFilter));
                pipeline.add(new Document("$match", new Document("$or", Arrays.asList(assocMatchApa, managingMatchApa))));
            } else {
                pipeline.add(new Document("$match", new Document(
                    "persona_info.staffOrganizationAssociations",
                    new Document("$elemMatch", assocCriteriaApa)
                )));
            }

            // Deduplicate by publication UUID, keep year
            pipeline.add(new Document("$group", new Document("_id", "$publicationUuid")
                    .append("publicationYear", new Document("$first", "$publicationYear"))));

            rows = mongoTemplate
                    .getCollection("Researchoutputs")
                    .aggregate(pipeline)
                    .into(new ArrayList<>());
        }

        if (rows.isEmpty()) return List.of();

        List<String> uuids = rows.stream()
                .map(r -> r.getString("_id"))
                .filter(u -> u != null && !u.isBlank())
                .toList();

        Map<String, Integer> yearByUuid = new LinkedHashMap<>();
        for (Document r : rows) {
            String uid = r.getString("_id");
            if (uid != null) yearByUuid.put(uid, r.getInteger("publicationYear", 0));
        }

        // Step 2: Fetch full documents for APA formatting
        List<Document> fullDocs = mongoTemplate
                .getCollection("Researchoutputs")
                .find(new Document("uuid", new Document("$in", uuids)))
                .into(new ArrayList<>());

        // Sort by year descending, then title ascending
        fullDocs.sort((a, b) -> {
            int ya = yearByUuid.getOrDefault(a.getString("uuid"), 0);
            int yb = yearByUuid.getOrDefault(b.getString("uuid"), 0);
            if (yb != ya) return Integer.compare(yb, ya);
            Document titleDocA = (Document) a.get("title");
            Document titleDocB = (Document) b.get("title");
            String ta = titleDocA != null ? titleDocA.getString("value") : "";
            String tb = titleDocB != null ? titleDocB.getString("value") : "";
            if (ta == null) ta = "";
            if (tb == null) tb = "";
            return ta.compareToIgnoreCase(tb);
        });

        List<Map<String, Object>> apaResult = fullDocs.stream().map(pub -> {
            String uuid = pub.getString("uuid");
            int year = yearByUuid.getOrDefault(uuid, 0);

            Document typeDoc = (Document) pub.get("type");
            Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
            String tipo = termDoc != null ? termDoc.getString("ca_ES") : null;
            if (tipo == null || tipo.isBlank()) tipo = "Sense tipus";

            Document titleDocPub = (Document) pub.get("title");
            String titulo = titleDocPub != null ? titleDocPub.getString("value") : "";
            if (titulo == null) titulo = "";

            String apa = researchOutputJournalLinkService.formatApaForDocument(pub);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uuid", uuid);
            result.put("year", year);
            result.put("tipo", tipo);
            result.put("titulo", titulo);
            result.put("apa", apa);
            return result;
        }).toList();

        apaListCache.put(cacheKey, new ApaListCacheEntry(apaResult, System.currentTimeMillis()));
        return apaResult;
    }

    // --- Research Outputs Corrections Dashboard Endpoints & Helpers ---

    private static final Map<String, List<String>> duplicatesCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> duplicatesCacheTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DUPLICATES_CACHE_TTL_MS = 5 * 60 * 1000;

    private List<String> getCachedDuplicateField(String cacheKey, String fieldPath) {
        long now = System.currentTimeMillis();
        Long last = duplicatesCacheTimestamps.get(cacheKey);
        if (last != null && (now - last) < DUPLICATES_CACHE_TTL_MS) {
            return duplicatesCache.get(cacheKey);
        }
        List<String> dups;
        if ("titles".equals(cacheKey)) {
            dups = getDuplicateCombinedTitles();
        } else {
            dups = getDuplicateFields(fieldPath);
        }
        duplicatesCache.put(cacheKey, dups);
        duplicatesCacheTimestamps.put(cacheKey, now);
        return dups;
    }

    private List<String> getCachedDuplicateUrls() {
        long now = System.currentTimeMillis();
        Long last = duplicatesCacheTimestamps.get("urls");
        if (last != null && (now - last) < DUPLICATES_CACHE_TTL_MS) {
            return duplicatesCache.get("urls");
        }
        List<String> dups = getDuplicateUrls();
        duplicatesCache.put("urls", dups);
        duplicatesCacheTimestamps.put("urls", now);
        return dups;
    }

    private List<String> getCachedDuplicateIdentifiers() {
        long now = System.currentTimeMillis();
        Long last = duplicatesCacheTimestamps.get("identifiers");
        if (last != null && (now - last) < DUPLICATES_CACHE_TTL_MS) {
            return duplicatesCache.get("identifiers");
        }
        List<String> dups = getDuplicateIdentifiers();
        duplicatesCache.put("identifiers", dups);
        duplicatesCacheTimestamps.put("identifiers", now);
        return dups;
    }

    private List<String> getDuplicateFields(String fieldPath) {
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
            org.springframework.data.mongodb.core.aggregation.Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where(fieldPath).exists(true).ne(null).ne("")),
            org.springframework.data.mongodb.core.aggregation.Aggregation.group(fieldPath).count().as("count"),
            org.springframework.data.mongodb.core.aggregation.Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("count").gt(1)),
            org.springframework.data.mongodb.core.aggregation.Aggregation.limit(5000)
        );
        org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> results = mongoTemplate.aggregate(agg, "Researchoutputs", Document.class);
        List<String> dups = new ArrayList<>();
        for (Document d : results.getMappedResults()) {
            Object idVal = d.get("_id");
            if (idVal != null) {
                dups.add(idVal.toString());
            }
        }
        return dups;
    }

    private List<String> getDuplicateUrls() {
        List<Document> pipeline = Arrays.asList(
            new Document("$project", new Document("urls", new Document("$concatArrays", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$electronicVersions.link", Arrays.asList())),
                new Document("$ifNull", Arrays.asList("$electronicVersions.file.url", Arrays.asList())),
                new Document("$ifNull", Arrays.asList("$links.url", Arrays.asList()))
            )))),
            new Document("$unwind", "$urls"),
            new Document("$match", new Document("urls", new Document("$ne", null).append("$ne", ""))),
            new Document("$group", new Document("_id", "$urls").append("count", new Document("$sum", 1))),
            new Document("$match", new Document("count", new Document("$gt", 1))),
            new Document("$limit", 5000)
        );
        List<String> dups = new ArrayList<>();
        mongoTemplate.getCollection("Researchoutputs")
            .aggregate(pipeline)
            .forEach(d -> {
                Object idVal = d.get("_id");
                if (idVal != null) {
                    dups.add(idVal.toString());
                }
            });
        return dups;
    }

    private List<String> getDuplicateIdentifiers() {
        List<Document> pipeline = Arrays.asList(
            new Document("$unwind", "$identifiers"),
            new Document("$match", new Document("identifiers.value", new Document("$ne", null).append("$ne", ""))),
            new Document("$group", new Document("_id", "$identifiers.value").append("count", new Document("$sum", 1))),
            new Document("$match", new Document("count", new Document("$gt", 1))),
            new Document("$limit", 5000)
        );
        List<String> dups = new ArrayList<>();
        mongoTemplate.getCollection("Researchoutputs")
            .aggregate(pipeline)
            .forEach(d -> {
                Object idVal = d.get("_id");
                if (idVal != null) {
                    dups.add(idVal.toString());
                }
            });
        return dups;
    }

    private static List<Document> getListDocument(Document doc, String key) {
        Object val = doc.get(key);
        if (val instanceof List<?> rawList) {
            List<Document> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Document d) {
                    result.add(d);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private static Document getNestedDocument(Document doc, String... path) {
        Object current = doc;
        for (String key : path) {
            if (current instanceof Document d) {
                current = d.get(key);
            } else {
                return null;
            }
        }
        return current instanceof Document d ? d : null;
    }

    private static Object getByPath(Document root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Document doc)) {
                return null;
            }
            current = doc.get(key);
        }
        return current;
    }

    private static String nestedString(Document root, String... path) {
        Object val = getByPath(root, path);
        return val instanceof String s ? s : null;
    }

    private boolean containsStrangeCharacters(String text) {
        if (text == null) return false;
        return text.contains("Ã³") || text.contains("Ã©") || text.contains("Ã¡") || text.contains("Ãº") || text.contains("Ã±") ||
               text.contains("â€™") || text.contains("Ã ") || text.contains("Ã²") || text.contains("Ã§") || text.contains("Ã¼") ||
               text.contains("Ã€") || text.contains("Ãˆ") || text.contains("Ã ") || text.contains("Ã  ") || text.contains("ï¿½") ||
               text.contains("Ã±") || text.contains("Ã‘") || text.contains("Ã­");
    }

    private boolean isAllUppercase(String text) {
        if (text == null || text.isBlank()) return false;
        boolean hasLetters = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                hasLetters = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return hasLetters;
    }

    private boolean isValidIsbn(String isbn) {
        if (isbn == null) return false;
        String clean = isbn.replaceAll("[\\s-]", "");
        if (clean.length() != 10 && clean.length() != 13) return false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (i == 9 && clean.length() == 10 && (c == 'X' || c == 'x')) {
                continue;
            }
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidIssn(String issn) {
        if (issn == null) return false;
        String clean = issn.replaceAll("[\\s-]", "");
        if (clean.length() != 8) return false;
        for (int i = 0; i < 8; i++) {
            char c = clean.charAt(i);
            if (i == 7 && (c == 'X' || c == 'x')) {
                continue;
            }
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private List<String> scanIssnValues(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof Document doc) {
            Document journalAssoc = (Document) doc.get("journalAssociation");
            if (journalAssoc != null) {
                Object issnObj = journalAssoc.get("issn");
                if (issnObj instanceof String issnStr && !issnStr.isBlank()) {
                    out.add(issnStr.trim());
                } else if (issnObj instanceof Document issnDoc) {
                    String issnVal = issnDoc.getString("issn");
                    if (issnVal == null) {
                        issnVal = issnDoc.getString("value");
                    }
                    if (issnVal != null && !issnVal.isBlank()) {
                        out.add(issnVal.trim());
                    }
                }
            }
        }
        return out;
    }

    private String extractAuthorsNames(Document doc) {
        List<Document> contrs = getListDocument(doc, "contributors");
        if (contrs == null || contrs.isEmpty()) return "Sense autor";
        List<String> names = new ArrayList<>();
        for (Document c : contrs) {
            Document name = (Document) c.get("name");
            if (name != null) {
                String fn = name.getString("firstName");
                String ln = name.getString("lastName");
                if (fn != null && ln != null) {
                    names.add(ln + ", " + fn);
                } else if (ln != null) {
                    names.add(ln);
                } else if (fn != null) {
                    names.add(fn);
                }
            }
        }
        return String.join(" | ", names);
    }

    private String extractFirstAuthorLastName(Document doc) {
        List<Document> contrs = getListDocument(doc, "contributors");
        if (contrs == null || contrs.isEmpty()) return null;
        Document first = contrs.get(0);
        Document name = (Document) first.get("name");
        if (name != null) {
            return name.getString("lastName");
        }
        return null;
    }

    private boolean isLocalOpenAccess(Document doc) {
        List<Document> evs = getListDocument(doc, "electronicVersions");
        if (evs != null) {
            for (Document ev : evs) {
                Document accessType = (Document) ev.get("accessType");
                if (accessType != null) {
                    String uri = accessType.getString("uri");
                    if (uri != null && (uri.contains("/open") || uri.contains("/openaccess"))) {
                        return true;
                    }
                    Document term = (Document) accessType.get("term");
                    if (term != null) {
                        for (Object val : term.values()) {
                            if (val instanceof String s) {
                                String ls = s.toLowerCase();
                                if (ls.equals("open") || ls.equals("obert") || ls.equals("abierto")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @GetMapping("/stats/corrections")
    public Map<String, Object> getPublicationCorrections(@RequestParam(required = false) Integer anio) {
        Map<String, Object> result = new LinkedHashMap<>();
        int defaultYear = LocalDate.now().getYear();

        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        if (anio != null && anio > 0) {
            query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("submissionYear").is(anio));
        } else if (anio != null && anio == -1) {
            query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("submissionYear").gte(defaultYear - 10));
        } else {
            query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("submissionYear").is(defaultYear));
        }

        query.fields()
             .include("uuid")
             .include("pureId")
             .include("title")
             .include("subTitle")
             .include("translatedTitle")
             .include("abstract")
             .include("language")
             .include("electronicVersions")
             .include("links")
             .include("contributors")
             .include("identifiers")
             .include("submissionYear")
             .include("type")
             .include("typeDiscriminator")
             .include("journalAssociation")
             .include("printISBNs")
             .include("electronicISBNs");

        List<Document> allPubs = mongoTemplate.find(query, Document.class, "Researchoutputs");

        // 1. Fetch corrections review statuses
        List<PublicacionCorreccion> allCorrections = publicacionCorreccionRepository.findAll();
        Map<String, PublicacionCorreccion> correctionsMap = new LinkedHashMap<>();
        for (PublicacionCorreccion pc : allCorrections) {
            if (pc.getId() != null) {
                correctionsMap.put(pc.getId(), pc);
            }
        }

        // 2. Fetch duplicate sets database-wide
        List<String> dupTitles = getCachedDuplicateField("titles", "title.value");
        List<String> dupDois = getCachedDuplicateField("dois", "electronicVersions.doi");
        List<String> dupUrls = getCachedDuplicateUrls();
        List<String> dupIdentifiers = getCachedDuplicateIdentifiers();

        Set<String> dupTitlesSet = new HashSet<>(dupTitles);
        Set<String> dupDoisSet = new HashSet<>(dupDois);
        Set<String> dupUrlsSet = new HashSet<>(dupUrls);
        Set<String> dupIdentifiersSet = new HashSet<>(dupIdentifiers);

        // 3. Find potential duplicates in selected dataset (title + author + year)
        Map<String, List<String>> potentialDupsGroup = new LinkedHashMap<>();
        for (Document p : allPubs) {
            String title = nestedString(p, "title", "value");
            String author = extractFirstAuthorLastName(p);
            Integer year = p.getInteger("submissionYear");
            String uuid = p.getString("uuid");
            if (title != null && !title.isBlank() && uuid != null) {
                String normTitle = title.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
                String normAuthor = author != null ? author.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
                String key = normTitle + "#" + normAuthor + "#" + (year != null ? year : 0);
                potentialDupsGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(uuid);
            }
        }
        
        Set<String> potentialDupUuids = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : potentialDupsGroup.entrySet()) {
            if (entry.getValue().size() >= 2) {
                potentialDupUuids.addAll(entry.getValue());
            }
        }

        int missingAbstractCount = 0;
        int undefinedLanguageCount = 0;
        int missingDoiCount = 0;
        int missingUrlCount = 0;
        int duplicateTitleCount = 0;
        int duplicateDoiCount = 0;
        int duplicateUrlCount = 0;
        int duplicateIdentifierCount = 0;
        int potentialDuplicateCount = 0;
        int textQualityCount = 0;
        int linkQualityCount = 0;
        int isbnQualityCount = 0;
        int issnQualityCount = 0;

        List<Map<String, Object>> pubsNeedingCorrection = new ArrayList<>();

        for (Document p : allPubs) {
            String uuid = p.getString("uuid");
            if (uuid == null) continue;

            String mainTitle = nestedString(p, "title", "value");
            String mainSubTitle = nestedString(p, "subTitle", "value");
            String mainLang = nestedString(p, "language", "uri");
            if (mainLang == null) mainLang = "";
            mainLang = mainLang.toLowerCase();

            // Check 2: Missing abstract
            boolean missingAbstract = true;
            Document ab = (Document) p.get("abstract");
            if (ab != null) {
                for (Map.Entry<String, Object> entry : ab.entrySet()) {
                    if (entry.getValue() instanceof String val && !val.isBlank()) {
                        missingAbstract = false;
                        break;
                    }
                }
            }

            // Check 3: Undefined language
            boolean undefinedLanguage = langUriNullOrUnd(mainLang);

            // Check 4: Missing DOI
            boolean missingDoi = true;
            List<Document> evs = getListDocument(p, "electronicVersions");
            List<String> dois = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String doi = ev.getString("doi");
                    if (doi != null && !doi.isBlank()) {
                        missingDoi = false;
                        dois.add(doi);
                    }
                }
            }

            // Check 5: Missing URL
            boolean missingUrl = true;
            List<String> urls = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String link = ev.getString("link");
                    if (link != null && !link.isBlank()) {
                        missingUrl = false;
                        urls.add(link);
                    }
                    Document file = (Document) ev.get("file");
                    if (file != null) {
                        String fileUrl = file.getString("url");
                        if (fileUrl != null && !fileUrl.isBlank()) {
                            missingUrl = false;
                            urls.add(fileUrl);
                        }
                    }
                }
            }
            List<Document> lks = getListDocument(p, "links");
            if (lks != null) {
                for (Document lk : lks) {
                    String url = lk.getString("url");
                    if (url != null && !url.isBlank()) {
                        missingUrl = false;
                        urls.add(url);
                    }
                }
            }

            // Check 6: Duplicates
            String combinedTitle = "";
            if (mainTitle != null && !mainTitle.isBlank()) {
                combinedTitle = mainTitle.trim() + (mainSubTitle != null && !mainSubTitle.isBlank() ? ": " + mainSubTitle.trim() : "");
            }
            boolean duplicateTitle = !combinedTitle.isEmpty() && dupTitlesSet.contains(combinedTitle);
            boolean duplicateDoi = false;
            for (String d : dois) {
                if (dupDoisSet.contains(d)) {
                    duplicateDoi = true;
                    break;
                }
            }
            boolean duplicateUrl = hasDuplicateUrlOfType(p);
            boolean duplicateIdentifier = false;
            List<Document> idList = getListDocument(p, "identifiers");
            for (Document idObj : idList) {
                String val = idObj.getString("value");
                if (val != null && dupIdentifiersSet.contains(val)) {
                    duplicateIdentifier = true;
                    break;
                }
            }
            boolean potentialDuplicate = potentialDupUuids.contains(uuid);

            // Check 7: Text quality
            boolean strangeChars = false;
            List<String> titlesToCheck = new ArrayList<>();
            titlesToCheck.add(mainTitle);
            Document transTitle = (Document) p.get("translatedTitle");
            if (transTitle != null) {
                for (Object val : transTitle.values()) {
                    if (val instanceof String s) titlesToCheck.add(s);
                }
            }
            for (String t : titlesToCheck) {
                if (t != null && containsStrangeCharacters(t)) {
                    strangeChars = true;
                    break;
                }
            }

            boolean allCaps = false;
            if (mainTitle != null && !mainTitle.isBlank() && isAllUppercase(mainTitle)) {
                allCaps = true;
            }
            if (!allCaps && ab != null) {
                for (Object val : ab.values()) {
                    if (val instanceof String s && !s.isBlank() && isAllUppercase(s)) {
                        allCaps = true;
                        break;
                    }
                }
            }

            boolean whitespaceOnly = false;
            if (mainTitle != null && !mainTitle.isBlank() && mainTitle.trim().isEmpty()) {
                whitespaceOnly = true;
            }
            if (!whitespaceOnly) {
                List<Document> contrs = getListDocument(p, "contributors");
                for (Document c : contrs) {
                    Document name = (Document) c.get("name");
                    if (name != null) {
                        String fn = name.getString("firstName");
                        String ln = name.getString("lastName");
                        if ((fn != null && !fn.isEmpty() && fn.trim().isEmpty()) ||
                            (ln != null && !ln.isEmpty() && ln.trim().isEmpty())) {
                            whitespaceOnly = true;
                            break;
                        }
                    }
                }
            }
            boolean textQualityIssue = strangeChars || allCaps || whitespaceOnly;

            // Check 8: Link quality
            boolean invalidUrl = false;
            for (String u : urls) {
                String lu = u.toLowerCase();
                if (!lu.startsWith("http://") && !lu.startsWith("https://")) {
                    invalidUrl = true;
                    break;
                }
                if (u.contains(" ")) {
                    invalidUrl = true;
                    break;
                }
                if (lu.contains("academia.edu") || lu.contains("researchgate.net")) {
                    invalidUrl = true;
                    break;
                }
            }

            // Check 9: ISBN
            Document typeDoc = (Document) p.get("type");
            String typeUri = typeDoc != null ? typeDoc.getString("uri") : "";
            boolean isBookOrChapter = false;
            if (typeUri != null) {
                String tu = typeUri.toLowerCase();
                if (tu.contains("/book") || tu.contains("/contributiontobook") || tu.contains("/chapter")) {
                    isBookOrChapter = true;
                }
            }
            boolean invalidIsbn = false;
            if (isBookOrChapter) {
                List<String> printIsbns = (List<String>) p.get("printISBNs");
                List<String> elecIsbns = (List<String>) p.get("electronicISBNs");
                boolean hasIsbn = false;
                List<String> allIsbns = new ArrayList<>();
                if (printIsbns != null) {
                    for (String isbn : printIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (elecIsbns != null) {
                    for (String isbn : elecIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (!hasIsbn) {
                    invalidIsbn = true;
                } else {
                    for (String isbn : allIsbns) {
                        if (!isValidIsbn(isbn)) {
                            invalidIsbn = true;
                            break;
                        }
                    }
                }
            }

            // Check 10: ISSN
            boolean isArticle = false;
            if (typeUri != null) {
                String tu = typeUri.toLowerCase();
                if (tu.contains("/article") || tu.contains("/contributiontojournal")) {
                    isArticle = true;
                }
            }
            boolean invalidIssn = false;
            List<String> issnsSugeridosRevista = new ArrayList<>();
            if (isArticle) {
                List<String> issns = scanIssnValues(p);
                boolean hasIssn = false;
                for (String issn : issns) {
                    if (issn != null && !issn.isBlank()) {
                        hasIssn = true;
                    }
                }
                if (!hasIssn) {
                    invalidIssn = true;
                    Document journalAssoc = (Document) p.get("journalAssociation");
                    Document journalRef = journalAssoc != null ? (Document) journalAssoc.get("journal") : null;
                    if (journalRef != null) {
                        String journalUuid = journalRef.getString("uuid");
                        Integer journalPureId = journalRef.getInteger("pureId");
                        org.springframework.data.mongodb.core.query.Query jQueries = null;
                        if (journalUuid != null) {
                            jQueries = new org.springframework.data.mongodb.core.query.Query(
                                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(journalUuid));
                        } else if (journalPureId != null) {
                            jQueries = new org.springframework.data.mongodb.core.query.Query(
                                    org.springframework.data.mongodb.core.query.Criteria.where("pureId").is(journalPureId));
                        }
                        if (jQueries != null) {
                            jQueries.fields().include("issns").include("additionalSearchableIssns");
                            Document journalDoc = mongoTemplate.findOne(jQueries, Document.class, "Journals");
                            if (journalDoc != null) {
                                Set<String> seen = new LinkedHashSet<>();
                                // From issns array
                                List<Document> jIssns = getListDocument(journalDoc, "issns");
                                if (jIssns != null) {
                                    for (Document jIssnObj : jIssns) {
                                        String jIssnVal = jIssnObj.getString("issn");
                                        if (jIssnVal != null && !jIssnVal.isBlank()) {
                                            seen.add(jIssnVal.trim());
                                        }
                                    }
                                }
                                // From additionalSearchableIssns array
                                List<Document> jAddIssns = getListDocument(journalDoc, "additionalSearchableIssns");
                                if (jAddIssns != null) {
                                    for (Document jIssnObj : jAddIssns) {
                                        String jIssnVal = jIssnObj.getString("issn");
                                        if (jIssnVal != null && !jIssnVal.isBlank()) {
                                            seen.add(jIssnVal.trim());
                                        }
                                    }
                                }
                                issnsSugeridosRevista.addAll(seen);
                                // Journal has ISSNs — clear the error, no need to save separately
                                if (!issnsSugeridosRevista.isEmpty()) {
                                    invalidIssn = false;
                                }
                            }
                        }
                    }
                } else {
                    for (String issn : issns) {
                        if (issn != null && !issn.isBlank() && !isValidIssn(issn)) {
                            invalidIssn = true;
                            break;
                        }
                    }
                }
            }

            List<String> printIsbnsClean = new ArrayList<>();
            List<String> printIsbns = (List<String>) p.get("printISBNs");
            if (printIsbns != null) {
                for (String isbn : printIsbns) {
                    if (isbn != null && !isbn.isBlank()) printIsbnsClean.add(isbn.trim());
                }
            }
            List<String> elecIsbnsClean = new ArrayList<>();
            List<String> elecIsbns = (List<String>) p.get("electronicISBNs");
            if (elecIsbns != null) {
                for (String isbn : elecIsbns) {
                    if (isbn != null && !isbn.isBlank()) elecIsbnsClean.add(isbn.trim());
                }
            }
            List<String> isbns = new ArrayList<>();
            isbns.addAll(printIsbnsClean);
            isbns.addAll(elecIsbnsClean);
            List<String> issns = scanIssnValues(p);

            // Flags aggregator
            if (missingAbstract || undefinedLanguage || missingDoi || missingUrl ||
                duplicateTitle || duplicateDoi || duplicateUrl || duplicateIdentifier || potentialDuplicate ||
                textQualityIssue || invalidUrl || invalidIsbn || invalidIssn) {

                List<String> errors = new ArrayList<>();
                if (missingAbstract) { errors.add("MISSING_ABSTRACT"); missingAbstractCount++; }
                if (undefinedLanguage) { errors.add("UNDEFINED_LANGUAGE"); undefinedLanguageCount++; }
                if (missingDoi) { errors.add("MISSING_DOI"); missingDoiCount++; }
                if (missingUrl) { errors.add("MISSING_URL"); missingUrlCount++; }
                if (duplicateTitle) { errors.add("DUPLICATE_TITLE"); duplicateTitleCount++; }
                if (duplicateDoi) { errors.add("DUPLICATE_DOI"); duplicateDoiCount++; }
                if (duplicateUrl) { errors.add("DUPLICATE_URL"); duplicateUrlCount++; }
                if (duplicateIdentifier) { errors.add("DUPLICATE_IDENTIFIER"); duplicateIdentifierCount++; }
                if (potentialDuplicate) { errors.add("POTENTIAL_DUPLICATE"); potentialDuplicateCount++; }
                if (textQualityIssue) { errors.add("TEXT_QUALITY"); textQualityCount++; }
                if (invalidUrl) { errors.add("LINK_QUALITY"); linkQualityCount++; }
                if (invalidIsbn) { errors.add("INVALID_ISBN"); isbnQualityCount++; }
                if (invalidIssn) { errors.add("INVALID_ISSN"); issnQualityCount++; }

                // Fetch review info
                PublicacionCorreccion pc = correctionsMap.get(uuid);
                boolean reviewed = pc != null && pc.isReviewed();
                String observations = pc != null ? pc.getObservations() : "";

                Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
                String tipo = termDoc != null ? termDoc.getString("ca_ES") : "Sense tipus";

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("uuid", uuid);
                details.put("pureId", p.get("pureId"));
                details.put("typeDiscriminator", p.getString("typeDiscriminator"));
                mainSubTitle = nestedString(p, "subTitle", "value");
                String fullTitle = mainTitle;
                if (mainTitle != null && mainSubTitle != null && !mainSubTitle.isBlank()) {
                    fullTitle = mainTitle + ": " + mainSubTitle;
                }
                details.put("titulo", fullTitle != null ? fullTitle : "Sense títol");
                details.put("autor", extractAuthorsNames(p));
                details.put("anio", p.getInteger("submissionYear"));
                details.put("tipo", tipo);
                details.put("errors", errors);
                details.put("dois", dois);
                details.put("urls", urls);
                details.put("isbns", isbns);
                details.put("printISBNs", printIsbnsClean);
                details.put("electronicISBNs", elecIsbnsClean);
                details.put("issns", issns);
                details.put("issnsSugeridosRevista", issnsSugeridosRevista);
                details.put("isBookOrChapter", isBookOrChapter);
                details.put("isArticle", isArticle);
                details.put("localOa", isLocalOpenAccess(p));
                details.put("reviewed", reviewed);
                details.put("observations", observations);

                pubsNeedingCorrection.add(details);
            }
        }

        // Sort by submissionYear descending (newest first)
        pubsNeedingCorrection.sort((a, b) -> {
            Integer yA = (Integer) a.get("anio");
            Integer yB = (Integer) b.get("anio");
            if (yA == null && yB == null) return 0;
            if (yA == null) return 1;
            if (yB == null) return -1;
            return yB.compareTo(yA);
        });

        Map<String, Integer> kpis = new LinkedHashMap<>();
        kpis.put("missingAbstract", missingAbstractCount);
        kpis.put("undefinedLanguage", undefinedLanguageCount);
        kpis.put("missingDoi", missingDoiCount);
        kpis.put("missingUrl", missingUrlCount);
        kpis.put("duplicateTitle", duplicateTitleCount);
        kpis.put("duplicateDoi", duplicateDoiCount);
        kpis.put("duplicateUrl", duplicateUrlCount);
        kpis.put("duplicateIdentifier", duplicateIdentifierCount);
        kpis.put("potentialDuplicate", potentialDuplicateCount);
        kpis.put("textQuality", textQualityCount);
        kpis.put("linkQuality", linkQualityCount);
        kpis.put("isbnQuality", isbnQualityCount);
        kpis.put("issnQuality", issnQualityCount);

        result.put("kpis", kpis);
        result.put("publications", pubsNeedingCorrection);

        return result;
    }

    private boolean langUriNullOrUnd(String lang) {
        return lang == null || lang.isBlank() || lang.endsWith("/und");
    }

    @PostMapping("/corrections/{uuid}/review")
    public ResponseEntity<?> saveReview(
            @PathVariable String uuid,
            @RequestBody Map<String, Object> body) {
        try {
            boolean reviewed = (Boolean) body.getOrDefault("reviewed", false);
            String observations = (String) body.getOrDefault("observations", "");
            
            PublicacionCorreccion pc = publicacionCorreccionRepository.findById(uuid)
                    .orElseGet(() -> {
                        PublicacionCorreccion newPc = new PublicacionCorreccion();
                        newPc.setId(uuid);
                        return newPc;
                    });
            pc.setReviewed(reviewed);
            pc.setObservations(observations);
            pc.setModifiedDate(new Date());
            
            publicacionCorreccionRepository.save(pc);
            
            return ResponseEntity.ok(Map.of("success", true, "message", "Revisió desada correctament"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/unpaywall")
    public ResponseEntity<?> checkUnpaywall(@RequestParam String doi) {
        try {
            String cleanDoi = doi.trim();
            if (cleanDoi.startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
            if (cleanDoi.startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());
            
            String encodedDoi = URLEncoder.encode(cleanDoi, StandardCharsets.UTF_8);
            String url = "https://api.unpaywall.org/v2/" + encodedDoi + "?email=recerca@uab.cat";
            
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);
                
                Boolean isOa = (Boolean) data.get("is_oa");
                String oaStatus = (String) data.get("oa_status");
                String bestUrl = "";
                Map<String, Object> bestLocus = (Map<String, Object>) data.get("best_oa_location");
                if (bestLocus != null) {
                    bestUrl = (String) bestLocus.get("url");
                }
                
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", true);
                res.put("isOa", isOa != null ? isOa : false);
                res.put("oaStatus", oaStatus != null ? oaStatus : "unknown");
                res.put("url", bestUrl != null ? bestUrl : "");
                return ResponseEntity.ok(res);
            } else {
                return ResponseEntity.status(response.statusCode()).body(Map.of("success", false, "message", "HTTP " + response.statusCode()));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/{uuid}/suggest-doi")
    public ResponseEntity<?> suggestDoi(@PathVariable String uuid) {
        try {
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }
            
            String title = nestedString(pub, "title", "value");
            String subTitle = nestedString(pub, "subTitle", "value");
            if (title == null || title.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "La publicació no té títol per cercar"));
            }
            if (subTitle != null && !subTitle.isBlank()) {
                title = title + ": " + subTitle;
            }
            
            String cleanTitle = title.replaceAll("<[^>]*>", "").replaceAll("[^\\p{L}0-9\\s]", " ").replaceAll("\\s+", " ").trim();
            String authorSearch = extractAuthorSearchString(pub);
            Integer year = getPublicationYear(pub);
            
            String typeDiscriminator = pub.getString("typeDiscriminator");
            boolean isBookOrChapter = "BookAnthology".equalsIgnoreCase(typeDiscriminator) || "Chapter".equalsIgnoreCase(typeDiscriminator);
            String typeFilter = isBookOrChapter 
                ? "type:book-chapter,type:book,type:monograph" 
                : "type:journal-article";

            // 1. Try search with primary clean title, author, query year, and strict year filter
            List<Map<String, Object>> suggestions = queryCrossrefForSuggestions(cleanTitle, authorSearch, year, year, typeFilter);
            
            // 2. Try clean title, author, query year, without strict year filter
            if (suggestions.isEmpty() && year != null) {
                suggestions = queryCrossrefForSuggestions(cleanTitle, authorSearch, year, null, typeFilter);
            }
            
            // 3. Try clean title, author, query year, without author and strict year filter
            if (suggestions.isEmpty() && !authorSearch.isEmpty()) {
                suggestions = queryCrossrefForSuggestions(cleanTitle, "", year, null, typeFilter);
            }

            // 4. Try clean title, query year (loosest search)
            if (suggestions.isEmpty()) {
                suggestions = queryCrossrefForSuggestions(cleanTitle, "", year, null, typeFilter);
            }
            

            
            return ResponseEntity.ok(Map.of("success", true, "suggestions", suggestions));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/doi")
    public ResponseEntity<?> saveDoi(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String doi = body.get("doi");
            String env = body.getOrDefault("env", "test");
            if (doi == null || doi.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "DOI no vàlid"));
            }
            
            String cleanDoi = doi.trim();
            if (cleanDoi.startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
            if (cleanDoi.startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());
            
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }
            
            List<Document> evs = getListDocument(pub, "electronicVersions");
            boolean updated = false;
            for (Document ev : evs) {
                String discriminator = ev.getString("typeDiscriminator");
                if ("DoiElectronicVersion".equals(discriminator)) {
                    ev.put("doi", cleanDoi);
                    updated = true;
                    break;
                }
            }
            
            if (!updated) {
                Document newEv = new Document();
                newEv.put("typeDiscriminator", "DoiElectronicVersion");
                newEv.put("doi", cleanDoi);
                
                Document accessType = new Document();
                accessType.put("uri", "/dk/atira/pure/core/openaccesspermission/unknown");
                Document accessTerm = new Document();
                accessTerm.put("en_GB", "Unknown");
                accessTerm.put("es_ES", "Sin especificar");
                accessTerm.put("ca_ES", "Sense especificar");
                accessType.put("term", accessTerm);
                newEv.put("accessType", accessType);
                
                Document versionType = new Document();
                versionType.put("uri", "/dk/atira/pure/researchoutput/electronicversion/versiontype/publishersversion");
                Document versionTerm = new Document();
                versionTerm.put("en_GB", "Final published version");
                versionTerm.put("es_ES", "Versión publicada final");
                versionTerm.put("ca_ES", "Versió publicada final");
                versionType.put("term", versionTerm);
                newEv.put("versionType", versionType);
                
                evs.add(newEv);
            }
            
            pub.put("electronicVersions", evs);
            
            // Sync to Egreta/Pure API
            String egretaError = syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }
            
            mongoTemplate.save(pub, "Researchoutputs");
            
            return ResponseEntity.ok(Map.of("success", true, "message", "DOI afegit correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private Integer getPublicationYear(Document pub) {
        Integer year = pub.getInteger("submissionYear");
        if (year == null) {
            Document pubDate = (Document) pub.get("publicationDate");
            if (pubDate != null) {
                year = pubDate.getInteger("year");
            }
        }
        if (year == null) {
            List<Document> statuses = getListDocument(pub, "publicationStatuses");
            if (statuses != null && !statuses.isEmpty()) {
                Document firstStatus = statuses.get(0);
                Document statusDate = (Document) firstStatus.get("publicationDate");
                if (statusDate != null) {
                    year = statusDate.getInteger("year");
                }
            }
        }
        return year;
    }

    private String extractAuthorSearchString(Document pub) {
        List<Document> contrs = getListDocument(pub, "contributors");
        if (contrs == null || contrs.isEmpty()) return "";
        List<String> lastNames = new ArrayList<>();
        for (int i = 0; i < Math.min(3, contrs.size()); i++) {
            Document c = contrs.get(i);
            Document name = (Document) c.get("name");
            if (name != null) {
                String ln = name.getString("lastName");
                if (ln != null && !ln.isBlank()) {
                    lastNames.add(ln.trim());
                } else {
                    String fn = name.getString("firstName");
                    if (fn != null && !fn.isBlank()) {
                        lastNames.add(fn.trim());
                    }
                }
            }
        }
        return String.join(" ", lastNames);
    }

    private boolean isTitleSimilar(String cleanPubTitle, String cleanSugTitle) {
        if (cleanPubTitle == null || cleanSugTitle == null) return false;
        
        String t1 = cleanPubTitle.toLowerCase().replaceAll("[^\\p{L}0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        String t2 = cleanSugTitle.toLowerCase().replaceAll("[^\\p{L}0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        
        if (t1.isEmpty() || t2.isEmpty()) return false;
        
        String[] w1 = t1.split(" ");
        String[] w2 = t2.split(" ");
        
        Set<String> s1 = new HashSet<>(Arrays.asList(w1));
        Set<String> s2 = new HashSet<>(Arrays.asList(w2));
        
        List<String> stopWords = Arrays.asList(
            "the", "of", "and", "in", "on", "at", "to", "for", "with", "by", "a", "an",
            "de", "la", "el", "i", "els", "les", "per", "en", "un", "una", "y", "o", "a"
        );
        s1.removeAll(stopWords);
        s2.removeAll(stopWords);
        
        if (s1.isEmpty() || s2.isEmpty()) return false;
        
        int intersection = 0;
        for (String word : s1) {
            if (s2.contains(word)) {
                intersection++;
            }
        }
        
        double ratio = (double) intersection / Math.min(s1.size(), s2.size());
        return ratio >= 0.5; // require at least 50% overlap of non-stop-words
    }

    private List<Map<String, Object>> queryCrossrefForSuggestions(String titleToSearch, String authorSearch, Integer queryYear, Integer filterYear, String typeFilter) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        try {
            String queryStr = titleToSearch 
                + (authorSearch == null || authorSearch.isEmpty() ? "" : " " + authorSearch) 
                + (queryYear != null && queryYear > 0 ? " " + queryYear : "");
            String encodedQuery = URLEncoder.encode(queryStr, StandardCharsets.UTF_8);
            String url = "https://api.crossref.org/works?query=" + encodedQuery;
            if (authorSearch != null && !authorSearch.isEmpty()) {
                url += "&query.author=" + URLEncoder.encode(authorSearch, StandardCharsets.UTF_8);
            }
            
            List<String> filters = new ArrayList<>();
            if (filterYear != null && filterYear > 0) {
                filters.add("from-pub-date:" + filterYear);
                filters.add("until-pub-date:" + filterYear);
            }
            if (typeFilter != null && !typeFilter.isEmpty()) {
                filters.add(typeFilter);
            }
            if (!filters.isEmpty()) {
                url += "&filter=" + String.join(",", filters);
            }
            url += "&rows=5";
            
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "PortalRecercaUAB/1.0 (mailto:recerca@uab.cat)")
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);
                Map<String, Object> message = (Map<String, Object>) data.get("message");
                List<Map<String, Object>> items = (List<Map<String, Object>>) (message != null ? message.get("items") : null);
                
                if (items != null) {
                    for (Map<String, Object> item : items) {
                        String doi = (String) item.get("DOI");
                        
                        String sTitle = "";
                        List<String> titles = (List<String>) item.get("title");
                        if (titles != null && !titles.isEmpty()) {
                            sTitle = titles.get(0);
                        }
                        
                        List<String> authorNames = new ArrayList<>();
                        List<Map<String, Object>> authors = (List<Map<String, Object>>) item.get("author");
                        if (authors != null) {
                            for (Map<String, Object> aut : authors) {
                                String family = (String) aut.get("family");
                                if (family != null) authorNames.add(family);
                            }
                        }
                        String sAuthors = String.join(", ", authorNames);
                        
                        Integer sYear = null;
                        Map<String, Object> pubPrint = (Map<String, Object>) item.get("published-print");
                        if (pubPrint != null) {
                            List<List<Integer>> dateParts = (List<List<Integer>>) pubPrint.get("date-parts");
                            if (dateParts != null && !dateParts.isEmpty() && !dateParts.get(0).isEmpty()) {
                                sYear = dateParts.get(0).get(0);
                            }
                        }
                        if (sYear == null) {
                            Map<String, Object> pubOnline = (Map<String, Object>) item.get("published-online");
                            if (pubOnline != null) {
                                List<List<Integer>> dateParts = (List<List<Integer>>) pubOnline.get("date-parts");
                                if (dateParts != null && !dateParts.isEmpty() && !dateParts.get(0).isEmpty()) {
                                    sYear = dateParts.get(0).get(0);
                                }
                            }
                        }
                        
                        if (filterYear != null && filterYear > 0 && sYear != null && !sYear.equals(filterYear)) {
                            continue;
                        }
                        
                        if (!isTitleSimilar(titleToSearch, sTitle)) {
                            continue;
                        }
                        
                        List<String> sIssns = (List<String>) item.get("ISSN");
                        List<String> sIsbns = (List<String>) item.get("ISBN");
                        
                        Map<String, Object> sug = new LinkedHashMap<>();
                        sug.put("doi", doi != null ? doi : "");
                        sug.put("title", sTitle != null ? sTitle : "Sense títol");
                        sug.put("authors", sAuthors != null ? sAuthors : "Sense autors");
                        sug.put("year", sYear != null ? sYear : "-");
                        sug.put("issn", sIssns != null && !sIssns.isEmpty() ? sIssns.get(0) : "");
                        sug.put("isbn", sIsbns != null && !sIsbns.isEmpty() ? sIsbns.get(0) : "");
                        suggestions.add(sug);
                        
                        if (suggestions.size() >= 3) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error querying Crossref: " + e.getMessage());
        }
        return suggestions;
    }

    private String extractEnglishTitle(Document pub) {
        Document transTitle = (Document) pub.get("translatedTitle");
        if (transTitle == null) return null;
        for (String key : transTitle.keySet()) {
            if (key.toLowerCase().startsWith("en")) {
                String val = transTitle.getString(key);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    private String translateToEnglish(String text) {
        try {
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://api.mymemory.translated.net/get?q=" + encodedText + "&langpair=any|en";
            
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "PortalRecercaUAB/1.0 (mailto:egreta@uab.cat)")
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);
                Map<String, Object> respData = (Map<String, Object>) data.get("responseData");
                if (respData != null) {
                    String translatedText = (String) respData.get("translatedText");
                    if (translatedText != null && !translatedText.isBlank()) {
                        return translatedText.trim();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error translating text: " + e.getMessage());
        }
        return null;
    }

    @PostMapping("/corrections/{uuid}/isbn")
    public ResponseEntity<?> saveIsbn(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String isbn = body.get("isbn");
            String env = body.getOrDefault("env", "test");
            if (isbn == null || isbn.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "ISBN no vàlid"));
            }
            
            String cleanIsbn = isbn.trim();
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }
            
            List<String> printIsbns = (List<String>) pub.get("printISBNs");
            if (printIsbns == null) {
                printIsbns = new ArrayList<>();
            }
            if (!printIsbns.contains(cleanIsbn)) {
                printIsbns.add(cleanIsbn);
            }
            pub.put("printISBNs", printIsbns);
            
            // Sync to Egreta/Pure API
            String egretaError = syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }
            
            mongoTemplate.save(pub, "Researchoutputs");
            return ResponseEntity.ok(Map.of("success", true, "message", "ISBN afegit correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/issn")
    public ResponseEntity<?> saveIssn(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String issn = body.get("issn");
            String env = body.getOrDefault("env", "test");
            if (issn == null || issn.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "ISSN no vàlid"));
            }
            
            String cleanIssn = issn.trim();
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }
            
            Document journalAssoc = (Document) pub.get("journalAssociation");
            if (journalAssoc == null) {
                journalAssoc = new Document();
            }
            
            // Sync to Egreta/Pure API
            // 1. Sync ISSN to linked Journal in Egreta first
            Integer issnPureId = null;
            Document journalRef = journalAssoc.get("journal") instanceof Document jr ? jr : null;
            if (journalRef != null) {
                String journalUuid = journalRef.getString("uuid");
                if (journalUuid != null) {
                    try {
                        issnPureId = syncJournalIssnToEgreta(journalUuid, cleanIssn, env);
                    } catch (Exception e) {
                        return ResponseEntity.status(500).body(Map.of(
                                "success", false,
                                "message", "Error al sincronitzar la revista vinculada amb l'API d'Egreta: " + e.getMessage()
                        ));
                    }
                }
            }

            Object existingIssn = journalAssoc.get("issn");
            Document issnDoc = null;
            if (existingIssn instanceof Document doc) {
                issnDoc = doc;
            } else {
                issnDoc = new Document();
            }
            issnDoc.remove("value"); // remove legacy key
            issnDoc.put("issn", cleanIssn);
            if (issnPureId != null) {
                issnDoc.put("pureId", issnPureId);
            } else {
                issnDoc.remove("pureId");
            }
            journalAssoc.put("issn", issnDoc);
            pub.put("journalAssociation", journalAssoc);

            // 2. Sync publication to Egreta
            String egretaError = syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar la publicació amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }
            
            mongoTemplate.save(pub, "Researchoutputs");
            
            // Also update the linked Journal document in Journals collection
            journalRef = journalAssoc.get("journal") instanceof Document jr ? jr : null;
            if (journalRef != null) {
                String journalUuid = journalRef.getString("uuid");
                if (journalUuid != null) {
                    org.springframework.data.mongodb.core.query.Query jQuery = new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(journalUuid));
                    Document journalDoc = mongoTemplate.findOne(jQuery, Document.class, "Journals");
                    if (journalDoc != null) {
                        List<Document> jIssns = getListDocument(journalDoc, "issns");
                        if (jIssns == null) jIssns = new ArrayList<>();
                        boolean alreadyExists = jIssns.stream()
                                .anyMatch(obj -> cleanIssn.equals(obj.getString("issn")));
                        if (!alreadyExists) {
                            Document newIssnEntry = new Document("issn", cleanIssn);
                            jIssns.add(newIssnEntry);
                            journalDoc.put("issns", jIssns);
                            mongoTemplate.save(journalDoc, "Journals");
                        }
                    }
                }
            }
            
            return ResponseEntity.ok(Map.of("success", true, "message", "ISSN afegit correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/language")
    public ResponseEntity<?> updateLanguage(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String langCode = body.get("language");
            if (langCode == null || langCode.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Idioma no vàlid"));
            }
            
            String cleanLang = langCode.trim();
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }
            
            Document languageDoc = new Document();
            languageDoc.put("uri", "/dk/atira/pure/core/languages/" + cleanLang);
            
            Map<String, String> term = new LinkedHashMap<>();
            org.springframework.data.mongodb.core.query.Query langQuery = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("baseUri").is("/dk/atira/pure/core/languages"));
            Document schemeDoc = mongoTemplate.findOne(langQuery, Document.class, "Classificationschemes");
            boolean resolved = false;
            if (schemeDoc != null) {
                List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
                if (contained != null) {
                    for (Document c : contained) {
                        String uri = c.getString("uri");
                        if (uri != null && uri.endsWith("/" + cleanLang)) {
                            Document termDoc = (Document) c.get("term");
                            if (termDoc != null) {
                                List<Document> textList = (List<Document>) termDoc.get("text");
                                if (textList != null) {
                                    for (Document t : textList) {
                                        String locale = t.getString("locale");
                                        String value = t.getString("value");
                                        if (locale != null && value != null) {
                                            term.put(locale, value);
                                        }
                                    }
                                    resolved = true;
                                }
                            }
                            break;
                        }
                    }
                }
            }
            
            if (!resolved) {
                if ("ca_ES".equals(cleanLang)) {
                    term.put("ca_ES", "Català");
                    term.put("es_ES", "Catalán");
                    term.put("en_GB", "Catalan");
                } else if ("es_ES".equals(cleanLang)) {
                    term.put("ca_ES", "Espanyol");
                    term.put("es_ES", "Español");
                    term.put("en_GB", "Spanish");
                } else if ("en_GB".equals(cleanLang)) {
                    term.put("ca_ES", "Anglès");
                    term.put("es_ES", "Inglés");
                    term.put("en_GB", "English");
                } else {
                    term.put("ca_ES", cleanLang);
                    term.put("es_ES", cleanLang);
                    term.put("en_GB", cleanLang);
                }
            }
            languageDoc.put("term", term);
            pub.put("language", languageDoc);
            
            // Sync to Egreta/Pure API
            String env = body.getOrDefault("env", "test");
            boolean egretaSyncSuccess = syncResearchOutputLanguageToEgreta(uuid, languageDoc, env);
            if (!egretaSyncSuccess) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ")."
                ));
            }
            
            mongoTemplate.save(pub, "Researchoutputs");
            return ResponseEntity.ok(Map.of("success", true, "message", "Idioma actualitzat correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/{uuid}/suggest-abstract")
    public ResponseEntity<?> suggestAbstract(@PathVariable String uuid) {
        try {
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            Map<String, String> suggestedMap = new java.util.LinkedHashMap<>();

            // 1. Check if publication has links pointing to ddd.uab.cat or dialnet.unirioja.es
            List<Document> links = getListDocument(pub, "links");
            if (links != null) {
                for (Document lk : links) {
                    String url = lk.getString("url");
                    if (url != null && !url.isBlank()) {
                        String urlLower = url.toLowerCase().trim();
                        String targetUrl = url.trim();
                        if (urlLower.startsWith("http://")) {
                            targetUrl = "https://" + targetUrl.substring(7);
                        }
                        if (urlLower.contains("ddd.uab.cat")) {
                            Map<String, String> dddAbs = fetchAbstractFromDdd(targetUrl);
                            if (dddAbs != null) {
                                suggestedMap.putAll(dddAbs);
                            }
                        } else if (urlLower.contains("dialnet.unirioja")) {
                            Map<String, String> dialnetAbs = fetchAbstractFromDialnet(targetUrl);
                            if (dialnetAbs != null) {
                                suggestedMap.putAll(dialnetAbs);
                            }
                        }
                    }
                }
            }

            // 2. If no abstracts found from links, fall back to DOIs and APIs (Semantic Scholar, OpenAlex)
            if (suggestedMap.isEmpty()) {
                String doi = null;
                List<Document> evs = getListDocument(pub, "electronicVersions");
                if (evs != null) {
                    for (Document ev : evs) {
                        String d = ev.getString("doi");
                        if (d != null && !d.isBlank()) {
                            doi = d.trim();
                            break;
                        }
                    }
                }

                String title = nestedString(pub, "title", "value");
                String cleanTitle = title != null ? title.replaceAll("<[^>]*>", "").replaceAll("[^\\p{L}0-9\\s]", " ").replaceAll("\\s+", " ").trim() : "";

                String abstractText = fetchAbstractFromApis(doi, cleanTitle);
                if (abstractText != null && !abstractText.isBlank()) {
                    suggestedMap.put("en_GB", abstractText);
                }
            }

            if (suggestedMap.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "No s'ha trobat cap resum per a aquesta publicació en les fonts externes o enllaços."));
            }

            return ResponseEntity.ok(Map.of("success", true, "abstracts", suggestedMap));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private Map<String, String> fetchAbstractFromDdd(String urlStr) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try {
            org.springframework.web.client.RestTemplate restTemplate = createUnsafeRestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(
                    urlStr, org.springframework.http.HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String html = response.getBody();
                
                java.util.regex.Pattern metaPattern = java.util.regex.Pattern.compile("<meta\\s+([^>]+)>", java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher matcher = metaPattern.matcher(html);
                while (matcher.find()) {
                    String attributes = matcher.group(1);
                    String name = extractAttribute(attributes, "name");
                    String content = extractAttribute(attributes, "content");
                    String lang = extractAttribute(attributes, "xml:lang");
                    if (lang == null || lang.isBlank()) {
                        lang = extractAttribute(attributes, "lang");
                    }
                    
                    if (name != null && content != null) {
                        String nameLower = name.toLowerCase();
                        if ("citation_abstract".equals(nameLower) || "dc.description".equals(nameLower) || "description".equals(nameLower)) {
                            String egretaLang = mapLocaleToEgretaLang(lang);
                            result.put(egretaLang, cleanHtmlText(content));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching abstract from DDD (" + urlStr + "): " + e.getMessage());
        }
        return result;
    }

    private Map<String, String> fetchAbstractFromDialnet(String urlStr) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try {
            org.springframework.web.client.RestTemplate restTemplate = createUnsafeRestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(
                    urlStr, org.springframework.http.HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String html = response.getBody();
                
                java.util.regex.Pattern resumenPattern = java.util.regex.Pattern.compile("<ul\\s+id=['\"]resumen['\"](.*?)</ul>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher matcher = resumenPattern.matcher(html);
                if (matcher.find()) {
                    String resumenBody = matcher.group(1);
                    java.util.regex.Pattern liPattern = java.util.regex.Pattern.compile("<li(?:\\s+xml:lang=['\"](.*?)['\"])?\\s*>(.*?)</li>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
                    java.util.regex.Matcher liMatcher = liPattern.matcher(resumenBody);
                    while (liMatcher.find()) {
                        String lang = liMatcher.group(1);
                        String textBody = liMatcher.group(2);
                        String text = cleanHtmlText(textBody);
                        if (text != null && !text.isBlank()) {
                            String egretaLang = mapLocaleToEgretaLang(lang);
                            result.put(egretaLang, text);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching abstract from Dialnet (" + urlStr + "): " + e.getMessage());
        }
        return result;
    }

    private String extractAttribute(String attributes, String attrName) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(attrName + "=['\"](.*?)['\"]", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(attributes);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String mapLocaleToEgretaLang(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en_GB";
        }
        String l = locale.toLowerCase().trim();
        if (l.startsWith("ca") || l.startsWith("cat")) {
            return "ca_ES";
        }
        if (l.startsWith("es") || l.startsWith("spa") || l.startsWith("cas")) {
            return "es_ES";
        }
        if (l.startsWith("en") || l.startsWith("eng")) {
            return "en_GB";
        }
        return "en_GB";
    }

    private String cleanHtmlText(String html) {
        if (html == null) return "";
        String text = html.replaceAll("<[^>]*>", "")
                          .replaceAll("&quot;", "\"")
                          .replaceAll("&amp;", "&")
                          .replaceAll("&lt;", "<")
                          .replaceAll("&gt;", ">")
                          .replaceAll("&apos;", "'")
                          .replaceAll("\\s+", " ")
                          .trim();
        return text;
    }

    private org.springframework.web.client.RestTemplate createUnsafeRestTemplate() {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                }
            };

            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            
            org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory = 
                new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                        if (connection instanceof javax.net.ssl.HttpsURLConnection httpsConnection) {
                            httpsConnection.setSSLSocketFactory(sc.getSocketFactory());
                            httpsConnection.setHostnameVerifier((hostname, session) -> true);
                        }
                        super.prepareConnection(connection, httpMethod);
                    }
                };
            
            return new org.springframework.web.client.RestTemplate(requestFactory);
        } catch (Exception e) {
            return new org.springframework.web.client.RestTemplate();
        }
    }

    @PostMapping("/corrections/{uuid}/abstract")
    public ResponseEntity<?> saveAbstract(
            @PathVariable String uuid,
            @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> abstracts = (Map<String, String>) body.get("abstracts");
            String env = (String) body.getOrDefault("env", "test");
            
            if (abstracts == null || abstracts.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "El resum no pot estar buit"));
            }
            
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }
            
            Document abDoc = (Document) pub.get("abstract");
            if (abDoc == null) {
                abDoc = new Document();
            }
            for (Map.Entry<String, String> entry : abstracts.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    abDoc.put(entry.getKey(), sanitizeAbstractText(entry.getValue().trim()));
                } else {
                    abDoc.remove(entry.getKey());
                }
            }
            pub.put("abstract", abDoc);
            
            // Save to MongoDB
            mongoTemplate.save(pub, "Researchoutputs");
            
            // Sync to Egreta
            String egretaError = syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar la publicació amb l'API d'Egreta: " + egretaError
                ));
            }
            
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private String fetchAbstractFromApis(String doi, String cleanTitle) {
        // 1. Try Semantic Scholar (if DOI is present)
        if (doi != null && !doi.isBlank()) {
            try {
                String url = "https://api.semanticscholar.org/graph/v1/paper/DOI:" + doi.trim() + "?fields=abstract";
                org.springframework.web.client.RestTemplate restTemplate = createUnsafeRestTemplate();
                Map<?, ?> resp = restTemplate.getForObject(url, Map.class);
                if (resp != null && resp.containsKey("abstract")) {
                    String ab = (String) resp.get("abstract");
                    if (ab != null && !ab.isBlank()) {
                        return ab.trim();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error fetching abstract from Semantic Scholar: " + e.getMessage());
            }
        }

        // 2. Try OpenAlex (if DOI is present)
        if (doi != null && !doi.isBlank()) {
            try {
                String url = "https://api.openalex.org/works/https://doi.org/" + doi.trim();
                org.springframework.web.client.RestTemplate restTemplate = createUnsafeRestTemplate();
                Map<?, ?> resp = restTemplate.getForObject(url, Map.class);
                if (resp != null && resp.containsKey("abstract_inverted_index")) {
                    Map<?, ?> invIndex = (Map<?, ?>) resp.get("abstract_inverted_index");
                    if (invIndex != null && !invIndex.isEmpty()) {
                        String reconstructed = reconstructAbstractFromInvertedIndex(invIndex);
                        if (reconstructed != null && !reconstructed.isBlank()) {
                            return reconstructed;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error fetching abstract from OpenAlex by DOI: " + e.getMessage());
            }
        }

        // 3. Try OpenAlex title search (if cleanTitle is present)
        if (cleanTitle != null && !cleanTitle.isBlank()) {
            try {
                String url = "https://api.openalex.org/works?filter=title.search:" + URLEncoder.encode(cleanTitle, StandardCharsets.UTF_8);
                org.springframework.web.client.RestTemplate restTemplate = createUnsafeRestTemplate();
                Map<?, ?> resp = restTemplate.getForObject(url, Map.class);
                if (resp != null && resp.containsKey("results")) {
                    List<?> results = (List<?>) resp.get("results");
                    if (results != null && !results.isEmpty()) {
                        Map<?, ?> firstResult = (Map<?, ?>) results.get(0);
                        String resTitle = "";
                        if (firstResult.get("title") instanceof String s) {
                            resTitle = s;
                        }
                        if (isTitleSimilar(cleanTitle, resTitle)) {
                            Map<?, ?> invIndex = (Map<?, ?>) firstResult.get("abstract_inverted_index");
                            if (invIndex != null && !invIndex.isEmpty()) {
                                String reconstructed = reconstructAbstractFromInvertedIndex(invIndex);
                                if (reconstructed != null && !reconstructed.isBlank()) {
                                    return reconstructed;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error searching abstract in OpenAlex by title: " + e.getMessage());
            }
        }

        return null;
    }

    private String reconstructAbstractFromInvertedIndex(Map<?, ?> invIndex) {
        try {
            List<Map.Entry<Integer, String>> wordList = new ArrayList<>();
            for (Map.Entry<?, ?> entry : invIndex.entrySet()) {
                String word = (String) entry.getKey();
                List<?> posList = (List<?>) entry.getValue();
                if (posList != null) {
                    for (Object pObj : posList) {
                        if (pObj instanceof Number num) {
                            wordList.add(new java.util.AbstractMap.SimpleEntry<>(num.intValue(), word));
                        }
                    }
                }
            }
            wordList.sort(Map.Entry.comparingByKey());
            List<String> words = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : wordList) {
                words.add(entry.getValue());
            }
            return String.join(" ", words);
        } catch (Exception e) {
            System.err.println("Error reconstructing abstract: " + e.getMessage());
            return null;
        }
    }

    private boolean syncResearchOutputLanguageToEgreta(String uuid, Document language, String targetEnv) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "research-outputs/" + uuid;

            // 1. GET
            org.springframework.http.ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta research-output UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify language
            Map<String, Object> langMap = new LinkedHashMap<>();
            langMap.put("uri", language.getString("uri"));
            langMap.put("term", language.get("term"));
            data.put("language", langMap);
            
            // 3. PUT
            org.springframework.http.HttpEntity<Map<String, Object>> putEntity = new org.springframework.http.HttpEntity<>(data, headers);
            org.springframework.http.ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing research-output language to Egreta: " + e.getMessage());
            return false;
        }
    }

    private String syncResearchOutputToEgreta(String uuid, Document pub, String targetEnv) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "research-outputs/" + uuid;

            // 1. GET current data from Egreta API
            org.springframework.http.ResponseEntity<Map> getResp;
            try {
                getResp = restTemplate.exchange(
                        url, org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), Map.class);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                return "La publicació no s'ha trobat a Egreta (404) en l'entorn de " + ("prod".equalsIgnoreCase(targetEnv) ? "producció" : "tests") + ".";
            } catch (Exception e) {
                return "Error al consultar la publicació a Egreta: " + e.getMessage();
            }
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                return "Error al consultar la publicació a Egreta. Status: " + getResp.getStatusCode();
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Sync fields from our modified local 'pub' Document into Egreta 'data' map
            if (pub.containsKey("electronicVersions")) {
                data.put("electronicVersions", pub.get("electronicVersions"));
            }
            if (pub.containsKey("printISBNs")) {
                data.put("printISBNs", pub.get("printISBNs"));
            }
            if (pub.containsKey("abstract")) {
                Object abObj = pub.get("abstract");
                if (abObj instanceof Map) {
                    Map<String, Object> escapedAbstract = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) abObj).entrySet()) {
                        String lang = (String) entry.getKey();
                        Object val = entry.getValue();
                        if (val instanceof String text) {
                            escapedAbstract.put(lang, escapeXmlExceptEntities(text));
                        } else {
                            escapedAbstract.put(lang, val);
                        }
                    }
                    data.put("abstract", escapedAbstract);
                } else {
                    data.put("abstract", abObj);
                }
            }
            
            // Merge journalAssociation safely
            if (pub.containsKey("journalAssociation")) {
                Object localJAObj = pub.get("journalAssociation");
                if (localJAObj instanceof Map localJA) {
                    Object apiJAObj = data.get("journalAssociation");
                    if (apiJAObj instanceof Map apiJA) {
                        Map<String, Object> mergedJA = new LinkedHashMap<>((Map<String, Object>) apiJA);
                        Object localIssn = localJA.get("issn");
                        if (localIssn != null) {
                            mergedJA.put("issn", localIssn);
                        }
                        Object localJournal = localJA.get("journal");
                        if (localJournal != null) {
                            mergedJA.put("journal", localJournal);
                        }
                        data.put("journalAssociation", mergedJA);
                    } else {
                        data.put("journalAssociation", localJAObj);
                    }
                } else {
                    data.put("journalAssociation", localJAObj);
                }
            }
            
            // 3. PUT back to Egreta API
            try {
                org.springframework.http.HttpEntity<Map<String, Object>> putEntity = new org.springframework.http.HttpEntity<>(data, headers);
                org.springframework.http.ResponseEntity<Map> putResp = restTemplate.exchange(
                        url, org.springframework.http.HttpMethod.PUT, putEntity, Map.class);
                
                if (putResp.getStatusCode().is2xxSuccessful()) {
                    return null; // Success!
                } else {
                    return "Error al desar la publicació a Egreta. Status: " + putResp.getStatusCode();
                }
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                String body = e.getResponseBodyAsString();
                return "Error de validació de l'API d'Egreta (" + e.getStatusCode().value() + "): " + (body.length() > 500 ? body.substring(0, 500) + "..." : body);
            } catch (Exception e) {
                return "Error al enviar la publicació actualitzada a Egreta: " + e.getMessage();
            }
        } catch (Exception e) {
            return "Error inesperat de sincronització: " + e.getMessage();
        }
    }

    private void autoMarkAsReviewedIfNoErrorsLeft(String uuid, Document p) {
        try {
            String mainTitle = nestedString(p, "title", "value");
            String mainSubTitle = nestedString(p, "subTitle", "value");
            String mainLang = nestedString(p, "language", "uri");
            if (mainLang == null) mainLang = "";
            mainLang = mainLang.toLowerCase();

            // Check 2: Missing abstract
            boolean missingAbstract = true;
            Document ab = (Document) p.get("abstract");
            if (ab != null) {
                for (Map.Entry<String, Object> entry : ab.entrySet()) {
                    if (entry.getValue() instanceof String val && !val.isBlank()) {
                        missingAbstract = false;
                        break;
                    }
                }
            }

            // Check 3: Undefined language
            boolean undefinedLanguage = langUriNullOrUnd(mainLang);

            // Check 4: Missing DOI
            boolean missingDoi = true;
            List<Document> evs = getListDocument(p, "electronicVersions");
            List<String> dois = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String doi = ev.getString("doi");
                    if (doi != null && !doi.isBlank()) {
                        missingDoi = false;
                        dois.add(doi);
                    }
                }
            }

            // Check 5: Missing URL
            boolean missingUrl = true;
            List<String> urls = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String link = ev.getString("link");
                    if (link != null && !link.isBlank()) {
                        missingUrl = false;
                        urls.add(link);
                    }
                    Document file = (Document) ev.get("file");
                    if (file != null) {
                        String fileUrl = file.getString("url");
                        if (fileUrl != null && !fileUrl.isBlank()) {
                            missingUrl = false;
                            urls.add(fileUrl);
                        }
                    }
                }
            }
            List<Document> lks = getListDocument(p, "links");
            if (lks != null) {
                for (Document lk : lks) {
                    String url = lk.getString("url");
                    if (url != null && !url.isBlank()) {
                        missingUrl = false;
                        urls.add(url);
                    }
                }
            }

            // Check 6: Duplicates
            List<String> dupTitles = getCachedDuplicateField("titles", "title.value");
            List<String> dupDois = getCachedDuplicateField("dois", "electronicVersions.doi");
            List<String> dupUrls = getCachedDuplicateUrls();
            List<String> dupIdentifiers = getCachedDuplicateIdentifiers();

            Set<String> dupTitlesSet = new HashSet<>(dupTitles);
            Set<String> dupDoisSet = new HashSet<>(dupDois);
            Set<String> dupUrlsSet = new HashSet<>(dupUrls);
            Set<String> dupIdentifiersSet = new HashSet<>(dupIdentifiers);

            String combinedTitle = "";
            if (mainTitle != null && !mainTitle.isBlank()) {
                combinedTitle = mainTitle.trim() + (mainSubTitle != null && !mainSubTitle.isBlank() ? ": " + mainSubTitle.trim() : "");
            }
            boolean duplicateTitle = !combinedTitle.isEmpty() && dupTitlesSet.contains(combinedTitle);
            boolean duplicateDoi = false;
            for (String d : dois) {
                if (dupDoisSet.contains(d)) {
                    duplicateDoi = true;
                    break;
                }
            }
            boolean duplicateUrl = hasDuplicateUrlOfType(p);
            boolean duplicateIdentifier = false;
            List<Document> idList = getListDocument(p, "identifiers");
            for (Document idObj : idList) {
                String val = idObj.getString("value");
                if (val != null && dupIdentifiersSet.contains(val)) {
                    duplicateIdentifier = true;
                    break;
                }
            }
            
            boolean potentialDuplicate = false;
            String author = extractFirstAuthorLastName(p);
            Integer year = p.getInteger("submissionYear");
            if (mainTitle != null && !mainTitle.isBlank()) {
                String normTitle = mainTitle.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
                org.springframework.data.mongodb.core.query.Query dupQuery = new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("uuid").ne(uuid)
                        .and("submissionYear").is(year)
                );
                List<Document> siblingPubs = mongoTemplate.find(dupQuery, Document.class, "Researchoutputs");
                for (Document sib : siblingPubs) {
                    String sibTitle = nestedString(sib, "title", "value");
                    if (sibTitle != null) {
                        String sibNormTitle = sibTitle.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
                        if (sibNormTitle.equals(normTitle)) {
                            String sibAuthor = extractFirstAuthorLastName(sib);
                            String sibNormAuthor = sibAuthor != null ? sibAuthor.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
                            String normAuthor = author != null ? author.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
                            if (sibNormAuthor.equals(normAuthor)) {
                                potentialDuplicate = true;
                                break;
                            }
                        }
                    }
                }
            }

            // Check 7: Text quality
            boolean strangeChars = false;
            List<String> titlesToCheck = new ArrayList<>();
            titlesToCheck.add(mainTitle);
            Document transTitle = (Document) p.get("translatedTitle");
            if (transTitle != null) {
                for (Object val : transTitle.values()) {
                    if (val instanceof String s) titlesToCheck.add(s);
                }
            }
            for (String t : titlesToCheck) {
                if (t != null && containsStrangeCharacters(t)) {
                    strangeChars = true;
                    break;
                }
            }

            boolean allCaps = false;
            if (mainTitle != null && !mainTitle.isBlank() && isAllUppercase(mainTitle)) {
                allCaps = true;
            }
            if (!allCaps && ab != null) {
                for (Object val : ab.values()) {
                    if (val instanceof String s && !s.isBlank() && isAllUppercase(s)) {
                        allCaps = true;
                        break;
                    }
                }
            }

            boolean whitespaceOnly = false;
            if (mainTitle != null && !mainTitle.isBlank() && mainTitle.trim().isEmpty()) {
                whitespaceOnly = true;
            }
            if (!whitespaceOnly) {
                List<Document> contrs = getListDocument(p, "contributors");
                for (Document c : contrs) {
                    Document name = (Document) c.get("name");
                    if (name != null) {
                        String fn = name.getString("firstName");
                        String ln = name.getString("lastName");
                        if ((fn != null && !fn.isEmpty() && fn.trim().isEmpty()) ||
                            (ln != null && !ln.isEmpty() && ln.trim().isEmpty())) {
                            whitespaceOnly = true;
                            break;
                        }
                    }
                }
            }
            boolean textQualityIssue = strangeChars || allCaps || whitespaceOnly;

            // Check 8: Link quality
            boolean invalidUrl = false;
            for (String u : urls) {
                String lu = u.toLowerCase();
                if (!lu.startsWith("http://") && !lu.startsWith("https://")) {
                    invalidUrl = true;
                    break;
                }
                if (u.contains(" ")) {
                    invalidUrl = true;
                    break;
                }
                if (lu.contains("academia.edu") || lu.contains("researchgate.net")) {
                    invalidUrl = true;
                    break;
                }
            }

            // Check 9: ISBN/ISSN
            boolean invalidIsbn = false;
            boolean invalidIssn = false;
            String typeDiscriminator = p.getString("typeDiscriminator");
            boolean isBookOrChapter = "BookAnthology".equalsIgnoreCase(typeDiscriminator)
                || "ContributionToBookAnthology".equalsIgnoreCase(typeDiscriminator)
                || "Book".equalsIgnoreCase(typeDiscriminator)
                || "Chapter".equalsIgnoreCase(typeDiscriminator);

            if (isBookOrChapter) {
                List<String> printIsbns = (List<String>) p.get("printISBNs");
                List<String> elecIsbns = (List<String>) p.get("electronicISBNs");
                List<String> allIsbns = new ArrayList<>();
                boolean hasIsbn = false;
                if (printIsbns != null) {
                    for (String isbn : printIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (elecIsbns != null) {
                    for (String isbn : elecIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (!hasIsbn) {
                    invalidIsbn = true;
                } else {
                    for (String isbn : allIsbns) {
                        if (!isValidIsbn(isbn)) {
                            invalidIsbn = true;
                            break;
                        }
                    }
                }
            } else {
                // Check 10: ISSN
                boolean isArticle = false;
                Document typeObj = (Document) p.get("type");
                String typeUri = typeObj != null ? typeObj.getString("uri") : null;
                if (typeUri != null) {
                    String tu = typeUri.toLowerCase();
                    if (tu.contains("/article") || tu.contains("/contributiontojournal")) {
                        isArticle = true;
                    }
                }
                if (isArticle) {
                    List<String> issns = scanIssnValues(p);
                    boolean hasIssn = false;
                    for (String issn : issns) {
                        if (issn != null && !issn.isBlank()) {
                            hasIssn = true;
                        }
                    }
                    if (!hasIssn) {
                        invalidIssn = true;
                    } else {
                        for (String issn : issns) {
                            if (!isValidIssn(issn)) {
                                invalidIssn = true;
                                break;
                            }
                        }
                    }
                }
            }

            boolean hasErrors = missingAbstract || undefinedLanguage
                || missingDoi || missingUrl || duplicateTitle || duplicateDoi || duplicateUrl
                || duplicateIdentifier || potentialDuplicate || textQualityIssue || invalidUrl
                || invalidIsbn || invalidIssn;

            if (!hasErrors) {
                PublicacionCorreccion pc = publicacionCorreccionRepository.findById(uuid).orElse(null);
                if (pc == null) {
                    pc = new PublicacionCorreccion();
                    pc.setId(uuid);
                }
                pc.setReviewed(true);
                publicacionCorreccionRepository.save(pc);
            }
        } catch (Exception e) {
            System.err.println("Error auto-marking publication as reviewed: " + e.getMessage());
        }
    }

    private Integer syncJournalIssnToEgreta(String journalUuid, String newIssn, String targetEnv) throws Exception {
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json;charset=utf-8");
        headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
        headers.set("Accept", "application/json");

        String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
        String url = baseUrl + "journals/" + journalUuid;

        // 1. GET journal data from Egreta
        org.springframework.http.ResponseEntity<Map> getResp;
        try {
            getResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new Exception("La revista vinculada no s'ha trobat a Egreta (404) en l'entorn de " + ("prod".equalsIgnoreCase(targetEnv) ? "producció" : "tests") + ".");
        } catch (Exception e) {
            throw new Exception("Error al consultar la revista vinculada a Egreta: " + e.getMessage());
        }

        if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
            throw new Exception("Error al consultar la revista vinculada a Egreta. Status: " + getResp.getStatusCode());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());

        // 2. Add ISSN if not already present in Egreta journal
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issns = (List<Map<String, Object>>) data.get("issns");
        if (issns == null) {
            issns = new ArrayList<>();
        }

        Map<String, Object> foundIssnObj = null;
        for (Map<String, Object> iObj : issns) {
            String val = (String) iObj.get("issn");
            if (newIssn.equalsIgnoreCase(val)) {
                foundIssnObj = iObj;
                break;
            }
        }

        if (foundIssnObj == null) {
            Map<String, Object> newIssnEntry = new LinkedHashMap<>();
            newIssnEntry.put("issn", newIssn);
            issns.add(newIssnEntry);
            data.put("issns", issns);

            // 3. PUT back to Egreta
            try {
                org.springframework.http.HttpEntity<Map<String, Object>> putEntity = new org.springframework.http.HttpEntity<>(data, headers);
                org.springframework.http.ResponseEntity<Map> putResp = restTemplate.exchange(
                        url, org.springframework.http.HttpMethod.PUT, putEntity, Map.class);
                if (!putResp.getStatusCode().is2xxSuccessful() || putResp.getBody() == null) {
                    throw new Exception("Error al actualitzar la revista a Egreta. Status: " + putResp.getStatusCode());
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> updatedJournal = putResp.getBody();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> updatedIssns = (List<Map<String, Object>>) updatedJournal.get("issns");
                if (updatedIssns != null) {
                    for (Map<String, Object> iObj : updatedIssns) {
                        String val = (String) iObj.get("issn");
                        if (newIssn.equalsIgnoreCase(val)) {
                            foundIssnObj = iObj;
                            break;
                        }
                    }
                }
            } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
                throw new Exception("Error de validació de la revista a Egreta (400): " + e.getResponseBodyAsString());
            } catch (Exception e) {
                throw new Exception("Error al enviar la revista actualitzada a Egreta: " + e.getMessage());
            }
        }

        if (foundIssnObj != null) {
            Object pid = foundIssnObj.get("pureId");
            if (pid instanceof Number num) {
                return num.intValue();
            }
        }
        return null;
    }

    private List<String> getDuplicateCombinedTitles() {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("title.value", new Document("$exists", true).append("$ne", ""))));
        pipeline.add(new Document("$group", new Document("_id", new Document("title", "$title.value").append("subTitle", "$subTitle.value"))
                .append("count", new Document("$sum", 1))));
        pipeline.add(new Document("$match", new Document("count", new Document("$gt", 1))));
        pipeline.add(new Document("$limit", 5000));

        com.mongodb.client.MongoCollection<Document> collection = mongoTemplate.getCollection("Researchoutputs");
        com.mongodb.client.AggregateIterable<Document> results = collection.aggregate(pipeline);

        List<String> dups = new ArrayList<>();
        for (Document d : results) {
            Document idDoc = (Document) d.get("_id");
            if (idDoc != null) {
                String title = idDoc.getString("title");
                String subTitle = idDoc.getString("subTitle");
                if (title != null && !title.isBlank()) {
                    String combined = title.trim() + (subTitle != null && !subTitle.isBlank() ? ": " + subTitle.trim() : "");
                    dups.add(combined);
                }
            }
        }
        return dups;
    }

    /**
     * Strips control characters (U+0000–U+001F, U+007F–U+009F) from abstract text
     * and normalises typographic/curly quotes to plain ASCII quotes so that Egreta's
     * API does not reject the payload with a 400/422 validation error.
     */
    private String sanitizeAbstractText(String text) {
        if (text == null) return null;
        // Replace typographic quotes with straight equivalents
        text = text
            .replace('\u201C', '"').replace('\u201D', '"')   // " "  → "
            .replace('\u2018', '\'').replace('\u2019', '\'') // ' '  → '
            .replace('\u00AB', '"').replace('\u00BB', '"')   // « »  → "
            .replace('\u2039', '\'').replace('\u203A', '\'') // ‹ ›  → '
            .replace('\u201E', '"').replace('\u201F', '"')   // „ ‟  → "
            .replace("\u2026", "...")                         // …   → ...
            .replace('\u2013', '-').replace('\u2014', '-');  // – —  → -
        // Strip remaining control characters (U+0000–U+001F except \t, \n, \r; and U+007F–U+009F)
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x20 || c == '\t' || c == '\n' || c == '\r') && !(c >= 0x7F && c <= 0x9F)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeXmlExceptEntities(String text) {
        if (text == null) return null;
        text = text.replace("<", "&lt;").replace(">", "&gt;");
        text = text.replaceAll("&(?![a-zA-Z0-9#]+;)", "&amp;");
        return text;
    }

    private boolean hasDuplicateUrlOfType(Document p) {
        List<Document> lks = getListDocument(p, "links");
        if (lks != null) {
            Map<String, Integer> linkTypeCounts = new java.util.HashMap<>();
            for (Document lk : lks) {
                Document lt = (Document) lk.get("linkType");
                String ltUri = lt != null ? lt.getString("uri") : null;
                if (ltUri != null && !ltUri.isEmpty()) {
                    linkTypeCounts.put(ltUri, linkTypeCounts.getOrDefault(ltUri, 0) + 1);
                }
            }
            for (int count : linkTypeCounts.values()) {
                if (count > 1) return true;
            }
        }
        return false;
    }

}
