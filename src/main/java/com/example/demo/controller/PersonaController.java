package com.example.demo.controller;

import com.example.demo.model.Organizacion;
import com.example.demo.model.Persona;
import com.example.demo.repository.PersonaRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/persons", "/persons", "/otr/api/persons"})
@CrossOrigin(origins = "*") // Permite llamadas desde tu index.html
public class PersonaController {

    private final MongoTemplate mongoTemplate;

    // Constructor para inyectar las dependencias (Soluciona el error de inicialización)
    public PersonaController(PersonaRepository repository, MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Lista todas las personas u opcionalmente filtra por apellido.
     * Ordenado siempre por apellido.
     */
    @GetMapping
    public PagedModel<Persona> listar(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String apellido,
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Boolean tieneOrcid, 
        @RequestParam(required = false) String employmentType, 
            @RequestParam(defaultValue = "0") int page) {
        
        Pageable pageable = PageRequest.of(page, 10, Sort.by("name.lastName").ascending());
        Query query = new Query().with(pageable);

        addPersonaSearchCriteria(query, resolvePersonaFilter(persona, apellido));

        // Filtro por Departamento (si existe)
        // Buscamos dentro del array staffOrganizationAssociations
        if (deptUuid != null && !deptUuid.trim().isEmpty()) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid));
        }

        // Filtro por Tiene ORCID
        if (tieneOrcid != null) {
            if (tieneOrcid) {
                query.addCriteria(Criteria.where("orcid").exists(true).ne(null).ne(""));
            } else {
                query.addCriteria(new Criteria().orOperator(
                    Criteria.where("orcid").exists(false),
                    Criteria.where("orcid").is(null),
                    Criteria.where("orcid").is("")
                ));
            }
        }

        // Filtro Employment Type desde gráfico
        if (employmentType != null && !employmentType.isEmpty()) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations.employmentType.term.text.value").is(employmentType));
        }

        // Ejecutar consulta
        List<Persona> personas = mongoTemplate.find(query, Persona.class);
        
        // Contar total para la paginación (quitando límites de la query original)
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Persona.class);
        
        Page<Persona> pageResult = PageableExecutionUtils.getPage(personas, pageable, () -> total);
        return new PagedModel<>(pageResult);
    }

    /**
     * Lista solo las personas vigentes (contrato null o posterior a hoy).
     */
    @GetMapping("/vigentes")
    public PagedModel<Persona> listarVigentes(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String apellido,
            @RequestParam(required = false) String deptUuid, // Nuevo parámetro
            @RequestParam(required = false) String personalType,
            @RequestParam(defaultValue = "0") int page) {
        
        Pageable pageable = PageRequest.of(page, 10, Sort.by("name.lastName").ascending());
        Query query = new Query().with(pageable);
        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();

        Criteria activeAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").exists(false),
            new Criteria().andOperator(
                Criteria.where("period.endDate").type(9),
                Criteria.where("period.endDate").gt(hoy)
            ),
            new Criteria().andOperator(
                Criteria.where("period.endDate").type(2),
                Criteria.where("period.endDate").gt(hoyIso)
            )
        );

        // 1. Filtro de Vigencia (obligatorio). Si hay departamento, ambos criterios
        // se aplican sobre la misma asociación para evitar cruces entre elementos del array.
        if (deptUuid != null && !deptUuid.trim().isEmpty()) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(
                new Criteria().andOperator(
                    Criteria.where("organization.uuid").is(deptUuid),
                    activeAssociationCriteria
                )
            ));
        } else {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(activeAssociationCriteria));
        }

        // 2. Filtro por Persona (nombre/apellido) (Opcional)
        addPersonaSearchCriteria(query, resolvePersonaFilter(persona, apellido));

        // 3. El filtro por departamento ya se integra con vigencia en el paso 1.

        addPersonalTypeCriteria(query, personalType);

        addPersonalTypeCriteria(query, personalType);

        // 4. Ejecución de la consulta
        List<Persona> personas = mongoTemplate.find(query, Persona.class);
        
        // 5. Conteo para paginación (respetando los filtros anteriores)
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Persona.class);
        
        Page<Persona> pageResult = PageableExecutionUtils.getPage(personas, pageable, () -> total);
        return new PagedModel<>(pageResult);
    }

    /**
     * Estadísticas para el gráfico de ORCID.
     */
    @GetMapping("/stats/orcid")
    public Map<String, Long> obtenerStatsOrcid(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String apellido,
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false, defaultValue = "false") boolean vigente) {

        Query query = new Query();
        LocalDate hoy = LocalDate.now();

        addPersonaSearchCriteria(query, resolvePersonaFilter(persona, apellido));

        // 2. Lógica combinada de Departamento y Vigencia
    if (deptUuid != null && !deptUuid.trim().isEmpty() && vigente) {
        // CASO CRÍTICO: Debe estar vigente EN el departamento seleccionado
        query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(
            new Criteria().andOperator(
                Criteria.where("organization.uuid").is(deptUuid),
                new Criteria().orOperator(
                    Criteria.where("period.endDate").is(null),
                    Criteria.where("period.endDate").gte(hoy)
                )
            )
        ));
    } else if (deptUuid != null && !deptUuid.trim().isEmpty()) {
        // Solo departamento
        query.addCriteria(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid));
    } else if (vigente) {
        // Solo vigencia (en cualquier departamento)
        query.addCriteria(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.period.endDate").is(null),
            Criteria.where("staffOrganizationAssociations.period.endDate").gte(hoy)
        ));
    }
        long totalFiltrados = mongoTemplate.count(query, Persona.class);
        
        // Contamos los que tienen ORCID dentro de ese subgrupo
        Query queryConOrcid = Query.of(query).addCriteria(
            Criteria.where("orcid").exists(true).ne(null).ne("")
        );
        long conOrcid = mongoTemplate.count(queryConOrcid, Persona.class);

        return Map.of("conOrcid", conOrcid, "sinOrcid", totalFiltrados - conOrcid);
    }

        /**
         * Reporte de asociaciones para una organización concreta y rango de fechas.
         * Ejecuta una agregación similar a la consulta suministrada por el usuario.
         */
        @GetMapping("/associations/report")
        public List<Map> getAssociationsReport(
            @RequestParam String orgUuid,
            @RequestParam(required = false, defaultValue = "2021-01-01") String startDate,
            @RequestParam(required = false, defaultValue = "2025-12-31") String endDate
        ) {
        // Construir pipeline en Document para mayor flexibilidad
        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$unwind", "$staffOrganizationAssociations"));

        // Match por periodo y rango de fechas
        Document match = new Document();
        Document andConditions = new Document();
        andConditions.put("staffOrganizationAssociations.period.startDate", new Document("$lte", endDate));

        List<Document> orList = new ArrayList<>();
        orList.add(new Document("staffOrganizationAssociations.period.endDate", null));
        orList.add(new Document("staffOrganizationAssociations.period.endDate", new Document("$gte", startDate)));

        match.put("$and", List.of(andConditions));
        match.put("$or", orList);

        pipeline.add(new Document("$match", match));

        pipeline.add(new Document("$addFields", new Document("assoc", "$staffOrganizationAssociations")));

        // Group by person uuid
        Document group = new Document();
        group.put("_id", "$uuid");
        group.put("nombre", new Document("$first", new Document("$concat", List.of("$name.firstName", " ", "$name.lastName"))));
        group.put("asociaciones", new Document("$push", "$assoc"));
        pipeline.add(new Document("$group", group));

        // addFields to split ibb_assoc and dept_assoc based on orgUuid
        Document addFields = new Document();
        addFields.put("ibb_assoc", new Document("$filter", new Document("input", "$asociaciones").append("as", "a").append("cond",
            new Document("$eq", List.of("$$a.organization.uuid", orgUuid))
        )));

        addFields.put("dept_assoc", new Document("$filter", new Document("input", "$asociaciones").append("as", "a").append("cond",
            new Document("$ne", List.of("$$a.organization.uuid", orgUuid))
        )));

        pipeline.add(new Document("$addFields", addFields));

        // unwind ibb_assoc (si no existe, la agregación descartará)
        pipeline.add(new Document("$unwind", "$ibb_assoc"));

        // empleo_final calc
        Document cond = new Document();
        cond.put("$eq", List.of("$ibb_assoc.employmentType.term.es_ES", "Adscripción a investigación"));

        Document empleoFinal = new Document("$cond", List.of(cond,
            new Document("$arrayElemAt", List.of("$dept_assoc.employmentType.term.es_ES", 0)),
            "$ibb_assoc.employmentType.term.es_ES"
        ));

        pipeline.add(new Document("$addFields", new Document("empleo_final", empleoFinal)));

        // project final
        Document project = new Document();
        project.put("_id", 0);
        project.put("nombre", 1);
        project.put("empleo", "$empleo_final");
        project.put("inicio_asociacion_IBB", "$ibb_assoc.period.startDate");
        project.put("fin_asociacion_IBB", "$ibb_assoc.period.endDate");
        pipeline.add(new Document("$project", project));

        pipeline.add(new Document("$sort", new Document("nombre", 1)));

        // Ejecutar agregación en la colección "Persons"
        List<Map> results = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons").aggregate(pipeline).forEach(d -> results.add(d));

        return results;
        }

    /**
     * Devuelve el último contrato por persona para un instituto dado, siguiendo
     * la pipeline proporcionada por el usuario (unwind, match por orgUuid,
     * sort por startDate desc, group, resolución de empleo_final, project).
     */
        @GetMapping("/associations/latest")
        public List<Map> getLatestAssociations(
            @RequestParam String orgUuid,
            @RequestParam(required = false, defaultValue = "2021-01-01") String startDate,
            @RequestParam(required = false, defaultValue = "2025-12-31") String endDate
        ) {
        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$unwind", "$staffOrganizationAssociations"));

        // Match por organización y por rango de fechas del slider
        Document assocMatch = new Document();
        assocMatch.put("$and", List.of(
            new Document("staffOrganizationAssociations.organization.uuid", orgUuid),
            new Document("staffOrganizationAssociations.period.startDate", new Document("$lte", endDate)),
            new Document("$or", List.of(
                new Document("staffOrganizationAssociations.period.endDate", null),
                new Document("staffOrganizationAssociations.period.endDate", new Document("$gte", startDate))
            ))
        ));

        pipeline.add(new Document("$match", assocMatch));

        pipeline.add(new Document("$sort", new Document("staffOrganizationAssociations.period.startDate", -1)));

        Document group = new Document();
        group.put("_id", "$uuid");
        group.put("nombre", new Document("$first", new Document("$concat", List.of("$name.firstName", " ", "$name.lastName"))));
        group.put("ultimo_contrato", new Document("$first", "$staffOrganizationAssociations"));
        group.put("asociaciones", new Document("$push", "$staffOrganizationAssociations"));
        pipeline.add(new Document("$group", group));

        Document empleoCond = new Document("$eq", List.of("$ultimo_contrato.employmentType.term.ca_ES", "Adscripció a recerca"));
        Document empleoFinal = new Document("$cond", List.of(empleoCond,
                new Document("$arrayElemAt", List.of("$asociaciones.employmentType.term.ca_ES", 1)),
                "$ultimo_contrato.employmentType.term.ca_ES"
        ));

        pipeline.add(new Document("$addFields", new Document("empleo_final", empleoFinal)));

        Document project = new Document();
        project.put("_id", 0);
        project.put("nombre", 1);
        project.put("empleo", "$empleo_final");
        project.put("inicio_asociacion_IBB", "$ultimo_contrato.period.startDate");
        project.put("fin_asociacion_IBB", "$ultimo_contrato.period.endDate");
        pipeline.add(new Document("$project", project));

        pipeline.add(new Document("$sort", new Document("nombre", 1)));

        List<Map> results = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons").aggregate(pipeline).forEach(d -> results.add(d));
        return results;
    }



    @GetMapping("/departamentos")
    public List<Map<String, String>> listarDepartamentos() {
        // Creamos la consulta con los criterios específicos
        Query query = new Query();
        
        // 1. Que el tipo sea departamento
        query.addCriteria(Criteria.where("type.term.ca_ES").is("Departament"));
        
        // 2. Que el endDate del lifecycle sea nulo (esté vigente)
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));

        // 2. ORDENACIÓN (Añade esta línea)
        query.with(Sort.by(Sort.Direction.ASC, "name.ca_ES"));
        // Obtenemos de la colección "organizations" el nombre y uuid
        // Ejecutamos la búsqueda en la colección "organizations"
        return mongoTemplate.find(query, Organizacion.class, "Organizations").stream()
            .map(org -> Map.of(
                "uuid", org.getUuid(),
                "nombre", org.getNombre()
            ))
            .toList();
    }

    /**
     * Lista los institutos propis (approx) filtrando por type.term que contenga
     * 'Institut' / 'Instituto' / 'Institute' y lifecycle.endDate nulo.
     */
    @GetMapping("/institutos")
    public List<Map<String, String>> listarInstitutos() {
        Query query = new Query();

        // Intentamos detectar de forma única el valor real en la BD para "instituts de recerca propis".
        List<String> distinctCa = mongoTemplate.getDb()
                .getCollection("Organizations")
                .distinct("type.term.ca_ES", String.class)
                .into(new java.util.ArrayList<>());

        // Buscamos candidatos que contengan las palabras "recerca" y "propi(s)" (case-insensitive)
        List<String> candidatos = distinctCa.stream()
                .filter(v -> v != null)
                .filter(v -> {
                    String low = v.toLowerCase();
                    return (low.contains("recerca") && (low.contains("propi") || low.contains("propis")));
                })
                .toList();

        if (candidatos.size() == 1) {
            // Usamos el valor exacto detectado
            query.addCriteria(Criteria.where("type.term.ca_ES").is(candidatos.get(0)));
        } else {
            // No se detectó un valor único: devolvemos lista vacía para evitar filtrar por patrones imprecisos.
            return java.util.Collections.emptyList();
        }

        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        query.with(Sort.by(Sort.Direction.ASC, "name.ca_ES"));

        return mongoTemplate.find(query, Organizacion.class, "Organizations").stream()
            .map(org -> Map.of(
                "uuid", org.getUuid(),
                "nombre", org.getNombre()
            ))
            .toList();
    }

    /**
     * Devuelve los valores distintos encontrados en la colección `Organizations` para
     * `type.term.ca_ES`, `type.term.es_ES` y `type.term.en_GB`.
     */
    @GetMapping("/organization-types")
    public Map<String, List<String>> listarOrganizationTypes() {
        List<String> ca = mongoTemplate.getDb().getCollection("Organizations").distinct("type.term.ca_ES", String.class).into(new java.util.ArrayList<>());
        List<String> es = mongoTemplate.getDb().getCollection("Organizations").distinct("type.term.es_ES", String.class).into(new java.util.ArrayList<>());
        List<String> en = mongoTemplate.getDb().getCollection("Organizations").distinct("type.term.en_GB", String.class).into(new java.util.ArrayList<>());
        return Map.of("ca_ES", ca, "es_ES", es, "en_GB", en);
    }

    @GetMapping("/stats/employment")
    public List<Map> getEmploymentStats(
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String apellido,
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false, defaultValue = "false") boolean vigentes) {

        // 1. Filtros básicos de persona
        List<Criteria> criteriaList = new ArrayList<>();
        String filtroPersona = resolvePersonaFilter(persona, apellido);
        if (filtroPersona != null && !filtroPersona.isEmpty()) {
            String escapedTerm = Pattern.quote(filtroPersona);
            criteriaList.add(new Criteria().orOperator(
                Criteria.where("name.firstName").regex(escapedTerm, "i"),
                Criteria.where("name.lastName").regex(escapedTerm, "i")
            ));
        }

        // 2. Construcción de la agregación
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.match(criteriaList.isEmpty() ? new Criteria() : new Criteria().andOperator(criteriaList.toArray(new Criteria[0]))),
            Aggregation.unwind("staffOrganizationAssociations"),
            
            // Filtro de Departamento y Vigencia sobre la asociación "desenrollada"
            Aggregation.match(buildAssocCriteria(deptUuid, vigentes)),
            
            // Agrupar por el nombre del tipo de empleo (asumiendo el primer valor del array de texto)
            Aggregation.group("staffOrganizationAssociations.employmentType.term.ca_ES").count().as("cantidad"),
            Aggregation.project("cantidad").and("_id").as("tipo"),
            Aggregation.sort(Sort.Direction.DESC, "cantidad")
        );

        return mongoTemplate.aggregate(agg, Persona.class, Map.class).getMappedResults();
    }

    // Método auxiliar para limpiar el código
    private Criteria buildAssocCriteria(String deptUuid, boolean vigentes) {
        List<Criteria> c = new ArrayList<>();
        if (deptUuid != null && !deptUuid.isEmpty()) {
            c.add(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid));
        }
        if (vigentes) {
            c.add(new Criteria().orOperator(
                Criteria.where("staffOrganizationAssociations.period.endDate").is(null),
                Criteria.where("staffOrganizationAssociations.period.endDate").gte(LocalDate.now())
            ));
        }
        return c.isEmpty() ? new Criteria() : new Criteria().andOperator(c.toArray(new Criteria[0]));
    }

    private String resolvePersonaFilter(String persona, String apellido) {
        if (persona != null && !persona.trim().isEmpty()) {
            return persona.trim();
        }
        if (apellido != null && !apellido.trim().isEmpty()) {
            return apellido.trim();
        }
        return null;
    }

    private void addPersonaSearchCriteria(Query query, String term) {
        if (term == null || term.isEmpty()) {
            return;
        }

        String escapedTerm = Pattern.quote(term);
        query.addCriteria(new Criteria().orOperator(
            Criteria.where("name.firstName").regex(escapedTerm, "i"),
            Criteria.where("name.lastName").regex(escapedTerm, "i")
        ));
    }

    @GetMapping("/with-projects")
    public List<Map> listarPersonasConProyectos(
            @RequestParam(defaultValue = "0") int page) {
        
        Aggregation agg = Aggregation.newAggregation(
            // 1. Unimos con la colección de Awards
            // Buscamos awards donde el uuid de la persona esté en la lista de holders
            Aggregation.lookup("awards", "uuid", "awardHolders.person.uuid", "proyectos"),
            
            // 2. Filtramos para que solo cuente los proyectos que están 'validated'
            // (Opcional, si quieres que el contador solo sea de proyectos oficiales)
            
            // 3. Proyectamos los datos finales
            Aggregation.project("pureId", "name", "staffOrganizationAssociations")
                .and("proyectos").size().as("totalProyectos"),
                
            Aggregation.sort(Sort.Direction.DESC, "totalProyectos"),
            Aggregation.skip((long) page * 10),
            Aggregation.limit(10)
        );

        return mongoTemplate.aggregate(agg, "persons", Map.class).getMappedResults();
    }

    @GetMapping("/stats/age-pyramid")
    public List<Map<String, Object>> getAgePyramidStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Query query = new Query();
        query.addCriteria(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.period.endDate").is(null),
            Criteria.where("staffOrganizationAssociations.period.endDate").gte(hoy)
        ));

        addPersonalTypeCriteria(query, personalType);
        addDepartmentCriteria(query, deptUuid);

        List<Document> docs = mongoTemplate.find(query, Document.class, "Persons");

        String[] labels = {
            "20-24", "25-29", "30-34", "35-39", "40-44", "45-49", "50-54", "55-59", "60-64", "65+"
        };

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (String label : labels) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rango", label);
            row.put("hombres", 0);
            row.put("mujeres", 0);
            row.put("otros", 0);
            buckets.add(row);
        }

        for (Document doc : docs) {
            Integer edad = extractAge(doc, hoy);
            if (edad == null || edad < 20) {
                continue;
            }

            int idx = edad >= 65 ? 9 : Math.max(0, Math.min(8, (edad - 20) / 5));
            Map<String, Object> bucket = buckets.get(idx);

            String genero = extractGender(doc);
            if (isMale(genero)) {
                bucket.put("hombres", ((Integer) bucket.get("hombres")) + 1);
            } else if (isFemale(genero)) {
                bucket.put("mujeres", ((Integer) bucket.get("mujeres")) + 1);
            } else {
                bucket.put("otros", ((Integer) bucket.get("otros")) + 1);
            }
        }

        return buckets;
    }

    @GetMapping("/stats/catedraticos")
    public Map<String, Long> getCatedraticosStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Criteria activeAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").gte(hoy)
        );

        Criteria catedraticosCriteria = new Criteria().orOperator(
            Criteria.where("employmentType.term.ca_ES").regex("catedr", "i"),
            Criteria.where("employmentType.term.es_ES").regex("catedr", "i"),
            Criteria.where("employmentType.term.en_GB").regex("chair|full professor", "i"),
            Criteria.where("employmentType.term.text.value").regex("catedr", "i")
        );

        Criteria catedraticActiveCriteria = Criteria.where("staffOrganizationAssociations").elemMatch(
            new Criteria().andOperator(activeAssociationCriteria, catedraticosCriteria)
        );

        Query query = new Query();
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            query.addCriteria(catedraticActiveCriteria);
        } else if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                catedraticActiveCriteria,
                Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())
            ));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                catedraticActiveCriteria,
                Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())
            ));
        } else {
            query.addCriteria(catedraticActiveCriteria);
        }

        addDepartmentCriteria(query, deptUuid);
        long total = mongoTemplate.count(query, Persona.class);

        return Map.of("total", total);
    }

    @GetMapping("/stats/titulares")
    public Map<String, Long> getTitularesStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Criteria activeAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").gte(hoy)
        );

        Criteria titularesCriteria = new Criteria().orOperator(
            Criteria.where("employmentType.term.ca_ES").regex("titular", "i"),
            Criteria.where("employmentType.term.es_ES").regex("titular", "i"),
            Criteria.where("employmentType.term.en_GB").regex("tenured|tenure", "i"),
            Criteria.where("employmentType.term.text.value").regex("titular", "i")
        );

        Criteria titularesActiveCriteria = Criteria.where("staffOrganizationAssociations").elemMatch(
            new Criteria().andOperator(activeAssociationCriteria, titularesCriteria)
        );

        Query query = new Query();
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            query.addCriteria(titularesActiveCriteria);
        } else if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                titularesActiveCriteria,
                Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())
            ));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                titularesActiveCriteria,
                Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())
            ));
        } else {
            query.addCriteria(titularesActiveCriteria);
        }

        addDepartmentCriteria(query, deptUuid);
        long total = mongoTemplate.count(query, Persona.class);

        return Map.of("total", total);
    }

    @GetMapping("/stats/agregados")
    public Map<String, Long> getAgregadosStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Criteria activeAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").gte(hoy)
        );

        Criteria agregadosCriteria = new Criteria().orOperator(
            Criteria.where("employmentType.term.ca_ES").regex("agregat", "i"),
            Criteria.where("employmentType.term.es_ES").regex("agregado", "i"),
            Criteria.where("employmentType.term.en_GB").regex("associate professor", "i"),
            Criteria.where("employmentType.term.text.value").regex("agregat|agregado", "i")
        );

        Criteria agregadosActiveCriteria = Criteria.where("staffOrganizationAssociations").elemMatch(
            new Criteria().andOperator(activeAssociationCriteria, agregadosCriteria)
        );

        Query query = new Query();
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            query.addCriteria(agregadosActiveCriteria);
        } else if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                agregadosActiveCriteria,
                Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())
            ));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                agregadosActiveCriteria,
                Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())
            ));
        } else {
            query.addCriteria(agregadosActiveCriteria);
        }

        addDepartmentCriteria(query, deptUuid);
        long total = mongoTemplate.count(query, Persona.class);

        return Map.of("total", total);
    }

    @GetMapping("/stats/lectores")
    public Map<String, Long> getLectoresStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Criteria activeAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").gte(hoy)
        );

        Criteria lectoresCriteria = new Criteria().orOperator(
            Criteria.where("employmentType.term.ca_ES").is("Professor/a lector/a ajudant doctor/a"),
            Criteria.where("employmentType.term.es_ES").is("Profesor/a lector/a ayudante doctor/a"),
            Criteria.where("employmentType.term.en_GB").is("Assistant Professor Lecturer"),
            Criteria.where("employmentType.term.text.value").is("Professor/a lector/a ajudant doctor/a")
        );

        Criteria lectoresActiveCriteria = Criteria.where("staffOrganizationAssociations").elemMatch(
            new Criteria().andOperator(activeAssociationCriteria, lectoresCriteria)
        );

        Query query = new Query();
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            query.addCriteria(lectoresActiveCriteria);
        } else if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                lectoresActiveCriteria,
                Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())
            ));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                lectoresActiveCriteria,
                Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())
            ));
        } else {
            query.addCriteria(lectoresActiveCriteria);
        }

        addDepartmentCriteria(query, deptUuid);
        long total = mongoTemplate.count(query, Persona.class);

        return Map.of("total", total);
    }

    @GetMapping("/stats/asociados")
    public Map<String, Long> getAsociadosStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Criteria activeAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").gte(hoy)
        );

        Criteria asociadosCriteria = new Criteria().orOperator(
            Criteria.where("employmentType.term.ca_ES").regex("associat", "i"),
            Criteria.where("employmentType.term.es_ES").regex("asociad", "i"),
            Criteria.where("employmentType.term.en_GB").regex("associate", "i"),
            Criteria.where("employmentType.term.text.value").regex("associat|asociad|associate", "i")
        );

        Criteria asociadosActiveCriteria = Criteria.where("staffOrganizationAssociations").elemMatch(
            new Criteria().andOperator(activeAssociationCriteria, asociadosCriteria)
        );

        Query query = new Query();
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            query.addCriteria(asociadosActiveCriteria);
        } else if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                asociadosActiveCriteria,
                Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())
            ));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                asociadosActiveCriteria,
                Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())
            ));
        } else {
            query.addCriteria(asociadosActiveCriteria);
        }

        addDepartmentCriteria(query, deptUuid);
        long total = mongoTemplate.count(query, Persona.class);
        return Map.of("total", total);
    }

    @GetMapping("/stats/icrea")
    public Map<String, Long> getIcreaStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        Criteria activeVisitingAssociationCriteria = new Criteria().orOperator(
            Criteria.where("period.endDate").is(null),
            Criteria.where("period.endDate").gte(hoy),
            Criteria.where("period.endDate").exists(false),
            Criteria.where("period").exists(false)
        );

        Criteria icreaJobTitleCriteria = new Criteria().orOperator(
            Criteria.where("jobTitle.term.ca_ES").regex("icrea", "i"),
            Criteria.where("jobTitle.term.es_ES").regex("icrea", "i"),
            Criteria.where("jobTitle.term.en_GB").regex("icrea", "i"),
            Criteria.where("jobTitle.term.text.value").regex("icrea", "i"),
            Criteria.where("jobTitle.term.value").regex("icrea", "i")
        );

        Criteria icreaActiveCriteria = Criteria.where("visitingScholarOrganizationAssociations").elemMatch(
            new Criteria().andOperator(activeVisitingAssociationCriteria, icreaJobTitleCriteria)
        );

        Query query = new Query();
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            query.addCriteria(icreaActiveCriteria);
        } else if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                icreaActiveCriteria,
                Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())
            ));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(new Criteria().andOperator(
                icreaActiveCriteria,
                Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())
            ));
        } else {
            query.addCriteria(icreaActiveCriteria);
        }

        addDepartmentCriteria(query, deptUuid);
        long total = mongoTemplate.count(query, Persona.class);
        return Map.of("total", total);
    }

    @GetMapping("/stats/lectores/candidates")
    public List<Map<String, Object>> getLectoresCandidates(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false, defaultValue = "lector|lectur|reader|associat|asociad|associate|ajudant|ayudant") String q) {
        LocalDate hoy = LocalDate.now();

        Criteria activeAssoc = new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.period.endDate").is(null),
            Criteria.where("staffOrganizationAssociations.period.endDate").gte(hoy)
        );

        Aggregation agg = Aggregation.newAggregation(
            Aggregation.unwind("staffOrganizationAssociations"),
            Aggregation.match(activeAssoc),
            Aggregation.match(buildUnwoundPersonalTypeCriteria(personalType)),
            Aggregation.group("staffOrganizationAssociations.employmentType.term.ca_ES").count().as("cantidad"),
            Aggregation.project("cantidad").and("_id").as("tipo"),
            Aggregation.sort(Sort.Direction.DESC, "cantidad")
        );

        List<Map> raw = mongoTemplate.aggregate(agg, Persona.class, Map.class).getMappedResults();

        Pattern matcher = Pattern.compile(q == null || q.isBlank() ? ".*" : q, Pattern.CASE_INSENSITIVE);
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map row : raw) {
            Object tipoObj = row.get("tipo");
            String tipo = tipoObj == null ? "" : tipoObj.toString();
            if (!matcher.matcher(tipo).find()) {
                continue;
            }

            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("tipo", tipoObj);
            mapped.put("cantidad", row.get("cantidad"));
            out.add(mapped);
        }

        return out;
    }

    @GetMapping("/stats/age-pyramid/debug-gender")
    public List<Map<String, Object>> debugGenderExtraction(
            @RequestParam(required = false) String personalType,
            @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDate hoy = LocalDate.now();

        Query query = new Query();
        query.addCriteria(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.period.endDate").is(null),
            Criteria.where("staffOrganizationAssociations.period.endDate").gte(hoy)
        ));
        addPersonalTypeCriteria(query, personalType);
        query.limit(safeLimit);

        List<Document> docs = mongoTemplate.find(query, Document.class, "Persons");
        List<Map<String, Object>> out = new ArrayList<>();

        for (Document doc : docs) {
            String uuid = doc.getString("uuid");
            Object genderTermRaw = getByPath(doc, "gender.term");
            String extractedGender = extractGender(doc);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuid);
            row.put("genderTermRaw", genderTermRaw);
            row.put("extractedGender", extractedGender);
            row.put("detected", classifyGender(extractedGender));
            out.add(row);
        }

        return out;
    }

    private Integer extractAge(Document doc, LocalDate today) {
        String[] dobPaths = {
            "dateOfBirth",
            "person.dateOfBirth",
            "personalDetails.dateOfBirth",
            "profile.dateOfBirth",
            "birthDate"
        };

        for (String path : dobPaths) {
            Object value = getByPath(doc, path);
            LocalDate birthDate = parseDate(value);
            if (birthDate != null) {
                return today.getYear() - birthDate.getYear()
                    - ((today.getDayOfYear() < birthDate.getDayOfYear()) ? 1 : 0);
            }
        }

        return null;
    }

    private LocalDate parseDate(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        if (value instanceof Document doc) {
            Object nestedDate = doc.get("$date");
            if (nestedDate != null) {
                return parseDate(nestedDate);
            }

            Object yearObj = doc.get("year");
            if (yearObj instanceof Number yearNumber) {
                int y = yearNumber.intValue();
                if (y >= 1900 && y <= 2100) {
                    return LocalDate.of(y, 1, 1);
                }
            }
        }

        if (value instanceof Map<?, ?> map) {
            Object dateValue = map.get("$date");
            if (dateValue != null) {
                return parseDate(dateValue);
            }
            Object yearObj = map.get("year");
            if (yearObj instanceof Number yearNumber) {
                int y = yearNumber.intValue();
                if (y >= 1900 && y <= 2100) {
                    return LocalDate.of(y, 1, 1);
                }
            }
        }

        if (value instanceof String str) {
            String clean = str.trim();
            if (clean.isEmpty()) {
                return null;
            }
            try {
                return LocalDate.parse(clean.substring(0, 10));
            } catch (Exception ignored) {
                return null;
            }
        }

        return null;
    }

    private String extractGender(Document doc) {
        String[] genderPaths = {
            "gender.term",
            "gender.term.ca_ES",
            "gender.term.es_ES",
            "gender.term.en_GB",
            "gender.ca_ES",
            "gender.es_ES",
            "gender.en_GB",
            "gender",
            "sex",
            "sex.term.ca_ES",
            "sex.term.es_ES",
            "sex.term.en_GB"
        };

        for (String path : genderPaths) {
            Object value = getByPath(doc, path);
            String text = extractTextValue(value);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }

        return "";
    }

    private String extractTextValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String str) {
            return str;
        }

        if (value instanceof Document doc) {
            return extractTextValueFromMap(doc);
        }

        if (value instanceof Map<?, ?> map) {
            return extractTextValueFromMap(map);
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nested = extractTextValue(item);
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String extractTextValueFromMap(Map<?, ?> map) {
        String[] keys = {"ca_ES", "es_ES", "en_GB", "value", "text", "term", "label", "name"};
        for (String key : keys) {
            String nested = extractTextValue(map.get(key));
            if (nested != null && !nested.isBlank()) {
                return nested;
            }
        }

        Object locale = map.get("locale");
        Object value = map.get("value");
        if (locale instanceof String && value instanceof String str && !str.isBlank()) {
            return str;
        }

        return null;
    }

    private boolean isMale(String value) {
        String normalized = normalize(value);
        return normalized.contains("male")
            || normalized.contains("hombre")
            || normalized.contains("masculi")
            || normalized.contains("home")
            || normalized.equals("m");
    }

    private boolean isFemale(String value) {
        String normalized = normalize(value);
        return normalized.contains("female")
            || normalized.contains("mujer")
            || normalized.contains("femeni")
            || normalized.contains("dona")
            || normalized.equals("f");
    }

    private String classifyGender(String value) {
        if (isMale(value)) {
            return "male";
        }
        if (isFemale(value)) {
            return "female";
        }
        return "other";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase();
    }

    private Object getByPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current instanceof Document doc) {
                current = doc.get(segment);
            } else if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }

        return current;
    }

    private void addPersonalTypeCriteria(Query query, String personalType) {
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            return;
        }

        Criteria academicTermCriteria = getAcademicTermCriteria();

        Criteria activeAcademicAssociation = Criteria.where("staffOrganizationAssociations").elemMatch(
            academicTermCriteria
        );

        if ("academic".equalsIgnoreCase(personalType)) {
            query.addCriteria(activeAcademicAssociation);
            return;
        }

        if ("nonAcademic".equalsIgnoreCase(personalType)) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").not().elemMatch(academicTermCriteria));
        }
    }

    private Criteria getAcademicTermCriteria() {
        return new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.staffType.term.ca_ES").regex("acad", "i"),
            Criteria.where("staffOrganizationAssociations.staffType.term.es_ES").regex("acad", "i"),
            Criteria.where("staffOrganizationAssociations.staffType.term.en_GB").regex("academic", "i")
        );
    }

    private Criteria buildUnwoundPersonalTypeCriteria(String personalType) {
        if (personalType == null || personalType.isBlank() || "all".equalsIgnoreCase(personalType)) {
            return new Criteria();
        }

        Criteria academic = new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.staffType.term.ca_ES").regex("acad", "i"),
            Criteria.where("staffOrganizationAssociations.staffType.term.es_ES").regex("acad", "i"),
            Criteria.where("staffOrganizationAssociations.staffType.term.en_GB").regex("academic", "i")
        );

        if ("academic".equalsIgnoreCase(personalType)) {
            return academic;
        }

        if ("nonAcademic".equalsIgnoreCase(personalType)) {
            return new Criteria().norOperator(academic);
        }

        return new Criteria();
    }

    private void addDepartmentCriteria(Query query, String deptUuid) {
        if (deptUuid == null || deptUuid.isBlank()) {
            return;
        }
        query.addCriteria(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid));
    }
}