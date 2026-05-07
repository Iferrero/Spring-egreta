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
@RequestMapping({"/api/student-theses", "/student-theses", "/otr/api/student-theses"})
@CrossOrigin(origins = "*")
public class StudentThesisController {

    private final StudentThesisRepository repository;
    private final MongoTemplate mongoTemplate;

    @Autowired
    public StudentThesisController(StudentThesisRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
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

    @GetMapping("/mismo-autor-director")
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
    @GetMapping("/stats/per-any-institut")
    public List<Map<String, Object>> tesisPerAnyInstitut(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "periode") String filtrePersonal) {

        // Step 1: resolve person UUIDs belonging to the institute
        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        Document assocCriteria;
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
            assocCriteria = conditions.size() == 1 ? conditions.get(0) : new Document("$and", conditions);
        } else {
            // vigent: endDate is null/missing or in the future
            LocalDate today = LocalDate.now();
            Date todayDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
            String todayIso = today.toString();
            assocCriteria = new Document("$or", List.of(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", List.of(
                    new Document("period.endDate", new Document("$type", 9)),
                    new Document("period.endDate", new Document("$gt", todayDate))
                )),
                new Document("$and", List.of(
                    new Document("period.endDate", new Document("$type", 2)),
                    new Document("period.endDate", new Document("$gt", todayIso))
                ))
            ));
        }

        Document elemMatch = new Document("$and", List.of(
            new Document("organization.uuid", orgUuid),
            assocCriteria
        ));

        List<Document> personDocs = mongoTemplate.getCollection("Persons")
            .find(new Document("staffOrganizationAssociations", new Document("$elemMatch", elemMatch)))
            .projection(new Document("uuid", 1).append("_id", 0))
            .into(new ArrayList<>());

        List<String> personUuids = personDocs.stream()
            .map(d -> d.getString("uuid"))
            .filter(v -> v != null && !v.isBlank())
            .distinct()
            .toList();

        if (personUuids.isEmpty()) {
            return List.of();
        }

        // Step 2: aggregate theses where any supervisor is in the person list, grouped by year
        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("$and", List.of(
            new Document("$or", List.of(
                new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
            )),
            new Document("supervisors.person.uuid", new Document("$in", personUuids))
        ))));

        if (desde != null || hasta != null) {
            Document yearRange = new Document();
            if (desde != null) yearRange.append("$gte", desde);
            if (hasta != null) yearRange.append("$lte", hasta);
            pipeline.add(new Document("$match", new Document("awardDate.year", yearRange)));
        }

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
     * Uses the same person/period resolution as per-any-institut.
     */
    @GetMapping("/stats/directors-institut")
    public List<Map<String, Object>> directorsInstitut(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "periode") String filtrePersonal) {

        // Reuse same person resolution logic
        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        Document assocCriteria;
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
            assocCriteria = conditions.size() == 1 ? conditions.get(0) : new Document("$and", conditions);
        } else {
            LocalDate today = LocalDate.now();
            Date todayDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
            String todayIso = today.toString();
            assocCriteria = new Document("$or", List.of(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", List.of(
                    new Document("period.endDate", new Document("$type", 9)),
                    new Document("period.endDate", new Document("$gt", todayDate))
                )),
                new Document("$and", List.of(
                    new Document("period.endDate", new Document("$type", 2)),
                    new Document("period.endDate", new Document("$gt", todayIso))
                ))
            ));
        }

        Document elemMatch = new Document("$and", List.of(
            new Document("organization.uuid", orgUuid),
            assocCriteria
        ));

        List<Document> personDocs = mongoTemplate.getCollection("Persons")
            .find(new Document("staffOrganizationAssociations", new Document("$elemMatch", elemMatch)))
            .projection(new Document("uuid", 1).append("_id", 0))
            .into(new ArrayList<>());

        List<String> personUuids = personDocs.stream()
            .map(d -> d.getString("uuid"))
            .filter(v -> v != null && !v.isBlank())
            .distinct()
            .toList();

        if (personUuids.isEmpty()) {
            return List.of();
        }

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("$and", List.of(
            new Document("$or", List.of(
                new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
            )),
            new Document("supervisors.person.uuid", new Document("$in", personUuids))
        ))));

        if (desde != null || hasta != null) {
            Document yearRange = new Document();
            if (desde != null) yearRange.append("$gte", desde);
            if (hasta != null) yearRange.append("$lte", hasta);
            pipeline.add(new Document("$match", new Document("awardDate.year", yearRange)));
        }

        // Unwind supervisors to group by each one individually
        pipeline.add(new Document("$unwind", "$supervisors"));

        // Only count supervisors that belong to the institute
        pipeline.add(new Document("$match", new Document("supervisors.person.uuid",
            new Document("$in", personUuids))));

        pipeline.add(new Document("$group", new Document("_id", "$supervisors.person.uuid")
            .append("lastName", new Document("$first", "$supervisors.name.lastName"))
            .append("firstName", new Document("$first", "$supervisors.name.firstName"))
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
     * Returns the full list of doctoral theses supervised by institute staff,
     * ordered by award year. Each item includes title, authors, directors and date.
     */
    @GetMapping("/stats/llista-institut")
    public List<Map<String, Object>> llistaTesisInstitut(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "periode") String filtrePersonal) {

        // Resolve persons belonging to the institute (same logic as other endpoints)
        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        Document assocCriteria;
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
            assocCriteria = conditions.size() == 1 ? conditions.get(0) : new Document("$and", conditions);
        } else {
            LocalDate today = LocalDate.now();
            Date todayDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
            String todayIso = today.toString();
            assocCriteria = new Document("$or", List.of(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", List.of(
                    new Document("period.endDate", new Document("$type", 9)),
                    new Document("period.endDate", new Document("$gt", todayDate))
                )),
                new Document("$and", List.of(
                    new Document("period.endDate", new Document("$type", 2)),
                    new Document("period.endDate", new Document("$gt", todayIso))
                ))
            ));
        }

        Document elemMatch = new Document("$and", List.of(
            new Document("organization.uuid", orgUuid),
            assocCriteria
        ));

        List<Document> personDocs = mongoTemplate.getCollection("Persons")
            .find(new Document("staffOrganizationAssociations", new Document("$elemMatch", elemMatch)))
            .projection(new Document("uuid", 1).append("_id", 0))
            .into(new ArrayList<>());

        List<String> personUuids = personDocs.stream()
            .map(d -> d.getString("uuid"))
            .filter(v -> v != null && !v.isBlank())
            .distinct()
            .toList();

        if (personUuids.isEmpty()) {
            return List.of();
        }

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("$and", List.of(
            new Document("$or", List.of(
                new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
            )),
            new Document("supervisors.person.uuid", new Document("$in", personUuids))
        ))));

        if (desde != null || hasta != null) {
            Document yearRange = new Document();
            if (desde != null) yearRange.append("$gte", desde);
            if (hasta != null) yearRange.append("$lte", hasta);
            pipeline.add(new Document("$match", new Document("awardDate.year", yearRange)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("_id", 0)
            .append("titol", "$title.value")
            .append("contributors", 1)
            .append("supervisors", 1)
            .append("any", "$awardDate.year")
            .append("mes", "$awardDate.month")
            .append("dia", "$awardDate.day")
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

            // Directors from supervisors — only institute persons
            List<String> directors = new ArrayList<>();
            List<String> directorUuids = new ArrayList<>();
            Object supObj = d.get("supervisors");
            if (supObj instanceof List<?> sups) {
                for (Object s : sups) {
                    if (s instanceof Document sd) {
                        Document personRef = sd.get("person", Document.class);
                        String supUuid = personRef != null ? personRef.getString("uuid") : null;
                        if (supUuid == null || !personUuids.contains(supUuid)) continue;
                        Document name = sd.get("name", Document.class);
                        if (name != null) {
                            String ln = name.getString("lastName");
                            String fn = name.getString("firstName");
                            if (ln != null && !ln.isBlank()) {
                                String initials = fn != null && !fn.isBlank()
                                    ? " " + fn.trim().charAt(0) + "."
                                    : "";
                                directors.add(ln.trim() + "," + initials);
                                directorUuids.add(supUuid);
                            }
                        }
                    }
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("titol", d.getString("titol"));
            row.put("autors", autors);
            row.put("directors", directors);
            row.put("directorUuids", directorUuids);
            row.put("any", d.get("any"));
            row.put("mes", d.get("mes"));
            row.put("dia", d.get("dia"));
            result.add(row);
        }
        return result;
    }
}
