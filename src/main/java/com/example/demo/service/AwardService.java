package com.example.demo.service;

import com.example.demo.util.MongoPipelineBuilder;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AwardService {

    private final MongoTemplate mongoTemplate;

    public AwardService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /*
    ===============================
    CATEGORÍAS
    ===============================
    */

    public List<String> getCategorias() {

        return runPipeline(
                "mongodb/awards/categories.json",
                null
        ).stream()

        .map(doc -> doc.getString("categoria"))

        .filter(c -> c != null && !c.isBlank())

        .toList();
    }

    /*
    ===============================
    TIPUS
    ===============================
    */

    public List<String> getTipus() {

        return runPipeline(
                "mongodb/awards/tipus.json",
                null
        ).stream()

        .map(doc -> {
            Document type = doc.get("type", Document.class);
            if (type == null) return null;

            Document term = type.get("term", Document.class);
            if (term == null) return null;

            return term.getString("ca_ES");
        })

        .filter(t -> t != null && !t.isBlank())

        .toList();
    }

    /*
    ===============================
    TOTAL STATS
    ===============================
    */

    public Map<String, Object> getTotalStats() {

        List<Document> pipeline = List.of(

                new Document("$match",
                        new Document("workflow.step", "validated")),

                new Document("$unwind", "$fundings"),

                new Document("$unwind",
                        "$fundings.fundingCollaborators"),

                new Document("$group",
                        new Document("_id", null)
                                .append("totalDinero",
                                        new Document("$sum",
                                                "$fundings.fundingCollaborators.institutionalPart.value"))
                                .append("totalProyectos",
                                        new Document("$sum", 1)))
        );

        List<Document> result = mongoTemplate
                .getCollection("Awards")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        if (result.isEmpty()) {

            return Map.of(
                    "totalDinero", 0,
                    "totalProyectos", 0);
        }

        Document doc = result.get(0);

        return Map.of(
                "totalDinero", doc.get("totalDinero"),
                "totalProyectos", doc.get("totalProyectos"));
    }

    /*
    ===============================
    POWER TABLE
    ===============================
    */

    public List<Document> getPowerTable(Integer desde,
                                        Integer hasta,
                                        String modoAnio) {

        return runPipeline(
                "mongodb/awards/powertable.json",
                builder -> {

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                });
    }

    /*
    ===============================
    POWER TABLE DEBUG
    ===============================
    */

    public List<Map> getPowerTableCategoryDebug(int limit) {

        int safeLimit = Math.max(1, Math.min(limit, 500));

        List<Document> pipeline = List.of(

                new Document("$match",
                        new Document("workflow.step", "validated")),

                new Document("$project",
                        new Document("_id", 0)
                                .append("uuid", 1)
                                .append("type_ca", "$type.term.ca_ES")
                                .append("categoria", "$categoria")),

                new Document("$limit", safeLimit)
        );

        return mongoTemplate
                .getCollection("Awards")
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }

    /*
    ===============================
    PERSONA RESUMEN
    ===============================
    */

    public List<Document> getPersonaResumen(String collaboratorUuid,
                                            String deptUuid,
                                            String persona,
                                            Integer desde,
                                            Integer hasta,
                                            String modoAnio,
                                            String gestionadosPorDept,
                                            List<String> categoria) {

        return runPipeline(
                "mongodb/awards/persona-resumen.json",
                builder -> {

                    builder.replaceMatch(
                            "fundings.fundingCollaborators.collaborator.uuid",
                            collaboratorUuid);

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                    if (deptUuid != null && !deptUuid.isBlank()) {
                        
                        if("managed".equalsIgnoreCase(gestionadosPorDept)) {
                            // Filtro para awards gestionados por el departamento
                            builder.matchManagingOrg(deptUuid);
                            
                        }

                        Set<String> persons = getPersonUuidsByFilters(deptUuid, persona);
                        if (!persons.isEmpty()) {
                            builder.filterArrayBeforeFirstGroup(
                                        "awardHolders",
                                        "person.uuid",
                                        persons
                                        );
                        }
                    }

                   if (categoria != null && !categoria.isEmpty()) {
                        builder.matchInBeforeFirstGroup("categoria", categoria);
                    }

                });
    }

    /*
    ===============================
    AWARDS POR PERSONA
    ===============================
    */

    public List<Document> getAwardsByPersona(String personUuid,
                                            Integer desde,
                                            Integer hasta,
                                            String collaboratorUuid,
                                            String deptUuid,
                                            String modoAnio,   
                                            List<String> categoria) {

        List<Document> awards = runPipeline(
                "mongodb/awards/persona-awards.json",
                builder -> {

                    builder.replaceMatch(
                            "awardHolders.person.uuid",
                            personUuid);

                    builder.replaceMatch(
                            "fundings.fundingCollaborators.collaborator.uuid",

                            collaboratorUuid);

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                    if (categoria != null && !categoria.isEmpty()) {
                        builder.matchInBeforeFirstProject("categoria", categoria);  
                    }

                });

                enrichCoManagingOrganization(awards);
                return awards;
    }

        private void enrichCoManagingOrganization(List<Document> awards) {
                if (awards == null || awards.isEmpty()) {
                        return;
                }

                Set<String> awardUuids = awards.stream()
                                .map(doc -> doc.getString("awardUuid"))
                                .filter(uuid -> uuid != null && !uuid.isBlank())
                                .collect(Collectors.toSet());

                if (awardUuids.isEmpty()) {
                        return;
                }

                List<Document> awardOrgPipeline = List.of(
                                new Document("$match", new Document("uuid", new Document("$in", awardUuids))),
                                new Document("$project", new Document("_id", 0)
                                                .append("uuid", 1)
                                                .append("managingUuid", "$managingOrganization.uuid")
                                                .append("coManagingUuids", new Document("$map",
                                                                new Document("input", new Document("$ifNull", List.of("$coManagingOrganizations", List.of())))
                                                                                .append("as", "org")
                                                                                .append("in", "$$org.uuid"))))
                );

                List<Document> awardOrgDocs = mongoTemplate.getCollection("Awards")
                                .aggregate(awardOrgPipeline)
                                .into(new ArrayList<>());

                Map<String, List<String>> coManagingByAward = new HashMap<>();
                Set<String> allOrgUuids = new HashSet<>();

                for (Document d : awardOrgDocs) {
                        String awardUuid = d.getString("uuid");
                        String managingUuid = d.getString("managingUuid");
                        List<String> uuids = d.getList("coManagingUuids", String.class);
                        if (awardUuid == null) {
                                continue;
                        }
                        List<String> safe = uuids == null ? new ArrayList<>() : uuids.stream()
                                        .filter(u -> u != null && !u.isBlank())
                                        .filter(u -> managingUuid == null || !managingUuid.equals(u))
                                        .distinct()
                                        .collect(Collectors.toList());
                        coManagingByAward.put(awardUuid, safe);
                        allOrgUuids.addAll(safe);
                }

                Map<String, String> orgNameByUuid = new HashMap<>();
                if (!allOrgUuids.isEmpty()) {
                        List<Document> orgs = mongoTemplate.getCollection("Organizations")
                                        .aggregate(List.of(
                                                        new Document("$match", new Document("uuid", new Document("$in", allOrgUuids))),
                                                        new Document("$project", new Document("_id", 0).append("uuid", 1).append("name", 1))
                                        ))
                                        .into(new ArrayList<>());

                        for (Document org : orgs) {
                                String uuid = org.getString("uuid");
                                Document name = org.get("name", Document.class);
                                if (uuid == null || name == null) {
                                        continue;
                                }
                                String display = firstNonBlank(
                                                name.getString("ca_ES"),
                                                name.getString("es_ES"),
                                                name.getString("en_GB")
                                );
                                if (display != null) {
                                        orgNameByUuid.put(uuid, display);
                                }
                        }
                }

                for (Document award : awards) {
                        String current = asString(award.get("comanagingOrganization"));
                        String managingName = asString(award.get("managingOrganization"));
                        String awardUuid = award.getString("awardUuid");

                        if ((isBlankOrDash(current) || (managingName != null && managingName.equals(current))) && awardUuid != null) {
                                List<String> coUuids = coManagingByAward.getOrDefault(awardUuid, List.of());
                                String resolved = coUuids.stream()
                                                .map(uuid -> orgNameByUuid.getOrDefault(uuid, uuid))
                                                .filter(name -> managingName == null || !managingName.equals(name))
                                                .filter(v -> v != null && !v.isBlank())
                                                .distinct()
                                                .collect(Collectors.joining(", "));
                                if (!resolved.isBlank()) {
                                        current = resolved;
                                }
                        }

                        if (current == null || current.isBlank()) {
                                current = "-";
                        }

                        award.put("comanagingOrganization", current);
                        award.put("coManagingOrganization", current);
                }
        }

        private String asString(Object value) {
                if (value == null) {
                        return null;
                }
                if (value instanceof String s) {
                        return s;
                }
                return String.valueOf(value);
        }

        private boolean isBlankOrDash(String value) {
                return value == null || value.isBlank() || "-".equals(value);
        }

        private String firstNonBlank(String... values) {
                for (String value : values) {
                        if (value != null && !value.isBlank()) {
                                return value;
                        }
                }
                return null;
        }

    private Set<String> getPersonUuidsByFilters(String deptUuid,
                                            String persona) {

            List<Document> personsPipeline = new ArrayList<>();

            if (deptUuid != null && !deptUuid.isBlank()) {
                // Solo contratos vigentes en el departamento
                personsPipeline.add(
                        new Document("$match",
                                new Document("staffOrganizationAssociations",
                                        new Document("$elemMatch",
                                                new Document("organization.uuid", deptUuid)
                                                        .append("$or", List.of(
                                                                new Document("period.endDate", null),
                                                                new Document("period.endDate", new Document("$gte", new Date()))
                                                        ))
                                        )
                                )
                        )
                );
            }

            if (persona != null && !persona.isBlank()) {

                personsPipeline.add(
                        new Document("$match",
                                new Document("$or", List.of(

                                        new Document("name.firstName",
                                                new Document("$regex", persona)
                                                        .append("$options", "i")),

                                        new Document("name.lastName",
                                                new Document("$regex", persona)
                                                        .append("$options", "i"))
                                ))
                ));
            }

            personsPipeline.add(
                    new Document("$project",
                            new Document("_id", 0)
                                    .append("uuid", 1))
            );

            List<Document> persons = mongoTemplate
                    .getCollection("Persons")
                    .aggregate(personsPipeline)
                    .into(new ArrayList<>());

            return persons.stream()
                    .map(p -> p.getString("uuid"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }               

    /*
    ===============================
    PROYECTOS POR AÑO
    ===============================
    */

    public List<Document> getProyectosPorAnio(Integer desde,
                                            Integer hasta,
                                            String collaboratorUuid,
                                            String deptUuid,
                                            String persona,
                                            String modoAnio,
                                            List<String> categoria) {

        return runPipeline(
                "mongodb/awards/proyectos-anio.json",
                builder -> {

                    builder.replaceMatch(
                            "fundings.fundingCollaborators.collaborator.uuid",
                            collaboratorUuid);

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                    // filtro por persona
                    if (persona != null && !persona.isBlank()) {
                        builder.match("awardHolders.person.uuid", persona);
                    }

                    // filtro por categoria
                    if (categoria != null && !categoria.isEmpty()) {
                        builder.matchInBeforeFirstGroup("categoria", categoria);
                    }

                    // filtro por departamento
                    if (deptUuid != null && !deptUuid.isBlank()) {

                        Set<String> persons =
                                getPersonUuidsByFilters(deptUuid, null);

                        if (!persons.isEmpty()) {
                            builder.matchIn("awardHolders.person.uuid", persons);
                        }
                    }

                });
    }

    /*
    ===============================
    MÉTODOS UTILIDAD
    ===============================
    */

private Document loadMongoQuery(String classpathLocation) {

        try {

            ClassPathResource resource =
                    new ClassPathResource(classpathLocation);

            String json = StreamUtils.copyToString(
                    resource.getInputStream(),
                    StandardCharsets.UTF_8);

            return Document.parse(json);

        } catch (IOException e) {

            throw new IllegalStateException(
                    "No se pudo cargar la consulta MongoDB: "
                            + classpathLocation,
                    e);
        }
    }

    private List<Document> runPipeline(String jsonPath,
                                   java.util.function.Consumer<MongoPipelineBuilder> config) {

                Document query = loadMongoQuery(jsonPath);

                List<Document> pipeline = query.getList("pipeline", Document.class);

                MongoPipelineBuilder builder = new MongoPipelineBuilder(pipeline);

                if (config != null) {
                        config.accept(builder);
                }

                List<Document> finalPipeline = builder.build();
                //System.out.println("[Mongo Pipeline] " + jsonPath + ":\n" + finalPipeline);
                finalPipeline.forEach(stage -> System.out.println(stage.toJson()));

                return mongoTemplate
                                .getCollection(query.getString("collection"))
                                .aggregate(finalPipeline)
                                .into(new ArrayList<>());
    }

    
}

