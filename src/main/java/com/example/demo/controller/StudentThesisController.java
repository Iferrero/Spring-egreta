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

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

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
            @RequestParam(required = false) String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String genderDirector,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        FilteredResult result = getFilteredTheses(buscar, orgUuid, desde, hasta, genderDirector, page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "awardDate.year"));
        return org.springframework.data.support.PageableExecutionUtils.getPage(result.content, pageable, () -> result.total);
    }

    @GetMapping("/export")
    public void exportarExcel(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(required = false) String orgUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String genderDirector,
            HttpServletResponse response) throws IOException {

        FilteredResult result = getFilteredTheses(buscar, orgUuid, desde, hasta, genderDirector, null, null);
        List<StudentThesis> theses = result.content;

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=tesis_doctorales.xlsx");

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Tesis Doctorales");

            // Styles
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.LEFT);

            // Columns headers
            String[] headers = {"Año", "Autor/a", "Título", "Beca", "Directores"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (StudentThesis t : theses) {
                Row row = sheet.createRow(rowNum++);

                // Año
                row.createCell(0).setCellValue(t.getYear() != null ? t.getYear() : 0);

                // Autor/a
                row.createCell(1).setCellValue(t.getAuthorsNames());

                // Título
                row.createCell(2).setCellValue(t.getFullTitle());

                // Beca
                row.createCell(3).setCellValue(t.getBecaTitol() != null ? t.getBecaTitol() : "-");

                // Directores
                row.createCell(4).setCellValue(formatSupervisorsForExcel(t.getSupervisors()));
            }

            // Adjust column widths (autoSize)
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }

    @GetMapping("/diagnose")
    public Map<String, Object> diagnoseDb() {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> corruptTheses = new ArrayList<>();
        List<String> corruptAwards = new ArrayList<>();

        // Fetch all IDs using projection first
        List<String> thesisIds = new ArrayList<>();
        try {
            for (Document doc : mongoTemplate.getCollection("StudentTheses").find().projection(new Document("_id", 1))) {
                Object idObj = doc.get("_id");
                if (idObj != null) {
                    thesisIds.add(idObj.toString());
                }
            }
            report.put("studentThesesTotalCount", thesisIds.size());
        } catch (Exception e) {
            report.put("studentThesesIdScanError", e.getMessage());
        }

        // Scan StudentTheses one by one
        int successTheses = 0;
        for (String id : thesisIds) {
            try {
                StudentThesis t = mongoTemplate.findById(id, StudentThesis.class);
                if (t != null) {
                    t.getFullTitle();
                    t.getAuthorsNames();
                    t.getDirectorsNames();
                    successTheses++;
                }
            } catch (Exception e) {
                corruptTheses.add("ID: " + id + " (error: " + e.getClass().getName() + " - " + e.getMessage() + ")");
            }
        }
        report.put("studentThesesSuccessCount", successTheses);
        report.put("studentThesesCorruptCount", corruptTheses.size());
        report.put("studentThesesCorruptList", corruptTheses);

        // Fetch all Award IDs
        List<String> awardIds = new ArrayList<>();
        try {
            for (Document doc : mongoTemplate.getCollection("Awards").find().projection(new Document("_id", 1))) {
                Object idObj = doc.get("_id");
                if (idObj != null) {
                    awardIds.add(idObj.toString());
                }
            }
            report.put("awardsTotalCount", awardIds.size());
        } catch (Exception e) {
            report.put("awardsIdScanError", e.getMessage());
        }

        // Scan Awards one by one
        int successAwards = 0;
        for (String id : awardIds) {
            try {
                Document doc = mongoTemplate.findById(id, Document.class, "Awards");
                if (doc != null) {
                    doc.toJson();
                    successAwards++;
                }
            } catch (Exception e) {
                corruptAwards.add("ID: " + id + " (error: " + e.getClass().getName() + " - " + e.getMessage() + ")");
            }
        }
        report.put("awardsSuccessCount", successAwards);
        report.put("awardsCorruptCount", corruptAwards.size());
        report.put("awardsCorruptList", corruptAwards);

        return report;
    }

    private String formatSupervisorsForExcel(List<StudentThesis.Supervisor> supervisors) {
        if (supervisors == null || supervisors.isEmpty()) {
            return "Sin director";
        }
        List<String> list = new ArrayList<>();
        for (StudentThesis.Supervisor s : supervisors) {
            String name = s.getDisplayName();
            if (name == null || name.isBlank()) continue;
            String roleStr = null;
            if (s.getRole() != null && s.getRole().getTerm() != null) {
                Map<String, String> term = s.getRole().getTerm();
                roleStr = term.get("ca_ES");
                if (roleStr == null || roleStr.isBlank()) roleStr = term.get("es_ES");
                if (roleStr == null || roleStr.isBlank()) roleStr = term.get("en_GB");
            }
            if (roleStr != null && !roleStr.isBlank()) {
                list.add(name + " (" + roleStr + ")");
            } else {
                list.add(name);
            }
        }
        return String.join(" | ", list);
    }

    private FilteredResult getFilteredTheses(
            String buscar,
            String orgUuid,
            Integer desde,
            Integer hasta,
            String genderDirector,
            Integer page,
            Integer size) {

        org.springframework.data.mongodb.core.query.Criteria typeCriteria = new org.springframework.data.mongodb.core.query.Criteria().orOperator(
            org.springframework.data.mongodb.core.query.Criteria.where("type.term.es_ES").regex("tesis doctoral", "i"),
            org.springframework.data.mongodb.core.query.Criteria.where("type.term.ca_ES").regex("tesi doctoral", "i"),
            org.springframework.data.mongodb.core.query.Criteria.where("type.term.en_GB").regex("doctoral thesis|phd thesis", "i")
        );

        List<org.springframework.data.mongodb.core.query.Criteria> andList = new ArrayList<>();
        andList.add(typeCriteria);
        andList.add(org.springframework.data.mongodb.core.query.Criteria.where("workflow.step").is("approved"));

        if (buscar != null && !buscar.isBlank()) {
            org.springframework.data.mongodb.core.query.Criteria searchCriteria = new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                org.springframework.data.mongodb.core.query.Criteria.where("title.value").regex(buscar, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("contributors.name.firstName").regex(buscar, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("contributors.name.lastName").regex(buscar, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("supervisors.name.firstName").regex(buscar, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("supervisors.name.lastName").regex(buscar, "i")
            );
            andList.add(searchCriteria);
        }

        if (orgUuid != null && !orgUuid.isBlank() && !"all".equalsIgnoreCase(orgUuid)) {
            andList.add(org.springframework.data.mongodb.core.query.Criteria.where("managingOrganization.uuid").is(orgUuid));
        }

        if (desde != null || hasta != null) {
            org.springframework.data.mongodb.core.query.Criteria yearCriteria = org.springframework.data.mongodb.core.query.Criteria.where("awardDate.year");
            if (desde != null && hasta != null) {
                yearCriteria = yearCriteria.gte(desde).lte(hasta);
            } else if (desde != null) {
                yearCriteria = yearCriteria.gte(desde);
            } else {
                yearCriteria = yearCriteria.lte(hasta);
            }
            andList.add(yearCriteria);
        }

        org.springframework.data.mongodb.core.query.Criteria finalCriteria = new org.springframework.data.mongodb.core.query.Criteria().andOperator(andList.toArray(new org.springframework.data.mongodb.core.query.Criteria[0]));

        List<StudentThesis> content = new ArrayList<>();
        long total = 0;
        boolean aggregationSuccess = false;
        List<String> matchedIds = new ArrayList<>();

        try {
            List<Document> pipeline = new ArrayList<>();
            pipeline.add(new Document("$match", finalCriteria.getCriteriaObject()));
            
            // Exclude theses that ONLY have tutor supervisors (must have at least one non-tutor supervisor)
            pipeline.add(new Document("$addFields", new Document("nonTutors",
                new Document("$filter", new Document()
                    .append("input", new Document("$ifNull", Arrays.asList("$supervisors", new ArrayList<>())))
                    .append("as", "s")
                    .append("cond", new Document("$not", new Document("$regexMatch", new Document()
                        .append("input", new Document("$toLower", new Document("$ifNull", Arrays.asList(
                            "$$s.role.term.ca_ES",
                            new Document("$ifNull", Arrays.asList(
                                "$$s.role.term.es_ES",
                                new Document("$ifNull", Arrays.asList("$$s.role.term.en_GB", ""))
                            ))
                        ))))
                        .append("regex", "tutor")
                    )))
                )
            )));
            pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(new Document("$size", "$nonTutors"), 0)))));

            if (genderDirector != null && !genderDirector.isBlank() && !"all".equalsIgnoreCase(genderDirector)) {
                // Bulk lookup to avoid unwinding and duplicate/corrupted field deserialization
                pipeline.add(new Document("$lookup", new Document()
                    .append("from", "Persons")
                    .append("localField", "nonTutors.person.uuid")
                    .append("foreignField", "uuid")
                    .append("as", "nonTutorsPersonInfo")));

                Document femaleFilter = new Document()
                    .append("input", new Document("$ifNull", Arrays.asList("$nonTutorsPersonInfo", new ArrayList<>())))
                    .append("as", "p")
                    .append("cond", buildGenderCond("dona|female|femella|woman"));

                Document maleFilter = new Document()
                    .append("input", new Document("$ifNull", Arrays.asList("$nonTutorsPersonInfo", new ArrayList<>())))
                    .append("as", "p")
                    .append("cond", buildGenderCond("home|male|masculi|hombre"));

                pipeline.add(new Document("$addFields", new Document()
                    .append("femaleCount", new Document("$size", new Document("$filter", femaleFilter)))
                    .append("maleCount", new Document("$size", new Document("$filter", maleFilter)))
                ));

                if ("female".equalsIgnoreCase(genderDirector)) {
                    pipeline.add(new Document("$match", new Document("femaleCount", new Document("$gt", 0))));
                } else if ("male".equalsIgnoreCase(genderDirector)) {
                    pipeline.add(new Document("$match", new Document("maleCount", new Document("$gt", 0))));
                } else if ("femaleOnly".equalsIgnoreCase(genderDirector)) {
                    pipeline.add(new Document("$match", new Document("femaleCount", new Document("$gt", 0))
                        .append("maleCount", 0)));
                } else if ("maleOnly".equalsIgnoreCase(genderDirector)) {
                    pipeline.add(new Document("$match", new Document("maleCount", new Document("$gt", 0))
                        .append("femaleCount", 0)));
                }
            }

            pipeline.add(new Document("$group", new Document("_id", "$_id")
                .append("awardYear", new Document("$first", "$awardDate.year"))));

            // Count pipeline
            List<Document> countPipeline = new ArrayList<>(pipeline);
            countPipeline.add(new Document("$count", "totalCount"));
            List<Document> countResult = mongoTemplate.getCollection("StudentTheses")
                .aggregate(countPipeline)
                .into(new ArrayList<>());
            total = countResult.isEmpty() ? 0 : countResult.get(0).getInteger("totalCount", 0);

            // Sort and paginate
            pipeline.add(new Document("$sort", new Document("awardYear", -1).append("_id", -1)));
            if (page != null && size != null) {
                pipeline.add(new Document("$skip", (long) page * size));
                pipeline.add(new Document("$limit", size));
            }

            List<Document> rawIds = mongoTemplate.getCollection("StudentTheses")
                .aggregate(pipeline)
                .into(new ArrayList<>());

            for (Document doc : rawIds) {
                Object idObj = doc.get("_id");
                if (idObj != null) {
                    matchedIds.add(idObj.toString());
                }
            }
            aggregationSuccess = true;
        } catch (Exception e) {
            System.err.println("Warning: Aggregation failed in getFilteredTheses. Falling back to Java-side filtering. Error: " + e.getMessage());
            aggregationSuccess = false;
        }

        if (!aggregationSuccess) {
            List<String> candidateIds = new ArrayList<>();
            try {
                for (Document doc : mongoTemplate.getCollection("StudentTheses")
                        .find(finalCriteria.getCriteriaObject())
                        .projection(new Document("_id", 1).append("awardDate.year", 1))
                        .sort(new Document("awardDate.year", -1).append("_id", -1))) {
                    Object idObj = doc.get("_id");
                    if (idObj != null) {
                        candidateIds.add(idObj.toString());
                    }
                }
            } catch (Exception ex) {
                System.err.println("Error: Fallback ID scan also failed: " + ex.getMessage());
            }

            List<String> filteredCandidateIds = new ArrayList<>();
            for (String id : candidateIds) {
                try {
                    StudentThesis t = mongoTemplate.findById(id, StudentThesis.class);
                    if (t != null) {
                        boolean hasNonTutor = false;
                        if (t.getSupervisors() != null) {
                            for (StudentThesis.Supervisor s : t.getSupervisors()) {
                                if (!isTutor(s)) {
                                    hasNonTutor = true;
                                    break;
                                }
                            }
                        }
                        if (!hasNonTutor) {
                            continue;
                        }

                        if (genderDirector != null && !genderDirector.isBlank() && !"all".equalsIgnoreCase(genderDirector)) {
                            int femaleCount = 0;
                            int maleCount = 0;
                            if (t.getSupervisors() != null) {
                                for (StudentThesis.Supervisor s : t.getSupervisors()) {
                                    if (isTutor(s)) {
                                        continue;
                                    }
                                    String genderRaw = null;
                                    if (s.getPerson() != null && s.getPerson().getUuid() != null) {
                                        try {
                                            Document personDoc = mongoTemplate.findById(s.getPerson().getUuid(), Document.class, "Persons");
                                            if (personDoc != null) {
                                                genderRaw = extractGender(personDoc);
                                            }
                                        } catch (Exception ex) {
                                            // Skip corrupt Person
                                        }
                                    }
                                    String genderClass = classifyGender(genderRaw);
                                    if ("female".equals(genderClass)) {
                                        femaleCount++;
                                    } else if ("male".equals(genderClass)) {
                                        maleCount++;
                                    }
                                }
                            }

                            if ("female".equalsIgnoreCase(genderDirector) && femaleCount == 0) {
                                continue;
                            }
                            if ("male".equalsIgnoreCase(genderDirector) && maleCount == 0) {
                                continue;
                            }
                            if ("femaleOnly".equalsIgnoreCase(genderDirector) && (femaleCount == 0 || maleCount > 0)) {
                                continue;
                            }
                            if ("maleOnly".equalsIgnoreCase(genderDirector) && (maleCount == 0 || femaleCount > 0)) {
                                continue;
                            }
                        }

                        filteredCandidateIds.add(id);
                    }
                } catch (Exception ex) {
                    System.err.println("Warning: BSON serialization error when reading StudentThesis " + id + " in fallback. Skipping. Error: " + ex.getMessage());
                }
            }

            total = filteredCandidateIds.size();

            int startIdx = 0;
            int endIdx = filteredCandidateIds.size();
            if (page != null && size != null) {
                startIdx = page * size;
                if (startIdx > filteredCandidateIds.size()) {
                    startIdx = filteredCandidateIds.size();
                }
                endIdx = startIdx + size;
                if (endIdx > filteredCandidateIds.size()) {
                    endIdx = filteredCandidateIds.size();
                }
            }

            matchedIds = filteredCandidateIds.subList(startIdx, endIdx);
        }

        content = new ArrayList<>();
        if (!matchedIds.isEmpty()) {
            for (String id : matchedIds) {
                try {
                    StudentThesis t = mongoTemplate.findById(id, StudentThesis.class);
                    if (t != null) {
                        content.add(t);
                    }
                } catch (Exception e) {
                    System.err.println("Warning: BSON serialization error when reading StudentThesis with ID: " + id + ". Skipping document. Error: " + e.getMessage());
                }
            }
        }

        // Fetch scholarship (beca) details for the authors of these theses
        List<String> authorUuids = new ArrayList<>();
        for (StudentThesis t : content) {
            if (t.getContributors() != null) {
                for (StudentThesis.Contributor c : t.getContributors()) {
                    if (c.getPerson() != null && c.getPerson().getUuid() != null) {
                        authorUuids.add(c.getPerson().getUuid());
                    }
                }
            }
        }

        java.util.Map<String, String> authorAwards = new java.util.HashMap<>();
        if (!authorUuids.isEmpty()) {
            List<Document> awardPipeline = new ArrayList<>();
            awardPipeline.add(new Document("$match", new Document("workflow.step", "validated")
                .append("awardHolders.person.uuid", new Document("$in", authorUuids))));
            awardPipeline.add(new Document("$project", new Document("natureTypes", 1)
                .append("title", 1)
                .append("holderUuids", "$awardHolders.person.uuid")));

            List<Document> matchedAwards = new ArrayList<>();
            boolean bulkSuccess = false;
            try {
                matchedAwards = mongoTemplate.getCollection("Awards")
                    .aggregate(awardPipeline)
                    .into(new ArrayList<>());
                bulkSuccess = true;
            } catch (Exception e) {
                System.err.println("Warning: BSON serialization error on Awards aggregation. Bypassing bulk fetch. Error: " + e.getMessage());
                // Fallback: fetch one by one to isolate and skip corrupt documents
                for (String authorUuid : authorUuids) {
                    try {
                        List<Document> individualAwards = mongoTemplate.getCollection("Awards")
                            .find(new Document("workflow.step", "validated")
                                .append("awardHolders.person.uuid", authorUuid))
                            .projection(new Document("natureTypes", 1)
                                .append("title", 1)
                                .append("awardHolders.person.uuid", 1))
                            .into(new ArrayList<>());
                        
                        for (Document award : individualAwards) {
                            String natureLabel = extractNatureLabelFromAward(award);
                            if (natureLabel != null) {
                                if (!authorAwards.containsKey(authorUuid)) {
                                    authorAwards.put(authorUuid, natureLabel);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("Warning: BSON serialization error for Award of author: " + authorUuid + ". Skipping. Error: " + ex.getMessage());
                    }
                }
            }

            if (bulkSuccess) {
                for (Document award : matchedAwards) {
                    String natureLabel = extractNatureLabelFromAward(award);
                    if (natureLabel != null) {
                        List<?> holdersList = award.get("holderUuids", List.class);
                        if (holdersList != null) {
                            for (Object hObj : holdersList) {
                                if (hObj instanceof String h && !h.isBlank() && authorUuids.contains(h)) {
                                    if (!authorAwards.containsKey(h)) {
                                        authorAwards.put(h, natureLabel);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (StudentThesis t : content) {
            if (t.getContributors() != null) {
                for (StudentThesis.Contributor c : t.getContributors()) {
                    if (c.getPerson() != null && c.getPerson().getUuid() != null) {
                        String uuid = c.getPerson().getUuid();
                        if (authorAwards.containsKey(uuid)) {
                            String beca = authorAwards.get(uuid);
                            t.setBecaTitol(beca);
                            t.setBecaCodi(extractScholarshipCode(beca));
                            break;
                        }
                    }
                }
            }
        }

        return new FilteredResult(content, total);
    }

    private static class FilteredResult {
        final List<StudentThesis> content;
        final long total;

        FilteredResult(List<StudentThesis> content, long total) {
            this.content = content;
            this.total = total;
        }
    }

    @GetMapping("/stats/same-author-director")
    public List<Document> mismoAutorDirector(
            @RequestParam(defaultValue = "2") int minCoincidencias,
            @RequestParam(defaultValue = "0") int limit) {

        int min = Math.max(1, minCoincidencias);
        int max = Math.max(0, limit);

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("$and", Arrays.asList(
                new Document("workflow.step", "approved"),
                new Document("$or", List.of(
                        new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
                ))
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
        matchConditions.add(new Document("workflow.step", "approved"));
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
        matchConditions.add(new Document("workflow.step", "approved"));
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
        matchConditions.add(new Document("workflow.step", "approved"));
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
        matchConditions.add(new Document("workflow.step", "approved"));
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

        List<Document> rawTheses = null;
        boolean aggregationSuccess = false;
        try {
            rawTheses = mongoTemplate.getCollection("StudentTheses")
                .aggregate(pipeline)
                .into(new ArrayList<>());
            aggregationSuccess = true;
        } catch (Exception e) {
            System.err.println("Warning: Aggregation failed in getGenderEvolution. Falling back to Java-side aggregation. Error: " + e.getMessage());
            aggregationSuccess = false;
        }

        if (!aggregationSuccess) {
            rawTheses = new ArrayList<>();
            try {
                List<Document> thesesDocs = mongoTemplate.getCollection("StudentTheses")
                    .find(new Document("$and", matchConditions))
                    .into(new ArrayList<>());

                for (Document tDoc : thesesDocs) {
                    try {
                        String thesisUuid = tDoc.getString("uuid");
                        if (thesisUuid == null) thesisUuid = tDoc.getString("_id");
                        
                        // Extract managingOrganization details
                        Document managingOrg = tDoc.get("managingOrganization", Document.class);
                        String orgUuidVal = managingOrg != null ? managingOrg.getString("uuid") : null;
                        Document orgInfo = null;
                        if (orgUuidVal != null) {
                            try {
                                orgInfo = mongoTemplate.findById(orgUuidVal, Document.class, "Organizations");
                            } catch (Exception ex) {
                                // Skip corrupt Org
                            }
                        }
                        
                        // Extract supervisors and look up their details in Persons
                        List<?> supervisorsList = tDoc.get("supervisors", List.class);
                        List<Document> mappedSupervisors = new ArrayList<>();
                        
                        if (supervisorsList != null) {
                            for (Object supObj : supervisorsList) {
                                if (supObj instanceof Document sd) {
                                    // Check role
                                    String roleText = "";
                                    Document roleDoc = sd.get("role", Document.class);
                                    if (roleDoc != null && roleDoc.get("term") instanceof Document termDoc) {
                                        roleText = termDoc.getString("ca_ES");
                                        if (roleText == null || roleText.isBlank()) roleText = termDoc.getString("es_ES");
                                        if (roleText == null || roleText.isBlank()) roleText = termDoc.getString("en_GB");
                                    }
                                    if (roleText != null && roleText.toLowerCase().contains("tutor")) {
                                        continue; // Exclude tutors
                                    }
                                    
                                    Document personRef = sd.get("person", Document.class);
                                    String supUuid = personRef != null ? personRef.getString("uuid") : null;
                                    String externalUuid = null;
                                    if (supUuid == null || supUuid.isBlank()) {
                                        Document extRef = sd.get("externalPerson", Document.class);
                                        supUuid = extRef != null ? extRef.getString("uuid") : null;
                                        externalUuid = supUuid;
                                    }
                                    
                                    Document personInfo = null;
                                    if (supUuid != null) {
                                        try {
                                            personInfo = mongoTemplate.findById(supUuid, Document.class, "Persons");
                                        } catch (Exception ex) {
                                            // Skip corrupt Person
                                        }
                                    }
                                    
                                    Document nameDoc = sd.get("name", Document.class);
                                    String firstName = nameDoc != null ? nameDoc.getString("firstName") : null;
                                    String lastName = nameDoc != null ? nameDoc.getString("lastName") : null;
                                    
                                    Document mappedSup = new Document()
                                        .append("uuid", supUuid)
                                        .append("externalUuid", externalUuid)
                                        .append("firstName", firstName)
                                        .append("lastName", lastName)
                                        .append("gender", personInfo != null ? personInfo.get("gender") : null)
                                        .append("sex", personInfo != null ? personInfo.get("sex") : null)
                                        .append("roleUri", roleDoc != null ? roleDoc.getString("uri") : null);
                                    
                                    mappedSupervisors.add(mappedSup);
                                }
                            }
                        }
                        
                        Document firstTitle = null;
                        Object titleObj = tDoc.get("title");
                        if (titleObj instanceof Document titleDoc) {
                            firstTitle = titleDoc;
                        } else if (titleObj instanceof String titleStr) {
                            firstTitle = new Document("value", titleStr);
                        }
                        
                        Document awardDate = tDoc.get("awardDate", Document.class);
                        Integer yearVal = awardDate != null ? awardDate.getInteger("year") : null;
                        
                        Document resultDoc = new Document("_id", thesisUuid)
                            .append("title", firstTitle != null ? firstTitle.getString("value") : null)
                            .append("year", yearVal)
                            .append("managingOrgUuid", orgUuidVal)
                            .append("managingOrgName", orgInfo != null ? orgInfo.get("name") : null)
                            .append("contributors", tDoc.get("contributors"))
                            .append("supervisors", mappedSupervisors);
                        
                        rawTheses.add(resultDoc);
                    } catch (Exception ex) {
                        System.err.println("Warning: BSON serialization error when reading StudentThesis in fallback of stats. Skipping. Error: " + ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                System.err.println("Error: Fallback scan in getGenderEvolution failed: " + ex.getMessage());
            }
        }

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
            // Project natureTypes (for beca detection) plus title/applications/holders
            awardPipeline.add(new Document("$project", new Document("natureTypes", 1)
                .append("type", 1)
                .append("title", 1)
                .append("applications", 1)
                .append("holderUuids", "$awardHolders.person.uuid")));

            List<Document> matchedAwards = mongoTemplate.getCollection("Awards")
                .aggregate(awardPipeline)
                .into(new ArrayList<>());

            for (Document award : matchedAwards) {
                String natureLabel = extractNatureLabelFromAward(award);
                if (natureLabel != null) {
                    List<?> holdersList = award.get("holderUuids", List.class);
                    if (holdersList != null) {
                        for (Object hObj : holdersList) {
                            if (hObj instanceof String h && !h.isBlank() && authorUuids.contains(h)) {
                                if (!authorAwards.containsKey(h)) {
                                    authorAwards.put(h, natureLabel);
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
        List<Map<String, Object>> maleOnlyThesesList = new ArrayList<>();
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

            // Classify thesis into mutually exclusive categories:
            // - solo mujeres (only women): hasFemale && !hasMale
            // - solo hombres (only men): hasMale && !hasFemale
            // - mixtos (mixed): hasFemale && hasMale
            // - unknown/other: !hasFemale && !hasMale
            String thesisClassification;
            if (hasFemale && !hasMale) {
                thesisClassification = "directedFemale"; // representing solo mujeres
            } else if (hasFemale && hasMale) {
                thesisClassification = "codirectedFemale"; // representing mixtos
            } else if (hasMale && !hasFemale) {
                thesisClassification = "maleOnly"; // representing solo hombres
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
                map.put("maleOnlyWithScholarship", 0);
                map.put("directedFemaleWithScholarship", 0);
                map.put("codirectedFemaleWithScholarship", 0);
                map.put("unknownWithScholarship", 0);
                return map;
            });
            stats.put("total", stats.get("total") + 1);
            
            if ("directedFemale".equals(thesisClassification)) {
                stats.put("directedFemale", stats.get("directedFemale") + 1);
            } else if ("codirectedFemale".equals(thesisClassification)) {
                stats.put("codirectedFemale", stats.get("codirectedFemale") + 1);
            } else if ("maleOnly".equals(thesisClassification)) {
                stats.put("maleOnly", stats.get("maleOnly") + 1);
            } else if ("unknown".equals(thesisClassification)) {
                stats.put("unknown", stats.get("unknown") + 1);
            }

            if (hasFemale) {
                stats.put("female", stats.get("female") + 1);
            }

            if (authorHasScholarship) {
                stats.put("withScholarship", stats.get("withScholarship") + 1);
                
                if ("directedFemale".equals(thesisClassification)) {
                    stats.put("directedFemaleWithScholarship", stats.get("directedFemaleWithScholarship") + 1);
                } else if ("codirectedFemale".equals(thesisClassification)) {
                    stats.put("codirectedFemaleWithScholarship", stats.get("codirectedFemaleWithScholarship") + 1);
                } else if ("maleOnly".equals(thesisClassification)) {
                    stats.put("maleOnlyWithScholarship", stats.get("maleOnlyWithScholarship") + 1);
                } else if ("unknown".equals(thesisClassification)) {
                    stats.put("unknownWithScholarship", stats.get("unknownWithScholarship") + 1);
                }
                
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
                ft.put("directores", allDirectorsInThesis); // Contains all directors (men and women)
                ft.put("totsDirectors", allDirectorsInThesis);
                ft.put("teBeca", authorHasScholarship);
                ft.put("becaTitol", scholarshipName != null ? scholarshipName : "");
                ft.put("becaCodi", scholarshipName != null ? extractScholarshipCode(scholarshipName) : "");
                ft.put("tipusDireccio", ("directedFemale".equals(thesisClassification)) ? "Només Dones" : "Mixta");
                femaleThesesList.add(ft);
            }

            // If directed by only men, add to detailed list
            if ("maleOnly".equals(thesisClassification)) {
                Map<String, Object> mt = new LinkedHashMap<>();
                mt.put("uuid", d.getString("_id"));
                mt.put("titol", d.getString("title"));
                mt.put("autor", authorStr);
                mt.put("any", year);
                mt.put("departament", deptName);
                mt.put("directores", allDirectorsInThesis); // Only male directors
                mt.put("totsDirectors", allDirectorsInThesis);
                mt.put("teBeca", authorHasScholarship);
                mt.put("becaTitol", scholarshipName != null ? scholarshipName : "");
                mt.put("becaCodi", scholarshipName != null ? extractScholarshipCode(scholarshipName) : "");
                mt.put("tipusDireccio", "Només Homes");
                maleOnlyThesesList.add(mt);
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
        response.put("maleOnlyTheses", maleOnlyThesesList);
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

    /**
     * Extreu l'etiqueta llegible de beca a partir dels natureTypes de l'Award.
     * Retorna null si l'Award no té cap natureType que indiqui que és una beca.
     *
     * Exemples d'URIs esperats:
     *   /dk/atira/pure/upm/nature/bec_doc   → "Beca Doctorat"
     *   /dk/atira/pure/upm/nature/gac_doc   → "Ajut Doctorat (GAC)"
     *   /dk/atira/pure/upm/nature/fpi       → "FPI"
     *   /dk/atira/pure/upm/nature/fpd       → "FPD"
     *   /dk/atira/pure/upm/nature/riu       → "RIU"
     *   ... (qualsevol URI que contingui "bec", "ajut", "fellowship", "fp", "riu", "contrato")
     *
     * El nom llegible s'obté del darrer segment de l'URI mapat a un diccionari de labels.
     */
    @SuppressWarnings("unchecked")
    private String extractNatureLabelFromAward(Document award) {
        // Mapa d'URIs (darrer segment) a etiquetes llegibles
        // Afegir nous valors aquí quan es necessitin
        java.util.Map<String, String> NATURE_LABELS = new java.util.LinkedHashMap<>();
        NATURE_LABELS.put("bec_doc",       "Beca Doctorat");
        NATURE_LABELS.put("bec_postdoc",   "Beca Postdoctorat");
        NATURE_LABELS.put("gac_doc",       "Ajut Doctorat (GAC)");
        NATURE_LABELS.put("gac_postdoc",   "Ajut Postdoctorat (GAC)");
        NATURE_LABELS.put("fpi",           "FPI");
        NATURE_LABELS.put("fpd",           "FPD");
        NATURE_LABELS.put("riu",           "RIU");
        NATURE_LABELS.put("fel",           "Fellowship");
        NATURE_LABELS.put("fellowship",    "Fellowship");
        NATURE_LABELS.put("beca",          "Beca");
        NATURE_LABELS.put("ajut_doc",      "Ajut Doctorat");
        NATURE_LABELS.put("contrato_doc",  "Contracte Doctorat");
        NATURE_LABELS.put("contrato",      "Contracte Recerca");

        // Keywords que, si apareixen en l'URI (qualsevol segment), indiquen beca
        List<String> BECA_KEYWORDS = Arrays.asList("bec", "ajut_doc", "gac_doc", "gac_postdoc",
                "fpi", "fpd", "riu", "fellowship", "fel", "contrato_doc");

        Object natureTypesObj = award.get("natureTypes");
        if (!(natureTypesObj instanceof List<?> natureList) || natureList.isEmpty()) {
            return null;
        }

        for (Object ntObj : natureList) {
            String uri = null;
            String labelText = null;
            if (ntObj instanceof Document ntDoc) {
                uri = ntDoc.getString("uri");
                Document termDoc = ntDoc.get("term", Document.class);
                if (termDoc != null) {
                    labelText = termDoc.getString("ca_ES");
                    if (labelText == null || labelText.isBlank()) {
                        labelText = termDoc.getString("es_ES");
                    }
                    if (labelText == null || labelText.isBlank()) {
                        labelText = termDoc.getString("en_GB");
                    }
                }
            } else if (ntObj instanceof String s) {
                uri = s;
            }
            if (uri == null || uri.isBlank()) continue;

            // Darrer segment de l'URI
            String segment = uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri;
            String segmentLower = segment.toLowerCase();

            // Comprovem si l'URI correspon a una beca
            boolean isBeca = NATURE_LABELS.containsKey(segmentLower);
            if (!isBeca) {
                String uriLower = uri.toLowerCase();
                for (String kw : BECA_KEYWORDS) {
                    if (uriLower.contains(kw)) {
                        isBeca = true;
                        break;
                    }
                }
            }

            if (isBeca) {
                // Si tenim el text descripció localitzat, el preferim
                if (labelText != null && !labelText.isBlank()) {
                    return labelText.trim();
                }
                // Si no, fem fallback al mapa o al propi segment
                return NATURE_LABELS.getOrDefault(segmentLower, segment);
            }
        }

        return null; // L'Award no té natureType de beca
    }

    private boolean isTutor(StudentThesis.Supervisor s) {
        if (s == null || s.getRole() == null) return false;
        Map<String, String> term = s.getRole().getTerm();
        if (term == null) return false;
        String roleText = term.get("ca_ES");
        if (roleText == null || roleText.isBlank()) roleText = term.get("es_ES");
        if (roleText == null || roleText.isBlank()) roleText = term.get("en_GB");
        if (roleText == null || roleText.isBlank()) return false;
        return roleText.trim().toLowerCase().contains("tutor");
    }

    private Document buildStringRegexCond(String path, String regexPattern) {
        return new Document("$cond", new Document()
            .append("if", new Document("$eq", Arrays.asList(new Document("$type", path), "string")))
            .append("then", new Document("$regexMatch", new Document("input", path).append("regex", regexPattern).append("options", "i")))
            .append("else", false)
        );
    }

    private Document buildGenderCond(String regexPattern) {
        List<Document> orList = new ArrayList<>();
        orList.add(buildStringRegexCond("$$p.gender.term.ca_ES", regexPattern));
        orList.add(buildStringRegexCond("$$p.gender.term.es_ES", regexPattern));
        orList.add(buildStringRegexCond("$$p.gender.term.en_GB", regexPattern));
        orList.add(buildStringRegexCond("$$p.gender.ca_ES", regexPattern));
        orList.add(buildStringRegexCond("$$p.gender.es_ES", regexPattern));
        orList.add(buildStringRegexCond("$$p.gender.en_GB", regexPattern));
        orList.add(buildStringRegexCond("$$p.gender", regexPattern));
        orList.add(buildStringRegexCond("$$p.sex.term.ca_ES", regexPattern));
        orList.add(buildStringRegexCond("$$p.sex.term.es_ES", regexPattern));
        orList.add(buildStringRegexCond("$$p.sex.term.en_GB", regexPattern));
        orList.add(buildStringRegexCond("$$p.sex", regexPattern));
        return new Document("$or", orList);
    }
}
