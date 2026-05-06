package com.example.demo.controller;

import com.example.demo.model.Organizacion;
import com.example.demo.model.Persona;
import com.example.demo.service.AwardService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;
import org.w3c.dom.Node;

@RestController
@RequestMapping({"/api/persons", "/persons", "/otr/api/persons"})
@CrossOrigin(origins = "*") // Permite llamadas desde tu index.html
public class PersonaController {

    private final MongoTemplate mongoTemplate;
    private final AwardService awardService;

    // Constructor para inyectar las dependencias (Soluciona el error de inicialización)
    public PersonaController(MongoTemplate mongoTemplate, AwardService awardService) {
        this.mongoTemplate = mongoTemplate;
        this.awardService = awardService;
    }

    /** DEBUG TEMPORAL: dump identifiers from Awards (optionally filter by orgUuid) */
    @GetMapping("/debug-identifiers")
    public List<Object> debugIdentifiers(
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String orgUuid) {
        List<Object> result = new ArrayList<>();
        Document filter = new Document("identifiers", new Document("$exists", true))
                .append("workflow.step", "validated")
                .append("categoria", new Document("$regex", "^Ajudes competitives"));
        if (orgUuid != null) {
            filter.append("$or", Arrays.asList(
                new Document("managingOrganization.uuid", orgUuid),
                new Document("coManagingOrganizations.uuid", orgUuid)
            ));
        }
        mongoTemplate.getDb().getCollection("Awards")
            .find(filter)
            .limit(limit)
            .forEach(doc -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("uuid", doc.getString("uuid"));
                entry.put("applicationReferenceNumber", doc.getString("applicationReferenceNumber"));
                Object titleObj = doc.get("title");
                if (titleObj instanceof Document t) entry.put("title", t.getString("ca_ES") != null ? t.getString("ca_ES") : t.getString("es_ES"));
                entry.put("identifiers", doc.get("identifiers"));
                result.add(entry);
            });
        return result;
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



    /**
     * Returns the list of persons belonging to a department, respecting the
     * filtrePersonal mode (vigent = active today, periode = active within [desde, hasta]).
     */
    @GetMapping("/by-dept")
    public List<Map<String, String>> listarPersonasByDept(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {

        if (deptUuid == null || deptUuid.isBlank()) {
            return List.of();
        }

        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        Document assocPeriodCriteria = usePeriode
                ? buildPeriodeAssocCriteria(desde, hasta)
                : buildVigentAssocCriteria();

        Document deptAssocCriteria = new Document("$and", List.of(
                new Document("organization.uuid", deptUuid),
                assocPeriodCriteria
        ));

        // Filter all associations for this department, then reduce to the latest one:
        // null endDate (active) > any date; among dated ones, take the maximum.
        Document filterAssoc = new Document("$filter", new Document()
                .append("input", "$staffOrganizationAssociations")
                .append("as", "a")
                .append("cond", new Document("$eq", List.of("$$a.organization.uuid", deptUuid))));
        Document latestAssocExpr = new Document("$reduce", new Document()
                .append("input", filterAssoc)
                .append("initialValue", (Object) null)
                .append("in", new Document("$cond", Arrays.asList(
                        // accumulated is null → take current
                        new Document("$eq", Arrays.asList("$$value", null)),
                        "$$this",
                        new Document("$cond", Arrays.asList(
                                // current has no endDate (active) → prefer it
                                new Document("$eq", Arrays.asList(
                                        new Document("$ifNull", Arrays.asList("$$this.period.endDate", null)), null)),
                                "$$this",
                                new Document("$cond", Arrays.asList(
                                        // accumulated has no endDate (active) → keep it
                                        new Document("$eq", Arrays.asList(
                                                new Document("$ifNull", Arrays.asList("$$value.period.endDate", null)), null)),
                                        "$$value",
                                        // both have endDates → take the later one
                                        new Document("$cond", List.of(
                                                new Document("$gte", List.of("$$this.period.endDate", "$$value.period.endDate")),
                                                "$$this",
                                                "$$value"
                                        ))
                                ))
                        ))
                ))));
        Document endDateExpr = new Document("$let", new Document()
                .append("vars", new Document("assoc", latestAssocExpr))
                .append("in", "$$assoc.period.endDate"));

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("staffOrganizationAssociations",
                new Document("$elemMatch", deptAssocCriteria))));
        pipeline.add(new Document("$project", new Document()
                .append("uuid", 1)
                .append("firstName", "$name.firstName")
                .append("lastName", "$name.lastName")
                .append("rawEndDate", endDateExpr)));
        pipeline.add(new Document("$sort", new Document("lastName", 1).append("firstName", 1)));

        List<Document> rows = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons").aggregate(pipeline).into(rows);

        String todayIso = LocalDate.now().toString();

        return rows.stream()
                .filter(doc -> doc.getString("uuid") != null && !doc.getString("uuid").isBlank())
                .map(doc -> {
                    String fn = doc.getString("firstName");
                    String ln = doc.getString("lastName");
                    String nombre = (ln != null && !ln.isBlank() ? ln : "")
                            + (fn != null && !fn.isBlank() ? ", " + fn : "");
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("uuid", doc.getString("uuid"));
                    m.put("nombre", nombre.isBlank() ? doc.getString("uuid") : nombre.trim());
                    Object endDateObj = doc.get("rawEndDate");
                    String endDateStr = null;
                    if (endDateObj instanceof Date d) {
                        endDateStr = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
                    } else if (endDateObj instanceof String s && !s.isBlank()) {
                        endDateStr = s.length() >= 10 ? s.substring(0, 10) : s;
                    }
                    // In vigent mode every returned person is active — never expose endDate.
                    // In periode mode, expose endDate only when it's in the past.
                    if (usePeriode && endDateStr != null && endDateStr.compareTo(todayIso) < 0) {
                        m.put("endDate", endDateStr);
                    }
                    return m;
                })
                .toList();
    }

    private static Document buildVigentAssocCriteria() {
        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();
        Date hoyDate = Date.from(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant());
        return new Document("$or", List.of(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 9)),
                        new Document("period.endDate", new Document("$gt", hoyDate))
                )),
                new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 2)),
                        new Document("period.endDate", new Document("$gt", hoyIso))
                ))
        ));
    }

    private static Document buildPeriodeAssocCriteria(Integer desde, Integer hasta) {
        if (desde == null && hasta == null) {
            return buildVigentAssocCriteria();
        }
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
    }

    @GetMapping("/departamentos")
    public List<Map<String, String>> listarDepartamentos() {
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
         * Retorna la llista d'àmbits distints vinculats a organitzacions
         * que són departaments vigents.
         */
    @GetMapping("/ambits")
    public List<String> listarAmbits() {
        Query deptQuery = new Query();
        deptQuery.addCriteria(Criteria.where("type.term.ca_ES").is("Departament"));
        deptQuery.addCriteria(Criteria.where("lifecycle.endDate").is(null));

        List<String> deptUuids = mongoTemplate.findDistinct(
            deptQuery,
            "uuid",
            "Organizations",
            String.class
        );

        if (deptUuids == null || deptUuids.isEmpty()) {
            return List.of();
        }

        return mongoTemplate.getDb()
                .getCollection("v_orga_ambit")
            .distinct(
                "ambit",
                new org.bson.Document("uuid", new org.bson.Document("$in", deptUuids)),
                String.class
            )
                .into(new java.util.ArrayList<>())
                .stream()
                .filter(a -> a != null && !a.isBlank())
                .sorted()
                .toList();
    }

    /**
     * Retorna els departaments (uuid + nombre) pertanyents a un àmbit concret,
     * creuant kraken.v_orga_ambit amb Organizations per obtenir el nom oficial.
     */
    @GetMapping("/departamentos-by-ambit")
    public List<Map<String, String>> listarDepartamentosPorAmbit(@RequestParam String ambit) {
        List<org.bson.Document> orgaAmbits = mongoTemplate.getDb()
                .getCollection("v_orga_ambit")
                .find(new org.bson.Document("ambit", ambit))
                .into(new java.util.ArrayList<>());

        List<String> uuids = orgaAmbits.stream()
                .map(d -> d.getString("uuid"))
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();

        if (uuids.isEmpty()) return List.of();

        Query query = new Query();
        query.addCriteria(Criteria.where("uuid").in(uuids));
        query.addCriteria(Criteria.where("type.term.ca_ES").is("Departament"));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        query.with(Sort.by(Sort.Direction.ASC, "name.ca_ES"));

        return mongoTemplate.find(query, Organizacion.class, "Organizations").stream()
                .map(org -> Map.of("uuid", org.getUuid(), "nombre", org.getNombre()))
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
                .into(new ArrayList<>());

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
            return Collections.emptyList();
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
        List<String> ca = mongoTemplate.getDb().getCollection("Organizations").distinct("type.term.ca_ES", String.class).into(new ArrayList<>());
        List<String> es = mongoTemplate.getDb().getCollection("Organizations").distinct("type.term.es_ES", String.class).into(new ArrayList<>());
        List<String> en = mongoTemplate.getDb().getCollection("Organizations").distinct("type.term.en_GB", String.class).into(new ArrayList<>());
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

    /**
     * Shared pipeline for all staffOrganizationAssociations stats endpoints.
     * Uses $unwind + string-safe date comparison to correctly handle both
     * Date objects and ISO string endDates in MongoDB.
     */
    private Map<String, Long> countByEmploymentTypeRegex(
            String regexCa, String regexEs, String regexEn,
            String personalType, String deptUuid) {
        String hoyStr = LocalDate.now().toString();

        List<AggregationOperation> ops = new ArrayList<>();

        if (deptUuid != null && !deptUuid.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }
        if ("academic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())));
        }

        ops.add(Aggregation.unwind("staffOrganizationAssociations"));

        ops.add(Aggregation.match(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.employmentType.term.ca_ES").regex(regexCa, "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.es_ES").regex(regexEs, "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.en_GB").regex(regexEn, "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.text.value").regex(regexCa + "|" + regexEs, "i")
        )));

        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("staffOrganizationAssociations.period.endDate", (Object) null),
            new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
        ))));

        ops.add(Aggregation.group("_id"));
        ops.add(Aggregation.count().as("total"));

        List<Document> result = mongoTemplate.aggregate(
            Aggregation.newAggregation(ops), Persona.class, Document.class).getMappedResults();
        long total = result.isEmpty() ? 0L : ((Number) result.get(0).get("total")).longValue();
        return Map.of("total", total);
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

        Query query = new Query();
        if (deptUuid != null && !deptUuid.isBlank()) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(
                new Criteria().andOperator(
                    Criteria.where("organization.uuid").is(deptUuid),
                    activeAssociationCriteria
                )
            ));
        } else {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(activeAssociationCriteria));
        }

        addPersonalTypeCriteria(query, personalType);

        query.fields()
                .include("dateOfBirth")
                .include("person.dateOfBirth")
                .include("personalDetails.dateOfBirth")
                .include("profile.dateOfBirth")
                .include("birthDate")
                .include("gender")
                .include("sex");

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

    @GetMapping("/stats/sex-distribution")
    public Map<String, Integer> getSexDistribution(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
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

        Query query = new Query();
        if (deptUuid != null && !deptUuid.isBlank()) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(
                new Criteria().andOperator(
                    Criteria.where("organization.uuid").is(deptUuid),
                    activeAssociationCriteria
                )
            ));
        } else {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(activeAssociationCriteria));
        }

        addPersonalTypeCriteria(query, personalType);

        query.fields()
                .include("gender")
                .include("sex");

        List<Document> docs = mongoTemplate.find(query, Document.class, "Persons");

        int hombres = 0, mujeres = 0, otros = 0;
        for (Document doc : docs) {
            String genero = extractGender(doc);
            if (isMale(genero)) {
                hombres++;
            } else if (isFemale(genero)) {
                mujeres++;
            } else {
                otros++;
            }
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("hombres", hombres);
        result.put("mujeres", mujeres);
        result.put("otros", otros);
        return result;
    }

    @GetMapping("/stats/nationality")
    public List<Map<String, Object>> getNationalityStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {

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

        Query query = new Query();
        if (deptUuid != null && !deptUuid.isBlank()) {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(
                new Criteria().andOperator(
                    Criteria.where("organization.uuid").is(deptUuid),
                    activeAssociationCriteria
                )
            ));
        } else {
            query.addCriteria(Criteria.where("staffOrganizationAssociations").elemMatch(activeAssociationCriteria));
        }

        addPersonalTypeCriteria(query, personalType);

        List<Document> docs = mongoTemplate.find(query, Document.class, "Persons");

        Map<String, Long> countByCountry = new LinkedHashMap<>();
        for (Document doc : docs) {
            String code = extractNationalityCode(doc);
            if (code != null && !code.isBlank()) {
                countByCountry.merge(code.toUpperCase(), 1L, Long::sum);
            }
        }

        return countByCountry.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("country", e.getKey());
                row.put("count", e.getValue());
                return row;
            })
            .collect(Collectors.toList());
    }

    private String extractNationalityCode(Document doc) {
        // 1. nationality.uri  ->  last path segment (ISO-2)  e.g. /dk/atira/pure/core/countries/es
        String code = codeFromUri(getByPath(doc, "nationality.uri"));
        if (code != null) return code;

        // 2. nationalityType.uri
        code = codeFromUri(getByPath(doc, "nationalityType.uri"));
        if (code != null) return code;

        // 3. nationalityTypes (array) -> first element uri
        Object nt = doc.get("nationalityTypes");
        if (nt instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Document d) {
                code = codeFromUri(d.get("uri"));
                if (code != null) return code;
            }
        }

        // 4. Direct 'nationality' string field
        Object nat = doc.get("nationality");
        if (nat instanceof String s && !s.isBlank()) {
            String v = s.trim();
            return v.length() == 2 ? v.toUpperCase() : null;
        }

        return null;
    }

    private String codeFromUri(Object uriObj) {
        if (!(uriObj instanceof String uri)) return null;
        String cleaned = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int idx = cleaned.lastIndexOf('/');
        if (idx < 0) return null;
        String segment = cleaned.substring(idx + 1);
        return (segment.length() == 2) ? segment.toUpperCase() : null;
    }

    @GetMapping("/stats/nationality/debug")
    public List<Map<String, Object>> debugNationalityFields(
            @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Query query = new Query().limit(safeLimit);
        List<Document> docs = mongoTemplate.find(query, Document.class, "Persons");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", doc.getString("uuid"));
            row.put("nationalityType", doc.get("nationalityType"));
            row.put("nationalityTypes", doc.get("nationalityTypes"));
            row.put("nationality", doc.get("nationality"));
            row.put("extractedCode", extractNationalityCode(doc));
            out.add(row);
        }
        return out;
    }

    @GetMapping("/stats/contract-type")
    public Map<String, Long> getContractTypeStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {

        List<AggregationOperation> ops = new ArrayList<>();

        ops.add(Aggregation.unwind("staffOrganizationAssociations"));

        if (deptUuid != null && !deptUuid.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }
        if (personalType != null && !personalType.isBlank() && !personalType.equals("all")) {
            if ("academic".equalsIgnoreCase(personalType)) {
                ops.add(Aggregation.match(new Criteria().orOperator(
                    Criteria.where("staffOrganizationAssociations.staffType.term.ca_ES").regex("^acadèm", "i"),
                    Criteria.where("staffOrganizationAssociations.staffType.term.es_ES").regex("^académ", "i"),
                    Criteria.where("staffOrganizationAssociations.staffType.term.en_GB").regex("^academic", "i")
                )));
            } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
                ops.add(Aggregation.match(new Criteria().orOperator(
                    Criteria.where("staffOrganizationAssociations.staffType.term.ca_ES").regex("^no acadèm", "i"),
                    Criteria.where("staffOrganizationAssociations.staffType.term.es_ES").regex("^no académ", "i"),
                    Criteria.where("staffOrganizationAssociations.staffType.term.en_GB").regex("^non-academic", "i")
                )));
            }
        }

        // Keep only active associations using $toDate + $$NOW
        ops.add(ctx -> new Document("$addFields", new Document("_endDateReal",
            new Document("$cond", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$staffOrganizationAssociations.period.endDate", false)),
                new Document("$toDate", "$staffOrganizationAssociations.period.endDate"),
                (Object) null
            ))
        )));
        ops.add(ctx -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("_endDateReal", null),
            new Document("$expr", new Document("$gt", Arrays.asList("$_endDateReal", "$$NOW")))
        ))));

        // Classify: permanent = no endDate, noPermament = has future endDate
        ops.add(ctx -> new Document("$addFields", new Document("_tipusContracte",
            new Document("$cond", Arrays.asList(
                new Document("$eq", Arrays.asList("$_endDateReal", null)),
                "permanent",
                "noPermament"
            ))
        )));

        // One row per person: if ANY active association is permanent, person = permanent
        // ("permanent" > "noPermament" alphabetically so $max picks permanent when present)
        ops.add(ctx -> new Document("$group", new Document("_id", "$uuid")
            .append("_tipusContracte", new Document("$max", "$_tipusContracte"))));

        // Count per type
        ops.add(ctx -> new Document("$group", new Document("_id", "$_tipusContracte")
            .append("total", new Document("$sum", 1))));

        List<Document> result = mongoTemplate.aggregate(
            Aggregation.newAggregation(ops), Persona.class, Document.class).getMappedResults();

        long permanent = 0, noPermament = 0;
        for (Document d : result) {
            String tipus = (String) d.get("_id");
            long count = ((Number) d.getOrDefault("total", 0)).longValue();
            if ("permanent".equals(tipus)) permanent = count;
            else if ("noPermament".equals(tipus)) noPermament = count;
        }
        return Map.of("permanent", permanent, "noPermament", noPermament);
    }

    @GetMapping("/stats/personal-academic")
    public Map<String, Long> getPersonalAcademicStats(
            @RequestParam(required = false) String deptUuid) {

        List<AggregationOperation> ops = new ArrayList<>();

        ops.add(Aggregation.unwind("staffOrganizationAssociations"));

        if (deptUuid != null && !deptUuid.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }

        ops.add(Aggregation.match(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.staffType.term.ca_ES").regex("^acadèm", "i"),
            Criteria.where("staffOrganizationAssociations.staffType.term.es_ES").regex("^académ", "i"),
            Criteria.where("staffOrganizationAssociations.staffType.term.en_GB").regex("^academic", "i")
        )));

        // Normalitza endDate a Date real (com $toDate) i filtra actius amb $$NOW
        ops.add(ctx -> new Document("$addFields", new Document("_endDateReal",
            new Document("$cond", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$staffOrganizationAssociations.period.endDate", false)),
                new Document("$toDate", "$staffOrganizationAssociations.period.endDate"),
                (Object) null
            ))
        )));

        ops.add(ctx -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("_endDateReal", null),
            new Document("$expr", new Document("$gt", Arrays.asList("$_endDateReal", "$$NOW")))
        ))));

        ops.add(Aggregation.group("_id"));
        ops.add(Aggregation.count().as("total"));

        List<Document> result = mongoTemplate.aggregate(
            Aggregation.newAggregation(ops), Persona.class, Document.class).getMappedResults();
        long total = result.isEmpty() ? 0L : ((Number) result.get(0).get("total")).longValue();
        return Map.of("total", total);
    }

    @GetMapping("/stats/catedraticos")
    public Map<String, Long> getCatedraticosStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("catedr", "catedr", "chair|full professor", personalType, deptUuid);
    }

    @GetMapping("/stats/titulares")
    public Map<String, Long> getTitularesStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("titular", "titular", "tenured|tenure", personalType, deptUuid);
    }

    @GetMapping("/stats/agregados")
    public Map<String, Long> getAgregadosStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("agregat", "agregado", "associate professor", personalType, deptUuid);
    }

    @GetMapping("/stats/lectores")
    public Map<String, Long> getLectoresStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        LocalDate hoy = LocalDate.now();

        List<AggregationOperation> ops = new ArrayList<>();

        if (deptUuid != null && !deptUuid.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }
        if ("academic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())));
        }

        ops.add(Aggregation.unwind("staffOrganizationAssociations"));

        ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.employmentType.term.ca_ES")
            .is("Professor/a lector/a ajudant doctor/a")));

        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("staffOrganizationAssociations.period.endDate", (Object) null),
            new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
        ))));

        ops.add(Aggregation.group("_id"));
        ops.add(Aggregation.count().as("total"));

        List<Document> result = mongoTemplate.aggregate(Aggregation.newAggregation(ops), Persona.class, Document.class).getMappedResults();
        long total = result.isEmpty() ? 0L : ((Number) result.get(0).get("total")).longValue();
        return Map.of("total", total);
    }

    @GetMapping("/stats/asociados")
    public Map<String, Long> getAsociadosStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("associat", "asociad", "adjunct", personalType, deptUuid);
    }

    @GetMapping("/stats/substituts")
    public Map<String, Long> getSubstitutsStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("substitu", "substitu", "substitu", personalType, deptUuid);
    }

    @GetMapping("/stats/predoctorals")
    public Map<String, Long> getPredoctoralsStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa", "predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa", "pre-doctoral|research training|FPI|FI-JOAN|FI-SDUR|novel research", personalType, deptUuid);
    }

    @GetMapping("/stats/postdoctorals")
    public Map<String, Long> getPostdoctoralsStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        return countByEmploymentTypeRegex("ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci", "ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distinguido|director de investig", "regular researcher|post-doctoral|Cajal|Beatriu|Cierva|distinguished research|research director", personalType, deptUuid);
    }

    @GetMapping("/stats/icrea")
    public Map<String, Long> getIcreaStats(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        String hoyStr = LocalDate.now().toString();

        List<AggregationOperation> ops = new ArrayList<>();

        if (deptUuid != null && !deptUuid.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }
        if ("academic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())));
        }

        ops.add(Aggregation.unwind("visitingScholarOrganizationAssociations"));

        ops.add(Aggregation.match(new Criteria().orOperator(
            Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.ca_ES").regex("icrea", "i"),
            Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.es_ES").regex("icrea", "i"),
            Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.en_GB").regex("icrea", "i")
        )));

        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("visitingScholarOrganizationAssociations.period.endDate", (Object) null),
            new Document("visitingScholarOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("visitingScholarOrganizationAssociations.period", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$visitingScholarOrganizationAssociations.period.endDate", "$$NOW")))
        ))));

        ops.add(Aggregation.group("_id"));
        ops.add(Aggregation.count().as("total"));

        List<Document> result = mongoTemplate.aggregate(
            Aggregation.newAggregation(ops), Persona.class, Document.class).getMappedResults();
        long total = result.isEmpty() ? 0L : ((Number) result.get(0).get("total")).longValue();
        return Map.of("total", total);
    }

    @GetMapping("/stats/lectores/candidates")
    public List<Map<String, Object>> getLectoresCandidates(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false, defaultValue = "lector|lectur|reader|associat|asociad|associate|ajudant|ayudant") String q) {
        LocalDate hoy = LocalDate.now();

        Aggregation agg = Aggregation.newAggregation(
            Aggregation.unwind("staffOrganizationAssociations"),
            context -> new Document("$match", new Document("$or", Arrays.asList(
                new Document("staffOrganizationAssociations.period.endDate", (Object) null),
                new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
                new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
            ))),
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

    @GetMapping("/stats/employment-types-summary")
    public Map<String, List<Map<String, Object>>> getEmploymentTypesSummary(
            @RequestParam(required = false) String personalType,
            @RequestParam(required = false) String deptUuid) {
        String hoyStr = LocalDate.now().toString();

        // Active terms with unique-person count per term
        List<AggregationOperation> ops = new ArrayList<>();

        if (deptUuid != null && !deptUuid.isBlank()) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }
        if ("academic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            ops.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())));
        }

        ops.add(Aggregation.unwind("staffOrganizationAssociations"));
        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("staffOrganizationAssociations.period.endDate", (Object) null),
            new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
        ))));
        // Deduplicate: count each person once per term (prefer ca_ES, fallback es_ES, then en_GB)
        ops.add(ctx -> new Document("$group", new Document("_id",
            new Document("person", "$_id")
                .append("term", new Document("$ifNull", Arrays.asList(
                    "$staffOrganizationAssociations.employmentType.term.ca_ES",
                    new Document("$ifNull", Arrays.asList(
                        "$staffOrganizationAssociations.employmentType.term.es_ES",
                        "$staffOrganizationAssociations.employmentType.term.en_GB"
                    ))
                ))))));
        ops.add(ctx -> new Document("$group",
            new Document("_id", "$_id.term").append("count", new Document("$sum", 1))));
        ops.add(Aggregation.sort(Sort.Direction.ASC, "_id"));

        List<Document> allRaw = mongoTemplate
            .aggregate(Aggregation.newAggregation(ops), Persona.class, Document.class)
            .getMappedResults()
            .stream()
            .filter(d -> d.get("_id") != null && !((String) d.get("_id")).isBlank())
            .toList();

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("catedraticos", filterTermsWithCount(allRaw, "catedr"));
        result.put("titulares",    filterTermsWithCount(allRaw, "titular"));
        result.put("agregados",    filterTermsWithCount(allRaw, "agregat|agregad"));
        result.put("lectores",     filterTermsWithCount(allRaw, "lector|lectura|reader"));
        result.put("asociados",    filterTermsWithCount(allRaw, "associat|asociad|adjunct"));
        result.put("substituts",   filterTermsWithCount(allRaw, "substitu"));
        result.put("predoctorals", filterTermsWithCount(allRaw, "predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa"));
        result.put("postdoctorals",filterTermsWithCount(allRaw, "ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit"));

        // ICREA: use same filtered pipeline as getIcreaStats()
        List<AggregationOperation> icreaOps = new ArrayList<>();
        if (deptUuid != null && !deptUuid.isBlank()) {
            icreaOps.add(Aggregation.match(Criteria.where("staffOrganizationAssociations.organization.uuid").is(deptUuid)));
        }
        if ("academic".equalsIgnoreCase(personalType)) {
            icreaOps.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").elemMatch(getAcademicTermCriteria())));
        } else if ("nonAcademic".equalsIgnoreCase(personalType)) {
            icreaOps.add(Aggregation.match(Criteria.where("staffOrganizationAssociations").not().elemMatch(getAcademicTermCriteria())));
        }
        icreaOps.add(Aggregation.unwind("visitingScholarOrganizationAssociations"));
        icreaOps.add(Aggregation.match(new Criteria().orOperator(
            Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.ca_ES").regex("icrea", "i"),
            Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.es_ES").regex("icrea", "i"),
            Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.en_GB").regex("icrea", "i")
        )));
        icreaOps.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("visitingScholarOrganizationAssociations.period.endDate", (Object) null),
            new Document("visitingScholarOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("visitingScholarOrganizationAssociations.period", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$visitingScholarOrganizationAssociations.period.endDate", "$$NOW")))
        ))));
        icreaOps.add(ctx -> new Document("$group",
            new Document("_id", new Document("person", "$_id")
                .append("term", new Document("$ifNull", Arrays.asList(
                    "$visitingScholarOrganizationAssociations.jobTitle.term.ca_ES",
                    new Document("$ifNull", Arrays.asList(
                        "$visitingScholarOrganizationAssociations.jobTitle.term.es_ES",
                        "$visitingScholarOrganizationAssociations.jobTitle.term.en_GB"
                    ))
                ))))));
        icreaOps.add(ctx -> new Document("$group",
            new Document("_id", "$_id.term").append("count", new Document("$sum", 1))));
        icreaOps.add(Aggregation.sort(Sort.Direction.DESC, "count"));

        List<Map<String, Object>> icreaTerms = mongoTemplate
            .aggregate(Aggregation.newAggregation(icreaOps), Persona.class, Document.class)
            .getMappedResults()
            .stream()
            .filter(d -> d.get("_id") != null && !((String) d.get("_id")).isBlank())
            .map(d -> {
                Map<String, Object> m = new HashMap<>();
                m.put("term", (String) d.get("_id"));
                m.put("count", ((Number) d.getOrDefault("count", 0)).longValue());
                return m;
            })
            .toList();
        result.put("icrea", icreaTerms);

        return result;
    }

    private List<Map<String, Object>> filterTermsWithCount(List<Document> docs, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return docs.stream()
            .filter(d -> {
                String term = (String) d.get("_id");
                return term != null && p.matcher(term).find();
            })
            .map(d -> {
                Map<String, Object> row = new HashMap<>();
                row.put("term", (String) d.get("_id"));
                row.put("count", ((Number) d.getOrDefault("count", 0)).longValue());
                return row;
            })
            .sorted(Comparator.comparingLong(m -> -(Long) m.get("count")))
            .toList();
    }

    @GetMapping("/stats/predoctorals/debug-terms")
    public List<Map<String, Object>> debugPredoctoralsTerms() {
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.unwind("staffOrganizationAssociations"));
        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("staffOrganizationAssociations.period.endDate", (Object) null),
            new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
        ))));
        ops.add(ctx -> new Document("$group", new Document("_id", new Document()
            .append("ca_ES", "$staffOrganizationAssociations.employmentType.term.ca_ES")
            .append("es_ES", "$staffOrganizationAssociations.employmentType.term.es_ES")
            .append("en_GB", "$staffOrganizationAssociations.employmentType.term.en_GB"))
            .append("count", new Document("$sum", 1))));
        ops.add(Aggregation.sort(Sort.Direction.DESC, "count"));

        return mongoTemplate.aggregate(Aggregation.newAggregation(ops), Persona.class, Document.class)
            .getMappedResults()
            .stream()
            .map(d -> {
                Document id = (Document) d.get("_id");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ca_ES", id.get("ca_ES"));
                row.put("es_ES", id.get("es_ES"));
                row.put("en_GB", id.get("en_GB"));
                row.put("count", ((Number) d.getOrDefault("count", 0)).longValue());
                return row;
            })
            .toList();
    }

    @GetMapping("/stats/asociados/debug-terms")
    public List<Map<String, Object>> debugAsociadosTerms() {
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.unwind("staffOrganizationAssociations"));
        ops.add(Aggregation.match(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.employmentType.term.ca_ES").regex("associat|asociad|associate", "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.es_ES").regex("associat|asociad|associate", "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.en_GB").regex("associat|asociad|associate", "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.text.value").regex("associat|asociad|associate", "i")
        )));
        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("staffOrganizationAssociations.period.endDate", (Object) null),
            new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
        ))));
        ops.add(ctx -> new Document("$group", new Document("_id", new Document()
            .append("ca_ES", "$staffOrganizationAssociations.employmentType.term.ca_ES")
            .append("es_ES", "$staffOrganizationAssociations.employmentType.term.es_ES")
            .append("en_GB", "$staffOrganizationAssociations.employmentType.term.en_GB"))
            .append("count", new Document("$sum", 1))));
        ops.add(Aggregation.sort(Sort.Direction.DESC, "count"));

        return mongoTemplate.aggregate(Aggregation.newAggregation(ops), Persona.class, Document.class)
            .getMappedResults()
            .stream()
            .map(d -> {
                Document id = (Document) d.get("_id");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ca_ES", id.get("ca_ES"));
                row.put("es_ES", id.get("es_ES"));
                row.put("en_GB", id.get("en_GB"));
                row.put("count", ((Number) d.getOrDefault("count", 0)).longValue());
                return row;
            })
            .toList();
    }

    @GetMapping("/stats/personal-academic/debug-types")
    public List<Map<String, Object>> debugAcademicStaffTypes() {
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.unwind("staffOrganizationAssociations"));
        ops.add(ctx -> new Document("$addFields", new Document("_endDateReal",
            new Document("$cond", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$staffOrganizationAssociations.period.endDate", false)),
                new Document("$toDate", "$staffOrganizationAssociations.period.endDate"),
                (Object) null
            ))
        )));
        ops.add(ctx -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("_endDateReal", null),
            new Document("$expr", new Document("$gt", Arrays.asList("$_endDateReal", "$$NOW")))
        ))));
        ops.add(ctx -> new Document("$group", new Document("_id", new Document()
            .append("ca_ES", "$staffOrganizationAssociations.staffType.term.ca_ES")
            .append("es_ES", "$staffOrganizationAssociations.staffType.term.es_ES")
            .append("en_GB", "$staffOrganizationAssociations.staffType.term.en_GB"))
            .append("count", new Document("$sum", 1))));
        ops.add(Aggregation.sort(Sort.Direction.DESC, "count"));

        return mongoTemplate.aggregate(Aggregation.newAggregation(ops), Persona.class, Document.class)
            .getMappedResults()
            .stream()
            .map(d -> {
                Document id = (Document) d.get("_id");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ca_ES", id.get("ca_ES"));
                row.put("es_ES", id.get("es_ES"));
                row.put("en_GB", id.get("en_GB"));
                row.put("count", ((Number) d.getOrDefault("count", 0)).longValue());
                return row;
            })
            .toList();
    }

    @GetMapping("/stats/investigadors/debug-terms")
    public List<Map<String, Object>> debugInvestigadorsTerms() {
        String hoyStr = LocalDate.now().toString();
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.unwind("staffOrganizationAssociations"));
        ops.add(Aggregation.match(new Criteria().orOperator(
            Criteria.where("staffOrganizationAssociations.employmentType.term.ca_ES").regex("investig", "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.es_ES").regex("investig", "i"),
            Criteria.where("staffOrganizationAssociations.employmentType.term.en_GB").regex("investig|research", "i")
        )));
        ops.add(context -> new Document("$match", new Document("$or", Arrays.asList(
            new Document("staffOrganizationAssociations.period.endDate", (Object) null),
            new Document("staffOrganizationAssociations.period.endDate", new Document("$exists", false)),
            new Document("$expr", new Document("$gt", Arrays.asList("$staffOrganizationAssociations.period.endDate", "$$NOW")))
        ))));
        ops.add(ctx -> new Document("$group", new Document("_id", new Document()
            .append("ca_ES", "$staffOrganizationAssociations.employmentType.term.ca_ES")
            .append("es_ES", "$staffOrganizationAssociations.employmentType.term.es_ES")
            .append("en_GB", "$staffOrganizationAssociations.employmentType.term.en_GB"))
            .append("count", new Document("$sum", 1))));
        ops.add(Aggregation.sort(Sort.Direction.DESC, "count"));

        return mongoTemplate.aggregate(Aggregation.newAggregation(ops), Persona.class, Document.class)
            .getMappedResults()
            .stream()
            .map(d -> {
                Document id = (Document) d.get("_id");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ca_ES", id.get("ca_ES"));
                row.put("es_ES", id.get("es_ES"));
                row.put("en_GB", id.get("en_GB"));
                row.put("count", ((Number) d.getOrDefault("count", 0)).longValue());
                return row;
            })
            .toList();
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
        return Normalizer.normalize(value, Normalizer.Form.NFD)
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

    // -----------------------------------------------------------------------
    // Informe Word: genera un .docx con el personal adscrit a un centre en
    // un rang de dates determinat.
    // -----------------------------------------------------------------------
    @GetMapping("/informe-word")
    public void generarInformeWord(
            @RequestParam String orgUuid,
            @RequestParam(required = false, defaultValue = "2000-01-01") String startDate,
            @RequestParam(required = false, defaultValue = "2050-12-31") String endDate,
            HttpServletResponse response) throws Exception {

        // 1. Fetch organization name
        Query orgQuery = new Query(Criteria.where("uuid").is(orgUuid));
        List<Organizacion> orgs = mongoTemplate.find(orgQuery, Organizacion.class, "Organizations");
        String orgNombre = orgs.isEmpty() ? orgUuid : orgs.get(0).getNombre();

        // Parse date strings → java.util.Date (Awards store actualPeriod dates as BSON Date, not String)
        Date startDateD = Date.from(LocalDate.parse(startDate).atStartOfDay(ZoneId.of("UTC")).toInstant());
        Date endDateD   = Date.from(LocalDate.parse(endDate).atStartOfDay(ZoneId.of("UTC")).toInstant());

        // 2. Aggregation: one row per person — keeps only the latest affiliation
        //    within the requested period (sorted by startDate desc → $first = latest).
        List<Document> pipeline = new ArrayList<>();

        // Unwind all associations
        pipeline.add(new Document("$unwind", "$staffOrganizationAssociations"));

        // Keep only associations for this org that overlap [startDate, endDate]
        Document matchDoc = new Document("$and", List.of(
            new Document("staffOrganizationAssociations.organization.uuid", orgUuid),
            new Document("staffOrganizationAssociations.period.startDate", new Document("$lte", endDate)),
            new Document("$or", List.of(
                new Document("staffOrganizationAssociations.period.endDate", null),
                new Document("staffOrganizationAssociations.period.endDate", new Document("$gte", startDate))
            ))
        ));
        pipeline.add(new Document("$match", matchDoc));

        // Sort by startDate descending so that $first picks the latest
        pipeline.add(new Document("$sort", new Document("staffOrganizationAssociations.period.startDate", -1)));

        // Group by person: take the first (= latest) association
        Document group = new Document();
        group.put("_id", "$uuid");
        group.put("nombre", new Document("$first", new Document("$concat",
                List.of("$name.lastName", ", ", "$name.firstName"))));
        group.put("ultimaAsoc", new Document("$first", "$staffOrganizationAssociations"));
        pipeline.add(new Document("$group", group));

        Document project = new Document();
        project.put("_id", 0);
        project.put("nombre", 1);
        project.put("empleo", "$ultimaAsoc.employmentType.term.ca_ES");
        project.put("inicio", "$ultimaAsoc.period.startDate");
        project.put("fi", "$ultimaAsoc.period.endDate");
        pipeline.add(new Document("$project", project));

        pipeline.add(new Document("$sort", new Document("nombre", 1)));

        List<Document> rows = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons").aggregate(pipeline)
                .forEach(rows::add);

        // 3. Build Word document from template
        InputStream tplIs = getClass().getClassLoader().getResourceAsStream("informe_dept_template.docx");
        try (XWPFDocument doc = new XWPFDocument(tplIs); OutputStream out = response.getOutputStream()) {

            // Clear template body content (preserves sectPr with margins, header, footer refs)
            {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody b = doc.getDocument().getBody();
                for (int i = b.sizeOfPArray() - 1; i >= 0; i--) b.removeP(i);
                for (int i = b.sizeOfTblArray() - 1; i >= 0; i--) b.removeTbl(i);
            }

            // Fill header: add org name to the nom_CER bookmark paragraph (right green cell)
            for (XWPFHeader hdr : doc.getHeaderList()) {
                for (XWPFTable hdrTbl : hdr.getTables()) {
                    for (XWPFTableRow hdrRow : hdrTbl.getRows()) {
                        for (XWPFTableCell hdrCell : hdrRow.getTableCells()) {
                            for (XWPFParagraph hp : hdrCell.getParagraphs()) {
                                if (hp.getCTP().xmlText().contains("nom_CER")) {
                                    XWPFRun hr = hp.createRun();
                                    hr.setBold(true);
                                    hr.setColor("FFFFFF");
                                    hr.setFontFamily("Calibri");
                                    hr.setFontSize(11);
                                    hr.setText(orgNombre);
                                }
                            }
                        }
                    }
                }
            }

            // Intro lines
            XWPFParagraph datePara = doc.createParagraph();
            XWPFRun dateRun = datePara.createRun();
            dateRun.setText("Data d'extracció: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            XWPFParagraph periodPara = doc.createParagraph();
            XWPFRun periodRun = periodPara.createRun();
            periodRun.setText("Període: " + startDate + " — " + endDate);

            doc.createParagraph();

            // Section 1 heading (Ttol1: blue bg #4F81BD, white bold caps Calibri 12pt)
            XWPFParagraph sec1Title = doc.createParagraph();
            sec1Title.setStyle("Ttol1");
            XWPFRun sec1Run = sec1Title.createRun();
            sec1Run.setText("Personal del centre");

            doc.createParagraph();

            // Table: 1 header row + 1 row per result
            String[] headers = {"Nom", "Tipus d'ocupació", "Inici vinculació", "Fi vinculació"};
            int totalRows = rows.size() + 1;
            XWPFTable table = doc.createTable(totalRows, headers.length);
            table.setWidth("100%");

            // Header row – blue bg (#4F81BD), white bold text
            XWPFTableRow headerRow = table.getRow(0);
            for (int i = 0; i < headers.length; i++) {
                XWPFTableCell cell = headerRow.getCell(i);
                cell.setText("");
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr =
                        cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd =
                        tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
                shd.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
                shd.setColor("auto");
                shd.setFill("4F81BD");
                XWPFParagraph cellPara = cell.getParagraphArray(0);
                XWPFRun cellRun = cellPara.createRun();
                cellRun.setBold(true);
                cellRun.setFontSize(10);
                cellRun.setColor("FFFFFF");
                cellRun.setText(headers[i]);
            }

            // Data rows
            for (int ri = 0; ri < rows.size(); ri++) {
                Document row = rows.get(ri);
                XWPFTableRow dataRow = table.getRow(ri + 1);
                String[] values = {
                    getStringVal(row, "nombre"),
                    getStringVal(row, "empleo"),
                    formatDateDMY(getStringVal(row, "inicio")),
                    formatDateDMY(getStringVal(row, "fi"))
                };
                for (int ci = 0; ci < values.length; ci++) {
                    XWPFTableCell cell = dataRow.getCell(ci);
                    cell.setText("");
                    XWPFParagraph cellPara = cell.getParagraphArray(0);
                    XWPFRun cellRun = cellPara.createRun();
                    cellRun.setFontSize(9);
                    cellRun.setText(values[ci]);
                }
            }

            // Footer: total count
            doc.createParagraph();
            XWPFParagraph footer = doc.createParagraph();
            XWPFRun footerRun = footer.createRun();
            footerRun.setFontSize(9);
            footerRun.setItalic(true);
            footerRun.setText("Total registres: " + rows.size());

            // ---- Section 2: Awards (competitive) managed or co-managed by the centre ----
            Document awardFilter = new Document()
                    .append("workflow.step", "validated")
                    .append("categoria", new Document("$regex", "^Ajudes competitives"))
                    .append("actualPeriod.startDate", new Document("$lte", endDateD))
                    .append("$and", Arrays.asList(
                            new Document("$or", Arrays.asList(
                                    new Document("managingOrganization.uuid", orgUuid),
                                    new Document("coManagingOrganizations.uuid", orgUuid)
                            )),
                            new Document("$or", Arrays.asList(
                                    new Document("actualPeriod.endDate", null),
                                    new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                            ))
                    ));
            List<Document> awardsRaw = new ArrayList<>();
            mongoTemplate.getDb().getCollection("Awards")
                    .find(awardFilter)
                    .sort(new Document("actualPeriod.startDate", -1))
                    .into(awardsRaw);

            // Batch-fetch funder org names
            Set<String> funderUuids = new HashSet<>();
            for (Document aw : awardsRaw) {
                @SuppressWarnings("unchecked")
                List<Document> awFundings = (List<Document>) aw.get("fundings");
                if (awFundings != null) {
                    for (Document funding : awFundings) {
                        Document funder = (Document) funding.get("funder");
                        if (funder != null) {
                            String fUuid = funder.getString("uuid");
                            if (fUuid != null) funderUuids.add(fUuid);
                        }
                    }
                }
            }
            Map<String, String> funderNames = new HashMap<>();
            if (!funderUuids.isEmpty()) {
                mongoTemplate.getDb().getCollection("ExternalOrganizations")
                        .find(new Document("uuid", new Document("$in", new ArrayList<>(funderUuids))))
                        .forEach(org -> {
                            String orgId = org.getString("uuid");
                            Document nameDoc = (Document) org.get("name");
                            String name = "";
                            if (nameDoc != null) {
                                name = nameDoc.containsKey("ca_ES") ? nameDoc.getString("ca_ES")
                                        : nameDoc.containsKey("es_ES") ? nameDoc.getString("es_ES")
                                        : nameDoc.containsKey("en_GB") ? nameDoc.getString("en_GB") : "";
                            }
                            funderNames.put(orgId, name);
                        });
            }

            doc.createParagraph();

            XWPFParagraph sec2Title = doc.createParagraph();
            sec2Title.setStyle("Ttol1");
            XWPFRun sec2Run = sec2Title.createRun();
            sec2Run.setText("Ajuts de recerca");

            doc.createParagraph();

            int awardIdx = 1;
            for (Document aw : awardsRaw) {
                String awTitle = getNestedStringVal(aw, "title", "ca_ES");
                if (awTitle.isEmpty()) awTitle = getNestedStringVal(aw, "title", "es_ES");
                if (awTitle.isEmpty()) awTitle = getNestedStringVal(aw, "title", "en_GB");

                // IP and equip
                String ip = "";
                List<String> equip = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Document> holders = (List<Document>) aw.get("awardHolders");
                if (holders != null) {
                    for (Document holder : holders) {
                        Document holderName = (Document) holder.get("name");
                        Document roleDoc = (Document) holder.get("role");
                        String firstName = holderName != null ? holderName.getString("firstName") : "";
                        String lastName = holderName != null ? holderName.getString("lastName") : "";
                        String fullName = (lastName != null ? lastName : "")
                                + (firstName != null && !firstName.isEmpty() ? ", " + firstName : "");
                        boolean isIp = false;
                        if (roleDoc != null) {
                            Document termDoc = (Document) roleDoc.get("term");
                            if (termDoc != null) {
                                String roleCa = termDoc.getString("ca_ES");
                                String roleEn = termDoc.getString("en_GB");
                                isIp = "Investigador/a Principal".equals(roleCa)
                                        || "Principal Investigator".equals(roleEn);
                            }
                        }
                        if (isIp) ip = fullName;
                        else if (!fullName.isBlank()) equip.add(fullName);
                    }
                }

                // Funder name (take first funder)
                String funderName = "";
                @SuppressWarnings("unchecked")
                List<Document> awFundings2 = (List<Document>) aw.get("fundings");
                if (awFundings2 != null && !awFundings2.isEmpty()) {
                    Document funding0 = awFundings2.get(0);
                    Document funderDoc = (Document) funding0.get("funder");
                    if (funderDoc != null) {
                        funderName = funderNames.getOrDefault(funderDoc.getString("uuid"), "");
                    }
                }

                // Import: sum institutionalPart.value across all fundingCollaborators
                double totalImport = 0.0;
                if (awFundings2 != null) {
                    for (Document funding : awFundings2) {
                        @SuppressWarnings("unchecked")
                        List<Document> collaborators = (List<Document>) funding.get("fundingCollaborators");
                        if (collaborators != null) {
                            for (Document col : collaborators) {
                                Document part = (Document) col.get("institutionalPart");
                                if (part != null) {
                                    Object val = part.get("value");
                                    if (val instanceof Number n) totalImport += n.doubleValue();
                                }
                            }
                        }
                    }
                }

                // Dates from actualPeriod
                Document period = (Document) aw.get("actualPeriod");
                String awStart = period != null ? formatDateDash(period.get("startDate")) : "";
                String awEnd   = period != null ? formatDateDash(period.get("endDate")) : "";

                // Official code: identifier with type.uri = referencecode
                String codiOficial = "";
                @SuppressWarnings("unchecked")
                List<Document> identifiers = (List<Document>) aw.get("identifiers");
                if (identifiers != null) {
                    codiOficial = identifiers.stream()
                            .filter(id -> {
                                Document idType = (Document) id.get("type");
                                return idType != null && "/dk/atira/pure/upm/classifiedsource/referencecode".equals(idType.getString("uri"));
                            })
                            .map(id -> id.getString("id") != null ? id.getString("id") : id.getString("value"))
                            .filter(v -> v != null && !v.isBlank())
                            .findFirst().orElse("");
                }

                // Write award block
                XWPFParagraph titlePara = doc.createParagraph();
                XWPFRun awTitleRun = titlePara.createRun();
                awTitleRun.setBold(true);
                awTitleRun.setFontSize(10);
                awTitleRun.setText(awardIdx + ".- " + awTitle);

                addLabelValueLine(doc, "Investigador principal", ip);
                addLabelValueLine(doc, "Equip investigador", String.join("; ", equip));
                addLabelValueLine(doc, "Entitat finançadora", funderName);
                addLabelValueLine(doc, "Import", formatImport(totalImport));
                addLabelValueLine(doc, "Data d'inici/fi", awStart + " → " + awEnd);
                addLabelValueLine(doc, "Codi oficial", codiOficial);

                doc.createParagraph();
                awardIdx++;
            }

            XWPFParagraph sec2Footer = doc.createParagraph();
            XWPFRun sec2FooterRun = sec2Footer.createRun();
            sec2FooterRun.setFontSize(9);
            sec2FooterRun.setItalic(true);
            sec2FooterRun.setText("Total ajuts competitius: " + awardsRaw.size());

            // ---- Section 3: Convenios (Concessió conveni) ----
            Document conveniFilter = new Document()
                    .append("workflow.step", "validated")
                    .append("type.term.ca_ES", "Concessió conveni")
                    .append("actualPeriod.startDate", new Document("$lte", endDateD))
                    .append("$and", Arrays.asList(
                            new Document("$or", Arrays.asList(
                                    new Document("managingOrganization.uuid", orgUuid),
                                    new Document("coManagingOrganizations.uuid", orgUuid)
                            )),
                            new Document("$or", Arrays.asList(
                                    new Document("actualPeriod.endDate", null),
                                    new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                            ))
                    ));
            List<Document> convenisRaw = new ArrayList<>();
            mongoTemplate.getDb().getCollection("Awards")
                    .find(conveniFilter)
                    .sort(new Document("actualPeriod.startDate", -1))
                    .into(convenisRaw);

            // Batch-fetch funder names for convenios
            Set<String> conveniFunderUuids = new HashSet<>();
            for (Document cv : convenisRaw) {
                @SuppressWarnings("unchecked")
                List<Document> cvFundings = (List<Document>) cv.get("fundings");
                if (cvFundings != null) {
                    for (Document funding : cvFundings) {
                        Document funder = (Document) funding.get("funder");
                        if (funder != null) { String u = funder.getString("uuid"); if (u != null) conveniFunderUuids.add(u); }
                    }
                }
            }
            Map<String, String> conveniFunderNames = new HashMap<>();
            if (!conveniFunderUuids.isEmpty()) {
                mongoTemplate.getDb().getCollection("ExternalOrganizations")
                        .find(new Document("uuid", new Document("$in", new ArrayList<>(conveniFunderUuids))))
                        .forEach(org -> {
                            String orgId = org.getString("uuid");
                            Document nd = (Document) org.get("name");
                            String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES")
                                    : nd.containsKey("es_ES") ? nd.getString("es_ES")
                                    : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                            conveniFunderNames.put(orgId, n);
                        });
            }

            doc.createParagraph();

            XWPFParagraph sec3Title = doc.createParagraph();
            sec3Title.setStyle("Ttol1");
            XWPFRun sec3Run = sec3Title.createRun();
            sec3Run.setText("Convenis");

            doc.createParagraph();

            int conveniIdx = 1;
            for (Document cv : convenisRaw) {
                String cvTitle = getNestedStringVal(cv, "title", "ca_ES");
                if (cvTitle.isEmpty()) cvTitle = getNestedStringVal(cv, "title", "es_ES");
                if (cvTitle.isEmpty()) cvTitle = getNestedStringVal(cv, "title", "en_GB");

                String cvIp = "";
                List<String> cvEquip = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Document> cvHolders = (List<Document>) cv.get("awardHolders");
                if (cvHolders != null) {
                    for (Document holder : cvHolders) {
                        Document hn = (Document) holder.get("name");
                        Document rd = (Document) holder.get("role");
                        String fn = hn != null ? hn.getString("firstName") : "";
                        String ln = hn != null ? hn.getString("lastName") : "";
                        String fullName = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                        boolean isIp = false;
                        if (rd != null) {
                            Document td = (Document) rd.get("term");
                            if (td != null) isIp = "Investigador/a Principal".equals(td.getString("ca_ES")) || "Principal Investigator".equals(td.getString("en_GB"));
                        }
                        if (isIp) cvIp = fullName; else if (!fullName.isBlank()) cvEquip.add(fullName);
                    }
                }

                String cvFunderName = "";
                @SuppressWarnings("unchecked")
                List<Document> cvFundings2 = (List<Document>) cv.get("fundings");
                if (cvFundings2 != null && !cvFundings2.isEmpty()) {
                    Document fd = (Document) cvFundings2.get(0).get("funder");
                    if (fd != null) cvFunderName = conveniFunderNames.getOrDefault(fd.getString("uuid"), "");
                }

                double cvImport = 0.0;
                if (cvFundings2 != null) {
                    for (Document funding : cvFundings2) {
                        @SuppressWarnings("unchecked")
                        List<Document> cols = (List<Document>) funding.get("fundingCollaborators");
                        if (cols != null) for (Document col : cols) {
                            Document part = (Document) col.get("institutionalPart");
                            if (part != null) { Object val = part.get("value"); if (val instanceof Number n) cvImport += n.doubleValue(); }
                        }
                    }
                }

                Document cvPeriod = (Document) cv.get("actualPeriod");
                String cvStart = cvPeriod != null ? formatDateDash(cvPeriod.get("startDate")) : "";
                String cvEnd   = cvPeriod != null ? formatDateDash(cvPeriod.get("endDate")) : "";

                String cvCodi = "";
                @SuppressWarnings("unchecked")
                List<Document> cvIds = (List<Document>) cv.get("identifiers");
                if (cvIds != null) {
                    cvCodi = cvIds.stream()
                            .filter(id -> {
                                Document idType = (Document) id.get("type");
                                return idType != null && "/dk/atira/pure/upm/classifiedsource/referencecode".equals(idType.getString("uri"));
                            })
                            .map(id -> id.getString("id") != null ? id.getString("id") : id.getString("value"))
                            .filter(v -> v != null && !v.isBlank())
                            .findFirst().orElse("");
                }

                XWPFParagraph cvTitlePara = doc.createParagraph();
                XWPFRun cvTitleRun = cvTitlePara.createRun();
                cvTitleRun.setBold(true);
                cvTitleRun.setFontSize(10);
                cvTitleRun.setText(conveniIdx + ".- " + cvTitle);

                addLabelValueLine(doc, "Investigador principal", cvIp);
                addLabelValueLine(doc, "Equip investigador", String.join("; ", cvEquip));
                addLabelValueLine(doc, "Entitat finançadora", cvFunderName);
                addLabelValueLine(doc, "Import", formatImport(cvImport));
                addLabelValueLine(doc, "Data d'inici/fi", cvStart + " \u2192 " + cvEnd);
                addLabelValueLine(doc, "Codi oficial", cvCodi);

                doc.createParagraph();
                conveniIdx++;
            }

            XWPFParagraph sec3Footer = doc.createParagraph();
            XWPFRun sec3FooterRun = sec3Footer.createRun();
            sec3FooterRun.setFontSize(9);
            sec3FooterRun.setItalic(true);
            sec3FooterRun.setText("Total convenis: " + convenisRaw.size());

            // ---- Section 4: Tesis doctorals gestionades per el centre ----
            // Filter by managingOrganization.uuid and awardDate.year within [startYear, endYear]
            int startYear = LocalDate.parse(startDate).getYear();
            int endYear   = LocalDate.parse(endDate).getYear();

            Document thesisFilter = new Document()
                    .append("workflow.step", "validated")
                    .append("managingOrganization.uuid", orgUuid)
                    .append("awardDate.year", new Document("$gte", startYear).append("$lte", endYear));
            List<Document> thesesRaw = new ArrayList<>();
            mongoTemplate.getDb().getCollection("StudentTheses")
                    .find(thesisFilter)
                    .sort(new Document("awardDate.year", -1).append("awardDate.month", -1))
                    .into(thesesRaw);

            doc.createParagraph();

            XWPFParagraph sec4Title = doc.createParagraph();
            sec4Title.setStyle("Ttol1");
            XWPFRun sec4Run = sec4Title.createRun();
            sec4Run.setText("Tesis doctorals");

            doc.createParagraph();

            int thesisIdx = 1;
            for (Document th : thesesRaw) {
                // Title
                Document thTitleDoc = (Document) th.get("title");
                String thTitle = thTitleDoc != null ? thTitleDoc.getString("value") : "";
                if (thTitle == null || thTitle.isBlank()) thTitle = "(sense títol)";

                // Year
                Document thAwardDate = (Document) th.get("awardDate");
                String thYear = thAwardDate != null && thAwardDate.get("year") != null
                        ? thAwardDate.get("year").toString() : "";

                // Author (contributors[0] or contributor with role=author)
                String doctorand = "";
                @SuppressWarnings("unchecked")
                List<Document> thContribs = (List<Document>) th.get("contributors");
                if (thContribs != null) {
                    doctorand = thContribs.stream()
                            .map(c -> {
                                Document n = (Document) c.get("name");
                                if (n == null) return "";
                                String ln = n.getString("lastName");
                                String fn = n.getString("firstName");
                                return (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                            })
                            .filter(s -> !s.isBlank())
                            .findFirst().orElse("");
                }

                // Directors (supervisors)
                List<String> directors = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Document> thSupervisors = (List<Document>) th.get("supervisors");
                if (thSupervisors != null) {
                    for (Document sv : thSupervisors) {
                        Document n = (Document) sv.get("name");
                        if (n == null) continue;
                        String ln = n.getString("lastName");
                        String fn = n.getString("firstName");
                        String full = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                        if (!full.isBlank()) directors.add(full);
                    }
                }

                // Write thesis block
                XWPFParagraph thTitlePara = doc.createParagraph();
                XWPFRun thTitleRun = thTitlePara.createRun();
                thTitleRun.setBold(true);
                thTitleRun.setFontSize(10);
                thTitleRun.setText(thesisIdx + ".- " + thTitle);

                addLabelValueLine(doc, "Doctorand/a", doctorand);
                addLabelValueLine(doc, "Director/a", String.join("; ", directors));
                addLabelValueLine(doc, "Any de lectura", thYear);

                doc.createParagraph();
                thesisIdx++;
            }

            XWPFParagraph sec4Footer = doc.createParagraph();
            XWPFRun sec4FooterRun = sec4Footer.createRun();
            sec4FooterRun.setFontSize(9);
            sec4FooterRun.setItalic(true);
            sec4FooterRun.setText("Total tesis doctorals: " + thesesRaw.size());

            // Response headers + write
            response.setContentType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            String filename = "informe-" + orgNombre.replaceAll("[^a-zA-Z0-9\\-_]", "_") + ".docx";
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            doc.write(out);
        }
    }

    private String getStringVal(Document doc, String key) {
        Object val = doc.get(key);
        if (val == null) return "";
        return val.toString();
    }

    private void addLabelValueLine(XWPFDocument wordDoc, String label, String value) {
        XWPFParagraph para = wordDoc.createParagraph();
        XWPFRun boldRun = para.createRun();
        boldRun.setBold(true);
        boldRun.setFontSize(10);
        boldRun.setText(label + ": ");
        XWPFRun valRun = para.createRun();
        valRun.setFontSize(10);
        valRun.setText(value);
    }

    private void applyProjectParagraphStyle(XWPFParagraph paragraph) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr =
                paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing sp =
                ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        sp.setAfter(BigInteger.valueOf(80));
        sp.setLine(BigInteger.valueOf(360));
        sp.setLineRule(org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule.AUTO);

        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTJc jc =
                ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
        jc.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc.LEFT);
    }

    private XWPFRun createProjectRun(XWPFParagraph paragraph, boolean bold, boolean italic) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Calibri");
        run.setLang("ca-ES");
        run.setFontSize(9);
        run.setBold(bold);
        run.setItalic(italic);
        return run;
    }

    private static void insertAtBookmark(
            XWPFDocument doc,
            String bookmarkName,
            String bookmarkValue,
            String font,
            int mida,
            boolean negreta,
            boolean italica) {
        List<XWPFParagraph> paragraphs = new ArrayList<XWPFParagraph>();
        paragraphs.addAll(doc.getParagraphs());

        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    if (cell.getTables().isEmpty()) {
                        paragraphs.addAll(cell.getParagraphs());
                    } else {
                        for (XWPFTable table2 : cell.getTables()) {
                            for (XWPFTableRow row2 : table2.getRows()) {
                                for (XWPFTableCell cell2 : row2.getTableCells()) {
                                    paragraphs.addAll(cell2.getParagraphs());
                                }
                            }
                        }
                    }
                }
            }
        }

        Iterator<XWPFParagraph> paraIter = paragraphs.iterator();
        while (paraIter.hasNext()) {
            XWPFParagraph para = paraIter.next();
            List<CTBookmark> bookmarkList = para.getCTP().getBookmarkStartList();
            Iterator<CTBookmark> bookmarkIter = bookmarkList.iterator();
            while (bookmarkIter.hasNext()) {
                CTBookmark bookmark = bookmarkIter.next();
                if (bookmark.getName().equals(bookmarkName)) {
                    XWPFRun run = para.createRun();
                    run.setBold(negreta);
                    run.setFontFamily(font);
                    if (mida > 0) {
                        run.setFontSize(mida);
                    }
                    run.setItalic(italica);
                    run.setText(bookmarkValue);
                    Node nextNode = bookmark.getDomNode().getNextSibling();
                    while (!(nextNode.getNodeName().contains("bookmarkEnd"))) {
                        para.getCTP().getDomNode().removeChild(nextNode);
                        nextNode = bookmark.getDomNode().getNextSibling();
                    }
                    para.getCTP().getDomNode().insertBefore(run.getCTR().getDomNode(), bookmark.getDomNode());
                }
            }
        }
    }

    private void addProjectLine(XWPFParagraph p, String text, boolean bold) {
        XWPFRun run = createProjectRun(p, bold, false);
        run.setText(text);
        run.addBreak();
    }

    private void addProjectLabelValueLine(XWPFParagraph p, String label, String value) {
        XWPFRun labelRun = createProjectRun(p, false, true);
        labelRun.setText(label + ": ");
        XWPFRun valueRun = createProjectRun(p, false, false);
        valueRun.setText(value);
        valueRun.addBreak();
    }

    private void addProjectBlankLine(XWPFParagraph p) {
        XWPFRun run = createProjectRun(p, false, true);
        run.setText(" ");
        run.addBreak();
    }

    private void appendProjectesPerAnyChart(XWPFDocument doc, List<Document> rowsPersonaResumen, String lang) throws Exception {
        Map<Integer, YearMetrics> perAny = calcularSeriesProjectesPerAny(rowsPersonaResumen);
        if (perAny.isEmpty()) {
            return;
        }

        XWPFParagraph pageBreakP = doc.createParagraph();
        XWPFRun pageBreakRun = pageBreakP.createRun();
        pageBreakRun.addBreak(BreakType.PAGE);

        XWPFParagraph titleP = doc.createParagraph();
        applyProjectParagraphStyle(titleP);
        titleP.setAlignment(ParagraphAlignment.LEFT);
        {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = titleP.getCTP().getPPr();
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
            ind.setLeft(BigInteger.valueOf(-714));
        }
        XWPFRun titleRun = createProjectRun(titleP, true, false);
        titleRun.setText("Ajuts per any");
        titleRun.addBreak();

        BufferedImage chart = construirGraficoProjectesPerAny(perAny);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(chart, "png", baos);
        byte[] bytes = baos.toByteArray();

        XWPFParagraph imgP = doc.createParagraph();
        applyProjectParagraphStyle(imgP);
        imgP.setAlignment(ParagraphAlignment.LEFT);
        {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = imgP.getCTP().getPPr();
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
            ind.setLeft(BigInteger.valueOf(-714));
        }
        XWPFRun imgRun = imgP.createRun();
        imgRun.addPicture(
                new ByteArrayInputStream(bytes),
                org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                "ajuts-per-any.png",
                Units.toEMU(520),
                Units.toEMU(300)
        );
        imgRun.addBreak();
    }

    private Map<Integer, YearMetrics> calcularSeriesProjectesPerAny(List<Document> rowsPersonaResumen) {
        Map<Integer, YearMetrics> perAny = new java.util.TreeMap<>();
        if (rowsPersonaResumen == null) {
            return perAny;
        }

        for (Document row : rowsPersonaResumen) {
            if (row == null) {
                continue;
            }

            Object anyoObj = row.get("Año");
            if (!(anyoObj instanceof Number)) {
                continue;
            }
            int year = ((Number) anyoObj).intValue();

            YearMetrics ym = perAny.computeIfAbsent(year, k -> new YearMetrics());

            int proyIp = toInt(row.get("Proyectos_IP"));
            int proyCoip = toInt(row.get("Proyectos_CoIP"));
            int proyMiembro = toInt(row.get("Proyectos_Miembro"));
            int total = proyIp + proyCoip + proyMiembro;

            ym.totalProjects += total;
            String categoria = asString(row.get("FunderType"));
            if (categoria == null || categoria.isBlank()) categoria = "Desconegut";
            ym.categoriaCounts.put(categoria, ym.categoriaCounts.getOrDefault(categoria, 0) + total);
            ym.importePonderat += toDouble(row.get("Importe_Ponderado (€)"));
        }
        return perAny;
    }

    private BufferedImage construirGraficoProjectesPerAny(Map<Integer, YearMetrics> perAny) {
        final int width = 1400;
        final int height = 760;
        final int left = 90;
        final int right = 90;
        final int top = 40;
        final int bottom = 120;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        int chartW = width - left - right;
        int chartH = height - top - bottom;
        List<Integer> years = new ArrayList<>(perAny.keySet());

        List<String> categories = perAny.values().stream()
                .flatMap(v -> v.categoriaCounts.keySet().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int maxProjects = perAny.values().stream().mapToInt(v -> v.totalProjects).max().orElse(1);
        double maxImport = perAny.values().stream().mapToDouble(v -> v.importePonderat).max().orElse(1d);
        int yTicks = Math.max(2, Math.min(6, maxProjects));

        g.setFont(new Font("Calibri", Font.PLAIN, 13));
        g.setColor(new Color(220, 226, 232));
        for (int i = 0; i <= yTicks; i++) {
            int v = (int) Math.round((double) i * maxProjects / yTicks);
            int y = top + chartH - (int) Math.round((double) v * chartH / Math.max(1, maxProjects));
            g.drawLine(left, y, left + chartW, y);
            g.setColor(new Color(89, 100, 115));
            g.drawString(String.valueOf(v), left - 28, y + 5);
            g.setColor(new Color(220, 226, 232));
        }

        int n = Math.max(1, years.size());
        int slot = chartW / n;
        int barW = Math.max(12, (int) (slot * 0.58));

        for (int i = 0; i < years.size(); i++) {
            int year = years.get(i);
            YearMetrics ym = perAny.get(year);
            int x = left + i * slot + (slot - barW) / 2;
            int yBase = top + chartH;

            for (String cat : categories) {
                int value = ym.categoriaCounts.getOrDefault(cat, 0);
                if (value <= 0) continue;
                int h = (int) Math.round((double) value * chartH / Math.max(1, maxProjects));
                int y = yBase - h;
                g.setColor(colorCategoria(cat));
                g.fillRect(x, y, barW, h);
                yBase = y;
            }

            g.setColor(new Color(42, 48, 55));
            g.drawString(String.valueOf(year), x + Math.max(2, barW / 2 - 14), top + chartH + 26);
        }

        g.setColor(new Color(224, 82, 82));
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f));
        int prevX = -1;
        int prevY = -1;
        for (int i = 0; i < years.size(); i++) {
            int year = years.get(i);
            YearMetrics ym = perAny.get(year);
            int x = left + i * slot + slot / 2;
            int y = top + chartH - (int) Math.round((ym.importePonderat / Math.max(1d, maxImport)) * chartH);
            if (prevX >= 0) {
                g.drawLine(prevX, prevY, x, y);
            }
            g.fillOval(x - 4, y - 4, 8, 8);
            prevX = x;
            prevY = y;
        }

        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(89, 100, 115));
        g.drawString("Projectes", left - 8, top - 10);
        g.drawString("Import (€)", left + chartW - 56, top - 10);

        g.setColor(new Color(151, 160, 170));
        g.drawLine(left, top + chartH, left + chartW, top + chartH);
        g.drawLine(left, top, left, top + chartH);

        drawLegend(g, left, top + chartH + 42, categories);

        g.dispose();
        return image;
    }

    private Color colorCategoria(String categoria) {
        List<String> paletteHex = List.of("#008037", "#F88C12", "#004D5E", "#596473", "#004d21", "#00a34f", "#fab84c", "#006b7a", "#8a99a8", "#2a3037");
        String raw = categoria == null ? "" : categoria;
        String lowered = raw.toLowerCase();
        String key = Normalizer.normalize(lowered, Normalizer.Form.NFD).replaceAll("\\p{M}", "");

        if ("publica".equals(key)) return Color.decode("#008037");
        if ("privada".equals(key)) return Color.decode("#F88C12");

        int h = 0;
        for (int i = 0; i < raw.length(); i++) {
            h = 31 * h + raw.charAt(i);
        }
        return Color.decode(paletteHex.get(Math.floorMod(Math.abs(h), paletteHex.size())));
    }

    private void drawLegend(Graphics2D g, int startX, int y, List<String> categories) {
        g.setFont(new Font("Calibri", Font.PLAIN, 12));
        int x = startX;
        for (String cat : categories) {
            g.setColor(colorCategoria(cat));
            g.fillRect(x, y - 10, 14, 10);
            g.setColor(new Color(42, 48, 55));
            g.drawString(cat, x + 18, y);
            x += 18 + Math.max(40, cat.length() * 7);
        }

        g.setColor(new Color(224, 82, 82));
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f));
        g.drawLine(startX, y + 20, startX + 18, y + 20);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(42, 48, 55));
        g.drawString("Import ponderat (€)", startX + 24, y + 24);
    }

    private static class YearMetrics {
        int totalProjects = 0;
        double importePonderat = 0d;
        Map<String, Integer> categoriaCounts = new LinkedHashMap<>();
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return 0d;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Formats an amount as "227.000,00 €" (Catalan/Spanish locale). */
    private String formatImport(double amount) {
        if (amount == 0) return "";
        return String.format(Locale.GERMAN, "%,.2f €", amount);
    }

    /** Converts a date value (Date or ISO string) to DD-MM-YYYY format with dashes. */
    private String formatDateDash(Object raw) {
        if (raw == null) return "";
        if (raw instanceof Date d) {
            LocalDate ld = d.toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
            return String.format("%02d-%02d-%04d", ld.getDayOfMonth(), ld.getMonthValue(), ld.getYear());
        }
        String s = raw.toString();
        if (s.isBlank()) return "";
        try {
            String datePart = s.contains("T") ? s.substring(0, s.indexOf('T')) : s.trim();
            String[] parts = datePart.split("-");
            if (parts.length == 3) {
                return parts[2] + "-" + parts[1] + "-" + parts[0];
            }
        } catch (Exception ignored) {}
        return s;
    }

    /** Traverses nested Documents by dot-separated keys. */
    private String getNestedStringVal(Document doc, String... keys) {
        Object current = doc;
        for (String key : keys) {
            if (!(current instanceof Document)) return "";
            current = ((Document) current).get(key);
            if (current == null) return "";
        }
        return current.toString();
    }

    /** Converts ISO date strings (YYYY-MM-DD or YYYY-MM-DDThh:mm:ss…) to dd/MM/YYYY. */
    private String formatDateDMY(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            // Take only the date part before any 'T'
            String datePart = raw.contains("T") ? raw.substring(0, raw.indexOf('T')) : raw.trim();
            String[] parts = datePart.split("-");
            if (parts.length == 3) {
                return parts[2] + "/" + parts[1] + "/" + parts[0];
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    // -----------------------------------------------------------------------
    // Autocomplete: returns active persons matching a query (uuid + nombre)
    // -----------------------------------------------------------------------
    @GetMapping("/search-vigentes")
    public List<Map<String, String>> searchPersonasVigentes(
            @RequestParam(required = false, defaultValue = "") String q) {
        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();
        Date hoyDate = Date.from(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant());

        List<Object> vigentOr = Arrays.asList(
            // period exists and is not null, but has no endDate (open-ended = still active)
            new Document("$and", List.of(
                new Document("period", new Document("$exists", true).append("$ne", null)),
                new Document("period.endDate", null)
            )),
            new Document("$and", List.of(
                new Document("period.endDate", new Document("$type", 9)),
                new Document("period.endDate", new Document("$gt", hoyDate))
            )),
            new Document("$and", List.of(
                new Document("period.endDate", new Document("$type", 2)),
                new Document("period.endDate", new Document("$gt", hoyIso))
            ))
        );
        /*Document filter = new Document("staffOrganizationAssociations",
            new Document("$elemMatch", new Document("$or", vigentOr)));*/
        
        Document filter = new Document();

        String safeQ = q.trim();
        if (!safeQ.isBlank()) {
            String escaped = Pattern.quote(safeQ);
            filter.append("$or", Arrays.asList(
                new Document("name.lastName",  new Document("$regex", escaped).append("$options", "i")),
                new Document("name.firstName", new Document("$regex", escaped).append("$options", "i"))
            ));
        }

        List<Document> docs = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons")
            .find(filter)
            .sort(new Document("name.lastName", 1).append("name.firstName", 1))
            .limit(20)
            .into(docs);

        return docs.stream().map(doc -> {
            Document nd = (Document) doc.get("name");
            String ln = nd != null ? nd.getString("lastName")  : "";
            String fn = nd != null ? nd.getString("firstName") : "";
            String nombre = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
            Map<String, String> m = new LinkedHashMap<>();
            m.put("uuid", doc.getString("uuid"));
            m.put("nombre", nombre.isBlank() ? doc.getString("uuid") : nombre.trim());
            return m;
        }).toList();
    }

    // -----------------------------------------------------------------------
    // Informe Word per persona: genera un .docx amb tots els ajuts (competitius
    // i convenis) on la persona és awardHolder durant un rang de dates.
    // -----------------------------------------------------------------------
    @GetMapping("/informe-word-persona")
    public void generarInformeWordPersona(
            @RequestParam String personUuid,
            @RequestParam(required = false, defaultValue = "2000-01-01") String startDate,
            @RequestParam(required = false, defaultValue = "2050-12-31") String endDate,
            @RequestParam(required = false, defaultValue = "all") String projectFilter,
            @RequestParam(required = false, defaultValue = "ca") String lang,
            @RequestParam(required = false, defaultValue = "false") boolean onlyAwards,
            HttpServletResponse response) throws Exception {

        // 1. Fetch person name
        List<Document> personDocs = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons")
            .find(new Document("uuid", personUuid)).limit(1).into(personDocs);
        String personName = personUuid;
        if (!personDocs.isEmpty()) {
            Document nd = (Document) personDocs.get(0).get("name");
            if (nd != null) {
                String ln = nd.getString("lastName");
                String fn = nd.getString("firstName");
                personName = (fn != null && !fn.isEmpty() ? fn + " " : "") + (ln != null ? ln : "");
                if (personName.isBlank()) personName = personUuid;
            }
        }

        Date startDateD = Date.from(LocalDate.parse(startDate).atStartOfDay(ZoneId.of("UTC")).toInstant());
        Date endDateD   = Date.from(LocalDate.parse(endDate).atStartOfDay(ZoneId.of("UTC")).toInstant());

        // 2. Competitive awards where this person is awardHolder
        // Date filter: actualPeriod.startDate within [startDate, endDate] (mirrors reference query)
        // Same collaborator UUID used by the table (UAB as funding collaborator)
        final String UAB_COLLABORATOR_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";

        // Workflow: validated OR closed
        Document awardFilter = new Document()
            .append("workflow.step", new Document("$in", Arrays.asList("validated", "closed")))
            .append("categoria", new Document("$regex", "^Ajudes competitives"))
            .append("awardHolders.person.uuid", personUuid)
            .append("fundings.fundingCollaborators.collaborator.uuid", UAB_COLLABORATOR_UUID)
            .append("actualPeriod.startDate", new Document("$gte", startDateD).append("$lte", endDateD));
        List<Document> awardsRaw = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Awards")
            .find(awardFilter)
            .sort(new Document("actualPeriod.startDate", -1))
            .into(awardsRaw);

        // 3. Convenios where this person is awardHolder
        Document conveniFilter = new Document()
            .append("workflow.step", new Document("$in", Arrays.asList("validated", "closed")))
            .append("type.term.ca_ES", "Concessió conveni")
            .append("awardHolders.person.uuid", personUuid)
            .append("fundings.fundingCollaborators.collaborator.uuid", UAB_COLLABORATOR_UUID)
            .append("actualPeriod.startDate", new Document("$gte", startDateD).append("$lte", endDateD));
        List<Document> convenisRaw = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Awards")
            .find(conveniFilter)
            .sort(new Document("actualPeriod.startDate", -1))
            .into(convenisRaw);

        List<Document> allAwards = new ArrayList<>();
        allAwards.addAll(awardsRaw);
        allAwards.addAll(convenisRaw);

        if ("ipcoip".equalsIgnoreCase(projectFilter)) {
            allAwards = allAwards.stream()
                .filter(award -> isPersonIpOrCoipInAward(award, personUuid))
                .collect(Collectors.toCollection(ArrayList::new));
        }

        allAwards.sort((a, b) -> {
            Document aPeriod = (Document) a.get("actualPeriod");
            Document bPeriod = (Document) b.get("actualPeriod");
            LocalDate aStart = aPeriod != null ? parseDate(aPeriod.get("startDate")) : null;
            LocalDate bStart = bPeriod != null ? parseDate(bPeriod.get("startDate")) : null;
            if (aStart == null && bStart == null) return 0;
            if (aStart == null) return 1;
            if (bStart == null) return -1;
            return bStart.compareTo(aStart);
        });

        // 4. Batch-fetch funder names
        Set<String> allFunderUuids = new HashSet<>();
        for (Document award : allAwards) {
            @SuppressWarnings("unchecked")
            List<Document> fs = (List<Document>) award.get("fundings");
            if (fs != null) for (Document f : fs) {
                Document funder = (Document) f.get("funder");
                if (funder != null && funder.getString("uuid") != null) allFunderUuids.add(funder.getString("uuid"));
            }
        }
        Map<String, String> funderNames = new HashMap<>();
        if (!allFunderUuids.isEmpty()) {
            mongoTemplate.getDb().getCollection("ExternalOrganizations")
                .find(new Document("uuid", new Document("$in", new ArrayList<>(allFunderUuids))))
                .forEach(org -> {
                    String orgId = org.getString("uuid");
                    Document nd = (Document) org.get("name");
                    String name = nd == null ? "" :
                        nd.containsKey("ca_ES") ? nd.getString("ca_ES") :
                        nd.containsKey("es_ES") ? nd.getString("es_ES") :
                        nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                    funderNames.put(orgId, name);
                });
        }

            int desdeYear = LocalDate.parse(startDate).getYear();
            int hastaYear = LocalDate.parse(endDate).getYear();
            List<Document> rowsForChart = awardService.getPersonaResumen(
                UAB_COLLABORATOR_UUID,
                null,
                null,
                desdeYear,
                hastaYear,
                "awardDate",
                null,
                null,
                null
            ).stream()
                .filter(r -> personUuid.equals(String.valueOf(r.get("PersonaUuid"))))
                .collect(Collectors.toList());

        // 5. Build Word document
        String templateName = switch (lang) {
            case "es" -> "plantilla_certificats_castella.docx";
            case "en" -> "plantilla_certificats_angles.docx";
            default  -> "plantilla_certificats_catala.docx";
        };
        if (onlyAwards) {
            // Template-based document: keep header/footer, remove intro body text, and write only awards
            InputStream tplIs = getClass().getClassLoader().getResourceAsStream(templateName);
            try (XWPFDocument doc = new XWPFDocument(tplIs); OutputStream out = response.getOutputStream()) {
                // Clear template body content (preserves sectPr with margins, header, footer refs)
                {
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody b = doc.getDocument().getBody();
                    for (int i = b.sizeOfPArray() - 1; i >= 0; i--) b.removeP(i);
                    for (int i = b.sizeOfTblArray() - 1; i >= 0; i--) b.removeTbl(i);
                }

                if (!allAwards.isEmpty()) {
                    XWPFParagraph awardPara = doc.createParagraph();
                    applyProjectParagraphStyle(awardPara);
                    int awardIdx = 1;
                    for (Document aw : allAwards) {
                        String awTitle = getNestedStringVal(aw, "title", "ca_ES");
                        if (awTitle.isEmpty()) awTitle = getNestedStringVal(aw, "title", "es_ES");
                        if (awTitle.isEmpty()) awTitle = getNestedStringVal(aw, "title", "en_GB");

                        List<String> ipOnlyList = new ArrayList<>();
                        List<String> coipList = new ArrayList<>();
                        String personRole = "";
                        @SuppressWarnings("unchecked")
                        List<Document> holders = (List<Document>) aw.get("awardHolders");
                        if (holders != null) {
                            for (Document holder : holders) {
                                Document hn = (Document) holder.get("name");
                                Document rd = (Document) holder.get("role");
                                Document holderPerson = (Document) holder.get("person");
                                String holderUuid = holderPerson != null ? holderPerson.getString("uuid") : "";
                                String fn = hn != null ? hn.getString("firstName") : "";
                                String ln = hn != null ? hn.getString("lastName")  : "";
                                String fullName = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                                boolean isIp = false;
                                boolean isPureIp = false;
                                String roleLocalized = "";
                                if (rd != null) {
                                    Document td = (Document) rd.get("term");
                                    if (td != null) {
                                        String roleCa = td.getString("ca_ES") != null ? td.getString("ca_ES") : "";
                                        isIp = isIpOrCoipRole(rd);
                                        isPureIp = isIp && !normalize(roleCa).startsWith("co");
                                        roleLocalized = switch (lang) {
                                            case "es" -> td.getString("es_ES") != null ? td.getString("es_ES") : roleCa;
                                            case "en" -> td.getString("en_GB") != null ? td.getString("en_GB") : roleCa;
                                            default  -> roleCa;
                                        };
                                    }
                                }
                                if (isIp && !fullName.isBlank()) {
                                    if (isPureIp) ipOnlyList.add(fullName); else coipList.add(fullName);
                                }
                                if (personUuid.equals(holderUuid)) personRole = roleLocalized;
                            }
                        }
                        List<String> ipAllList = new ArrayList<>(ipOnlyList);
                        ipAllList.addAll(coipList);
                        String ip = String.join(" & ", ipAllList);

                        String funderName = "";
                        @SuppressWarnings("unchecked")
                        List<Document> awFundings = (List<Document>) aw.get("fundings");
                        if (awFundings != null && !awFundings.isEmpty()) {
                            Document fd = (Document) awFundings.get(0).get("funder");
                            if (fd != null) funderName = funderNames.getOrDefault(fd.getString("uuid"), "");
                        }

                        double importTotal = 0.0;
                        double importUAB = 0.0;
                        if (awFundings != null) {
                            for (Document funding : awFundings) {
                                Document amountDoc = (Document) funding.get("amount");
                                if (amountDoc != null) { Object val = amountDoc.get("value"); if (val instanceof Number n) importTotal += n.doubleValue(); }
                                @SuppressWarnings("unchecked")
                                List<Document> cols = (List<Document>) funding.get("fundingCollaborators");
                                if (cols != null) for (Document col : cols) {
                                    Document part = (Document) col.get("institutionalPart");
                                    if (part != null) { Object val = part.get("value"); if (val instanceof Number n) importUAB += n.doubleValue(); }
                                }
                            }
                        }
                        if (importTotal == 0) importTotal = importUAB;

                        Document period = (Document) aw.get("actualPeriod");
                        String awStart = period != null ? formatDateDash(period.get("startDate")) : "";
                        String awEnd   = period != null ? formatDateDash(period.get("endDate"))   : "";

                        String codiOficial = "";
                        @SuppressWarnings("unchecked")
                        List<Document> identifiers = (List<Document>) aw.get("identifiers");
                        if (identifiers != null) {
                            codiOficial = identifiers.stream()
                                .filter(id -> { Document idType = (Document) id.get("type"); return idType != null && "/dk/atira/pure/upm/classifiedsource/referencecode".equals(idType.getString("uri")); })
                                .map(id -> id.getString("id") != null ? id.getString("id") : id.getString("value"))
                                .filter(v -> v != null && !v.isBlank())
                                .findFirst().orElse("");
                        }

                        String lbIp     = switch (lang) { case "es" -> "Investigador principal"; case "en" -> "Principal investigator"; default -> "Investigador principal"; };
                        String lbRol    = switch (lang) { case "es" -> "Rol"; case "en" -> "Role"; default -> "Rol"; };
                        String lbFunder = switch (lang) { case "es" -> "Entidad financiadora"; case "en" -> "Funder"; default -> "Entitat finan\u00e7adora"; };
                        String lbTotal  = switch (lang) { case "es" -> "Importe total"; case "en" -> "Full awarding amount"; default -> "Import total"; };
                        String lbUAB    = switch (lang) { case "es" -> "Importe UAB"; case "en" -> "UAB awarding amount"; default -> "Import UAB"; };
                        String lbDates  = switch (lang) { case "es" -> "Fecha de inicio/fin"; case "en" -> "Start/end dates"; default -> "Data d'inici/fi"; };
                        String lbCode   = switch (lang) { case "es" -> "C\u00f3digo Oficial"; case "en" -> "Reference code"; default -> "Codi Oficial"; };

                        addProjectLine(awardPara, awardIdx + ".- " + awTitle, true);
                        if (!ip.isBlank()) addProjectLabelValueLine(awardPara, lbIp, ip);
                        if (!personRole.isBlank()) addProjectLabelValueLine(awardPara, lbRol, personRole);
                        if (!funderName.isBlank()) addProjectLabelValueLine(awardPara, lbFunder, funderName);
                        if (importTotal > 0) addProjectLabelValueLine(awardPara, lbTotal, formatImport(importTotal));
                        if (importUAB > 0) addProjectLabelValueLine(awardPara, lbUAB, formatImport(importUAB));
                        addProjectLabelValueLine(awardPara, lbDates, awStart + " \u2192 " + awEnd);
                        if (!codiOficial.isBlank()) addProjectLabelValueLine(awardPara, lbCode, codiOficial);
                        addProjectBlankLine(awardPara);
                        awardIdx++;
                    }
                }
                appendProjectesPerAnyChart(doc, rowsForChart, lang);
                response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                String filename = "awards-" + personName.replaceAll("[^a-zA-Z0-9\\-_]", "_") + ".docx";
                response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                doc.write(out);
            }
            return;
        }

        InputStream tplIs = getClass().getClassLoader().getResourceAsStream(templateName);
        try (XWPFDocument doc = new XWPFDocument(tplIs); OutputStream out = response.getOutputStream()) {

            // Inject person name at bookmark "nom"
            insertAtBookmark(doc, "nom", personName, "Calibri", 0, false, true);

            // Find the "projectes" bookmark paragraph BEFORE adding any new content.
            // We will move the new paragraphs here after building them.
            Node projectesAnchorNode = null;
            for (XWPFParagraph p : doc.getParagraphs()) {
                for (CTBookmark bm : p.getCTP().getBookmarkStartList()) {
                    if ("projectes".equals(bm.getName())) {
                        projectesAnchorNode = p.getCTP().getDomNode();
                        break;
                    }
                }
                if (projectesAnchorNode != null) break;
            }

            // ---- Tots els awards (ajuts + convenis) ----
            int parasBefore = doc.getParagraphs().size();
            if (!allAwards.isEmpty()) {
                // All award lines go into ONE paragraph using <w:br/> separators,
                // matching the reference document structure.
                XWPFParagraph awardPara = doc.createParagraph();
                applyProjectParagraphStyle(awardPara);
                int awardIdx = 1;
                for (Document aw : allAwards) {
                    String awTitle = getNestedStringVal(aw, "title", "ca_ES");
                    if (awTitle.isEmpty()) awTitle = getNestedStringVal(aw, "title", "es_ES");
                    if (awTitle.isEmpty()) awTitle = getNestedStringVal(aw, "title", "en_GB");

                    List<String> ipOnlyList = new ArrayList<>();
                    List<String> coipList = new ArrayList<>();
                    String personRole = "";
                    @SuppressWarnings("unchecked")
                    List<Document> holders = (List<Document>) aw.get("awardHolders");
                    if (holders != null) {
                        for (Document holder : holders) {
                            Document hn = (Document) holder.get("name");
                            Document rd = (Document) holder.get("role");
                            Document holderPerson = (Document) holder.get("person");
                            String holderUuid = holderPerson != null ? holderPerson.getString("uuid") : "";
                            String fn = hn != null ? hn.getString("firstName") : "";
                            String ln = hn != null ? hn.getString("lastName")  : "";
                            String fullName = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                            boolean isIp = false;
                            boolean isPureIp = false;
                            String roleLocalized = "";
                            if (rd != null) {
                                Document td = (Document) rd.get("term");
                                if (td != null) {
                                    String roleCa = td.getString("ca_ES") != null ? td.getString("ca_ES") : "";
                                    isIp = isIpOrCoipRole(rd);
                                    isPureIp = isIp && !normalize(roleCa).startsWith("co");
                                    roleLocalized = switch (lang) {
                                        case "es" -> td.getString("es_ES") != null ? td.getString("es_ES") : roleCa;
                                        case "en" -> td.getString("en_GB") != null ? td.getString("en_GB") : roleCa;
                                        default  -> roleCa;
                                    };
                                }
                            }
                            if (isIp && !fullName.isBlank()) {
                                if (isPureIp) ipOnlyList.add(fullName); else coipList.add(fullName);
                            }
                            if (personUuid.equals(holderUuid)) personRole = roleLocalized;
                        }
                    }
                    List<String> ipAllList = new ArrayList<>(ipOnlyList);
                    ipAllList.addAll(coipList);
                    String ip = String.join(" & ", ipAllList);

                    String funderName = "";
                    @SuppressWarnings("unchecked")
                    List<Document> awFundings = (List<Document>) aw.get("fundings");
                    if (awFundings != null && !awFundings.isEmpty()) {
                        Document fd = (Document) awFundings.get(0).get("funder");
                        if (fd != null) funderName = funderNames.getOrDefault(fd.getString("uuid"), "");
                    }

                    double importTotal = 0.0;
                    double importUAB = 0.0;
                    if (awFundings != null) {
                        for (Document funding : awFundings) {
                            // Total import: sum funding-level amount
                            Document amountDoc = (Document) funding.get("amount");
                            if (amountDoc != null) {
                                Object val = amountDoc.get("value");
                                if (val instanceof Number n) importTotal += n.doubleValue();
                            }
                            // UAB import: sum institutionalPart across all collaborators
                            @SuppressWarnings("unchecked")
                            List<Document> cols = (List<Document>) funding.get("fundingCollaborators");
                            if (cols != null) for (Document col : cols) {
                                Document part = (Document) col.get("institutionalPart");
                                if (part != null) { Object val = part.get("value"); if (val instanceof Number n) importUAB += n.doubleValue(); }
                            }
                        }
                    }
                    // Fallback: if no funding-level amount, use sum of institutional parts as total
                    if (importTotal == 0) importTotal = importUAB;

                    Document period = (Document) aw.get("actualPeriod");
                    String awStart = period != null ? formatDateDash(period.get("startDate")) : "";
                    String awEnd   = period != null ? formatDateDash(period.get("endDate"))   : "";

                    String codiOficial = "";
                    @SuppressWarnings("unchecked")
                    List<Document> identifiers = (List<Document>) aw.get("identifiers");
                    if (identifiers != null) {
                        codiOficial = identifiers.stream()
                            .filter(id -> { Document idType = (Document) id.get("type"); return idType != null && "/dk/atira/pure/upm/classifiedsource/referencecode".equals(idType.getString("uri")); })
                            .map(id -> id.getString("id") != null ? id.getString("id") : id.getString("value"))
                            .filter(v -> v != null && !v.isBlank())
                            .findFirst().orElse("");
                    }

                    // Labels translated per language
                    String lbIp      = switch (lang) { case "es" -> "Investigador principal"; case "en" -> "Principal investigator"; default -> "Investigador principal"; };
                    String lbRol     = switch (lang) { case "es" -> "Rol"; case "en" -> "Role"; default -> "Rol"; };
                    String lbFunder  = switch (lang) { case "es" -> "Entidad financiadora"; case "en" -> "Funder"; default -> "Entitat finan\u00e7adora"; };
                    String lbTotal   = switch (lang) { case "es" -> "Importe total"; case "en" -> "Full awarding amount"; default -> "Import total"; };
                    String lbUAB     = switch (lang) { case "es" -> "Importe UAB"; case "en" -> "UAB awarding amount"; default -> "Import UAB"; };
                    String lbDates   = switch (lang) { case "es" -> "Fecha de inicio/fin"; case "en" -> "Start/end dates"; default -> "Data d'inici/fi"; };
                    String lbCode    = switch (lang) { case "es" -> "C\u00f3digo Oficial"; case "en" -> "Reference code"; default -> "Codi Oficial"; };

                    addProjectLine(awardPara, awardIdx + ".- " + awTitle, true);
                    if (!ip.isBlank()) {
                        addProjectLabelValueLine(awardPara, lbIp, ip);
                    }
                    if (!personRole.isBlank()) {
                        addProjectLabelValueLine(awardPara, lbRol, personRole);
                    }
                    if (!funderName.isBlank()) {
                        addProjectLabelValueLine(awardPara, lbFunder, funderName);
                    }
                    if (importTotal > 0) {
                        addProjectLabelValueLine(awardPara, lbTotal, formatImport(importTotal));
                    }
                    if (importUAB > 0) {
                        addProjectLabelValueLine(awardPara, lbUAB, formatImport(importUAB));
                    }
                    addProjectLabelValueLine(awardPara, lbDates, awStart + " \u2192 " + awEnd);
                    if (!codiOficial.isBlank()) {
                        addProjectLabelValueLine(awardPara, lbCode, codiOficial);
                    }
                    addProjectBlankLine(awardPara);
                    awardIdx++;
                }
            }

            appendProjectesPerAnyChart(doc, rowsForChart, lang);

            // Move all newly appended paragraphs to just after the "projectes" bookmark paragraph.
            if (projectesAnchorNode != null) {
                Node bodyNode = projectesAnchorNode.getParentNode();
                Node insertAfter = projectesAnchorNode;
                List<XWPFParagraph> allParas = doc.getParagraphs();
                for (int i = parasBefore; i < allParas.size(); i++) {
                    Node paraNode = allParas.get(i).getCTP().getDomNode();
                    bodyNode.removeChild(paraNode);
                    bodyNode.insertBefore(paraNode, insertAfter.getNextSibling());
                    insertAfter = paraNode;
                }
            }

            // Response
            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            String filename = "informe-" + personName.replaceAll("[^a-zA-Z0-9\\-_]", "_") + ".docx";
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            doc.write(out);
        }
    }

    private boolean isPersonIpOrCoipInAward(Document award, String personUuid) {
        if (award == null || personUuid == null || personUuid.isBlank()) {
            return false;
        }

        @SuppressWarnings("unchecked")
        List<Document> holders = (List<Document>) award.get("awardHolders");
        if (holders == null) {
            return false;
        }

        for (Document holder : holders) {
            if (holder == null) {
                continue;
            }
            Document person = (Document) holder.get("person");
            String holderUuid = person != null ? person.getString("uuid") : null;
            if (!personUuid.equals(holderUuid)) {
                continue;
            }
            if (isIpOrCoipRole((Document) holder.get("role"))) {
                return true;
            }
        }

        return false;
    }

    private boolean isIpOrCoipRole(Document roleDoc) {
        if (roleDoc == null) {
            return false;
        }

        List<String> roleTexts = new ArrayList<>();
        String termText = extractTextValue(roleDoc.get("term"));
        if (termText != null && !termText.isBlank()) {
            roleTexts.add(termText);
        }

        for (String key : List.of("ca_ES", "es_ES", "en_GB", "value")) {
            Object value = roleDoc.get(key);
            if (value instanceof String s && !s.isBlank()) {
                roleTexts.add(s);
            }
        }

        for (String raw : roleTexts) {
            String normalized = normalize(raw);
            boolean hasInvestigator = normalized.contains("investigador") || normalized.contains("investigator");
            boolean hasPrincipal = normalized.contains("principal");
            if (hasInvestigator && hasPrincipal) {
                return true;
            }
        }

        return false;
    }
}