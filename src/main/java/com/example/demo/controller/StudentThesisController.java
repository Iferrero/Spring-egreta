package com.example.demo.controller;

import com.example.demo.model.StudentThesis;
import com.example.demo.repository.StudentThesisRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/student-theses")
@CrossOrigin(origins = "*")
public class StudentThesisController {

    private final StudentThesisRepository repository;
    private final MongoTemplate mongoTemplate;

    @Autowired
    public StudentThesisController(StudentThesisRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    private String normalizeSupervisorRole(Document supervisorDoc) {
        if (supervisorDoc == null) return "Director/a";
        Document role = supervisorDoc.get("role", Document.class);
        Document term = role != null ? role.get("term", Document.class) : null;
        String roleText = null;
        if (term != null) {
            roleText = term.getString("ca_ES");
            if (roleText == null || roleText.isBlank()) roleText = term.getString("es_ES");
            if (roleText == null || roleText.isBlank()) roleText = term.getString("en_GB");
        }
        if (roleText == null || roleText.isBlank()) return "Director/a";

        String normalized = roleText.trim().toLowerCase();
        if (normalized.contains("tutor")) return "Tutor/a";
        return "Director/a";
    }

    @GetMapping
    public Page<StudentThesis> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "awardDate.year"));

        if (year != null) {
                        return repository.findDoctoralByAwardDateYear(year, pageable);
        }

        if (buscar == null || buscar.isBlank()) {
                        return repository.findDoctoral(pageable);
        }

                return repository.findDoctoralByTitleContainingIgnoreCase(buscar, pageable);
    }

    @GetMapping("/stats/same-author-director")
    public List<Document> mismoAutorDirector(
            @RequestParam(defaultValue = "2") int minCoincidencias,
            @RequestParam(defaultValue = "0") int limit) {

        int min = Math.max(1, minCoincidencias);
        int max = Math.max(0, limit);

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("$or", List.of(
                new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
        ))));

        Document autorNombre = new Document("$trim", new Document("input", new Document("$concat", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$$c.name.lastName", "")), ", ",
                new Document("$ifNull", Arrays.asList("$$c.name.firstName", ""))
        ))).append("chars", " ,"));

        Document autorUuid = new Document("$trim", new Document("input", new Document("$ifNull", Arrays.asList(
                "$$c.person.uuid",
                new Document("$ifNull", Arrays.asList("$$c.externalPerson.uuid", ""))
        ))));

        Document autorObj = new Document("nombre", autorNombre)
                .append("uuid", autorUuid);

        Document autoresExpr = new Document("$map", new Document()
                .append("input", new Document("$ifNull", Arrays.asList("$contributors", List.of())))
                .append("as", "c")
                .append("in", autorObj));

        Document addFieldsStage = new Document("$addFields",
                new Document("autores", autoresExpr));
        pipeline.add(addFieldsStage);

        Document projectStage = new Document("$project",
                new Document("uuid", "$uuid")
                        .append("titulo", "$title.value")
                        .append("anio", "$awardDate.year")
                        .append("autores", 1));
        pipeline.add(projectStage);

        pipeline.add(new Document("$unwind", "$autores"));

        pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(
                new Document("$strLenCP", new Document("$trim", new Document("input", "$autores.nombre"))), 0
        )))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("autor", "$autores.nombre")
                .append("autorUuid", "$autores.uuid")
                .append("uuid", "$uuid"))
                .append("titulo", new Document("$first", "$titulo"))
                .append("anio", new Document("$first", "$anio"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("autor", "$_id.autor")
                .append("autorUuid", "$_id.autorUuid"))
                .append("totalTesis", new Document("$sum", 1))
                .append("tesis", new Document("$addToSet", new Document()
                        .append("uuid", "$_id.uuid")
                        .append("titulo", "$titulo")
                        .append("anio", "$anio")))));

        pipeline.add(new Document("$match", new Document("totalTesis", new Document("$gte", min))));

        pipeline.add(new Document("$project", new Document("_id", 0)
                .append("autor", "$_id.autor")
                .append("autorUuid", "$_id.autorUuid")
                .append("totalTesis", 1)
                .append("tesis", 1)));

        pipeline.add(new Document("$sort", new Document("totalTesis", -1)
                .append("autor", 1)));

        if (max > 0) {
            pipeline.add(new Document("$limit", max));
        }

        return mongoTemplate
                .getCollection("StudentTheses")
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }

    /**
     * Returns thesis counts per year for supervisors belonging to a given institute.
     * Filters persons by active membership (vigent) or membership overlapping the period (periode).
     */
    @GetMapping("/stats/per-year-institute")
    public List<Map<String, Object>> tesisPerAnyInstitut(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "periode") String filtrePersonal) {

        List<Document> pipeline = new ArrayList<>();

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("$or", List.of(
            new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
            new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
            new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
        )));

        if (orgUuid != null && !orgUuid.isBlank() && !"all".equalsIgnoreCase(orgUuid)) {
            matchConditions.add(new Document("managingOrganization.uuid", orgUuid));
        }

        if (desde != null || hasta != null) {
            Document yearRange = new Document();
            if (desde != null) yearRange.append("$gte", desde);
            if (hasta != null) yearRange.append("$lte", hasta);
            matchConditions.add(new Document("awardDate.year", yearRange));
        }

        pipeline.add(new Document("$match", new Document("$and", matchConditions)));

        // Group by year and count theses
        pipeline.add(new Document("$group", new Document("_id", "$awardDate.year")
            .append("tesis", new Document("$sum", 1))));

        pipeline.add(new Document("$sort", new Document("_id", 1)));

        List<Document> raw = mongoTemplate.getCollection("StudentTheses")
            .aggregate(pipeline)
            .into(new ArrayList<>());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document d : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("any", d.get("_id"));
            row.put("tesis", d.getInteger("tesis", 0));
            result.add(row);
        }
        return result;
    }

    /**
     * Returns thesis counts per supervisor (director) for a given institute.
     */
    @GetMapping("/stats/directors-institut")
    public List<Map<String, Object>> directorsInstitut(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "periode") String filtrePersonal) {

        List<Document> pipeline = new ArrayList<>();

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("$or", List.of(
            new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
            new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
            new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
        )));

        if (orgUuid != null && !orgUuid.isBlank() && !"all".equalsIgnoreCase(orgUuid)) {
            matchConditions.add(new Document("managingOrganization.uuid", orgUuid));
        }

        if (desde != null || hasta != null) {
            Document yearRange = new Document();
            if (desde != null) yearRange.append("$gte", desde);
            if (hasta != null) yearRange.append("$lte", hasta);
            matchConditions.add(new Document("awardDate.year", yearRange));
        }

        pipeline.add(new Document("$match", new Document("$and", matchConditions)));

        // Unwind supervisors to group by each one
        pipeline.add(new Document("$unwind", "$supervisors"));

        // Get supervisor role text
        pipeline.add(new Document("$addFields", new Document("supervisorRoleText",
            new Document("$toLower", new Document("$ifNull", Arrays.asList(
                "$supervisors.role.term.ca_ES",
                new Document("$ifNull", Arrays.asList(
                    "$supervisors.role.term.es_ES",
                    new Document("$ifNull", Arrays.asList("$supervisors.role.term.en_GB", ""))
                ))
            )))
        )));
        
        // Exclude tutors
        pipeline.add(new Document("$match", new Document("supervisorRoleText",
            new Document("$not", new Document("$regex", "tutor")))));

        // Create a unique supervisor identifier (using person uuid, external person uuid, or name)
        pipeline.add(new Document("$addFields", new Document("supervisorUuid",
            new Document("$ifNull", Arrays.asList(
                "$supervisors.person.uuid",
                new Document("$ifNull", Arrays.asList(
                    "$supervisors.externalPerson.uuid",
                    new Document("$concat", Arrays.asList("$supervisors.name.lastName", ", ", "$supervisors.name.firstName"))
                ))
            ))
        )));

        // Dedupe thesis-supervisor pairs
        pipeline.add(new Document("$group", new Document("_id", new Document()
            .append("supervisorUuid", "$supervisorUuid")
            .append("thesisUuid", "$uuid"))
            .append("lastName", new Document("$first", "$supervisors.name.lastName"))
            .append("firstName", new Document("$first", "$supervisors.name.firstName"))));

        pipeline.add(new Document("$group", new Document("_id", "$_id.supervisorUuid")
            .append("lastName", new Document("$first", "$lastName"))
            .append("firstName", new Document("$first", "$firstName"))
            .append("tesis", new Document("$sum", 1))));

        pipeline.add(new Document("$sort", new Document("lastName", 1).append("firstName", 1)));

        List<Document> raw = mongoTemplate.getCollection("StudentTheses")
            .aggregate(pipeline)
            .into(new ArrayList<>());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document d : raw) {
            String uuid = d.getString("_id");
            String lastName = d.getString("lastName");
            String firstName = d.getString("firstName");
            String nom = (lastName != null && !lastName.isBlank())
                ? lastName.trim() + (firstName != null && !firstName.isBlank() ? ", " + firstName.trim() : "")
                : (firstName != null ? firstName : "-");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuid);
            row.put("nom", nom);
            row.put("tesis", d.getInteger("tesis", 0));
            result.add(row);
        }
        return result;
    }

    /**
     * Returns the full list of doctoral theses, ordered by award year.
     * Each item includes title, authors, directors and date.
     */
    @GetMapping("/stats/list-institute")
    public List<Map<String, Object>> llistaTesisInstitut(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "periode") String filtrePersonal) {

        List<Document> pipeline = new ArrayList<>();

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("$or", List.of(
            new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
            new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
            new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
        )));

        if (orgUuid != null && !orgUuid.isBlank() && !"all".equalsIgnoreCase(orgUuid)) {
            matchConditions.add(new Document("managingOrganization.uuid", orgUuid));
        }

        if (desde != null || hasta != null) {
            Document yearRange = new Document();
            if (desde != null) yearRange.append("$gte", desde);
            if (hasta != null) yearRange.append("$lte", hasta);
            matchConditions.add(new Document("awardDate.year", yearRange));
        }

        pipeline.add(new Document("$match", new Document("$and", matchConditions)));

        pipeline.add(new Document("$unwind", "$supervisors"));

        pipeline.add(new Document("$addFields", new Document("supervisorRoleText",
            new Document("$toLower", new Document("$ifNull", Arrays.asList(
                "$supervisors.role.term.ca_ES",
                new Document("$ifNull", Arrays.asList(
                    "$supervisors.role.term.es_ES",
                    new Document("$ifNull", Arrays.asList("$supervisors.role.term.en_GB", ""))
                ))
            )))
        )));
        
        // Exclude tutors
        pipeline.add(new Document("$match", new Document("supervisorRoleText",
            new Document("$not", new Document("$regex", "tutor")))));

        // Look up gender of supervisors from Persons
        pipeline.add(new Document("$lookup", new Document()
            .append("from", "Persons")
            .append("localField", "supervisors.person.uuid")
            .append("foreignField", "uuid")
            .append("as", "personInfo")));
        pipeline.add(new Document("$unwind", new Document()
            .append("path", "$personInfo")
            .append("preserveNullAndEmptyArrays", true)));

        // Group by thesis uuid to compile list of supervisors and genders
        pipeline.add(new Document("$group", new Document("_id", "$uuid")
            .append("titol", new Document("$first", "$title.value"))
            .append("contributors", new Document("$first", "$contributors"))
            .append("supervisors", new Document("$addToSet", "$supervisors"))
            .append("supervisorGenders", new Document("$addToSet", "$personInfo.gender.term.ca_ES"))
            .append("any", new Document("$first", "$awardDate.year"))
            .append("mes", new Document("$first", "$awardDate.month"))
            .append("dia", new Document("$first", "$awardDate.day"))
        ));

        pipeline.add(new Document("$project", new Document()
            .append("_id", 0)
            .append("titol", 1)
            .append("contributors", 1)
            .append("supervisors", 1)
            .append("supervisorGenders", 1)
            .append("any", 1)
            .append("mes", 1)
            .append("dia", 1)
        ));

        pipeline.add(new Document("$sort", new Document("any", 1).append("mes", 1).append("dia", 1)));

        List<Document> raw = mongoTemplate.getCollection("StudentTheses")
            .aggregate(pipeline)
            .into(new ArrayList<>());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document d : raw) {
            // Authors from contributors
            List<String> autors = new ArrayList<>();
            Object contribObj = d.get("contributors");
            if (contribObj instanceof List<?> contribs) {
                for (Object c : contribs) {
                    if (c instanceof Document cd) {
                        Document name = cd.get("name", Document.class);
                        if (name != null) {
                            String ln = name.getString("lastName");
                            String fn = name.getString("firstName");
                            if (ln != null && !ln.isBlank()) {
                                String initials = fn != null && !fn.isBlank()
                                    ? " " + fn.trim().charAt(0) + "."
                                    : "";
                                autors.add(ln.trim() + "," + initials);
                            }
                        }
                    }
                }
            }

            // Directors from supervisors
            List<String> directors = new ArrayList<>();
            List<String> directorUuids = new ArrayList<>();
            List<String> supervisorRoles = new ArrayList<>();
            Object supObj = d.get("supervisors");
            if (supObj instanceof List<?> sups) {
                for (Object s : sups) {
                    if (s instanceof Document sd) {
                        Document personRef = sd.get("person", Document.class);
                        String supUuid = personRef != null ? personRef.getString("uuid") : null;
                        if (supUuid == null || supUuid.isBlank()) {
                            Document extRef = sd.get("externalPerson", Document.class);
                            supUuid = extRef != null ? extRef.getString("uuid") : null;
                        }
                        Document name = sd.get("name", Document.class);
                        if (name != null) {
                            String ln = name.getString("lastName");
                            String fn = name.getString("firstName");
                            if (ln != null && !ln.isBlank()) {
                                String initials = fn != null && !fn.isBlank()
                                    ? " " + fn.trim().charAt(0) + "."
                                    : "";
                                directors.add(ln.trim() + "," + initials);
                                directorUuids.add(supUuid != null ? supUuid : "");
                                supervisorRoles.add(normalizeSupervisorRole(sd));
                            }
                        }
                    }
                }
            }

            // Genders from personInfo
            List<String> genders = new ArrayList<>();
            Object gendersObj = d.get("supervisorGenders");
            if (gendersObj instanceof List<?> gList) {
                for (Object g : gList) {
                    if (g instanceof String genderStr) {
                        genders.add(genderStr);
                    }
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("titol", d.getString("titol"));
            row.put("autors", autors);
            row.put("directors", directors);
            row.put("directorUuids", directorUuids);
            row.put("supervisorRoles", supervisorRoles);
            row.put("supervisorGenders", genders);
            row.put("any", d.get("any"));
            row.put("mes", d.get("mes"));
            row.put("dia", d.get("dia"));
            result.add(row);
        }
        return result;
    }

    @GetMapping("/stats/gender-evolution")
    public Map<String, Object> getGenderEvolution(
            @RequestParam(required = false) String orgUuid) {

        List<Document> pipeline = new ArrayList<>();

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("$or", List.of(
            new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
            new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
            new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
        )));

        if (orgUuid != null && !orgUuid.isBlank() && !"all".equalsIgnoreCase(orgUuid)) {
            matchConditions.add(new Document("managingOrganization.uuid", orgUuid));
        }

        pipeline.add(new Document("$match", new Document("$and", matchConditions)));

        // Unwind supervisors to look up genders
        pipeline.add(new Document("$unwind", new Document()
            .append("path", "$supervisors")
            .append("preserveNullAndEmptyArrays", true)));

        // Exclude tutors
        pipeline.add(new Document("$addFields", new Document("supervisorRoleText",
            new Document("$toLower", new Document("$ifNull", Arrays.asList(
                "$supervisors.role.term.ca_ES",
                new Document("$ifNull", Arrays.asList(
                    "$supervisors.role.term.es_ES",
                    new Document("$ifNull", Arrays.asList("$supervisors.role.term.en_GB", ""))
                ))
            )))
        )));
        pipeline.add(new Document("$match", new Document("$or", Arrays.asList(
            new Document("supervisors", null),
            new Document("supervisorRoleText", new Document("$not", new Document("$regex", "tutor")))
        ))));

        // Lookup supervisor details from Persons
        pipeline.add(new Document("$lookup", new Document()
            .append("from", "Persons")
            .append("localField", "supervisors.person.uuid")
            .append("foreignField", "uuid")
            .append("as", "personInfo")));
        pipeline.add(new Document("$unwind", new Document()
            .append("path", "$personInfo")
            .append("preserveNullAndEmptyArrays", true)));

        // Lookup department/managingOrganization details from Organizations
        pipeline.add(new Document("$lookup", new Document()
            .append("from", "Organizations")
            .append("localField", "managingOrganization.uuid")
            .append("foreignField", "uuid")
            .append("as", "orgInfo")));
        pipeline.add(new Document("$unwind", new Document()
            .append("path", "$orgInfo")
            .append("preserveNullAndEmptyArrays", true)));

        // Group back by thesis uuid to aggregate supervisors
        pipeline.add(new Document("$group", new Document("_id", "$uuid")
            .append("title", new Document("$first", "$title.value"))
            .append("year", new Document("$first", "$awardDate.year"))
            .append("managingOrgUuid", new Document("$first", "$managingOrganization.uuid"))
            .append("managingOrgName", new Document("$first", "$orgInfo.name"))
            .append("contributors", new Document("$first", "$contributors"))
            .append("supervisors", new Document("$push", new Document()
                .append("uuid", "$supervisors.person.uuid")
                .append("externalUuid", "$supervisors.externalPerson.uuid")
                .append("firstName", "$supervisors.name.firstName")
                .append("lastName", "$supervisors.name.lastName")
                .append("gender", "$personInfo.gender")
                .append("sex", "$personInfo.sex")
                .append("roleUri", "$supervisors.role.uri")
            ))
        ));

        List<Document> rawTheses = mongoTemplate.getCollection("StudentTheses")
            .aggregate(pipeline)
            .into(new ArrayList<>());

        // Collect all unique author UUIDs
        java.util.Set<String> authorUuids = new java.util.HashSet<>();
        for (Document d : rawTheses) {
            Object contribs = d.get("contributors");
            if (contribs instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Document doc) {
                        Document personDoc = doc.get("person", Document.class);
                        String authorUuid = personDoc != null ? personDoc.getString("uuid") : null;
                        if (authorUuid != null && !authorUuid.isBlank()) {
                            authorUuids.add(authorUuid);
                        }
                    }
                }
            }
        }

        // Query Awards collection for validated scholarships held by these authors
        java.util.Map<String, String> authorAwards = new java.util.HashMap<>();
        if (!authorUuids.isEmpty()) {
            List<Document> awardPipeline = new ArrayList<>();
            awardPipeline.add(new Document("$match", new Document("workflow.step", "validated")
                .append("awardHolders.person.uuid", new Document("$in", new ArrayList<>(authorUuids)))));
            awardPipeline.add(new Document("$project", new Document("type", 1)
                .append("title", 1)
                .append("applications", 1)
                .append("holderUuids", "$awardHolders.person.uuid")));

            List<Document> matchedAwards = mongoTemplate.getCollection("Awards")
                .aggregate(awardPipeline)
                .into(new ArrayList<>());

            java.util.Map<String, List<String>> appUuidToAuthorUuids = new java.util.HashMap<>();
            java.util.Set<String> appUuids = new java.util.HashSet<>();

            for (Document award : matchedAwards) {
                Document typeDoc = award.get("type", Document.class);
                Document termDoc = typeDoc != null ? typeDoc.get("term", Document.class) : null;
                String typeText = null;
                if (termDoc != null) {
                    typeText = termDoc.getString("ca_ES");
                    if (typeText == null) typeText = termDoc.getString("es_ES");
                    if (typeText == null) typeText = termDoc.getString("en_GB");
                }
                
                if (typeText != null && (typeText.contains("Beques") || typeText.contains("Becas") || typeText.contains("Fellowship"))) {
                    List<?> holdersList = award.get("holderUuids", List.class);
                    List<String> awardAuthors = new ArrayList<>();
                    if (holdersList != null) {
                        for (Object hObj : holdersList) {
                            if (hObj instanceof String h && !h.isBlank() && authorUuids.contains(h)) {
                                awardAuthors.add(h);
                            }
                        }
                    }
                    
                    if (!awardAuthors.isEmpty()) {
                        List<?> appsList = award.get("applications", List.class);
                        if (appsList != null) {
                            for (Object appObj : appsList) {
                                if (appObj instanceof Document appDoc) {
                                    String appUuid = appDoc.getString("uuid");
                                    if (appUuid != null && !appUuid.isBlank()) {
                                        appUuids.add(appUuid);
                                        appUuidToAuthorUuids.computeIfAbsent(appUuid, k -> new ArrayList<>()).addAll(awardAuthors);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Resolve Applications to FundingOpportunities
            java.util.Map<String, String> appUuidToFoUuid = new java.util.HashMap<>();
            java.util.Set<String> foUuids = new java.util.HashSet<>();
            if (!appUuids.isEmpty()) {
                List<Document> matchedApps = mongoTemplate.getCollection("Applications")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(appUuids))))
                    .projection(new Document("uuid", 1).append("fundingOpportunity.uuid", 1))
                    .into(new ArrayList<>());
                
                for (Document appDoc : matchedApps) {
                    String appUuid = appDoc.getString("uuid");
                    Document foDoc = appDoc.get("fundingOpportunity", Document.class);
                    String foUuid = foDoc != null ? foDoc.getString("uuid") : null;
                    if (appUuid != null && foUuid != null && !foUuid.isBlank()) {
                        appUuidToFoUuid.put(appUuid, foUuid);
                        foUuids.add(foUuid);
                    }
                }
            }

            // Resolve FundingOpportunities to Type Names
            java.util.Map<String, String> foUuidToName = new java.util.HashMap<>();
            if (!foUuids.isEmpty()) {
                List<Document> matchedFos = mongoTemplate.getCollection("FundingOpportunities")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(foUuids))))
                    .projection(new Document("uuid", 1).append("title", 1).append("type", 1))
                    .into(new ArrayList<>());
                
                for (Document foDoc : matchedFos) {
                    String foUuid = foDoc.getString("uuid");
                    if (foUuid == null) continue;
                    
                    Document typeDoc = foDoc.get("type", Document.class);
                    Document termDoc = typeDoc != null ? typeDoc.get("term", Document.class) : null;
                    String foName = extractLocalizedTextFromList(termDoc);
                    
                    if (foName == null || foName.isBlank()) {
                        Document titleDoc = foDoc.get("title", Document.class);
                        foName = extractLocalizedTextFromList(titleDoc);
                    }
                    
                    if (foName != null && !foName.isBlank()) {
                        foUuidToName.put(foUuid, foName);
                    }
                }
            }

            // Map authors to resolved FundingOpportunity type/name
            for (Map.Entry<String, List<String>> entry : appUuidToAuthorUuids.entrySet()) {
                String appUuid = entry.getKey();
                String foUuid = appUuidToFoUuid.get(appUuid);
                if (foUuid != null) {
                    String foName = foUuidToName.get(foUuid);
                    if (foName != null) {
                        for (String authorUuid : entry.getValue()) {
                            authorAwards.put(authorUuid, foName);
                        }
                    }
                }
            }

            // Fallback: if an author has a scholarship but no resolved FundingOpportunity, use Award title
            for (Document award : matchedAwards) {
                Document typeDoc = award.get("type", Document.class);
                Document termDoc = typeDoc != null ? typeDoc.get("term", Document.class) : null;
                String typeText = null;
                if (termDoc != null) {
                    typeText = termDoc.getString("ca_ES");
                    if (typeText == null) typeText = termDoc.getString("es_ES");
                    if (typeText == null) typeText = termDoc.getString("en_GB");
                }
                
                if (typeText != null && (typeText.contains("Beques") || typeText.contains("Becas") || typeText.contains("Fellowship"))) {
                    List<?> holdersList = award.get("holderUuids", List.class);
                    if (holdersList != null) {
                        for (Object hObj : holdersList) {
                            if (hObj instanceof String h && !h.isBlank() && authorUuids.contains(h)) {
                                if (!authorAwards.containsKey(h)) {
                                    Document titleDoc = award.get("title", Document.class);
                                    String awardTitle = null;
                                    if (titleDoc != null) {
                                        awardTitle = titleDoc.getString("ca_ES");
                                        if (awardTitle == null || awardTitle.isBlank()) awardTitle = titleDoc.getString("es_ES");
                                        if (awardTitle == null || awardTitle.isBlank()) awardTitle = titleDoc.getString("en_GB");
                                        if (awardTitle == null || awardTitle.isBlank()) awardTitle = titleDoc.getString("value");
                                    }
                                    if (awardTitle == null || awardTitle.isBlank()) {
                                        awardTitle = "Beca";
                                    }
                                    authorAwards.put(h, awardTitle);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Process results
        java.util.Map<Integer, java.util.Map<String, Integer>> yearlyStats = new java.util.TreeMap<>();
        List<Map<String, Object>> femaleThesesList = new ArrayList<>();
        java.util.Map<String, Map<String, Object>> activeFemaleDirectors = new java.util.HashMap<>();

        for (Document d : rawTheses) {
            Integer year = d.getInteger("year");
            if (year == null) {
                continue; // Skip or group as unknown
            }

            // Grouping by department name
            Document orgNameDoc = d.get("managingOrgName", Document.class);
            String deptName = "Desconegut";
            if (orgNameDoc != null) {
                deptName = orgNameDoc.getString("ca_ES");
                if (deptName == null || deptName.isBlank()) deptName = orgNameDoc.getString("es_ES");
                if (deptName == null || deptName.isBlank()) deptName = orgNameDoc.getString("en_GB");
            }

            // Authors/Contributors
            List<String> authors = new ArrayList<>();
            Object contribs = d.get("contributors");
            if (contribs instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Document doc) {
                        Document nameDoc = doc.get("name", Document.class);
                        if (nameDoc != null) {
                            String ln = nameDoc.getString("lastName");
                            String fn = nameDoc.getString("firstName");
                            if (ln != null && !ln.isBlank()) {
                                String initials = (fn != null && !fn.isBlank()) ? " " + fn.trim().charAt(0) + "." : "";
                                authors.add(ln.trim() + "," + initials);
                            }
                        }
                    }
                }
            }
            String authorStr = String.join(" | ", authors);

            // Check if author has scholarship (beca)
            boolean authorHasScholarship = false;
            String scholarshipName = null;
            if (contribs instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Document doc) {
                        Document personDoc = doc.get("person", Document.class);
                        String authorUuid = personDoc != null ? personDoc.getString("uuid") : null;
                        if (authorUuid != null && authorAwards.containsKey(authorUuid)) {
                            authorHasScholarship = true;
                            scholarshipName = authorAwards.get(authorUuid);
                            break;
                        }
                    }
                }
            }

            // Supervisors
            List<Document> sups = d.getList("supervisors", Document.class);
            boolean hasFemale = false;
            boolean hasMale = false;
            boolean femaleIsDirector = false;
            boolean femaleIsCodirector = false;
            List<String> femaleDirectorsInThesis = new ArrayList<>();
            List<String> allDirectorsInThesis = new ArrayList<>();

            if (sups != null) {
                for (Document s : sups) {
                    // Skip if supervisors name is empty (could be the case with outer-join nulls)
                    String ln = s.getString("lastName");
                    String fn = s.getString("firstName");
                    if ((ln == null || ln.isBlank()) && (fn == null || fn.isBlank())) {
                        continue;
                    }
                    String supName = (ln != null ? ln.trim() : "") + (fn != null ? ", " + fn.trim() : "");

                    String genderRaw = extractGender(s);
                    String genderClass = classifyGender(genderRaw);
                    String roleUri = s.getString("roleUri");

                    if ("female".equals(genderClass)) {
                        hasFemale = true;
                        femaleDirectorsInThesis.add(supName);
                        if (roleUri != null && roleUri.contains("/codirector")) {
                            femaleIsCodirector = true;
                        } else {
                            femaleIsDirector = true;
                        }
                    } else if ("male".equals(genderClass)) {
                        hasMale = true;
                    }
                    allDirectorsInThesis.add(supName);

                    // Track active female directors
                    if ("female".equals(genderClass)) {
                        String supUuid = s.getString("uuid");
                        if (supUuid == null || supUuid.isBlank()) {
                            supUuid = s.getString("externalUuid");
                        }
                        if (supUuid == null || supUuid.isBlank()) {
                            supUuid = supName; // Fallback to name
                        }

                        if (!supUuid.isBlank()) {
                            Map<String, Object> dirData = activeFemaleDirectors.computeIfAbsent(supUuid, k -> {
                                Map<String, Object> map = new LinkedHashMap<>();
                                map.put("uuid", k);
                                map.put("nom", supName);
                                map.put("tesisCount", 0);
                                map.put("departaments", new java.util.HashSet<String>());
                                return map;
                            });
                            dirData.put("tesisCount", (int) dirData.get("tesisCount") + 1);
                            ((java.util.Set<String>) dirData.get("departaments")).add(deptName);
                        }
                    }
                }
            }

            // Classify thesis
            String thesisClassification;
            if (hasFemale) {
                if (femaleIsDirector) {
                    thesisClassification = "directedFemale";
                } else {
                    thesisClassification = "codirectedFemale";
                }
            } else if (hasMale) {
                thesisClassification = "maleOnly";
            } else {
                thesisClassification = "unknown";
            }

            // Increment yearly stats
            java.util.Map<String, Integer> stats = yearlyStats.computeIfAbsent(year, k -> {
                java.util.Map<String, Integer> map = new java.util.HashMap<>();
                map.put("total", 0);
                map.put("female", 0);
                map.put("directedFemale", 0);
                map.put("codirectedFemale", 0);
                map.put("maleOnly", 0);
                map.put("unknown", 0);
                map.put("withScholarship", 0);
                return map;
            });
            stats.put("total", stats.get("total") + 1);
            stats.put(thesisClassification, stats.get(thesisClassification) + 1);
            if (hasFemale) {
                stats.put("female", stats.get("female") + 1);
            }
            if (authorHasScholarship) {
                stats.put("withScholarship", stats.get("withScholarship") + 1);
                String classificationScholarshipKey = thesisClassification + "WithScholarship";
                stats.put(classificationScholarshipKey, stats.getOrDefault(classificationScholarshipKey, 0) + 1);
                
                String scCode = extractScholarshipCode(scholarshipName);
                if (scCode != null && !scCode.isBlank()) {
                    stats.put(scCode, stats.getOrDefault(scCode, 0) + 1);
                }
            }

            // If directed/co-directed by female, add to detailed list
            if (hasFemale) {
                Map<String, Object> ft = new LinkedHashMap<>();
                ft.put("uuid", d.getString("_id"));
                ft.put("titol", d.getString("title"));
                ft.put("autor", authorStr);
                ft.put("any", year);
                ft.put("departament", deptName);
                ft.put("directores", femaleDirectorsInThesis);
                ft.put("totsDirectors", allDirectorsInThesis);
                ft.put("teBeca", authorHasScholarship);
                ft.put("becaTitol", scholarshipName != null ? scholarshipName : "");
                ft.put("becaCodi", scholarshipName != null ? extractScholarshipCode(scholarshipName) : "");
                ft.put("tipusDireccio", femaleIsDirector ? "Dirigida" : "Codirigida");
                femaleThesesList.add(ft);
            }
        }

        // Format evolution list
        List<Map<String, Object>> evolutionList = new ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.Map<String, Integer>> entry : yearlyStats.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("year", entry.getKey());
            point.put("total", entry.getValue().get("total"));
            point.put("female", entry.getValue().get("female"));
            point.put("directedFemale", entry.getValue().get("directedFemale"));
            point.put("codirectedFemale", entry.getValue().get("codirectedFemale"));
            point.put("maleOnly", entry.getValue().get("maleOnly"));
            point.put("unknown", entry.getValue().get("unknown"));
            point.put("withScholarship", entry.getValue().get("withScholarship"));
            point.put("maleOnlyWithScholarship", entry.getValue().getOrDefault("maleOnlyWithScholarship", 0));
            point.put("directedFemaleWithScholarship", entry.getValue().getOrDefault("directedFemaleWithScholarship", 0));
            point.put("codirectedFemaleWithScholarship", entry.getValue().getOrDefault("codirectedFemaleWithScholarship", 0));
            point.put("unknownWithScholarship", entry.getValue().getOrDefault("unknownWithScholarship", 0));
            
            // Add all other keys representing scholarship codes
            for (Map.Entry<String, Integer> subEntry : entry.getValue().entrySet()) {
                String key = subEntry.getKey();
                if (!Arrays.asList("total", "female", "directedFemale", "codirectedFemale", "maleOnly", "unknown", "withScholarship", "maleOnlyWithScholarship", "directedFemaleWithScholarship", "codirectedFemaleWithScholarship", "unknownWithScholarship").contains(key)) {
                    point.put(key, subEntry.getValue());
                }
            }
            evolutionList.add(point);
        }

        // Format active female directors list and sort by count desc
        List<Map<String, Object>> activeDirectorsList = new ArrayList<>();
        for (Map<String, Object> val : activeFemaleDirectors.values()) {
            java.util.Set<String> depts = (java.util.Set<String>) val.get("departaments");
            val.put("departament", String.join(", ", depts));
            val.remove("departaments");
            activeDirectorsList.add(val);
        }
        activeDirectorsList.sort((a, b) -> Integer.compare((int) b.get("tesisCount"), (int) a.get("tesisCount")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("evolution", evolutionList);
        response.put("femaleTheses", femaleThesesList);
        response.put("activeFemaleDirectors", activeDirectorsList);
        return response;
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
        if (value instanceof Map<?, ?> map) {
            Object textVal = map.get("value");
            if (textVal instanceof String str && !str.isBlank()) {
                return str;
            }
            for (String key : Arrays.asList("ca_ES", "es_ES", "en_GB")) {
                Object v = map.get(key);
                if (v instanceof String str2 && !str2.isBlank()) {
                    return str2;
                }
            }
        }
        return value.toString();
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

    private String extractLocalizedTextFromList(Document doc) {
        if (doc == null) return null;
        Object textObj = doc.get("text");
        if (textObj instanceof List<?> list) {
            String caVal = null;
            String esVal = null;
            String enVal = null;
            for (Object item : list) {
                if (item instanceof Document textDoc) {
                    String locale = textDoc.getString("locale");
                    String value = textDoc.getString("value");
                    if (value != null && !value.isBlank()) {
                        if ("ca_ES".equals(locale)) caVal = value;
                        else if ("es_ES".equals(locale)) esVal = value;
                        else if ("en_GB".equals(locale)) enVal = value;
                    }
                }
            }
            if (caVal != null) return caVal;
            if (esVal != null) return esVal;
            if (enVal != null) return enVal;
        }
        return null;
    }

    private String extractScholarshipCode(String foName) {
        if (foName == null || foName.isBlank()) {
            return "Sense Beca";
        }
        int idx = foName.indexOf('_');
        if (idx > 0) {
            return foName.substring(0, idx).trim();
        }
        if (foName.length() <= 5) {
            return foName;
        }
        int spaceIdx = foName.indexOf(' ');
        if (spaceIdx > 0) {
            String firstWord = foName.substring(0, spaceIdx).trim();
            if (firstWord.length() <= 5) {
                return firstWord;
            }
        }
        return "Altres";
    }
}
