package com.example.demo.controller;

import com.example.demo.model.Award;
import com.example.demo.repository.AwardRepository;
import com.example.demo.service.AwardService;

import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/awards", "/awards", "/otr/api/awards"})
public class AwardController {

    private final AwardRepository repository;
    private final AwardService service;

    public AwardController(AwardRepository repository,
                           AwardService service) {

        this.repository = repository;
        this.service = service;
    }

    /*
    ===============================
    LISTADO BÁSICO
    ===============================
    */

    @GetMapping
    public PagedModel<Award> getAwards(
            @RequestParam(defaultValue = "0") int page) {

        return new PagedModel<>(
                repository.findValidated(PageRequest.of(page, 10)));
    }

    /*
    ===============================
    ESTADÍSTICAS GENERALES
    ===============================
    */

    @GetMapping("/stats/categories")
    public List<String> getCategorias() {
        return service.getCategorias();
    }

    @GetMapping("/stats/tipus")
    public List<String> getTipus() {
        return service.getTipus();
    }

    @GetMapping("/stats/total")
    public Map<String, Object> getTotalStats() {
        return service.getTotalStats();
    }

<<<<<<< HEAD
    /*
    ===============================
    POWER TABLE
    ===============================
    */
=======
@GetMapping("/stats/naturetype-kpi")
public Map<String, Object> getNatureTypeKpi() {
    List<Document> pipeline = List.of(
        new Document("$match", new Document("workflow.step", "validated")),
        new Document("$addFields", new Document("natureTypesSafe", new Document("$ifNull", List.of("$natureTypes", List.of())))),
        new Document("$addFields", new Document("hasNatureType",
            new Document("$gt", List.of(
                new Document("$size", new Document("$filter", new Document("input", "$natureTypesSafe")
                    .append("as", "nt")
                    .append("cond", new Document("$gt", List.of(
                        new Document("$strLenCP", new Document("$trim", new Document("input",
                            new Document("$ifNull", List.of("$$nt.term.ca_ES", ""))
                        ))),
                        0
                    ))))),
                0
            ))
        )),
        new Document("$group", new Document("_id", null)
            .append("withNatureType", new Document("$sum", new Document("$cond", List.of("$hasNatureType", 1, 0))))
            .append("withoutNatureType", new Document("$sum", new Document("$cond", List.of("$hasNatureType", 0, 1))))
            .append("total", new Document("$sum", 1)))
    );

    List<Document> result = mongoTemplate
        .getCollection("Awards")
        .aggregate(pipeline)
        .into(new ArrayList<>());

    if (result.isEmpty()) {
        return Map.of("withNatureType", 0, "withoutNatureType", 0, "total", 0);
    }

    Document row = result.get(0);
    int withNatureType = row.get("withNatureType") instanceof Number n ? n.intValue() : 0;
    int withoutNatureType = row.get("withoutNatureType") instanceof Number n ? n.intValue() : 0;
    int total = row.get("total") instanceof Number n ? n.intValue() : 0;

    return Map.of(
        "withNatureType", withNatureType,
        "withoutNatureType", withoutNatureType,
        "total", total
    );
}

@GetMapping("/stats/naturetypes-breakdown")
public List<Document> getNatureTypesBreakdown() {
    List<Document> pipeline = List.of(
        new Document("$match", new Document("workflow.step", "validated")),
        new Document("$unwind", new Document("path", "$natureTypes").append("preserveNullAndEmptyArrays", true)),
        new Document("$addFields", new Document()
            .append("natureTypeLabel", new Document("$ifNull", List.of("$natureTypes.term.ca_ES", "Sense valor")))
            .append("typeLabel", new Document("$ifNull", List.of(
                "$type.term.ca_ES",
                new Document("$ifNull", List.of("$type.term.en_GB", "Sense tipus"))
            )))),
        new Document("$group", new Document("_id", new Document("NatureType", "$natureTypeLabel")
            .append("Type", "$typeLabel"))
            .append("total_awards", new Document("$sum", 1))),
        new Document("$project", new Document("_id", 0)
            .append("NatureType", "$_id.NatureType")
            .append("Type", "$_id.Type")
            .append("Total Awards", "$total_awards")),
        new Document("$sort", new Document("Total Awards", -1)
            .append("NatureType", 1)
            .append("Type", 1))
    );

    return mongoTemplate
        .getCollection("Awards")
        .aggregate(pipeline)
        .into(new ArrayList<>());
}

@GetMapping("/stats/powertable")
public List<Map> getPowerTable(
        @RequestParam(required = false) Integer desde,
    @RequestParam(required = false) Integer hasta,
    @RequestParam(defaultValue = "awardDate") String modoAnio) {
        Document query = loadMongoQuery("mongodb/awards/powertable.json");
        List<Document> pipeline = query.getList("pipeline", Document.class);
>>>>>>> 3da728f7973b34d1c4273b233fc44513add433b7

    @GetMapping("/stats/powertable")
    public List<Document> getPowerTable(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "awardDate") String modoAnio) {

<<<<<<< HEAD
        return service.getPowerTable(desde, hasta, modoAnio);
=======
        return mongoTemplate
                .getCollection(query.getString("collection"))
                .aggregate(pipeline)
                .into(new ArrayList<>());
}   

@GetMapping("/stats/powertable/category-debug")
public List<Map> getPowerTableCategoryDebug(
    @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));

        List<Document> pipeline = List.of(
            new Document("$match", new Document("workflow.step", "validated")),
            new Document("$project", new Document("_id", 0)
                .append("uuid", 1)
                .append("type_ca", "$type.term.ca_ES")
                .append("categoria", "$categoria")
                .append("classification_term_ca", "$classification.term.ca_ES")
                .append("classification_term_en", "$classification.term.en_GB")
                .append("classification_ca", "$classification.ca_ES")
                .append("classification_en", "$classification.en_GB")
                .append("raw_categoria", "$categoria")
                .append("raw_classification", "$classification")),
            new Document("$limit", safeLimit)
        );

        return mongoTemplate
            .getCollection("Awards")
            .aggregate(pipeline)
            .into(new ArrayList<>());
}

@GetMapping("/resumen-awards")
public String resumenAwards(Model model) {

    List<Document> resultados = repository.getResumenConOrganismos();

    model.addAttribute("tabla", resultados);

    return "resumen-awards";
}

@GetMapping("/stats/persona-resumen")
public List<Document> getPersonaResumen(
    @RequestParam(defaultValue = "84443078-1a60-462d-9d0a-b04312afd9eb") String collaboratorUuid,
    @RequestParam(required = false) String deptUuid,
    @RequestParam(required = false) String persona,
    @RequestParam(required = false) Integer desde,
    @RequestParam(required = false) Integer hasta,
    @RequestParam(defaultValue = "awardDate") String modoAnio) {

        Document query = loadMongoQuery("mongodb/awards/persona-resumen.json");
        List<Document> pipeline = query.getList("pipeline", Document.class);

        applyYearMode(pipeline, "anyo", modoAnio);
        if (isVigenciaMode(modoAnio)) {
            adaptPersonaResumenGroupingForVigencia(pipeline);
        }

        pipeline.removeIf(stage -> {
            Document match = stage.get("$match", Document.class);
            return match != null && match.containsKey("fundings.fundingCollaborators.collaborator.uuid");
        });

        if (collaboratorUuid != null && !collaboratorUuid.isBlank()) {
            int collaboratorInsertIndex = -1;
            for (int i = 0; i < pipeline.size(); i++) {
                Document unwind = pipeline.get(i).get("$unwind", Document.class);
                if (unwind != null && "$fundings.fundingCollaborators".equals(unwind.getString("path"))) {
                    collaboratorInsertIndex = i + 1;
                    break;
                }
            }

            Document collaboratorMatchStage = new Document("$match",
                new Document("fundings.fundingCollaborators.collaborator.uuid", collaboratorUuid));

            if (collaboratorInsertIndex >= 0 && collaboratorInsertIndex <= pipeline.size()) {
                pipeline.add(collaboratorInsertIndex, collaboratorMatchStage);
            } else {
                pipeline.add(collaboratorMatchStage);
            }
        }

        addYearRangeFilter(pipeline, "anyo", desde, hasta);

        if ((deptUuid != null && !deptUuid.isBlank()) || (persona != null && !persona.isBlank())) {
            Set<String> personUuids = getPersonUuidsByFilters(deptUuid, persona);
            if (personUuids.isEmpty()) {
                return List.of();
            }

            for (int i = 0; i < pipeline.size(); i++) {
                Document addFields = pipeline.get(i).get("$addFields", Document.class);
                if (addFields != null && addFields.containsKey("personUuid")) {
                    pipeline.add(i + 1, new Document("$match", new Document("personUuid", new Document("$in", personUuids))));
                    break;
                }
            }
        }

        return mongoTemplate
                .getCollection(query.getString("collection"))
                .aggregate(pipeline)
                .into(new ArrayList<>());
}

@GetMapping("/stats/persona-awards")
public List<Document> getAwardsByPersona(
    @RequestParam String personUuid,
    @RequestParam(required = false) Integer desde,
    @RequestParam(required = false) Integer hasta,
    @RequestParam(defaultValue = "84443078-1a60-462d-9d0a-b04312afd9eb") String collaboratorUuid,
    @RequestParam(required = false) String deptUuid,
    @RequestParam(defaultValue = "awardDate") String modoAnio) {

        Document query = loadMongoQuery("mongodb/awards/persona-awards.json");
        List<Document> pipeline = query.getList("pipeline", Document.class);

        if (isVigenciaMode(modoAnio)) {
            relaxInitialMatchForVigencia(pipeline);
            addVigenciaYearOverlapFilter(pipeline, desde, hasta);
        }

        for (Document stage : pipeline) {
            Document match = stage.get("$match", Document.class);
            if (match != null && match.containsKey("awardHolders.person.uuid")) {
                match.put("awardHolders.person.uuid", personUuid);
            }
            if (match != null && match.containsKey("fundings.fundingCollaborators.collaborator.uuid")) {
                match.put("fundings.fundingCollaborators.collaborator.uuid", collaboratorUuid);
            }
        }

        if (!isVigenciaMode(modoAnio)) {
            addYearRangeFilter(pipeline, "anyo", desde, hasta);
        }

        if (deptUuid != null && !deptUuid.isBlank()) {
            Set<String> personUuids = getPersonUuidsByFilters(deptUuid, null);
            int deptInsertIndex = pipeline.size();
            for (int i = 0; i < pipeline.size(); i++) {
                Document match = pipeline.get(i).get("$match", Document.class);
                if (match != null && match.containsKey("awardHolders.person.uuid")) {
                    deptInsertIndex = i + 1;
                    break;
                }
            }
            pipeline.add(deptInsertIndex, new Document("$match", new Document("awardHolders.person.uuid", new Document("$in", personUuids))));
        }

        return mongoTemplate
                .getCollection(query.getString("collection"))
                .aggregate(pipeline)
                .into(new ArrayList<>());
}

private void addVigenciaYearOverlapFilter(List<Document> pipeline, Integer desde, Integer hasta) {
    if (desde == null && hasta == null) {
        return;
>>>>>>> 3da728f7973b34d1c4273b233fc44513add433b7
    }

    @GetMapping("/stats/powertable/category-debug")
    public List<Map> getPowerTableCategoryDebug(
            @RequestParam(defaultValue = "50") int limit) {

        return service.getPowerTableCategoryDebug(limit);
    }

    /*
    ===============================
    PERSONA RESUMEN
    ===============================
    */

    @GetMapping("/stats/persona-resumen")
    public List<Document> getPersonaResumen(

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(required = false)
            String persona,

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            String gestionadosPorDept,

            @RequestParam(required = false)
            List<String> categoria) {

        return service.getPersonaResumen(
                collaboratorUuid,
                deptUuid,
                persona,
                desde,
                hasta,
                modoAnio,
                gestionadosPorDept,
                categoria);
    }

    /*
    ===============================
    AWARDS POR PERSONA
    ===============================
    */

    @GetMapping("/stats/persona-awards")
    public List<Document> getAwardsByPersona(

            @RequestParam String personUuid,

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            List<String> categoria) {

        return service.getAwardsByPersona(
                personUuid,
                desde,
                hasta,
                collaboratorUuid,
                deptUuid,
                modoAnio,
                categoria);
    }

    /*
    ===============================
    PROYECTOS POR AÑO
    ===============================
    */

    @GetMapping("/stats/proyectos-anio")
    public List<Document> getProyectosPorAnio(

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(required = false)
            String persona,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            List<String> categoria) {

        return service.getProyectosPorAnio(
                desde,
                hasta,
                collaboratorUuid,
                deptUuid,
                persona,
                modoAnio,
                categoria
                );
    }
}