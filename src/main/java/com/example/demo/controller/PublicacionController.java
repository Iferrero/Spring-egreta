package com.example.demo.controller;

// 1. Modelos y Repositorios propios
import com.example.demo.model.Publicacion;
import com.example.demo.repository.PublicacionRepository;
import com.example.demo.service.ResearchOutputJournalLinkService;

// 2. Spring Web (Anotaciones del Controlador)
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.bson.Document;

@RestController
@RequestMapping({"/api/pure", "/pure", "/otr/api/pure"})
@CrossOrigin(origins = "*")
public class PublicacionController {

    private final PublicacionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;

    public PublicacionController(
            PublicacionRepository repository,
            MongoTemplate mongoTemplate,
            ResearchOutputJournalLinkService researchOutputJournalLinkService) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
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

    @GetMapping("/buscar")
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
            @RequestParam(required = false) Integer hasta) {
        return researchOutputJournalLinkService.quartileDistributionByDepartment(deptUuid, desde, hasta);
    }

    @GetMapping("/stats/quartiles/articles")
    public List<Map<String, Object>> getQuartileArticlesByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return researchOutputJournalLinkService.quartileArticlesByDepartment(deptUuid, desde, hasta);
    }

    @GetMapping("/stats/quartiles/evolution")
    public List<Map<String, Object>> getQuartileEvolutionByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return researchOutputJournalLinkService.quartileEvolutionByDepartment(deptUuid, desde, hasta);
    }
    
    // Estadísticas para Gráfico de Líneas (Años)
@GetMapping("/stats/anios")
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
    @GetMapping("/stats/tipos")
    public List<Map> statsTipos() {
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.group("type.term.es_ES").count().as("total"),
            Aggregation.project("total").and("_id").as("tipo")
        );
        return mongoTemplate.aggregate(agg, "Researchoutputs", Map.class).getMappedResults();
    }

    @GetMapping("/stats/persona-resumen")
    public List<Map> statsPersonaResumen(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid) {

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
                                new Document("$ifNull", Arrays.asList("$contributors.name.firstName", "")),
                                " ",
                                new Document("$ifNull", Arrays.asList("$contributors.name.lastName", ""))
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
                new Document("$ifNull", Arrays.asList("$persona_info.name.firstName", "")),
                " ",
                new Document("$ifNull", Arrays.asList("$persona_info.name.lastName", ""))
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
            .append("personUuid", 1)
            .append("tipoPublicacion", 1)
            .append("persona", personaExpr)));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationUuid", "$publicationUuid")
                .append("personUuid", "$personUuid")
                .append("tipoPublicacion", "$tipoPublicacion")
                .append("persona", "$persona"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("personUuid", "$_id.personUuid")
                .append("persona", "$_id.persona")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

        pipeline.add(new Document("$project", new Document("_id", 0)
                .append("person_uuid", "$_id.personUuid")
                .append("nombre", "$_id.persona")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("num_publicaciones", "$totalPublicaciones")
                // Alias para el frontend actual
                .append("personaUuid", "$_id.personUuid")
                .append("persona", "$_id.persona")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

        pipeline.add(new Document("$sort", new Document("nombre", 1)
                .append("tipo_publicacion", 1)));

        return mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }

    

}