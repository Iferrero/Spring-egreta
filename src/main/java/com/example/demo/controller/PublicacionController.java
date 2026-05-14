package com.example.demo.controller;

// 1. Modelos y Repositorios propios
import com.example.demo.model.Publicacion;
import com.example.demo.repository.PublicacionRepository;
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
import java.util.Comparator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;

@RestController
@RequestMapping("/api/pure")
@CrossOrigin(origins = "*")
public class PublicacionController {

    private final PublicacionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;

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
            ResearchOutputJournalLinkService researchOutputJournalLinkService) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
    }

    private String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid) {
        return buildStatsCacheKey(desde, hasta, deptUuid, null);
    }

    private String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid, String filtrePersonal) {
        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();
        String fp = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal;
        return String.valueOf(desde) + "_" + String.valueOf(hasta) + "_" + String.join(",", deptFilter) + "_" + fp;
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

    // Estadísticas para Gráfico de Donut (Tipos)
    @GetMapping("/stats/types")
    public List<Map> statsTipos() {
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.group("type.term.es_ES").count().as("total"),
            Aggregation.project("total").and("_id").as("tipo")
        );
        return mongoTemplate.aggregate(agg, "Researchoutputs", Map.class).getMappedResults();
    }

    @GetMapping("/stats/types-by-year")
    public List<Map> statsTiposPorAnio(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid,
            @RequestParam(required = false, defaultValue = "vigent") String filtrePersonal) {

        String cacheKey = buildStatsCacheKey(desde, hasta, deptUuid, filtrePersonal);
        StatsCacheEntry cached = tiposPorAnioCache.get(cacheKey);
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
                .append("publicationYear", 1)
                .append("tipoPublicacion", 1)
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

        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();

        Document assocCriteria = buildPersonAssocCriteria(filtrePersonal, desde, hasta);

        if (!deptFilter.isEmpty()) {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", new Document("$and", Arrays.asList(
                    new Document("organization.uuid", new Document("$in", deptFilter)),
                    assocCriteria
                )))
            )));
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
            @RequestParam(required = false, defaultValue = "vigent") String filtrePersonal) {

        // Check cache first
        String fp = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal;
        String cacheKey = desde + "_" + hasta + "_" + (deptUuid == null ? "" : String.join(",", deptUuid)) + "_" + fp;
        ApaListCacheEntry cached = apaListCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < APA_LIST_CACHE_TTL_MS) {
            return cached.data();
        }

        // Step 1: Get unique publication UUIDs with year, filtered by department/year
        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))
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

        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();

        Document assocCriteriaApa = buildPersonAssocCriteria(filtrePersonal, desde, hasta);

        if (!deptFilter.isEmpty()) {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", new Document("$and", Arrays.asList(
                    new Document("organization.uuid", new Document("$in", deptFilter)),
                    assocCriteriaApa
                )))
            )));
        } else {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", assocCriteriaApa)
            )));
        }

        // Deduplicate by publication UUID, keep year
        pipeline.add(new Document("$group", new Document("_id", "$publicationUuid")
                .append("publicationYear", new Document("$first", "$publicationYear"))));

        List<Document> rows = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

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

}