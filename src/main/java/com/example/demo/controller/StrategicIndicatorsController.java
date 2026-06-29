package com.example.demo.controller;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.AwardService;
import com.example.demo.service.ResearchOutputJournalLinkService;

import java.util.*;

@RestController
@RequestMapping("/api/strategic-indicators")
@CrossOrigin(origins = "*")
public class StrategicIndicatorsController {

    private final MongoTemplate mongoTemplate;
    private final AwardService awardService;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;

    @Autowired
    public StrategicIndicatorsController(MongoTemplate mongoTemplate, 
                                         AwardService awardService, 
                                         ResearchOutputJournalLinkService researchOutputJournalLinkService) {
        this.mongoTemplate = mongoTemplate;
        this.awardService = awardService;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats(
            @RequestParam(required = false, defaultValue = "2024") int year,
            @RequestParam(required = false, defaultValue = "all") String dept) {

        String collaboratorUuid = "all".equalsIgnoreCase(dept) ? null : dept;
        List<Document> powerTableRows = awardService.getPowerTable(year, year, "awardDate", collaboratorUuid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("year", year);
        response.put("dept", dept);

        // 1. PDI SGR Doctors
        long sgrDoctors = getSgrDoctorsCount(year, dept);
        response.put("sgrDoctors", sgrDoctors);

        // 2. Lideratge (L) en projectes europeus
        long europeanProjectsLead = getEuropeanProjectsLeadCount(powerTableRows, year, dept);
        response.put("europeanProjectsLead", europeanProjectsLead);

        // 3. Participació (P) en projectes europeus
        long europeanProjectsPart = getEuropeanProjectsPartCount(powerTableRows, year, dept);
        if (europeanProjectsPart < europeanProjectsLead) {
            europeanProjectsPart = europeanProjectsLead;
        }
        response.put("europeanProjectsPart", europeanProjectsPart);

        // 3. Competitive Funding (in Millions)
        double competitiveFunding = getCompetitiveFunding(powerTableRows, year, dept);
        response.put("competitiveFunding", competitiveFunding);

        // 4. Non-Competitive Funding (in Millions)
        double nonCompetitiveFunding = getNonCompetitiveFunding(powerTableRows, year, dept);
        response.put("nonCompetitiveFunding", nonCompetitiveFunding);

        // 5. Q1 + Q2 Publications
        long q1q2Publications = getQ1Q2Publications(year, collaboratorUuid, dept);
        response.put("q1q2Publications", q1q2Publications);

        // 6. Incubated Companies
        long incubatedCompanies = getIncubatedCompanies(year, dept);
        response.put("incubatedCompanies", incubatedCompanies);

        // 7. Transfer Agreements (Patents / Know-how)
        long transferAgreements = getTransferAgreements(year, dept);
        response.put("transferAgreements", transferAgreements);

        // 8. Trams de recerca vius (extracted from Excel)
        long tramsVius = getTramsViusCountFromExcel(year, dept);
        response.put("tramsVius", tramsVius);

        return response;
    }

    private List<String> getSgrGroupUuids() {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").in("Grup de Recerca", "Grup de Recerca UAB"));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        List<Document> orgs = mongoTemplate.find(query, Document.class, "Organizations");
        List<String> uuids = new ArrayList<>();
        for (Document org : orgs) {
            String uuid = org.getString("uuid");
            if (uuid != null) uuids.add(uuid);
        }
        return uuids;
    }

    private long getSgrDoctorsCount(int year, String dept) {
        String dateBoundary = year + "-01-01";
        List<String> doctorUris = List.of(
            "/dk/atira/pure/person/employmenttypes/agregat_contractat",
            "/dk/atira/pure/person/employmenttypes/catedratics_contractat",
            "/dk/atira/pure/person/employmenttypes/catedratics",
            "/dk/atira/pure/person/employmenttypes/catedratics_escola",
            "/dk/atira/pure/person/employmenttypes/direccio_investigacio",
            "/dk/atira/pure/person/employmenttypes/investigador_ordinari",
            "/dk/atira/pure/person/employmenttypes/professor_titular_universitat",
            "/dk/atira/pure/person/employmenttypes/professor_titular_escola_universitat",
            "/dk/atira/pure/person/employmenttypes/professor_titular_universitat_interi",
            "/dk/atira/pure/person/employmenttypes/investigador_postdoctoral_projectes",
            "/dk/atira/pure/person/employmenttypes/investigador_doctor_distingit",
            "/dk/atira/pure/person/employmenttypes/investigador_postdoctoral_indefit",
            "/dk/atira/pure/person/employmenttypes/investigador_beatriupinos",
            "/dk/atira/pure/person/employmenttypes/investigador_juandelacierva",
            "/dk/atira/pure/person/employmenttypes/agregat_interi",
            "/dk/atira/pure/person/employmenttypes/investigador_beatrizgalindo",
            "/dk/atira/pure/person/employmenttypes/catedratic_contractat_interi",
            "/dk/atira/pure/person/employmenttypes/investigador_margaritasalas",
            "/dk/atira/pure/person/employmenttypes/investigador_mariazambrano",
            "/dk/atira/pure/person/employmenttypes/investigador_ramonycajal",
            "/dk/atira/pure/person/employmenttypes/professor_lector_ajudant",
            "/dk/atira/pure/person/employmenttypes/professor_lector_interi"
        );

        List<Criteria> matchCriteriaList = new ArrayList<>();

        // 1. Doctor active contract criteria
        Criteria doctorContractCriteria = Criteria.where("employmentType.uri").in(doctorUris)
            .orOperator(
                Criteria.where("period.endDate").exists(false),
                Criteria.where("period.endDate").is(null),
                Criteria.where("period.endDate").gt(dateBoundary)
            );
        matchCriteriaList.add(Criteria.where("staffOrganizationAssociations").elemMatch(doctorContractCriteria));

        // 2. Department active contract criteria (if dept is specified)
        if (dept != null && !"all".equalsIgnoreCase(dept)) {
            Criteria deptContractCriteria = Criteria.where("organization.uuid").is(dept)
                .orOperator(
                    Criteria.where("period.endDate").exists(false),
                    Criteria.where("period.endDate").is(null),
                    Criteria.where("period.endDate").gt(dateBoundary)
                );
            matchCriteriaList.add(Criteria.where("staffOrganizationAssociations").elemMatch(deptContractCriteria));
        }

        AggregationOperation matchStage = Aggregation.match(new Criteria().andOperator(matchCriteriaList.toArray(new Criteria[0])));

        // 3. AddFields for active category and SGR orgs
        Document addFieldsDoc = new Document("$addFields", new Document()
            .append("categoriaActiva", new Document("$arrayElemAt", Arrays.asList(
                new Document("$filter", new Document()
                    .append("input", "$staffOrganizationAssociations")
                    .append("as", "a")
                    .append("cond", new Document("$and", Arrays.asList(
                        new Document("$or", Arrays.asList(
                            new Document("$not", new Document("$ifNull", Arrays.asList("$$a.period.endDate", false))),
                            new Document("$gt", Arrays.asList("$$a.period.endDate", dateBoundary))
                        )),
                        new Document("$in", Arrays.asList("$$a.employmentType.uri", doctorUris))
                    )))
                ),
                0
            )))
            .append("sgrOrgUuids", new Document("$map", new Document()
                .append("input", new Document("$filter", new Document()
                    .append("input", "$staffOrganizationAssociations")
                    .append("as", "a")
                    .append("cond", new Document("$and", Arrays.asList(
                        new Document("$or", Arrays.asList(
                            new Document("$not", new Document("$ifNull", Arrays.asList("$$a.period.endDate", false))),
                            new Document("$gt", Arrays.asList("$$a.period.endDate", dateBoundary))
                        )),
                        new Document("$eq", Arrays.asList("$$a.employmentType.uri", "/dk/atira/pure/person/employmenttypes/adscripcio_recerca"))
                    )))
                ))
                .append("as", "a")
                .append("in", "$$a.organization.uuid")
            ))
        );
        AggregationOperation addFieldsStage = context -> addFieldsDoc;

        // 4. Lookup from Organizations
        AggregationOperation lookupStage = Aggregation.lookup("Organizations", "sgrOrgUuids", "uuid", "sgrOrgs");

        // 5. AddFields for SGR Org Filtering
        Document addFields2Doc = new Document("$addFields", new Document("sgrOrgs",
            new Document("$filter", new Document()
                .append("input", "$sgrOrgs")
                .append("as", "o")
                .append("cond", new Document("$gt", Arrays.asList(
                    new Document("$size", new Document("$filter", new Document()
                        .append("input", new Document("$ifNull", Arrays.asList("$$o.identifiers", Collections.emptyList())))
                        .append("as", "id")
                        .append("cond", new Document("$regexMatch", new Document()
                            .append("input", new Document("$ifNull", Arrays.asList("$$id.type.uri", "")))
                            .append("regex", "sgr")
                        ))
                    )),
                    0
                )))
            )
        ));
        AggregationOperation addFields2Stage = context -> addFields2Doc;

        // 6. Match stage to filter out empty SGR Orgs
        AggregationOperation match2Stage = Aggregation.match(Criteria.where("sgrOrgs").ne(Collections.emptyList()));

        // 7. Count stage
        AggregationOperation countStage = Aggregation.count().as("total");

        List<Document> results = mongoTemplate.aggregate(
            Aggregation.newAggregation(
                matchStage,
                addFieldsStage,
                lookupStage,
                addFields2Stage,
                match2Stage,
                countStage
            ),
            "Persons",
            Document.class
        ).getMappedResults();

        return results.isEmpty() ? 0L : ((Number) results.get(0).get("total")).longValue();
    }

    private long getEuropeanProjectsLeadCount(List<Document> powerTableRows, int year, String dept) {
        long count = 0;
        for (Document doc : powerTableRows) {
            String tipo = doc.getString("tipo");
            Boolean esLider = doc.getBoolean("esLider");
            if (tipo != null && "Programa Marc Europeu".equalsIgnoreCase(tipo) && Boolean.TRUE.equals(esLider)) {
                Number ajuts = doc.get("ajuts", Number.class);
                if (ajuts != null) {
                    count += ajuts.longValue();
                }
            }
        }
        return count;
    }

    private long getEuropeanProjectsPartCount(List<Document> powerTableRows, int year, String dept) {
        long count = 0;
        for (Document doc : powerTableRows) {
            String tipo = doc.getString("tipo");
            if (tipo != null && "Programa Marc Europeu".equalsIgnoreCase(tipo)) {
                Number ajuts = doc.get("ajuts", Number.class);
                if (ajuts != null) {
                    count += ajuts.longValue();
                }
            }
        }
        return count;
    }

    private double getCompetitiveFunding(List<Document> powerTableRows, int year, String dept) {
        double total = 0;
        for (Document doc : powerTableRows) {
            String cat = doc.getString("categoria");
            if (cat != null && cat.startsWith("Ajudes competitives")) {
                Object amountObj = doc.get("import");
                if (amountObj instanceof Number) {
                    total += ((Number) amountObj).doubleValue();
                }
            }
        }
        return Math.round((total / 1_000_000.0) * 100.0) / 100.0;
    }

    private double getNonCompetitiveFunding(List<Document> powerTableRows, int year, String dept) {
        double total = 0;
        for (Document doc : powerTableRows) {
            String cat = doc.getString("categoria");
            if (cat != null && (cat.startsWith("Prestaci") || cat.equals("Ajudes no competitives nacionals"))) {
                Object amountObj = doc.get("import");
                if (amountObj instanceof Number) {
                    total += ((Number) amountObj).doubleValue();
                }
            }
        }
        return Math.round((total / 1_000_000.0) * 100.0) / 100.0;
    }

    private long getQ1Q2Publications(int year, String collaboratorUuid, String dept) {
        try {
            Map<String, Object> pubStats = researchOutputJournalLinkService.quartilesDashboardByDepartment(
                    collaboratorUuid, year, year, "vigent", null);
            if (pubStats != null && pubStats.containsKey("quartiles")) {
                List<?> quartiles = (List<?>) pubStats.get("quartiles");
                long q1q2Count = 0;
                for (Object q : quartiles) {
                    if (q instanceof Map) {
                        Map<?, ?> qMap = (Map<?, ?>) q;
                        String label = String.valueOf(qMap.get("quartile"));
                        if ("Q1".equalsIgnoreCase(label) || "Q2".equalsIgnoreCase(label)) {
                            Number count = (Number) qMap.get("total");
                            if (count != null) {
                                q1q2Count += count.longValue();
                            }
                        }
                    }
                }
                return q1q2Count;
            }
        } catch (Exception e) {
            // Fall through — return 0
        }
        return 0;
    }

    private long getIncubatedCompanies(int year, String dept) {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").is("Empresa Esfera"));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        return mongoTemplate.count(query, "Organizations");
    }

    private long getTransferAgreements(int year, String dept) {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").regex(".*Patent.*", "i"));
        return mongoTemplate.count(query, "Researchoutputs");
    }

    private long getTramsViusCountFromExcel(int year, String dept) {
        if (!"all".equalsIgnoreCase(dept)) {
            return 0;
        }
        long count = 0;
        try {
            org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("Trams de recerca vius de la UAB (RC0019).xlsx");
            if (resource.exists()) {
                try (java.io.InputStream is = resource.getInputStream();
                     org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(is)) {
                    org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
                    String targetDateLabel = "Desembre " + year;
                    for (int r = 6; r <= sheet.getLastRowNum(); r++) {
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                        if (row == null) continue;
                        String dateVal = getCellStringValue(row.getCell(0));
                        String typeVal = getCellStringValue(row.getCell(1));
                        if (targetDateLabel.equalsIgnoreCase(dateVal) &&
                            ("Estatals".equalsIgnoreCase(typeVal) || "Bàsics".equalsIgnoreCase(typeVal))) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(7);
                            if (cell != null) {
                                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                                    count += (long) cell.getNumericCellValue();
                                } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                    try {
                                        count += Long.parseLong(cell.getStringCellValue().trim());
                                    } catch (NumberFormatException e) {
                                        // Ignore unparseable cells
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading Trams excel: " + e.getMessage());
        }
        // Return exact Excel value. 0 means no data available for this year.
        return count;
    }

    private String getCellStringValue(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
            return cell.getStringCellValue().trim();
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            return String.valueOf((int) cell.getNumericCellValue());
        }
        return null;
    }
}
