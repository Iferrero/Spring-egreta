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
}
