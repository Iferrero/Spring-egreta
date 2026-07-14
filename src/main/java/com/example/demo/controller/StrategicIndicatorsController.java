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
    private final Map<String, Long> tramsViusCache = new java.util.concurrent.ConcurrentHashMap<>();

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
            if (cat != null && (cat.startsWith("Prestaci") || cat.equals("Ajudes no competitives"))) {
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
        String cacheKey = year + "-" + dept;
        if (tramsViusCache.containsKey(cacheKey)) {
            return tramsViusCache.get(cacheKey);
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
        tramsViusCache.put(cacheKey, count);
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

    @GetMapping("/financament-recerca")
    public Map<String, Object> getFinancamentRecercaStats() {
        // 1. Fetch all active organizations
        Query allOrgsQuery = new Query();
        allOrgsQuery.fields().include("uuid").include("name").include("type").include("parents").include("lifecycle");
        List<Document> allOrgs = mongoTemplate.find(allOrgsQuery, Document.class, "Organizations");
        
        Map<String, Document> allOrgsMap = new HashMap<>();
        List<Document> activeOrgs = new ArrayList<>();
        for (Document o : allOrgs) {
            String u = o.getString("uuid");
            if (u != null) {
                allOrgsMap.put(u, o);
            }
            if (isOrgActive(o)) {
                activeOrgs.add(o);
            }
        }

        Set<String> sphereTypes = Set.of(
            "Centres amb conveni de participació en l'esfera UAB",
            "Empresa Esfera",
            "Centres de recerca en el campus de la UAB",
            "Centres de recerca participats",
            "Centres del CSIC amb conveni amb la UAB"
        );

        final String UAB_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";
        final String ESFERA_UUID = "53d7b18a-caf7-4ded-840e-942ff50adc82";

        Map<String, String> resolvedParentMap = new HashMap<>();
        for (Document org : activeOrgs) {
            String uuid = org.getString("uuid");
            if (UAB_UUID.equals(uuid) || ESFERA_UUID.equals(uuid)) {
                resolvedParentMap.put(uuid, null);
                continue;
            }

            String foundParentUuid = null;
            Document current = org;
            java.util.Set<String> visited = new java.util.HashSet<>();

            while (current != null) {
                @SuppressWarnings("unchecked")
                List<Document> parentsList = (List<Document>) current.get("parents");
                if (parentsList == null || parentsList.isEmpty()) {
                    break;
                }
                
                String pUuid = parentsList.get(0).getString("uuid");
                if (visited.contains(pUuid)) {
                    break;
                }
                visited.add(pUuid);
                
                Document parentDoc = allOrgsMap.get(pUuid);
                if (parentDoc != null && isOrgActive(parentDoc)) {
                    foundParentUuid = pUuid;
                    break;
                }
                current = parentDoc;
            }

            if (foundParentUuid != null) {
                resolvedParentMap.put(uuid, foundParentUuid);
            } else {
                String typeCa = "";
                Document typeDoc = (Document) org.get("type");
                if (typeDoc != null) {
                    Document termDoc = (Document) typeDoc.get("term");
                    if (termDoc != null) {
                        typeCa = termDoc.getString("ca_ES");
                    }
                }
                if (typeCa == null) typeCa = "";
                boolean isEsfera = sphereTypes.contains(typeCa);
                resolvedParentMap.put(uuid, isEsfera ? ESFERA_UUID : UAB_UUID);
            }
        }

        Map<String, Boolean> orgEsferaMap = new HashMap<>();
        for (Document org : activeOrgs) {
            String uuid = org.getString("uuid");
            orgEsferaMap.put(uuid, belongsToEsfera(uuid, resolvedParentMap));
        }

        Query awardQuery = new Query();
        Criteria approvedConveni = Criteria.where("type.term.ca_ES").is("Conveni extern a la UAB")
                                            .and("workflow.step").is("approved");
        Criteria allValidated = Criteria.where("workflow.step").is("validated");
        awardQuery.addCriteria(new Criteria().orOperator(approvedConveni, allValidated));
        
        awardQuery.fields().include("uuid").include("awardDate").include("categoria").include("type.term.ca_ES")
                           .include("managingOrganization.uuid").include("coManagingOrganizations.uuid").include("fundings");
        
        List<Document> awards = mongoTemplate.find(awardQuery, Document.class, "Awards");

        // Helper class to collect yearly statistics
        class YearStats {
            double totalUab = 0.0;
            double totalEsfera = 0.0;
            double compUab = 0.0;
            double compEsfera = 0.0;
            double nocompUab = 0.0;
            double nocompEsfera = 0.0;
            double estatals = 0.0;
            double internacionals = 0.0;
            double convenis = 0.0;
            double serveis = 0.0;
        }

        Map<Integer, YearStats> stats = new TreeMap<>();

        for (Document aw : awards) {
            Object dateVal = aw.get("awardDate");
            Integer year = null;
            if (dateVal instanceof java.util.Date date) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(date);
                year = cal.get(java.util.Calendar.YEAR);
            } else if (dateVal instanceof java.time.Instant instant) {
                year = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC).getYear();
            }
            if (year == null || year < 2018 || year > 2026) continue;

            double uabPart = 0.0;
            @SuppressWarnings("unchecked")
            List<Document> fundings = (List<Document>) aw.get("fundings");
            if (fundings != null) {
                for (Document f : fundings) {
                    @SuppressWarnings("unchecked")
                    List<Document> collaborators = (List<Document>) f.get("fundingCollaborators");
                    if (collaborators != null) {
                        for (Document col : collaborators) {
                            Document collaborator = (Document) col.get("collaborator");
                            if (collaborator != null && UAB_UUID.equals(collaborator.getString("uuid"))) {
                                Document instPart = (Document) col.get("institutionalPart");
                                if (instPart != null) {
                                    Object valObj = instPart.get("value");
                                    if (valObj instanceof Number) {
                                        uabPart += ((Number) valObj).doubleValue();
                                    } else if (valObj instanceof String) {
                                        try {
                                            uabPart += Double.parseDouble((String) valObj);
                                        } catch (NumberFormatException e) {
                                            // ignore
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            String managingUuid = null;
            Document managingOrg = (Document) aw.get("managingOrganization");
            if (managingOrg != null) {
                managingUuid = managingOrg.getString("uuid");
            }

            boolean isEsfera = false;
            if (managingUuid != null && orgEsferaMap.containsKey(managingUuid) && orgEsferaMap.get(managingUuid)) {
                isEsfera = true;
            } else {
                @SuppressWarnings("unchecked")
                List<Document> coManagingOrgs = (List<Document>) aw.get("coManagingOrganizations");
                if (coManagingOrgs != null) {
                    for (Document co : coManagingOrgs) {
                        String coUuid = co.getString("uuid");
                        if (coUuid != null && orgEsferaMap.containsKey(coUuid) && orgEsferaMap.get(coUuid)) {
                            isEsfera = true;
                            break;
                        }
                    }
                }
            }

            String cat = aw.getString("categoria");
            if (cat == null) cat = "Sense categoria";
            String typeTerm = "";
            Document typeDoc = (Document) aw.get("type");
            if (typeDoc != null) {
                Document termDoc = (Document) typeDoc.get("term");
                if (termDoc != null) {
                    typeTerm = termDoc.getString("ca_ES");
                }
            }
            if (typeTerm == null) typeTerm = "";

            boolean isComp = cat.startsWith("Ajudes competitives") || (cat.equals("Externs UAB") && !typeTerm.equals("Conveni extern a la UAB"));
            boolean isNocomp = cat.startsWith("Ajudes no competitives") || (cat.equals("Externs UAB") && typeTerm.equals("Conveni extern a la UAB"));

            YearStats ys = stats.computeIfAbsent(year, k -> new YearStats());

            if (isEsfera) {
                ys.totalEsfera += uabPart;
                if (isComp) ys.compEsfera += uabPart;
                if (isNocomp) ys.nocompEsfera += uabPart;
            } else {
                ys.totalUab += uabPart;
                if (isComp) ys.compUab += uabPart;
                if (isNocomp) ys.nocompUab += uabPart;
            }

            if (isComp) {
                if (cat.contains("internacionals")) {
                    ys.internacionals += uabPart;
                } else {
                    ys.estatals += uabPart;
                }
            } else if (isNocomp) {
                if (typeTerm.equals("Concessió conveni") || typeTerm.equals("Conveni extern a la UAB")) {
                    ys.convenis += uabPart;
                } else {
                    ys.serveis += uabPart;
                }
            }
        }

        // Aggregate all direct billing from excelCache
        Map<String, Map<Integer, Map<String, Double>>> excelCache = awardService.getExcelCache();
        if (excelCache != null) {
            for (Map<Integer, Map<String, Double>> orgCache : excelCache.values()) {
                orgCache.forEach((year, ids) -> {
                    if (year >= 2018 && year <= 2026) {
                        double sum = ids.values().stream().mapToDouble(Double::doubleValue).sum();
                        YearStats ys = stats.computeIfAbsent(year, k -> new YearStats());
                        ys.totalUab += sum;
                        ys.nocompUab += sum;
                        ys.serveis += sum;
                    }
                });
            }
        }

        // Convert stats map to response format
        Map<String, Object> response = new LinkedHashMap<>();
        for (Map.Entry<Integer, YearStats> entry : stats.entrySet()) {
            Integer yr = entry.getKey();
            YearStats ys = entry.getValue();
            
            Map<String, Object> yrData = new LinkedHashMap<>();
            yrData.put("totalUab", Math.round(ys.totalUab * 100.0) / 100.0);
            yrData.put("totalEsfera", Math.round(ys.totalEsfera * 100.0) / 100.0);
            yrData.put("compUab", Math.round(ys.compUab * 100.0) / 100.0);
            yrData.put("compEsfera", Math.round(ys.compEsfera * 100.0) / 100.0);
            yrData.put("nocompUab", Math.round(ys.nocompUab * 100.0) / 100.0);
            yrData.put("nocompEsfera", Math.round(ys.nocompEsfera * 100.0) / 100.0);
            yrData.put("estatals", Math.round(ys.estatals * 100.0) / 100.0);
            yrData.put("internacionals", Math.round(ys.internacionals * 100.0) / 100.0);
            yrData.put("convenis", Math.round(ys.convenis * 100.0) / 100.0);
            yrData.put("serveis", Math.round(ys.serveis * 100.0) / 100.0);
            
            response.put(String.valueOf(yr), yrData);
        }

        return response;
    }

    private boolean isOrgActive(Document org) {
        if (org == null) return false;
        Document lifecycle = (Document) org.get("lifecycle");
        if (lifecycle == null) return true;
        return lifecycle.get("endDate") == null;
    }

    private boolean belongsToEsfera(String uuid, Map<String, String> resolvedParentMap) {
        final String ESFERA_UUID = "53d7b18a-caf7-4ded-840e-942ff50adc82";
        final String UAB_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";
        
        String current = uuid;
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (current != null) {
            if (ESFERA_UUID.equals(current)) {
                return true;
            }
            if (UAB_UUID.equals(current)) {
                return false;
            }
            if (visited.contains(current)) {
                break;
            }
            visited.add(current);
            current = resolvedParentMap.get(current);
        }
        return false;
    }

    @GetMapping("/activitats-recerca")
    public Map<String, Object> getActivitatsRecercaStats() {
        Query awardQuery = new Query();
        Criteria approvedConveni = Criteria.where("type.term.ca_ES").is("Conveni extern a la UAB")
                                            .and("workflow.step").is("approved");
        Criteria allValidated = Criteria.where("workflow.step").is("validated");
        awardQuery.addCriteria(new Criteria().orOperator(approvedConveni, allValidated));
        awardQuery.fields().include("awardDate").include("categoria").include("type.term.ca_ES");
        List<Document> awards = mongoTemplate.find(awardQuery, Document.class, "Awards");

        // 2. Query StudentTheses
        Query thesisQuery = new Query();
        thesisQuery.fields().include("awardDate");
        List<Document> theses = mongoTemplate.find(thesisQuery, Document.class, "StudentTheses");

        // 3. Query Persons (filtered by predoctoral roles for speed)
        Query personQuery = new Query();
        personQuery.addCriteria(Criteria.where("staffOrganizationAssociations.employmentType.term.ca_ES")
                                         .regex("predoctoral|en formaci|fpi|fpu|novell|la caixa|pif|estudiant de doctorat|dgu", "i"));
        personQuery.fields().include("staffOrganizationAssociations");
        List<Document> persons = mongoTemplate.find(personQuery, Document.class, "Persons");

        // Helper class to store yearly activity stats
        class YearActStats {
            long estatals = 0;
            long internacionals = 0;
            long convenis = 0;
            long serveis = 0;
            long thesesDefended = 0;
            long newPredocs = 0;
        }

        Map<Integer, YearActStats> stats = new TreeMap<>();
        int[] targetYears = {2018, 2019, 2020, 2021, 2022, 2023, 2024, 2025, 2026};
        Set<Integer> yearsSet = new HashSet<>();
        for (int y : targetYears) yearsSet.add(y);

        // Aggregate Awards
        for (Document aw : awards) {
            Object dateVal = aw.get("awardDate");
            Integer year = null;
            if (dateVal instanceof java.util.Date date) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(date);
                year = cal.get(Calendar.YEAR);
            } else if (dateVal instanceof java.time.Instant instant) {
                year = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC).getYear();
            }
            if (year == null || !yearsSet.contains(year)) continue;

            String cat = aw.getString("categoria");
            if (cat == null) cat = "Sense categoria";
            String typeTerm = "";
            Document typeDoc = (Document) aw.get("type");
            if (typeDoc != null) {
                Document termDoc = (Document) typeDoc.get("term");
                if (termDoc != null) {
                    typeTerm = termDoc.getString("ca_ES");
                }
            }
            if (typeTerm == null) typeTerm = "";

            boolean isComp = cat.startsWith("Ajudes competitives") || (cat.equals("Externs UAB") && !typeTerm.toLowerCase().contains("conveni"));
            boolean isNocomp = cat.startsWith("Ajudes no competitives") || (cat.equals("Externs UAB") && typeTerm.toLowerCase().contains("conveni"));

            YearActStats ys = stats.computeIfAbsent(year, k -> new YearActStats());

            if (isComp) {
                if (cat.contains("internacionals")) {
                    ys.internacionals++;
                } else {
                    ys.estatals++;
                }
            } else if (isNocomp) {
                if (typeTerm.toLowerCase().contains("conveni")) {
                    ys.convenis++;
                } else {
                    ys.serveis++;
                }
            }
        }

        // Load Excel Prestacio de Serveis rows for activity counts
        Map<String, Map<Integer, Map<String, Double>>> excelCacheActivity = awardService.getExcelCache();
        if (excelCacheActivity != null) {
            for (Map<Integer, Map<String, Double>> orgCache : excelCacheActivity.values()) {
                orgCache.forEach((year, ids) -> {
                    if (yearsSet.contains(year)) {
                        YearActStats ys = stats.computeIfAbsent(year, k -> new YearActStats());
                        ys.serveis += ids.size();
                    }
                });
            }
        }

        // Aggregate Theses
        for (Document th : theses) {
            Document awardDate = (Document) th.get("awardDate");
            if (awardDate != null) {
                Object yrObj = awardDate.get("year");
                if (yrObj instanceof Number n) {
                    int yr = n.intValue();
                    if (yearsSet.contains(yr)) {
                        YearActStats ys = stats.computeIfAbsent(yr, k -> new YearActStats());
                        ys.thesesDefended++;
                    }
                }
            }
        }

        // Aggregate Persons for new predocs
        for (Document p : persons) {
            @SuppressWarnings("unchecked")
            List<Document> associations = (List<Document>) p.get("staffOrganizationAssociations");
            if (associations == null) continue;

            for (Document assoc : associations) {
                Document empType = (Document) assoc.get("employmentType");
                if (empType != null) {
                    Document termDoc = (Document) empType.get("term");
                    if (termDoc != null) {
                        String termCa = termDoc.getString("ca_ES");
                        if (termCa != null) {
                            String norm = termCa.toLowerCase();
                            if (norm.contains("predoctoral") || norm.contains("en formaci") || norm.contains("fpi") || norm.contains("fpu") || norm.contains("novell") || norm.contains("la caixa") || norm.contains("pif") || norm.contains("estudiant de doctorat") || norm.contains("dgu")) {
                                Document period = (Document) assoc.get("period");
                                if (period != null) {
                                    Object startVal = period.get("startDate");
                                    java.time.LocalDate startDate = null;
                                    if (startVal instanceof java.util.Date date) {
                                        startDate = date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                                    } else if (startVal instanceof java.time.Instant instant) {
                                        startDate = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                                    } else if (startVal instanceof String s) {
                                        try {
                                            startDate = java.time.LocalDate.parse(s.substring(0, 10));
                                        } catch (Exception e) {
                                            // ignore
                                        }
                                    }

                                    if (startDate != null) {
                                        int startYr = startDate.getYear();
                                        if (yearsSet.contains(startYr)) {
                                            YearActStats ys = stats.computeIfAbsent(startYr, k -> new YearActStats());
                                            ys.newPredocs++;
                                            break; // count person at most once for their first start year
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Convert to response format
        Map<String, Object> response = new LinkedHashMap<>();
        for (Map.Entry<Integer, YearActStats> entry : stats.entrySet()) {
            Integer yr = entry.getKey();
            YearActStats ys = entry.getValue();

            Map<String, Object> yrData = new LinkedHashMap<>();
            yrData.put("estatals", ys.estatals);
            yrData.put("internacionals", ys.internacionals);
            yrData.put("convenis", ys.convenis);
            yrData.put("serveis", ys.serveis);
            yrData.put("thesesDefended", ys.thesesDefended);
            yrData.put("newPredocs", ys.newPredocs);

            response.put(String.valueOf(yr), yrData);
        }

        return response;
    }

    @GetMapping("/ods")
    public List<Map<String, Object>> getOdsStats(
            @RequestParam(required = false, defaultValue = "2024") int year,
            @RequestParam(required = false, defaultValue = "all") String dept) {

        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        try {
            // Read all projects from MongoDB
            List<Document> projects = mongoTemplate.findAll(Document.class, "Projects");
            
            for (Document project : projects) {
                if (!isProjectInDept(project, dept)) continue;
                if (!isProjectActiveInYear(project, year)) continue;
                
                List<?> keywordGroups = project.getList("keywordGroups", Object.class);
                if (keywordGroups == null) continue;
                
                for (Object kg : keywordGroups) {
                    if (kg instanceof Document group) {
                        String nameEn = group.getEmbedded(List.of("name", "en_GB"), String.class);
                        String nameCa = group.getEmbedded(List.of("name", "ca_ES"), String.class);
                        if ("Sustainable Development Goals".equalsIgnoreCase(nameEn) || "Objectius de Desenvolupament Sostenible".equalsIgnoreCase(nameCa)) {
                            List<?> classifications = group.getList("classifications", Object.class);
                            if (classifications != null) {
                                for (Object c : classifications) {
                                    if (c instanceof Document classification) {
                                        String term = classification.getEmbedded(List.of("term", "ca_ES"), String.class);
                                        if (term == null) {
                                            term = classification.getEmbedded(List.of("term", "en_GB"), String.class);
                                        }
                                        if (term != null) {
                                            String normalized = term.replace("ODG", "ODS").replace(" - ", ": ").replace(" – ", ": ");
                                            counts.put(normalized, counts.getOrDefault(normalized, 0) + 1);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Convert to list of maps
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                item.put("value", entry.getValue());
                result.add(item);
            }
            
            // Sort by value descending
            result.sort((a, b) -> Integer.compare((Integer) b.get("value"), (Integer) a.get("value")));
            
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    private boolean isProjectInDept(Document doc, String dept) {
        if (dept == null || "all".equalsIgnoreCase(dept)) return true;
        
        Document managingOrg = doc.get("managingOrganization", Document.class);
        if (managingOrg != null && dept.equals(managingOrg.getString("uuid"))) {
            return true;
        }
        
        List<?> orgs = doc.getList("organizations", Object.class);
        if (orgs != null) {
            for (Object o : orgs) {
                if (o instanceof Document org && dept.equals(org.getString("uuid"))) {
                    return true;
                }
            }
        }
        
        List<?> participants = doc.getList("participants", Object.class);
        if (participants != null) {
            for (Object p : participants) {
                if (p instanceof Document participant) {
                    List<?> pOrgs = participant.getList("organizations", Object.class);
                    if (pOrgs != null) {
                        for (Object po : pOrgs) {
                            if (po instanceof Document pOrg && dept.equals(pOrg.getString("uuid"))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isProjectActiveInYear(Document doc, int year) {
        Document period = doc.get("period", Document.class);
        if (period == null) return false;
        
        String startDate = period.getString("startDate");
        String endDate = period.getString("endDate");
        
        if (startDate == null) return false;
        try {
            int startYear = Integer.parseInt(startDate.substring(0, 4));
            int endYear = 9999;
            if (endDate != null && endDate.length() >= 4) {
                endYear = Integer.parseInt(endDate.substring(0, 4));
            }
            return year >= startYear && year <= endYear;
        } catch (Exception e) {
            return false;
        }
    }
}

