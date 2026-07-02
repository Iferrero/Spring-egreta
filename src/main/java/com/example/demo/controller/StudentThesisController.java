package com.example.demo.controller;

import com.example.demo.model.StudentThesis;
import com.example.demo.repository.StudentThesisRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

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
    private com.optimaize.langdetect.LanguageDetector languageDetector;
    private com.optimaize.langdetect.text.TextObjectFactory textObjectFactory;

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
                        .append("pureId", "$pureId")
                        .append("titulo", "$title.value")
                        .append("anio", "$awardDate.year")
                        .append("autores", 1)
                        .append("links", "$links"));
        pipeline.add(projectStage);

        pipeline.add(new Document("$unwind", "$autores"));

        pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(
                new Document("$strLenCP", new Document("$trim", new Document("input", "$autores.nombre"))), 0
            )))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("autor", "$autores.nombre")
                .append("autorUuid", "$autores.uuid")
                .append("anio", "$anio")
                .append("uuid", "$uuid"))
                .append("pureId", new Document("$first", "$pureId"))
                .append("titulo", new Document("$first", "$titulo"))
                .append("links", new Document("$first", "$links"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("autor", "$_id.autor")
                .append("autorUuid", "$_id.autorUuid")
                .append("anio", "$_id.anio"))
                .append("totalTesis", new Document("$sum", 1))
                .append("tesis", new Document("$addToSet", new Document()
                        .append("uuid", "$_id.uuid")
                        .append("pureId", "$pureId")
                        .append("titulo", "$titulo")
                        .append("anio", "$_id.anio")
                        .append("links", "$links")))));

        pipeline.add(new Document("$match", new Document("totalTesis", new Document("$gte", min))));

        pipeline.add(new Document("$project", new Document("_id", 0)
                .append("autor", "$_id.autor")
                .append("autorUuid", "$_id.autorUuid")
                .append("anio", "$_id.anio")
                .append("totalTesis", 1)
                .append("tesis", 1)));

        pipeline.add(new Document("$sort", new Document("totalTesis", -1)
                .append("autor", 1)
                .append("anio", -1)));

        if (max > 0) {
            pipeline.add(new Document("$limit", max));
        }

        return mongoTemplate
                .getCollection("StudentTheses")
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }

    @GetMapping("/stats/corrections")
    public Map<String, Object> getThesisCorrections() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        Query query = new Query();
        query.addCriteria(Criteria.where("workflow.step").is("approved"));
        
        Criteria typeCriteria = new Criteria().orOperator(
            Criteria.where("type.term.es_ES").regex("tesis doctoral", "i"),
            Criteria.where("type.term.ca_ES").regex("tesi doctoral", "i"),
            Criteria.where("type.term.en_GB").regex("doctoral thesis|phd thesis", "i")
        );
        query.addCriteria(typeCriteria);

        query.fields()
             .include("uuid")
             .include("pureId")
             .include("title")
             .include("contributors")
             .include("supervisors")
             .include("awardDate")
             .include("abstract")
             .include("links")
             .include("language");

        List<StudentThesis> allTheses = mongoTemplate.find(query, StudentThesis.class);

        int multipleDddCount = 0;
        int uppercaseTitleCount = 0;
        int noDddCount = 0;
        int missingAbstractCount = 0;
        int undefinedLanguageCount = 0;

        List<Map<String, Object>> thesesNeedingCorrection = new ArrayList<>();

        java.time.LocalDate today = java.time.LocalDate.now();

        for (StudentThesis t : allTheses) {
            if (t.getAwardDate() == null || t.getAwardDate().getYear() == null) {
                continue;
            }
            Integer year = t.getAwardDate().getYear();
            Integer month = t.getAwardDate().getMonth();
            Integer day = t.getAwardDate().getDay();
            
            int m = month != null ? month : 12;
            int d = day != null ? day : 28;
            if (m < 1) m = 1;
            if (m > 12) m = 12;
            
            int maxDays;
            try {
                maxDays = java.time.YearMonth.of(year, m).lengthOfMonth();
            } catch (Exception e) {
                maxDays = 28;
            }
            if (d < 1) d = 1;
            if (d > maxDays) d = maxDays;

            try {
                java.time.LocalDate awardLocalDate = java.time.LocalDate.of(year, m, d);
                if (!awardLocalDate.isBefore(today)) {
                    continue;
                }
            } catch (Exception e) {
                if (year >= today.getYear()) {
                    continue;
                }
            }

            boolean hasMultipleDdd = false;
            boolean isUppercase = false;
            boolean hasNoDdd = false;
            boolean hasMissingAbstract = false;

            int dddLinksCount = 0;
            if (t.getLinks() != null) {
                for (StudentThesis.Link link : t.getLinks()) {
                    if (isDddLink(link)) {
                        dddLinksCount++;
                    }
                }
            }

            if (dddLinksCount >= 2) {
                hasMultipleDdd = true;
                multipleDddCount++;
            } else if (dddLinksCount == 0) {
                hasNoDdd = true;
                noDddCount++;
            }

            String title = t.getFullTitle();
            if (isTitleUppercase(title)) {
                isUppercase = true;
                uppercaseTitleCount++;
            }

            if (!t.hasAbstract()) {
                hasMissingAbstract = true;
                missingAbstractCount++;
            }

            boolean hasUndefinedLanguage = false;
            if (t.getLanguage() == null || t.getLanguage().getUri() == null || t.getLanguage().getUri().isBlank() || t.getLanguage().getUri().endsWith("/und")) {
                hasUndefinedLanguage = true;
                undefinedLanguageCount++;
            }

            if (hasMultipleDdd || isUppercase || hasNoDdd || hasMissingAbstract || hasUndefinedLanguage) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("uuid", t.getUuid());
                details.put("pureId", t.getPureId());
                details.put("titulo", title);
                details.put("autor", t.getAuthorsNames());
                details.put("director", t.getDirectorsNames());
                details.put("anio", t.getYear());
                
                List<String> errors = new ArrayList<>();
                if (hasMultipleDdd) errors.add("MULTIPLE_DDD");
                if (isUppercase) errors.add("UPPERCASE_TITLE");
                if (hasNoDdd) errors.add("NO_DDD");
                if (hasMissingAbstract) errors.add("MISSING_ABSTRACT");
                if (hasUndefinedLanguage) errors.add("UNDEFINED_LANGUAGE");
                details.put("errors", errors);

                List<String> allUrls = new ArrayList<>();
                if (t.getLinks() != null) {
                    for (StudentThesis.Link link : t.getLinks()) {
                        if (link.getUrl() != null && isDddLink(link)) {
                            allUrls.add(link.getUrl());
                        }
                    }
                }
                details.put("links", allUrls);
                
                thesesNeedingCorrection.add(details);
            }
        }

        // Sort thesesNeedingCorrection by year descending (most recent first)
        thesesNeedingCorrection.sort((a, b) -> {
            Integer yA = (Integer) a.get("anio");
            Integer yB = (Integer) b.get("anio");
            if (yA == null && yB == null) return 0;
            if (yA == null) return 1;
            if (yB == null) return -1;
            return yB.compareTo(yA);
        });

        Map<String, Integer> kpis = new LinkedHashMap<>();
        kpis.put("multipleDdd", multipleDddCount);
        kpis.put("uppercaseTitle", uppercaseTitleCount);
        kpis.put("noDdd", noDddCount);
        kpis.put("missingAbstract", missingAbstractCount);
        kpis.put("undefinedLanguage", undefinedLanguageCount);

        result.put("kpis", kpis);
        result.put("theses", thesesNeedingCorrection);

        return result;
    }

    @GetMapping("/languages")
    public List<Map<String, Object>> getLanguages() {
        Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/core/languages"));
        Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
        if (schemeDoc == null) {
            return getDefaultFallbackLanguages();
        }
        
        List<Document> contained = null;
        try {
            contained = (List<Document>) schemeDoc.get("containedClassifications");
        } catch (Exception e) {
            // ignore
        }
        if (contained == null || contained.isEmpty()) {
            return getDefaultFallbackLanguages();
        }
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document c : contained) {
            Boolean disabled = c.getBoolean("disabled");
            if (disabled != null && disabled) {
                continue;
            }
            String uri = c.getString("uri");
            if (uri == null) continue;
            // The language code is the last part of the URI, e.g., "es_ES" or "ar"
            String code = uri.substring(uri.lastIndexOf('/') + 1);
            
            Map<String, Object> langMap = new LinkedHashMap<>();
            langMap.put("code", code);
            langMap.put("uri", uri);
            
            // Extract terms
            Map<String, String> termMap = new LinkedHashMap<>();
            Object termObj = c.get("term");
            if (termObj instanceof Document) {
                Document termDoc = (Document) termObj;
                List<Document> textList = null;
                try {
                    textList = (List<Document>) termDoc.get("text");
                } catch (Exception e) {
                    // ignore
                }
                if (textList != null) {
                    for (Document t : textList) {
                        String locale = t.getString("locale");
                        String value = t.getString("value");
                        if (locale != null && value != null) {
                            termMap.put(locale, value);
                        }
                    }
                }
            }
            langMap.put("term", termMap);
            
            // Determine display name
            String label = termMap.get("ca_ES");
            if (label == null || label.isBlank()) {
                label = termMap.get("es_ES");
            }
            if (label == null || label.isBlank()) {
                label = termMap.get("en_GB");
            }
            if (label == null || label.isBlank()) {
                label = code;
            }
            langMap.put("label", label);
            
            result.add(langMap);
        }
        
        // Sort alphabetically by label
        result.sort((a, b) -> ((String) a.get("label")).compareToIgnoreCase((String) b.get("label")));
        return result;
    }

    private List<Map<String, Object>> getDefaultFallbackLanguages() {
        List<Map<String, Object>> fallback = new ArrayList<>();
        fallback.add(Map.of("code", "ca_ES", "uri", "/dk/atira/pure/core/languages/ca_ES", "label", "Català", "term", Map.of("ca_ES", "Català", "es_ES", "Catalán", "en_GB", "Catalan")));
        fallback.add(Map.of("code", "es_ES", "uri", "/dk/atira/pure/core/languages/es_ES", "label", "Espanyol", "term", Map.of("ca_ES", "Espanyol", "es_ES", "Español", "en_GB", "Spanish")));
        fallback.add(Map.of("code", "en_GB", "uri", "/dk/atira/pure/core/languages/en_GB", "label", "Anglès", "term", Map.of("ca_ES", "Anglès", "es_ES", "Inglés", "en_GB", "English")));
        return fallback;
    }

    private synchronized com.optimaize.langdetect.LanguageDetector getLanguageDetector() {
        if (this.languageDetector == null) {
            try {
                List<com.optimaize.langdetect.profiles.LanguageProfile> languageProfiles = 
                    new com.optimaize.langdetect.profiles.LanguageProfileReader().readAllBuiltIn();
                this.languageDetector = com.optimaize.langdetect.LanguageDetectorBuilder.create(
                    com.optimaize.langdetect.ngram.NgramExtractors.standard())
                        .withProfiles(languageProfiles)
                        .build();
                this.textObjectFactory = com.optimaize.langdetect.text.CommonTextObjectFactories.forDetectingShortCleanText();
            } catch (Exception e) {
                System.err.println("Failed to initialize language detector: " + e.getMessage());
            }
        }
        return this.languageDetector;
    }

    @GetMapping("/detect-language")
    public Map<String, Object> detectLanguage(@RequestParam String title) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (title == null || title.isBlank()) {
            response.put("language", "en_GB");
            response.put("success", true);
            return response;
        }
        
        try {
            com.optimaize.langdetect.LanguageDetector detector = getLanguageDetector();
            if (detector != null && this.textObjectFactory != null) {
                com.optimaize.langdetect.text.TextObject textObject = this.textObjectFactory.forText(title);
                com.google.common.base.Optional<com.optimaize.langdetect.i18n.LdLocale> langResult = detector.detect(textObject);
                if (langResult.isPresent()) {
                    String detectedCode = langResult.get().getLanguage();
                    String mappedCode = resolveLanguageCodeFromPure(detectedCode);
                    response.put("language", mappedCode);
                    response.put("rawCode", detectedCode);
                    response.put("success", true);
                    return response;
                }
            }
        } catch (Exception e) {
            System.err.println("Error detecting language: " + e.getMessage());
        }
        
        String fallback = fallbackDetectLanguage(title);
        response.put("language", fallback);
        response.put("fallback", true);
        response.put("success", true);
        return response;
    }

    private String resolveLanguageCodeFromPure(String detectedCode) {
        if (detectedCode == null || detectedCode.isBlank()) return "en_GB";
        
        Query query = new Query(Criteria.where("baseUri").is("/dk/atira/pure/core/languages"));
        Document schemeDoc = mongoTemplate.findOne(query, Document.class, "Classificationschemes");
        if (schemeDoc != null) {
            List<Document> contained = null;
            try {
                contained = (List<Document>) schemeDoc.get("containedClassifications");
            } catch (Exception e) {}
            if (contained != null) {
                for (Document c : contained) {
                    String uri = c.getString("uri");
                    if (uri != null) {
                        String code = uri.substring(uri.lastIndexOf('/') + 1);
                        if (code.equalsIgnoreCase(detectedCode) || code.startsWith(detectedCode + "_")) {
                            return code;
                        }
                    }
                }
            }
        }
        
        if ("ca".equalsIgnoreCase(detectedCode)) return "ca_ES";
        if ("es".equalsIgnoreCase(detectedCode)) return "es_ES";
        if ("en".equalsIgnoreCase(detectedCode)) return "en_GB";
        
        return detectedCode;
    }

    private String fallbackDetectLanguage(String title) {
        if (title == null || title.isBlank()) return "ca_ES";
        
        String clean = title.toLowerCase();
        String[] words = clean.split("[\\s,.;:()?!\"'’`·\\-]+");
        
        double caScore = 0;
        double esScore = 0;
        double enScore = 0;
        
        if (clean.contains("ç") || clean.contains("à") || clean.contains("è") || clean.contains("ò") || clean.contains("l·l") 
                || clean.matches(".*\\b(d'|l'|n'|s'|t')\\w+.*")) {
            caScore += 3;
        }
        if (clean.contains("ñ") || clean.contains("¿") || clean.contains("¡")) {
            esScore += 3;
        }
        
        java.util.Set<String> caOnly = java.util.Set.of(
            "i", "els", "les", "amb", "dels", "per", "fins", "pels", "sota", "als", "quelcom", 
            "així", "també", "però", "aquesta", "aquest", "aquests", "aquestes", "seu", "seva", 
            "seus", "seves", "nostre", "nostra", "vostre", "vostra"
        );
        java.util.Set<String> esOnly = java.util.Set.of(
            "y", "los", "las", "con", "por", "para", "bajo", "como", "esta", "este", "estos", 
            "estas", "su", "sus", "nuestro", "nuestra", "vuestro", "vuestra", "pero", "también"
        );
        java.util.Set<String> enOnly = java.util.Set.of(
            "the", "and", "of", "in", "to", "for", "with", "on", "a", "an", "by", "at", 
            "from", "about", "that", "which", "this", "is", "are", "it", "its", "their", 
            "our", "your", "but", "also"
        );
        java.util.Set<String> sharedRomance = java.util.Set.of(
            "de", "que", "la", "en", "del", "un", "una", "sobre", "social", "anàlisi", "analisis", 
            "estudi", "estudio", "desenvolupament", "desarrollo", "investigació", "investigación"
        );
        
        for (String w : words) {
            if (caOnly.contains(w)) caScore += 2;
            if (esOnly.contains(w)) esScore += 2;
            if (enOnly.contains(w)) enScore += 2;
            if (sharedRomance.contains(w)) {
                caScore += 0.5;
                esScore += 0.5;
            }
        }
        
        for (String w : words) {
            if (w.endsWith("ment") && w.length() > 5) {
                caScore += 1;
                enScore += 1;
            }
            if (w.endsWith("miento") && w.length() > 6) esScore += 1;
            if (w.endsWith("ción") || w.endsWith("sión")) esScore += 1;
            if (w.endsWith("ció") || w.endsWith("sió")) caScore += 1;
            if (w.endsWith("tion") || w.endsWith("sion")) enScore += 1;
            if (w.endsWith("ing") && w.length() > 4) enScore += 1;
            if (w.endsWith("ity") && w.length() > 4) enScore += 1;
            if (w.endsWith("ly") && w.length() > 3) enScore += 1;
            if (w.endsWith("tive") && w.length() > 4) enScore += 1;
            if (w.endsWith("sive") && w.length() > 4) enScore += 1;
            if (w.endsWith("nce") && w.length() > 4) enScore += 1;
            if (w.endsWith("ical") && w.length() > 4) enScore += 1;
        }
        
        if (caScore > esScore && caScore > enScore) return "ca_ES";
        if (esScore > caScore && esScore > enScore) return "es_ES";
        if (enScore > caScore && enScore > esScore) return "en_GB";
        
        if (caScore == esScore && caScore > 0) {
            boolean hasCatalanAccents = clean.matches(".*[àèòíóúé].*");
            boolean hasSpanishAccents = clean.matches(".*[áéíóú].*");
            if (hasCatalanAccents && !hasSpanishAccents) return "ca_ES";
            if (hasSpanishAccents && !hasCatalanAccents) return "es_ES";
            
            if (clean.contains(" estudi ") || clean.contains(" d'") || clean.contains(" l'")) return "ca_ES";
            if (clean.contains(" estudio ")) return "es_ES";
        }
        
        return "ca_ES";
    }

    @PostMapping("/{uuid}/language")
    public Map<String, Object> updateLanguage(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        
        String langCode = body.get("language"); // e.g. "ca_ES", "es_ES", "en_GB" or any code from ClassificationSchemes
        String env = body.getOrDefault("env", "test"); // e.g. "test", "prod"
        Map<String, Object> response = new LinkedHashMap<>();
        
        if (langCode == null || langCode.isBlank()) {
            response.put("success", false);
            response.put("message", "Idioma no vàlid");
            return response;
        }
        
        Query query = new Query(Criteria.where("uuid").is(uuid));
        StudentThesis thesis = mongoTemplate.findOne(query, StudentThesis.class);
        
        if (thesis == null) {
            response.put("success", false);
            response.put("message", "Tesi no trobada");
            return response;
        }
        
        // Build UriTerm for language
        StudentThesis.UriTerm language = new StudentThesis.UriTerm();
        language.setUri("/dk/atira/pure/core/languages/" + langCode);
        
        // Let's resolve the term from Classificationschemes dynamically!
        Map<String, String> term = new LinkedHashMap<>();
        boolean resolved = false;
        
        Query langQuery = new Query(Criteria.where("baseUri").is("/dk/atira/pure/core/languages"));
        Document schemeDoc = mongoTemplate.findOne(langQuery, Document.class, "Classificationschemes");
        if (schemeDoc != null) {
            List<Document> contained = null;
            try {
                contained = (List<Document>) schemeDoc.get("containedClassifications");
            } catch (Exception e) {
                // ignore
            }
            if (contained != null) {
                for (Document c : contained) {
                    String uri = c.getString("uri");
                    if (uri != null && uri.endsWith("/" + langCode)) {
                        Document termDoc = (Document) c.get("term");
                        if (termDoc != null) {
                            List<Document> textList = null;
                            try {
                                textList = (List<Document>) termDoc.get("text");
                            } catch (Exception e) {
                                // ignore
                            }
                            if (textList != null) {
                                for (Document t : textList) {
                                    String locale = t.getString("locale");
                                    String value = t.getString("value");
                                    if (locale != null && value != null) {
                                        term.put(locale, value);
                                    }
                                }
                                resolved = true;
                            }
                        }
                        break;
                    }
                }
            }
        }
        
        if (!resolved) {
            // Fallback for standard ones
            if ("ca_ES".equals(langCode)) {
                term.put("ca_ES", "Català");
                term.put("es_ES", "Catalán");
                term.put("en_GB", "Catalan");
            } else if ("es_ES".equals(langCode)) {
                term.put("ca_ES", "Espanyol");
                term.put("es_ES", "Español");
                term.put("en_GB", "Spanish");
            } else if ("en_GB".equals(langCode)) {
                term.put("ca_ES", "Anglès");
                term.put("es_ES", "Inglés");
                term.put("en_GB", "English");
            } else {
                term.put("ca_ES", langCode);
                term.put("es_ES", langCode);
                term.put("en_GB", langCode);
            }
        }
        language.setTerm(term);
        
        // Sync to Egreta/Pure API
        boolean egretaSyncSuccess = syncStudentThesisLanguageToEgreta(uuid, language, env);
        if (!egretaSyncSuccess) {
            response.put("success", false);
            response.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ").");
            return response;
        }
        
        thesis.setLanguage(language);
        mongoTemplate.save(thesis);
        
        response.put("success", true);
        response.put("message", "Idioma actualitzat correctament a Egreta i a la base de dades local.");
        return response;
    }

    private boolean syncStudentThesisLanguageToEgreta(String uuid, StudentThesis.UriTerm language, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;

            // 1. GET
            ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta student-thesis UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify language
            Map<String, Object> langMap = new LinkedHashMap<>();
            langMap.put("uri", language.getUri());
            langMap.put("term", language.getTerm());
            data.put("language", langMap);
            
            // 3. PUT
            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
            ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing student-thesis language to Egreta: " + e.getMessage());
            return false;
        }
    }

    @PostMapping("/{uuid}/abstract")
    public Map<String, Object> updateAbstract(
            @PathVariable String uuid,
            @RequestBody Map<String, Object> body) {
        
        @SuppressWarnings("unchecked")
        Map<String, String> abstracts = (Map<String, String>) body.get("abstracts");
        String env = (String) body.getOrDefault("env", "test");
        Map<String, Object> response = new LinkedHashMap<>();
        
        if (abstracts == null || abstracts.isEmpty()) {
            response.put("success", false);
            response.put("message", "Abstract no vàlid");
            return response;
        }
        
        Query query = new Query(Criteria.where("uuid").is(uuid));
        StudentThesis thesis = mongoTemplate.findOne(query, StudentThesis.class);
        
        if (thesis == null) {
            response.put("success", false);
            response.put("message", "Tesi no trobada");
            return response;
        }
        
        // Sync to Egreta/Pure API
        boolean egretaSyncSuccess = syncStudentThesisAbstractToEgreta(uuid, abstracts, env);
        if (!egretaSyncSuccess) {
            response.put("success", false);
            response.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ").");
            return response;
        }
        
        thesis.setAbstractText(abstracts);
        mongoTemplate.save(thesis);
        
        response.put("success", true);
        response.put("message", "Abstract actualitzat correctament a Egreta i a la base de dades local.");
        return response;
    }

    private boolean syncStudentThesisAbstractToEgreta(String uuid, Map<String, String> abstracts, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;

            // 1. GET
            ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta student-thesis UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify abstract
            data.put("abstract", abstracts);
            
            // 3. PUT
            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
            ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing student-thesis abstract to Egreta: " + e.getMessage());
            return false;
        }
    }

    @PostMapping("/{uuid}/title")
    public Map<String, Object> updateTitle(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        
        String newTitleVal = body.get("title");
        String env = body.getOrDefault("env", "test");
        Map<String, Object> response = new LinkedHashMap<>();
        
        if (newTitleVal == null || newTitleVal.isBlank()) {
            response.put("success", false);
            response.put("message", "Títol no vàlid");
            return response;
        }
        
        Query query = new Query(Criteria.where("uuid").is(uuid));
        StudentThesis thesis = mongoTemplate.findOne(query, StudentThesis.class);
        
        if (thesis == null) {
            response.put("success", false);
            response.put("message", "Tesi no trobada");
            return response;
        }
        
        // Sync to Egreta/Pure API
        boolean egretaSyncSuccess = syncStudentThesisTitleToEgreta(uuid, newTitleVal, env);
        if (!egretaSyncSuccess) {
            response.put("success", false);
            response.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ").");
            return response;
        }
        
        StudentThesis.Title t = thesis.getTitle();
        if (t == null) {
            t = new StudentThesis.Title();
            thesis.setTitle(t);
        }
        t.setValue(newTitleVal);
        mongoTemplate.save(thesis);
        
        response.put("success", true);
        response.put("message", "Títol actualitzat correctament a Egreta i a la base de dades local.");
        return response;
    }

    @PostMapping("/{uuid}/link")
    public Map<String, Object> addLink(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        
        String linkUrl = body.get("link");
        String env = body.getOrDefault("env", "test");
        Map<String, Object> response = new LinkedHashMap<>();
        
        if (linkUrl == null || linkUrl.isBlank()) {
            response.put("success", false);
            response.put("message", "Enllaç no vàlid");
            return response;
        }
        
        Query query = new Query(Criteria.where("uuid").is(uuid));
        StudentThesis thesis = mongoTemplate.findOne(query, StudentThesis.class);
        
        if (thesis == null) {
            response.put("success", false);
            response.put("message", "Tesi no trobada");
            return response;
        }
        
        // Sync to Egreta/Pure API
        boolean egretaSyncSuccess = syncStudentThesisLinkToEgreta(uuid, linkUrl, env);
        if (!egretaSyncSuccess) {
            response.put("success", false);
            response.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ").");
            return response;
        }
        
        // Retrieve updated data from Egreta/Pure API to sync generated pureId correctly
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");
            String baseUrl = "prod".equalsIgnoreCase(env) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;
            ResponseEntity<StudentThesis> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), StudentThesis.class);
            if (getResp.getStatusCode().is2xxSuccessful() && getResp.getBody() != null) {
                StudentThesis updatedThesis = getResp.getBody();
                thesis.setLinks(updatedThesis.getLinks());
            } else {
                // Fallback: manually construct link if GET failed for some reason
                List<StudentThesis.Link> links = thesis.getLinks();
                if (links == null) {
                    links = new ArrayList<>();
                    thesis.setLinks(links);
                }
                boolean exists = false;
                for (StudentThesis.Link l : links) {
                    if (l.getUrl() != null && l.getUrl().equalsIgnoreCase(linkUrl)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    StudentThesis.Link newLink = new StudentThesis.Link();
                    newLink.setUrl(linkUrl);
                    newLink.setAlias("DDD");
                    StudentThesis.UriTerm linkType = new StudentThesis.UriTerm();
                    linkType.setUri("/dk/atira/pure/links/studentthesis/ddd");
                    Map<String, String> term = new LinkedHashMap<>();
                    term.put("en_GB", "DDD");
                    term.put("es_ES", "DDD");
                    term.put("ca_ES", "DDD");
                    linkType.setTerm(term);
                    newLink.setLinkType(linkType);
                    links.add(newLink);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching updated thesis from Egreta after link update: " + e.getMessage());
            // Fallback: manually construct link
            List<StudentThesis.Link> links = thesis.getLinks();
            if (links == null) {
                links = new ArrayList<>();
                thesis.setLinks(links);
            }
            boolean exists = false;
            for (StudentThesis.Link l : links) {
                if (l.getUrl() != null && l.getUrl().equalsIgnoreCase(linkUrl)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                StudentThesis.Link newLink = new StudentThesis.Link();
                newLink.setUrl(linkUrl);
                newLink.setAlias("DDD");
                StudentThesis.UriTerm linkType = new StudentThesis.UriTerm();
                linkType.setUri("/dk/atira/pure/links/studentthesis/ddd");
                Map<String, String> term = new LinkedHashMap<>();
                term.put("en_GB", "DDD");
                term.put("es_ES", "DDD");
                term.put("ca_ES", "DDD");
                linkType.setTerm(term);
                newLink.setLinkType(linkType);
                links.add(newLink);
            }
        }
        
        mongoTemplate.save(thesis);
        
        response.put("success", true);
        response.put("message", "Enllaç afegit correctament a Egreta i a la base de dades local.");
        
        List<String> dddUrls = new ArrayList<>();
        if (thesis.getLinks() != null) {
            for (StudentThesis.Link l : thesis.getLinks()) {
                if (l.getUrl() != null && isDddLink(l)) {
                    dddUrls.add(l.getUrl());
                }
            }
        }
        response.put("links", dddUrls);
        return response;
    }

    @PostMapping("/{uuid}/link/delete")
    public Map<String, Object> deleteLink(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        
        String linkUrl = body.get("link");
        String env = body.getOrDefault("env", "test");
        Map<String, Object> response = new LinkedHashMap<>();
        
        if (linkUrl == null || linkUrl.isBlank()) {
            response.put("success", false);
            response.put("message", "Enllaç no vàlid");
            return response;
        }
        
        Query query = new Query(Criteria.where("uuid").is(uuid));
        StudentThesis thesis = mongoTemplate.findOne(query, StudentThesis.class);
        
        if (thesis == null) {
            response.put("success", false);
            response.put("message", "Tesi no trobada");
            return response;
        }
        
        // Sync delete to Egreta/Pure API
        boolean egretaSyncSuccess = syncStudentThesisDeleteLinkToEgreta(uuid, linkUrl, env);
        if (!egretaSyncSuccess) {
            response.put("success", false);
            response.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ").");
            return response;
        }
        
        // Retrieve updated data from Egreta/Pure API to sync generated pureId correctly
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");
            String baseUrl = "prod".equalsIgnoreCase(env) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;
            ResponseEntity<StudentThesis> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), StudentThesis.class);
            if (getResp.getStatusCode().is2xxSuccessful() && getResp.getBody() != null) {
                StudentThesis updatedThesis = getResp.getBody();
                thesis.setLinks(updatedThesis.getLinks());
            } else {
                // Fallback: manually delete link if GET failed
                List<StudentThesis.Link> links = thesis.getLinks();
                if (links != null) {
                    links.removeIf(l -> l.getUrl() != null && l.getUrl().equalsIgnoreCase(linkUrl));
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching updated thesis from Egreta after link deletion: " + e.getMessage());
            // Fallback: manually delete link
            List<StudentThesis.Link> links = thesis.getLinks();
            if (links != null) {
                links.removeIf(l -> l.getUrl() != null && l.getUrl().equalsIgnoreCase(linkUrl));
            }
        }
        
        mongoTemplate.save(thesis);
        
        response.put("success", true);
        response.put("message", "Enllaç eliminat correctament a Egreta i a la base de dades local.");
        
        List<String> dddUrls = new ArrayList<>();
        if (thesis.getLinks() != null) {
            for (StudentThesis.Link l : thesis.getLinks()) {
                if (l.getUrl() != null && isDddLink(l)) {
                    dddUrls.add(l.getUrl());
                }
            }
        }
        response.put("links", dddUrls);
        return response;
    }

    private boolean syncStudentThesisTitleToEgreta(String uuid, String newTitle, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;

            // 1. GET
            ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta student-thesis UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify title
            Map<String, Object> titleMap = new LinkedHashMap<>();
            titleMap.put("value", newTitle);
            data.put("title", titleMap);
            
            // 3. PUT
            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
            ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing student-thesis title to Egreta: " + e.getMessage());
            return false;
        }
    }

    private boolean syncStudentThesisLinkToEgreta(String uuid, String linkUrl, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;

            // 1. GET
            ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta student-thesis UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify links list
            List<Map<String, Object>> linksList = (List<Map<String, Object>>) data.get("links");
            if (linksList == null) {
                linksList = new ArrayList<>();
            } else {
                linksList = new ArrayList<>(linksList);
            }
            
            boolean linkExists = false;
            for (Map<String, Object> existingLink : linksList) {
                String existingUrl = (String) existingLink.get("url");
                if (existingUrl != null && existingUrl.trim().equalsIgnoreCase(linkUrl.trim())) {
                    linkExists = true;
                    break;
                }
            }
            
            if (!linkExists) {
                Map<String, Object> newLinkMap = new LinkedHashMap<>();
                newLinkMap.put("url", linkUrl);
                newLinkMap.put("alias", "DDD");
                
                Map<String, Object> linkTypeMap = new LinkedHashMap<>();
                linkTypeMap.put("uri", "/dk/atira/pure/links/studentthesis/ddd");
                
                Map<String, String> termMap = new LinkedHashMap<>();
                termMap.put("en_GB", "DDD");
                termMap.put("es_ES", "DDD");
                termMap.put("ca_ES", "DDD");
                linkTypeMap.put("term", termMap);
                
                newLinkMap.put("linkType", linkTypeMap);
                linksList.add(newLinkMap);
                
                data.put("links", linksList);
            }
            
            // 3. PUT
            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
            ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing student-thesis link to Egreta: " + e.getMessage());
            return false;
        }
    }

    private boolean syncStudentThesisDeleteLinkToEgreta(String uuid, String linkUrl, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(targetEnv) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "student-theses/" + uuid;

            // 1. GET
            ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta student-thesis UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify links list (remove match)
            List<Map<String, Object>> linksList = (List<Map<String, Object>>) data.get("links");
            if (linksList != null) {
                linksList = new ArrayList<>(linksList);
                linksList.removeIf(linkMap -> {
                    String urlVal = (String) linkMap.get("url");
                    return urlVal != null && urlVal.trim().equalsIgnoreCase(linkUrl.trim());
                });
                data.put("links", linksList);
            }
            
            // 3. PUT
            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
            ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing student-thesis delete link to Egreta: " + e.getMessage());
            return false;
        }
    }



    private boolean isDddLink(StudentThesis.Link link) {
        if (link == null) return false;
        if (link.getLinkType() != null && link.getLinkType().getUri() != null) {
            String uri = link.getLinkType().getUri().toLowerCase();
            if (uri.equals("/dk/atira/pure/links/studentthesis/ddd") || uri.endsWith("/ddd")) {
                return true;
            }
        }
        if (link.getUrl() != null && link.getUrl().toLowerCase().contains("ddd.uab.cat")) {
            return true;
        }
        if (link.getLinkType() != null && link.getLinkType().getTerm() != null) {
            for (String termVal : link.getLinkType().getTerm().values()) {
                if (termVal != null && "ddd".equalsIgnoreCase(termVal.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTitleUppercase(String str) {
        if (str == null || str.isBlank()) return false;
        boolean hasLetter = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return hasLetter;
    }

    @GetMapping("/suggest-ddd")
    public Map<String, Object> suggestDddLink(@RequestParam String uuid) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        StudentThesis thesis = mongoTemplate.findOne(new Query(Criteria.where("uuid").is(uuid)), StudentThesis.class);
        if (thesis == null) {
            result.put("success", false);
            result.put("error", "Tesis no trobada");
            return result;
        }

        String title = thesis.getFullTitle();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> suggestedLinks = new ArrayList<>();
        String strategyUsed = "Cap";
        String queryUsed = "";

        // 1. Try Title search first
        String titleQuery = getTitleQuery(title);
        if (!titleQuery.isEmpty()) {
            List<String> titleResults = performDddSearch(titleQuery, seen);
            if (!titleResults.isEmpty()) {
                suggestedLinks.addAll(titleResults);
                strategyUsed = "Títol";
                queryUsed = titleQuery;
            }
        }

        // 2. Fallback to Author search
        if (suggestedLinks.isEmpty()) {
            String authorQuery = getAuthorQuery(thesis);
            if (!authorQuery.isEmpty()) {
                List<String> authorResults = performDddSearch(authorQuery, seen);
                if (!authorResults.isEmpty()) {
                    suggestedLinks.addAll(authorResults);
                    strategyUsed = "Autor";
                    queryUsed = authorQuery;
                }
            }
        }

        if (suggestedLinks.isEmpty()) {
            result.put("success", false);
            result.put("error", "No s'han trobat suggeriments al DDD");
            return result;
        }

        result.put("success", true);
        result.put("links", suggestedLinks);
        result.put("strategy", strategyUsed);
        result.put("query", queryUsed);

        return result;
    }

    @GetMapping("/suggest-tdx")
    public Map<String, Object> suggestTdxAbstract(@RequestParam String uuid) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        StudentThesis thesis = mongoTemplate.findOne(new org.springframework.data.mongodb.core.query.Query(
            org.springframework.data.mongodb.core.query.Criteria.where("uuid").is(uuid)
        ), StudentThesis.class);
        
        if (thesis == null) {
            result.put("success", false);
            result.put("error", "Tesis no trobada");
            return result;
        }

        // Find TDX handle in thesis links
        String handle = null;
        String handleUrl = null;
        if (thesis.getLinks() != null) {
            for (StudentThesis.Link link : thesis.getLinks()) {
                String url = link.getUrl();
                if (url != null && (url.toLowerCase().contains("tdx.cat") || url.toLowerCase().contains("handle"))) {
                    String extracted = extractHandleFromUrl(url);
                    if (extracted != null) {
                        handle = extracted;
                        handleUrl = url;
                        break;
                    }
                }
            }
        }

        if (handle == null) {
            String authorLastName = "";
            if (thesis.getContributors() != null && !thesis.getContributors().isEmpty()) {
                StudentThesis.Contributor c = thesis.getContributors().get(0);
                if (c.getName() != null && c.getName().getLastName() != null) {
                    authorLastName = c.getName().getLastName();
                }
            }
            authorLastName = authorLastName.trim();

            String cleanTitle = thesis.getFullTitle();
            if (cleanTitle != null) {
                cleanTitle = cleanTitle.replace("\uFFFD", " ").trim();
                cleanTitle = cleanTitle.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚçÇñÑàèòÀÈÒïüÏÜ\\s]", " ");
                String[] words = cleanTitle.split("\\s+");
                List<String> firstWords = new ArrayList<>();
                for (int i = 0; i < Math.min(words.length, 6); i++) {
                    if (!words[i].isBlank()) {
                        firstWords.add(words[i]);
                    }
                }
                cleanTitle = String.join(" ", firstWords);
            }

            if (cleanTitle != null && !cleanTitle.isBlank()) {
                handle = findHandleInTdx(cleanTitle, authorLastName);
                if (handle == null) {
                    handle = findHandleInTdx(cleanTitle, "");
                }
            }
            
            if (handle != null) {
                handleUrl = "https://www.tdx.cat/handle/" + handle;
            }
        }

        if (handle == null) {
            result.put("success", false);
            result.put("error", "No s'ha pogut trobar aquesta tesi a TDX (ni per enllaç de handle ni per cerca de títol i autor a la UAB).");
            return result;
        }

        // Query TDX OAI-PMH using GetRecord and metadataPrefix=dim
        try {
            String oaiUrl = "https://www.tdx.cat/oai/request?verb=GetRecord&metadataPrefix=dim&identifier=oai:tdx.cat:" + handle;
            String xml = fetchUrlHtml(oaiUrl);
            if (xml == null || xml.isEmpty()) {
                result.put("success", false);
                result.put("error", "Error en connectar amb el servidor OAI de TDX.");
                return result;
            }

            // Parse abstracts from DIM XML
            Map<String, String> abstracts = extractAbstractsFromDimXml(xml);
            if (abstracts.isEmpty()) {
                result.put("success", false);
                result.put("error", "No s'han trobat abstracts per a aquesta tesi al servidor OAI de TDX.");
                return result;
            }

            result.put("success", true);
            List<Map<String, Object>> resultsList = new ArrayList<>();
            Map<String, Object> singleResult = new LinkedHashMap<>();
            singleResult.put("handleUrl", handleUrl);
            singleResult.put("abstracts", abstracts);
            resultsList.add(singleResult);
            result.put("results", resultsList);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error en processar la resposta de l'OAI: " + e.getMessage());
            return result;
        }
    }

    private String extractHandleFromUrl(String url) {
        if (url == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("10803/\\d+");
        java.util.regex.Matcher m = p.matcher(url);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    private String findHandleInTdx(String cleanTitle, String authorLastName) {
        try {
            String queryStr = "title:(" + cleanTitle + ")";
            if (authorLastName != null && !authorLastName.isBlank()) {
                queryStr += " AND " + authorLastName;
            }
            String encodedQuery = java.net.URLEncoder.encode(queryStr, "UTF-8");
            String searchUrl = "https://www.tdx.cat/discover?query=" + encodedQuery + "&scope=10803/120";
            
            String html = fetchUrlHtml(searchUrl);
            if (html == null || html.isEmpty()) {
                return null;
            }
            
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("10803/\\d+");
            java.util.regex.Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                return matcher.group();
            }
        } catch (Exception e) {
            System.err.println("Error searching TDX: " + e.getMessage());
        }
        return null;
    }

    private Map<String, String> extractAbstractsFromDimXml(String xml) {
        Map<String, String> abstracts = new LinkedHashMap<>();
        
        // Match: <dim:field element="description" qualifier="abstract" lang="cat">Abstract text</dim:field>
        java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
            "<dim:field[^>]*>",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = fieldPattern.matcher(xml);
        
        int startPos = 0;
        while (matcher.find(startPos)) {
            String tag = matcher.group();
            int tagStart = matcher.start();
            int tagEnd = matcher.end();
            
            // Check if element="description" and qualifier="abstract"
            boolean isAbstract = tag.contains("element=\"description\"") && tag.contains("qualifier=\"abstract\"");
            if (isAbstract) {
                // Find closing tag </dim:field>
                int closeTagStart = xml.indexOf("</dim:field>", tagEnd);
                if (closeTagStart != -1) {
                    String content = xml.substring(tagEnd, closeTagStart);
                    
                    // Extract lang attribute
                    java.util.regex.Pattern langPattern = java.util.regex.Pattern.compile(
                        "lang=\"([^\"]*)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                    );
                    java.util.regex.Matcher langMatcher = langPattern.matcher(tag);
                    String lang = "unknown";
                    if (langMatcher.find()) {
                        lang = langMatcher.group(1).toLowerCase();
                    }
                    
                    content = org.springframework.web.util.HtmlUtils.htmlUnescape(content).trim();
                    if (!content.isEmpty()) {
                        String uniqueLang = lang;
                        int suffix = 2;
                        while (abstracts.containsKey(uniqueLang)) {
                            uniqueLang = lang + "_" + suffix;
                            suffix++;
                        }
                        abstracts.put(uniqueLang, content);
                    }
                    startPos = closeTagStart + 12;
                    continue;
                }
            }
            startPos = tagEnd;
        }
        return abstracts;
    }

    private String fetchUrlHtml(String targetUrl) {
        try {
            java.net.URL url = new java.net.URL(targetUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            int status = conn.getResponseCode();
            if (status != 200) {
                return null;
            }

            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine).append("\n");
            }
            in.close();
            conn.disconnect();

            return content.toString();
        } catch (Exception e) {
            System.err.println("Error fetching URL: " + targetUrl + " - " + e.getMessage());
            return null;
        }
    }

    private String getTitleQuery(String title) {
        if (title == null || title.isBlank()) return "";
        String clean = title.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚçÇñÑàèòÀÈÒïüÏÜ]", " ").trim();
        String[] words = clean.split("\\s+");
        List<String> parts = new ArrayList<>();
        int count = 0;
        for (String w : words) {
            if (w.length() >= 4) {
                parts.add(w);
                count++;
                if (count >= 5) {
                    break;
                }
            }
        }
        return String.join(" ", parts).trim();
    }

    private String getAuthorQuery(StudentThesis thesis) {
        if (thesis.getContributors() == null || thesis.getContributors().isEmpty()) return "";
        StudentThesis.Contributor c = thesis.getContributors().get(0);
        if (c.getName() == null) return "";
        String ln = c.getName().getLastName() != null ? c.getName().getLastName() : "";
        String fn = c.getName().getFirstName() != null ? c.getName().getFirstName() : "";
        
        String cleanLn = ln.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚçÇñÑàèòÀÈÒïüÏÜ]", " ").trim();
        String cleanFn = fn.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚçÇñÑàèòÀÈÒïüÏÜ]", " ").trim();
        
        return (cleanLn + " " + cleanFn).trim();
    }

    private List<String> performDddSearch(String searchQuery, java.util.Set<String> seen) {
        List<String> links = new ArrayList<>();
        try {
            String encodedQuery = java.net.URLEncoder.encode(searchQuery, "UTF-8");
            String dddUrl = "https://ddd.uab.cat/search?ln=ca&cc=tesis&p=" + encodedQuery 
                + "&f=&action_search=Cerca&c=tesis&c=&sf=&so=d&rm=&rg=10&sc=1&of=hb";
            
            java.net.URL url = new java.net.URL(dddUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            int status = conn.getResponseCode();
            if (status != 200) {
                return links;
            }

            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine).append("\n");
            }
            in.close();
            conn.disconnect();

            String html = content.toString();
            
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/record/(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(html);
            
            int count = 0;
            while (matcher.find()) {
                String rid = matcher.group(1);
                if (seen.add(rid)) {
                    links.add("https://ddd.uab.cat/record/" + rid);
                    count++;
                    if (count >= 5) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore connection errors and fall through
        }
        return links;
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
