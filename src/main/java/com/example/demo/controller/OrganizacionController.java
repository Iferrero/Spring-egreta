package com.example.demo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.bson.Document;
import com.example.demo.service.AwardService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;

@RestController
@RequestMapping("/api/organizations")
@CrossOrigin(origins = "*")
public class OrganizacionController {

    private final MongoTemplate mongoTemplate;
    private final AwardService awardService;

    @Autowired
    public OrganizacionController(MongoTemplate mongoTemplate, AwardService awardService) {
        this.mongoTemplate = mongoTemplate;
        this.awardService = awardService;
    }

    @GetMapping("/debug/keys")
    public Map<String, Object> debugKeys() {
        Document org = mongoTemplate.findOne(new Query(), Document.class, "Organizations");
        Document amb = mongoTemplate.findOne(new Query(), Document.class, "v_orga_ambit");
        Map<String, Object> res = new HashMap<>();
        res.put("organization", org);
        res.put("orga_ambit", amb);
        return res;
    }

    @GetMapping("/stats/research-structures")
    public Map<String, Long> getResearchStructuresStats() {
        Map<String, Long> stats = new HashMap<>();

        stats.put("departaments", countActiveByType("Departament"));
        stats.put("cers", countActiveByType("Centres d'Estudis i de Recerca"));
        stats.put("institutsPropis", countActiveByType("Instituts Universitaris de Recerca Propis"));
        
        stats.put("sgrs", countSgrGroups());
        
        stats.put("esfera", countActiveByTypes(List.of(
            "Centres amb conveni de participació en l'esfera UAB",
            "Empresa Esfera",
            "Centres de recerca en el campus de la UAB",
            "Centres de recerca participats",
            "Centres del CSIC amb conveni amb la UAB"
        )));

        return stats;
    }

    private long countActiveByType(String typeName) {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").is(typeName));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        return mongoTemplate.count(query, "Organizations");
    }

    private long countActiveByTypes(List<String> typeNames) {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").in(typeNames));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        return mongoTemplate.count(query, "Organizations");
    }

    private long countSgrGroups() {
        Query query = new Query();
        query.addCriteria(Criteria.where("identifiers.id").regex("^2021SGR"));
        return mongoTemplate.count(query, "Organizations");
    }

    private double parseAmount(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
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

    @GetMapping("/stats/economic")
    public List<Map<String, Object>> getInternalOrgsEconomicStats(
            @RequestParam(required = false) Integer startYear,
            @RequestParam(required = false) Integer endYear) {
        // 1. Fetch all organizations (active and inactive) to resolve parent hierarchies
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

        // 2. Fetch all validated awards from Mongo (including awardDate for filtering)
        Query awardQuery = new Query(Criteria.where("workflow.step").is("validated"));
        awardQuery.fields().include("uuid").include("title").include("fundings").include("awardDate")
                          .include("managingOrganization.uuid").include("coManagingOrganizations.uuid");
        List<Document> awards = mongoTemplate.find(awardQuery, Document.class, "Awards");

        class OrgEconomic {
            double managedAmount = 0.0;
            double managedUabPart = 0.0;
            long managedCount = 0;
            double coManagedAmount = 0.0;
            double coManagedUabPart = 0.0;
            long coManagedCount = 0;
        }

        Map<String, OrgEconomic> statsMap = new HashMap<>();
        final String UAB_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";

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

            if (startYear != null || endYear != null) {
                if (year == null) {
                    continue;
                }
                if (startYear != null && year < startYear) {
                    continue;
                }
                if (endYear != null && year > endYear) {
                    continue;
                }
            }

            String managingUuid = null;
            Document managingOrg = (Document) aw.get("managingOrganization");
            if (managingOrg != null) {
                managingUuid = managingOrg.getString("uuid");
            }

            List<String> coManagingUuids = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Document> coManagingOrgs = (List<Document>) aw.get("coManagingOrganizations");
            if (coManagingOrgs != null) {
                for (Document co : coManagingOrgs) {
                    String coUuid = co.getString("uuid");
                    if (coUuid != null) {
                        coManagingUuids.add(coUuid);
                    }
                }
            }

            double totalAwardAmount = 0.0;
            double totalUabPart = 0.0;

            @SuppressWarnings("unchecked")
            List<Document> fundings = (List<Document>) aw.get("fundings");
            if (fundings != null) {
                for (Document f : fundings) {
                    Document awardedAmount = (Document) f.get("awardedAmount");
                    if (awardedAmount != null) {
                        totalAwardAmount += parseAmount(awardedAmount.get("value"));
                    }

                    @SuppressWarnings("unchecked")
                    List<Document> collaborators = (List<Document>) f.get("fundingCollaborators");
                    if (collaborators != null) {
                        for (Document col : collaborators) {
                            Document collaborator = (Document) col.get("collaborator");
                            if (collaborator != null && UAB_UUID.equals(collaborator.getString("uuid"))) {
                                Document instPart = (Document) col.get("institutionalPart");
                                if (instPart != null) {
                                    totalUabPart += parseAmount(instPart.get("value"));
                                }
                            }
                        }
                    }
                }
            }

            if (managingUuid != null && !managingUuid.isBlank()) {
                OrgEconomic stat = statsMap.computeIfAbsent(managingUuid, k -> new OrgEconomic());
                stat.managedAmount += totalAwardAmount;
                stat.managedUabPart += totalUabPart;
                stat.managedCount++;
            }

            for (String coUuid : coManagingUuids) {
                if (!coUuid.isBlank()) {
                    OrgEconomic stat = statsMap.computeIfAbsent(coUuid, k -> new OrgEconomic());
                    stat.coManagedAmount += totalAwardAmount;
                    stat.coManagedUabPart += totalUabPart;
                    stat.coManagedCount++;
                }
            }
        }

        // 3. Supplement statsMap with Excel direct billing (Prestació de Serveis) data
        Map<String, Map<Integer, Map<String, Double>>> excelCache = awardService.getExcelCache();
        if (excelCache != null) {
            excelCache.forEach((orgUuid, byYear) -> {
                if (byYear != null) {
                    OrgEconomic econ = statsMap.computeIfAbsent(orgUuid, k -> new OrgEconomic());
                    byYear.forEach((year, ids) -> {
                        if (ids != null) {
                            if (startYear != null && year < startYear) return;
                            if (endYear != null && year > endYear) return;

                            double totalImport = ids.values().stream().mapToDouble(Double::doubleValue).sum();
                            econ.managedAmount += totalImport;
                            econ.managedUabPart += totalImport;
                            econ.managedCount += ids.size();
                        }
                    });
                }
            });
        }

        Set<String> sphereTypes = Set.of(
            "Centres amb conveni de participació en l'esfera UAB",
            "Empresa Esfera",
            "Centres de recerca en el campus de la UAB",
            "Centres de recerca participats",
            "Centres del CSIC amb conveni amb la UAB"
        );

        final String ESFERA_UUID = "53d7b18a-caf7-4ded-840e-942ff50adc82";

        // 4. Recursively resolve active parents for active organizations
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
                    break; // prevent cycles
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
                // Determine fallback to UAB or Esfera based on type
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

        // 5. Build response rows
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document org : activeOrgs) {
            String uuid = org.getString("uuid");
            if (uuid == null) continue;

            String typeCa = "";
            Document typeDoc = (Document) org.get("type");
            if (typeDoc != null) {
                Document termDoc = (Document) typeDoc.get("term");
                if (termDoc != null) {
                    typeCa = termDoc.getString("ca_ES");
                }
            }
            if (typeCa == null) typeCa = "";

            boolean isEsfera = belongsToEsfera(uuid, resolvedParentMap);
            OrgEconomic econ = statsMap.getOrDefault(uuid, new OrgEconomic());
            String parentUuid = resolvedParentMap.get(uuid);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuid);
            row.put("parentUuid", parentUuid);

            Document nameDoc = (Document) org.get("name");
            String displayName = nameDoc == null ? "" :
                    nameDoc.containsKey("ca_ES") ? nameDoc.getString("ca_ES") :
                    nameDoc.containsKey("es_ES") ? nameDoc.getString("es_ES") :
                    nameDoc.containsKey("en_GB") ? nameDoc.getString("en_GB") : "";
            row.put("name", displayName.isBlank() ? "(sense nom)" : displayName);
            row.put("type", typeCa.isBlank() ? "Organització" : typeCa);
            row.put("isEsfera", isEsfera);

            row.put("managedAmount", econ.managedAmount);
            row.put("managedUabPart", econ.managedUabPart);
            row.put("managedCount", econ.managedCount);
            row.put("coManagedAmount", econ.coManagedAmount);
            row.put("coManagedUabPart", econ.coManagedUabPart);
            row.put("coManagedCount", econ.coManagedCount);
            
            double totalAmount = econ.managedAmount + econ.coManagedAmount;
            row.put("totalAmount", totalAmount);
            row.put("totalCount", econ.managedCount + econ.coManagedCount);
            
            // Add UAB Part total for sorting/display
            double uabPartTotal = econ.managedUabPart + econ.coManagedUabPart;
            row.put("uabPartTotal", uabPartTotal);

            result.add(row);
        }

        result.sort((a, b) -> {
            int cmp = Double.compare((Double) b.get("uabPartTotal"), (Double) a.get("uabPartTotal"));
            if (cmp != 0) return cmp;
            return ((String) a.get("name")).compareTo((String) b.get("name"));
        });

        return result;
    }

    @GetMapping("/stats/economic/details")
    public List<Map<String, Object>> getInternalOrgEconomicDetails(
            @RequestParam String orgUuid,
            @RequestParam(required = false) Integer startYear,
            @RequestParam(required = false) Integer endYear) {
        // 1. Fetch Mongo validated awards
        Criteria criteria = new Criteria().andOperator(
            Criteria.where("workflow.step").is("validated"),
            new Criteria().orOperator(
                Criteria.where("managingOrganization.uuid").is(orgUuid),
                Criteria.where("coManagingOrganizations.uuid").is(orgUuid)
            )
        );
        Query query = new Query(criteria);
        query.fields().include("uuid").include("title").include("fundings").include("awardDate")
                     .include("managingOrganization.uuid").include("coManagingOrganizations.uuid");
        List<Document> awards = mongoTemplate.find(query, Document.class, "Awards");

        final String UAB_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";
        List<Map<String, Object>> result = new ArrayList<>();

        for (Document aw : awards) {
            Object awardDate = aw.get("awardDate");
            Integer year = null;
            String dateStr = "—";
            if (awardDate instanceof java.util.Date dateVal) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(dateVal);
                year = cal.get(java.util.Calendar.YEAR);
                dateStr = dateVal.toInstant().toString();
            } else if (awardDate instanceof java.time.Instant instantVal) {
                year = java.time.LocalDateTime.ofInstant(instantVal, java.time.ZoneOffset.UTC).getYear();
                dateStr = instantVal.toString();
            } else if (awardDate != null) {
                dateStr = awardDate.toString();
            }

            if (startYear != null || endYear != null) {
                if (year == null) {
                    continue;
                }
                if (startYear != null && year < startYear) {
                    continue;
                }
                if (endYear != null && year > endYear) {
                    continue;
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", aw.getString("uuid"));
            
            Document titleDoc = (Document) aw.get("title");
            String title = titleDoc == null ? "" :
                    titleDoc.containsKey("ca_ES") ? titleDoc.getString("ca_ES") :
                    titleDoc.containsKey("es_ES") ? titleDoc.getString("es_ES") :
                    titleDoc.containsKey("en_GB") ? titleDoc.getString("en_GB") : "";
            row.put("title", title.isBlank() ? "Sense títol" : title);
            row.put("date", dateStr);

            String role = "Co-gestor";
            Document managingOrg = (Document) aw.get("managingOrganization");
            if (managingOrg != null && orgUuid.equals(managingOrg.getString("uuid"))) {
                role = "Gestor principal";
            }
            row.put("role", role);

            double totalAwardAmount = 0.0;
            double totalUabPart = 0.0;

            @SuppressWarnings("unchecked")
            List<Document> fundings = (List<Document>) aw.get("fundings");
            if (fundings != null) {
                for (Document f : fundings) {
                    Document awardedAmount = (Document) f.get("awardedAmount");
                    if (awardedAmount != null) {
                        totalAwardAmount += parseAmount(awardedAmount.get("value"));
                    }

                    @SuppressWarnings("unchecked")
                    List<Document> collaborators = (List<Document>) f.get("fundingCollaborators");
                    if (collaborators != null) {
                        for (Document col : collaborators) {
                            Document collaborator = (Document) col.get("collaborator");
                            if (collaborator != null && UAB_UUID.equals(collaborator.getString("uuid"))) {
                                Document instPart = (Document) col.get("institutionalPart");
                                if (instPart != null) {
                                    totalUabPart += parseAmount(instPart.get("value"));
                                }
                            }
                        }
                    }
                }
            }

            row.put("amount", totalAwardAmount);
            row.put("uabPart", totalUabPart);
            result.add(row);
        }

        // 2. Supplement details with Excel direct billing (Prestació de Serveis) rows
        List<Document> excelDetails = awardService.getExcelPrestacioLlistaRows(orgUuid, startYear, endYear);
        if (excelDetails != null) {
            int counter = 0;
            for (Document ed : excelDetails) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", "excel_" + orgUuid + "_" + (counter++));
                
                String title = ed.getString("titulo");
                row.put("title", title == null || title.isBlank() ? "Prestació de Serveis" : title);

                Integer year = ed.getInteger("anyo");
                String dateStr = "—";
                if (year != null) {
                    dateStr = java.time.Instant.from(java.time.LocalDate.of(year, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)).toString();
                }
                row.put("date", dateStr);
                row.put("role", "Gestor principal");

                double impVal = 0.0;
                Object impObj = ed.get("institutionalPart");
                if (impObj instanceof Number num) {
                    impVal = num.doubleValue();
                }
                row.put("amount", impVal);
                row.put("uabPart", impVal);

                result.add(row);
            }
        }

        result.sort((a, b) -> {
            String da = (String) a.get("date");
            String db = (String) b.get("date");
            boolean aBlank = da == null || da.equals("—");
            boolean bBlank = db == null || db.equals("—");
            if (aBlank && bBlank) return 0;
            if (aBlank) return 1;   // a sin fecha → va al final (más antiguo)
            if (bBlank) return -1;  // b sin fecha → va al final (más antiguo)
            return db.compareTo(da); // desc: más moderno primero
        });

        return result;
    }

    @GetMapping("/stats/economic/evolution")
    public Map<String, List<Map<String, Object>>> getEconomicEvolution(
            @RequestParam(required = false) Integer startYear,
            @RequestParam(required = false) Integer endYear,
            @RequestParam(required = false) String orgUuid) {
        // 1. Fetch parent resolutions (same as getInternalOrgsEconomicStats)
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

        // Resolve active parents
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

        // Map: orgUuid -> belongsToEsfera (boolean)
        Map<String, Boolean> orgEsferaMap = new HashMap<>();
        for (Document org : activeOrgs) {
            String uuid = org.getString("uuid");
            orgEsferaMap.put(uuid, belongsToEsfera(uuid, resolvedParentMap));
        }

        // If a specific org is requested, return simplified single-series evolution
        if (orgUuid != null && !orgUuid.isBlank()) {
            Query awardQueryOrg = new Query(Criteria.where("workflow.step").is("validated"));
            awardQueryOrg.fields().include("uuid").include("awardDate").include("fundings")
                                  .include("managingOrganization.uuid").include("coManagingOrganizations.uuid");
            List<Document> awardsOrg = mongoTemplate.find(awardQueryOrg, Document.class, "Awards");

            final String UAB_UUID2 = "84443078-1a60-462d-9d0a-b04312afd9eb";
            Map<Integer, Double> orgYearMap = new HashMap<>();

            for (Document aw : awardsOrg) {
                // Check if this award involves the requested org
                boolean involved = false;
                Document managingOrgDoc = (Document) aw.get("managingOrganization");
                if (managingOrgDoc != null && orgUuid.equals(managingOrgDoc.getString("uuid"))) involved = true;
                if (!involved) {
                    @SuppressWarnings("unchecked")
                    List<Document> coOrgs = (List<Document>) aw.get("coManagingOrganizations");
                    if (coOrgs != null) {
                        for (Document co : coOrgs) {
                            if (orgUuid.equals(co.getString("uuid"))) { involved = true; break; }
                        }
                    }
                }
                if (!involved) continue;

                Object dateVal2 = aw.get("awardDate");
                Integer year2 = null;
                if (dateVal2 instanceof java.util.Date d2) {
                    java.util.Calendar c2 = java.util.Calendar.getInstance();
                    c2.setTime(d2);
                    year2 = c2.get(java.util.Calendar.YEAR);
                } else if (dateVal2 instanceof java.time.Instant i2) {
                    year2 = java.time.LocalDateTime.ofInstant(i2, java.time.ZoneOffset.UTC).getYear();
                }
                if (year2 == null) continue;
                if (startYear != null && year2 < startYear) continue;
                if (endYear != null && year2 > endYear) continue;

                double uabPart = 0.0;
                @SuppressWarnings("unchecked")
                List<Document> fundings2 = (List<Document>) aw.get("fundings");
                if (fundings2 != null) {
                    for (Document f2 : fundings2) {
                        @SuppressWarnings("unchecked")
                        List<Document> cols2 = (List<Document>) f2.get("fundingCollaborators");
                        if (cols2 != null) {
                            for (Document col2 : cols2) {
                                Document collab2 = (Document) col2.get("collaborator");
                                if (collab2 != null && UAB_UUID2.equals(collab2.getString("uuid"))) {
                                    Document ip2 = (Document) col2.get("institutionalPart");
                                    if (ip2 != null) uabPart += parseAmount(ip2.get("value"));
                                }
                            }
                        }
                    }
                }
                orgYearMap.merge(year2, uabPart, Double::sum);
            }

            // Excel data for this org
            Map<String, Map<Integer, Map<String, Double>>> excelCacheOrg = awardService.getExcelCache();
            if (excelCacheOrg != null && excelCacheOrg.containsKey(orgUuid)) {
                Map<Integer, Map<String, Double>> byYear = excelCacheOrg.get(orgUuid);
                if (byYear != null) {
                    byYear.forEach((yr, ids) -> {
                        if (startYear != null && yr < startYear) return;
                        if (endYear != null && yr > endYear) return;
                        if (ids != null) orgYearMap.merge(yr, ids.values().stream().mapToDouble(Double::doubleValue).sum(), Double::sum);
                    });
                }
            }

            List<Map<String, Object>> orgList = new ArrayList<>();
            orgYearMap.forEach((yr, amt) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("year", yr);
                m.put("amount", Math.round(amt * 100.0) / 100.0);
                orgList.add(m);
            });
            orgList.sort((a, b) -> Integer.compare((Integer) a.get("year"), (Integer) b.get("year")));

            Map<String, List<Map<String, Object>>> orgResponse = new HashMap<>();
            orgResponse.put("uab", orgList);
            orgResponse.put("esfera", new ArrayList<>());
            return orgResponse;
        }

        // Map: branch -> year -> amount
        Map<Integer, Double> uabYearMap = new HashMap<>();
        Map<Integer, Double> esferaYearMap = new HashMap<>();

        // Fetch all validated awards
        Query awardQuery = new Query(Criteria.where("workflow.step").is("validated"));
        awardQuery.fields().include("uuid").include("awardDate").include("fundings")
                          .include("managingOrganization.uuid").include("coManagingOrganizations.uuid");
        List<Document> awards = mongoTemplate.find(awardQuery, Document.class, "Awards");

        for (Document aw : awards) {
            // Get year from awardDate
            Object dateVal = aw.get("awardDate");
            Integer year = null;
            if (dateVal instanceof java.util.Date date) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(date);
                year = cal.get(java.util.Calendar.YEAR);
            } else if (dateVal instanceof java.time.Instant instant) {
                year = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC).getYear();
            }
            if (year == null) continue;
            // Apply year range filter
            if (startYear != null && year < startYear) continue;
            if (endYear != null && year > endYear) continue;

            // Calculate UAB part instead of total award amount
            double totalUabPart = 0.0;
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
                                    totalUabPart += parseAmount(instPart.get("value"));
                                }
                            }
                        }
                    }
                }
            }

            // Check if managing organization belongs to UAB or Esfera
            String managingUuid = null;
            Document managingOrg = (Document) aw.get("managingOrganization");
            if (managingOrg != null) {
                managingUuid = managingOrg.getString("uuid");
            }

            if (managingUuid != null && orgEsferaMap.containsKey(managingUuid)) {
                boolean isEsfera = orgEsferaMap.get(managingUuid);
                if (isEsfera) {
                    esferaYearMap.merge(year, totalUabPart, Double::sum);
                } else {
                    uabYearMap.merge(year, totalUabPart, Double::sum);
                }
            }

            // Also check co-managing organizations
            @SuppressWarnings("unchecked")
            List<Document> coManagingOrgs = (List<Document>) aw.get("coManagingOrganizations");
            if (coManagingOrgs != null) {
                for (Document co : coManagingOrgs) {
                    String coUuid = co.getString("uuid");
                    if (coUuid != null && orgEsferaMap.containsKey(coUuid)) {
                        boolean isEsfera = orgEsferaMap.get(coUuid);
                        if (isEsfera) {
                            esferaYearMap.merge(year, totalUabPart, Double::sum);
                        } else {
                            uabYearMap.merge(year, totalUabPart, Double::sum);
                        }
                    }
                }
            }
        }

        // Add Excel direct billing (Prestació de Serveis) data by year
        Map<String, Map<Integer, Map<String, Double>>> excelCache = awardService.getExcelCache();
        if (excelCache != null) {
            excelCache.forEach((cacheOrgUuid, byYear) -> {
                if (byYear != null && orgEsferaMap.containsKey(cacheOrgUuid)) {
                    boolean isEsfera = orgEsferaMap.get(cacheOrgUuid);
                    byYear.forEach((year, ids) -> {
                        if (ids != null) {
                            // Apply year range filter for Excel data
                            if (startYear != null && year < startYear) return;
                            if (endYear != null && year > endYear) return;
                            double totalImport = ids.values().stream().mapToDouble(Double::doubleValue).sum();
                            if (isEsfera) {
                                esferaYearMap.merge(year, totalImport, Double::sum);
                            } else {
                                uabYearMap.merge(year, totalImport, Double::sum);
                            }
                        }
                    });
                }
            });
        }

        // Format response
        List<Map<String, Object>> uabList = new ArrayList<>();
        uabYearMap.forEach((year, amount) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("year", year);
            m.put("amount", Math.round(amount * 100.0) / 100.0);
            uabList.add(m);
        });
        uabList.sort((a, b) -> Integer.compare((Integer) a.get("year"), (Integer) b.get("year")));

        List<Map<String, Object>> esferaList = new ArrayList<>();
        esferaYearMap.forEach((year, amount) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("year", year);
            m.put("amount", Math.round(amount * 100.0) / 100.0);
            esferaList.add(m);
        });
        esferaList.sort((a, b) -> Integer.compare((Integer) a.get("year"), (Integer) b.get("year")));

        Map<String, List<Map<String, Object>>> response = new HashMap<>();
        response.put("uab", uabList);
        response.put("esfera", esferaList);
        return response;
    }

}
