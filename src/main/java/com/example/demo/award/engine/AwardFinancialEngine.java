package com.example.demo.award.engine;

import com.example.demo.award.model.CostCodePrefix;
import com.example.demo.award.model.PowerTableQueryCriteria;
import com.example.demo.award.model.ResignedAwardsQueryCriteria;
import com.example.demo.service.AwardService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

/**
 * Deep domain engine for financial grant analytics, PowerTable calculations,
 * prefixed cost-code metrics (PK, EA, PO), and concession resignation analytics.
 */
@Service
public class AwardFinancialEngine {

    private final MongoTemplate mongoTemplate;
    private final AwardService awardService;

    @Autowired
    public AwardFinancialEngine(MongoTemplate mongoTemplate, AwardService awardService) {
        this.mongoTemplate = mongoTemplate;
        this.awardService = awardService;
    }

    /*
    ========================================================================
    1. PREFIXED AWARDS FINANCIAL ANALYTICS (PK, EA, PO, ALL)
    ========================================================================
    */

    public List<Document> getPkAwardsStats() {
        return getPrefixedAwardsStats(CostCodePrefix.PK);
    }

    public List<Document> getEaAwardsStats() {
        return getPrefixedAwardsStats(CostCodePrefix.EA);
    }

    public List<Document> getPoAwardsStats() {
        return getPrefixedAwardsStats(CostCodePrefix.PO);
    }

    public List<Document> getAllPrefixedAwardsStats() {
        return getPrefixedAwardsStats(CostCodePrefix.ALL);
    }

    public List<Document> getPrefixedAwardsStats(CostCodePrefix prefix) {
        String budgetArrayName = switch (prefix) {
            case PK -> "pkBudgets";
            case EA -> "eaBudgets";
            case PO -> "poBudgets";
            case ALL -> "prefixedBudgets";
        };
        String regex = prefix == CostCodePrefix.ALL
                ? "^(PK|EA|PO|PH|PI|DR|BJ|AG|AB|IM|IB|YA|PV|PZ|BA|BF|BB|NF|ND|NM|MH|NN|MI|NB)"
                : prefix.getMongoRegex();

        List<Document> pipeline = buildPrefixedPipeline(regex, budgetArrayName);
        return executeAggregation("Awards", pipeline);
    }

    private List<Document> buildPrefixedPipeline(String regex, String budgetArrayName) {
        List<Document> pipeline = new ArrayList<>();

        Document matchStage = new Document("$match", new Document("$or", Arrays.asList(
                new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", new Document("$in", Arrays.asList("approved", "validated"))),
                new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        )).append("budgets.costCode", new Document("$regex", regex).append("$options", "i")));
        pipeline.add(matchStage);

        pipeline.add(new Document("$graphLookup", new Document()
                .append("from", "Organizations")
                .append("startWith", "$managingOrganization.uuid")
                .append("connectFromField", "parents.uuid")
                .append("connectToField", "uuid")
                .append("as", "orgAncestors")
        ));

        Document isResignedExpr = new Document("$reduce", new Document()
                .append("input", new Document("$ifNull", Arrays.asList("$keywordGroups", Collections.emptyList())))
                .append("initialValue", false)
                .append("in", new Document("$or", Arrays.asList(
                        "$$value",
                        new Document("$reduce", new Document()
                                .append("input", new Document("$ifNull", Arrays.asList("$$this.keywordContainers", Collections.emptyList())))
                                .append("initialValue", false)
                                .append("in", new Document("$or", Arrays.asList(
                                        "$$value",
                                        new Document("$eq", Arrays.asList("$$this.structuredKeyword.uri", "/uab/awards/estat_ajut/resigned"))
                                )))
                        )
                )))
        );

        pipeline.add(new Document("$addFields", new Document()
                .append("orgScope", new Document("$cond", Arrays.asList(
                        new Document("$or", Arrays.asList(
                                new Document("$eq", Arrays.asList("$managingOrganization.uuid", "53d7b18a-caf7-4ded-840e-942ff50adc82")),
                                new Document("$in", Arrays.asList("53d7b18a-caf7-4ded-840e-942ff50adc82", "$orgAncestors.uuid"))
                        )),
                        "Esfera",
                        "UAB"
                )))
                .append("awardDateReal", new Document("$convert", new Document()
                        .append("input", "$awardDate")
                        .append("to", "date")
                        .append("onError", null)
                        .append("onNull", null)
                ))
                .append("isDeclined", new Document("$cond", Arrays.asList(
                        new Document("$or", Arrays.asList(
                                new Document("$eq", Arrays.asList("$status.typeDiscriminator", "DeclinedAwardStatus")),
                                isResignedExpr
                        )),
                        true,
                        false
                )))
                .append(budgetArrayName, new Document("$filter", new Document()
                        .append("input", new Document("$ifNull", Arrays.asList("$budgets", Collections.emptyList())))
                        .append("as", "b")
                        .append("cond", new Document("$regexMatch", new Document()
                                .append("input", new Document("$ifNull", Arrays.asList("$$b.costCode", "")))
                                .append("regex", regex)
                                .append("options", "i")
                        ))
                ))
        ));

        pipeline.add(new Document("$addFields", new Document()
                .append("anyo", new Document("$cond", Arrays.asList(
                        new Document("$ne", Arrays.asList("$awardDateReal", null)),
                        new Document("$year", "$awardDateReal"),
                        new Document("$cond", Arrays.asList(
                                new Document("$ne", Arrays.asList("$awardDate", null)),
                                new Document("$toInt", new Document("$substrCP", Arrays.asList("$awardDate", 0, 4))),
                                null
                        ))
                )))
                .append("hasPrefixedBudget", new Document("$gt", Arrays.asList(new Document("$size", "$" + budgetArrayName), 0)))
        ));

        pipeline.add(new Document("$match", new Document("hasPrefixedBudget", true)));

        pipeline.add(new Document("$project", new Document()
                .append("uuid", 1)
                .append("pureId", 1)
                .append("title", 1)
                .append("type", 1)
                .append("status", 1)
                .append("actualAmounts", 1)
                .append("awardDate", 1)
                .append("awardDateReal", 1)
                .append("managingOrganization", 1)
                .append("orgScope", 1)
                .append("isDeclined", 1)
                .append("anyo", 1)
                .append("budgets", "$" + budgetArrayName)
        ));

        return pipeline;
    }

    /*
    ========================================================================
    2. COST CENTER & CONVOCATÒRIES
    ========================================================================
    */

    public List<Map<String, Object>> getCostCenterConvocatorias() {
        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("$or", Arrays.asList(
                new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
                new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        ))));

        pipeline.add(new Document("$unwind",
                new Document("path", "$budgets").append("preserveNullAndEmptyArrays", false)));

        pipeline.add(new Document("$match", new Document("budgets.costCode",
                new Document("$exists", true).append("$ne", null).append("$ne", ""))));

        pipeline.add(new Document("$addFields", new Document()
                .append("costCode", "$budgets.costCode")
                .append("tipologia", new Document("$toUpper",
                        new Document("$substrCP", Arrays.asList("$budgets.costCode", 0, 2))))));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Applications")
                .append("localField", "applications.uuid")
                .append("foreignField", "uuid")
                .append("as", "appDocs")));

        pipeline.add(new Document("$unwind",
                new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "FundingOpportunities")
                .append("localField", "appDocs.fundingOpportunity.uuid")
                .append("foreignField", "uuid")
                .append("as", "fOpp")));

        pipeline.add(new Document("$unwind",
                new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));

        pipeline.add(new Document("$group", new Document()
                .append("_id", new Document()
                        .append("tipologia", "$tipologia")
                        .append("costCode", "$costCode")
                        .append("fOppUuid", "$fOpp.uuid")
                        .append("fOppTitle", "$fOpp.title")
                        .append("fOppType", "$fOpp.type"))
                .append("awardCount", new Document("$sum", 1))));

        pipeline.add(new Document("$group", new Document()
                .append("_id", new Document()
                        .append("tipologia", "$_id.tipologia")
                        .append("costCode", "$_id.costCode"))
                .append("totalAwards", new Document("$sum", "$awardCount"))
                .append("convocatories", new Document("$push", new Document()
                        .append("fOppUuid", "$_id.fOppUuid")
                        .append("fOppTitle", "$_id.fOppTitle")
                        .append("fOppType", "$_id.fOppType")
                        .append("count", "$awardCount")))));

        pipeline.add(new Document("$group", new Document()
                .append("_id", "$_id.tipologia")
                .append("tipologia", new Document("$first", "$_id.tipologia"))
                .append("totalAwards", new Document("$sum", "$totalAwards"))
                .append("costCodes", new Document("$addToSet", "$_id.costCode"))
                .append("convocatoriesRaw", new Document("$push", "$convocatories"))));

        pipeline.add(new Document("$sort", new Document("tipologia", 1)));

        List<Document> rawResults = executeAggregation("Awards", pipeline);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Document doc : rawResults) {
            String tipologia = doc.getString("tipologia");
            if (tipologia == null || tipologia.isBlank()) continue;

            int totalAwards = ((Number) doc.getOrDefault("totalAwards", 0)).intValue();
            List<?> costCodesList = (List<?>) doc.get("costCodes");
            List<String> sortedCostCodes = new ArrayList<>();
            if (costCodesList != null) {
                for (Object cc : costCodesList) {
                    if (cc instanceof String s) sortedCostCodes.add(s);
                }
                Collections.sort(sortedCostCodes);
            }

            Map<String, Map<String, Object>> convMap = new LinkedHashMap<>();
            List<?> rawConvOuter = (List<?>) doc.get("convocatoriesRaw");
            if (rawConvOuter != null) {
                for (Object outerItem : rawConvOuter) {
                    if (!(outerItem instanceof List<?> innerList)) continue;
                    for (Object innerItem : innerList) {
                        if (!(innerItem instanceof Document convDoc)) continue;
                        String fOppUuid = convDoc.getString("fOppUuid");
                        String key = (fOppUuid != null && !fOppUuid.isBlank()) ? fOppUuid : "__no_conv__";
                        int cnt = ((Number) convDoc.getOrDefault("count", 0)).intValue();

                        if (!convMap.containsKey(key)) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("fOppUuid", fOppUuid);
                            entry.put("fOppTitle", convDoc.get("fOppTitle"));
                            entry.put("fOppType", convDoc.get("fOppType"));
                            entry.put("count", cnt);
                            convMap.put(key, entry);
                        } else {
                            Map<String, Object> entry = convMap.get(key);
                            entry.put("count", ((Number) entry.get("count")).intValue() + cnt);
                        }
                    }
                }
            }

            List<Map<String, Object>> convList = new ArrayList<>(convMap.values());
            convList.sort((a, b) -> {
                boolean aHasConv = a.get("fOppUuid") != null;
                boolean bHasConv = b.get("fOppUuid") != null;
                if (aHasConv != bHasConv) return aHasConv ? -1 : 1;
                int ca = ((Number) a.getOrDefault("count", 0)).intValue();
                int cb = ((Number) b.getOrDefault("count", 0)).intValue();
                return Integer.compare(cb, ca);
            });

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tipologia", tipologia);
            row.put("totalAwards", totalAwards);
            row.put("costCodes", sortedCostCodes);
            row.put("convocatories", convList);
            result.add(row);
        }

        return result;
    }

    /*
    ========================================================================
    3. RESIGNED AWARDS & REASON COMPARISONS
    ========================================================================
    */

    public Map<String, Object> getResignedAwards(ResignedAwardsQueryCriteria criteria) {
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();

        // 1. Must have a non-empty status.reason
        andConditions.add(new Document("status.reason", new Document("$exists", true).append("$ne", null).append("$ne", "")));

        // 2. Status type filtering (Terminated / Curtailed / Declined)
        String statusType = criteria.statusType();
        if (statusType != null && !statusType.isBlank() && !"all".equalsIgnoreCase(statusType)) {
            if ("terminated".equalsIgnoreCase(statusType) || "terminated_curtailed".equalsIgnoreCase(statusType)) {
                andConditions.add(new Document("status.typeDiscriminator", new Document("$in", Arrays.asList("TerminatedAwardStatus", "CurtailedAwardStatus"))));
            } else if ("declined".equalsIgnoreCase(statusType)) {
                andConditions.add(new Document("status.typeDiscriminator", "DeclinedAwardStatus"));
            } else {
                andConditions.add(new Document("status.typeDiscriminator", statusType.trim()));
            }
        } else {
            andConditions.add(new Document("status.typeDiscriminator", new Document("$in", Arrays.asList("DeclinedAwardStatus", "CurtailedAwardStatus", "TerminatedAwardStatus"))));
        }

        // 3. Reason filter if specific renuncia requested
        String reasonFilter = criteria.reasonFilter();
        if (reasonFilter != null && !reasonFilter.isBlank() && !"all".equalsIgnoreCase(reasonFilter)) {
            if ("renuncia".equalsIgnoreCase(reasonFilter)) {
                andConditions.add(new Document("status.reason", new Document("$regex", "renunc|renuncia|resign|declin|personals|laborals|incompatib|tesi|baja|desist|revoc").append("$options", "i")));
            }
        }

        // 4. Text search
        String search = criteria.search();
        if (search != null && !search.isBlank()) {
            String s = search.trim();
            andConditions.add(new Document("$or", Arrays.asList(
                    new Document("title.ca_ES", new Document("$regex", s).append("$options", "i")),
                    new Document("title.es_ES", new Document("$regex", s).append("$options", "i")),
                    new Document("title.en_GB", new Document("$regex", s).append("$options", "i")),
                    new Document("status.reason", new Document("$regex", s).append("$options", "i")),
                    new Document("uuid", new Document("$regex", s).append("$options", "i")),
                    new Document("pureId", new Document("$regex", s).append("$options", "i")),
                    new Document("awardHolders.name.firstName", new Document("$regex", s).append("$options", "i")),
                    new Document("awardHolders.name.lastName", new Document("$regex", s).append("$options", "i"))
            )));
        }

        // 5. Categoria filter
        String categoria = criteria.categoria();
        if (categoria != null && !categoria.isBlank() && !"all".equalsIgnoreCase(categoria)) {
            andConditions.add(new Document("categoria", categoria.trim()));
        }

        // 6. Tipus filter
        String tipus = criteria.tipus();
        if (tipus != null && !tipus.isBlank() && !"all".equalsIgnoreCase(tipus)) {
            andConditions.add(new Document("$or", Arrays.asList(
                    new Document("type.term.ca_ES", tipus.trim()),
                    new Document("type.term.es_ES", tipus.trim())
            )));
        }

        // 7. Year filter
        String year = criteria.year();
        if (year != null && !year.isBlank() && !"all".equalsIgnoreCase(year)) {
            andConditions.add(new Document("$or", Arrays.asList(
                    new Document("awardDate", new Document("$regex", year.trim())),
                    new Document("status.date", new Document("$regex", year.trim()))
            )));
        }

        if (!andConditions.isEmpty()) {
            matchQuery.put("$and", andConditions);
        }

        List<Document> rawDocs = new ArrayList<>();
        try {
            mongoTemplate.getCollection("Awards")
                    .find(matchQuery)
                    .into(rawDocs);
        } catch (Exception e) {
            System.err.println("[AwardFinancialEngine] Error finding resigned awards: " + e.getMessage());
        }

        List<Map<String, Object>> processedList = new ArrayList<>();
        double totalAmountAll = 0.0;

        Map<String, Integer> categoryCounts = new HashMap<>();
        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, Integer> statusTypeCounts = new HashMap<>();
        Map<String, Integer> topReasonsMap = new HashMap<>();

        for (Document doc : rawDocs) {
            Map<String, Object> map = new HashMap<>();
            map.put("uuid", doc.getString("uuid"));
            map.put("pureId", doc.get("pureId"));

            Document titleDoc = (Document) doc.get("title");
            String title = "";
            if (titleDoc != null) {
                title = titleDoc.getString("ca_ES");
                if (title == null || title.isBlank()) title = titleDoc.getString("es_ES");
                if (title == null || title.isBlank()) title = titleDoc.getString("en_GB");
            }
            map.put("title", title != null ? title : "");

            Document statusDoc = (Document) doc.get("status");
            String docStatusType = "";
            String statusDate = "";
            String reason = "";
            if (statusDoc != null) {
                docStatusType = statusDoc.getString("typeDiscriminator");
                statusDate = statusDoc.getString("date");
                reason = statusDoc.getString("reason");
            }
            map.put("statusType", docStatusType != null ? docStatusType : "");
            map.put("statusDate", statusDate != null ? statusDate : "");
            map.put("reason", reason != null ? reason : "");

            if (docStatusType != null && !docStatusType.isBlank()) {
                statusTypeCounts.put(docStatusType, statusTypeCounts.getOrDefault(docStatusType, 0) + 1);
            }

            if (reason != null && !reason.isBlank()) {
                String cleanReason = reason.trim();
                topReasonsMap.put(cleanReason, topReasonsMap.getOrDefault(cleanReason, 0) + 1);
            }

            Document typeDoc = (Document) doc.get("type");
            String typeTerm = "";
            if (typeDoc != null) {
                Document termDoc = (Document) typeDoc.get("term");
                if (termDoc != null) {
                    typeTerm = termDoc.getString("ca_ES");
                    if (typeTerm == null || typeTerm.isBlank()) typeTerm = termDoc.getString("es_ES");
                }
            }
            typeTerm = typeTerm != null ? typeTerm : "Altres";
            map.put("type", typeTerm);
            typeCounts.put(typeTerm, typeCounts.getOrDefault(typeTerm, 0) + 1);

            String cat = doc.getString("categoria");
            cat = cat != null ? cat : "Sense categoria";
            map.put("categoria", cat);
            categoryCounts.put(cat, categoryCounts.getOrDefault(cat, 0) + 1);

            Object awardDateObj = doc.get("awardDate");
            String awardDateStr = awardDateObj != null ? awardDateObj.toString() : "";
            map.put("awardDate", awardDateStr);

            double amount = 0.0;
            String funderName = "";
            List<?> fundings = (List<?>) doc.get("fundings");
            if (fundings != null && !fundings.isEmpty()) {
                for (Object fObj : fundings) {
                    if (fObj instanceof Document fDoc) {
                        Document amtDoc = (Document) fDoc.get("awardedAmount");
                        if (amtDoc != null) {
                            Object val = amtDoc.get("value");
                            if (val instanceof Number n) {
                                amount += n.doubleValue();
                            } else if (val instanceof String strVal) {
                                try { amount += Double.parseDouble(strVal); } catch (Exception ignored) {}
                            }
                        }
                        if (funderName.isBlank()) {
                            Document funderDoc = (Document) fDoc.get("funder");
                            if (funderDoc != null) {
                                funderName = funderDoc.getString("systemName");
                            }
                        }
                    }
                }
            }
            map.put("amount", amount);
            totalAmountAll += amount;
            map.put("funder", funderName);

            List<Map<String, String>> holders = new ArrayList<>();
            List<?> holdersList = (List<?>) doc.get("awardHolders");
            if (holdersList != null) {
                for (Object hObj : holdersList) {
                    if (hObj instanceof Document hDoc) {
                        Document nameDoc = (Document) hDoc.get("name");
                        Document roleDoc = (Document) hDoc.get("role");
                        String personName = "";
                        if (nameDoc != null) {
                            String first = nameDoc.getString("firstName");
                            String last = nameDoc.getString("lastName");
                            personName = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
                        }
                        String roleName = "";
                        if (roleDoc != null) {
                            Document rTerm = (Document) roleDoc.get("term");
                            if (rTerm != null) {
                                roleName = rTerm.getString("ca_ES");
                                if (roleName == null) roleName = rTerm.getString("es_ES");
                            }
                        }
                        if (!personName.isBlank()) {
                            Map<String, String> hMap = new HashMap<>();
                            hMap.put("name", personName);
                            hMap.put("role", roleName != null ? roleName : "");
                            holders.add(hMap);
                        }
                    }
                }
            }
            map.put("holders", holders);
            processedList.add(map);
        }

        final boolean isAsc = "asc".equalsIgnoreCase(criteria.sortOrder());
        String sortBy = criteria.sortBy();
        processedList.sort((a, b) -> {
            int cmp = 0;
            if ("amount".equalsIgnoreCase(sortBy)) {
                Double aVal = (Double) a.getOrDefault("amount", 0.0);
                Double bVal = (Double) b.getOrDefault("amount", 0.0);
                cmp = Double.compare(aVal, bVal);
            } else if ("title".equalsIgnoreCase(sortBy)) {
                String aVal = (String) a.getOrDefault("title", "");
                String bVal = (String) b.getOrDefault("title", "");
                cmp = aVal.compareToIgnoreCase(bVal);
            } else if ("reason".equalsIgnoreCase(sortBy)) {
                String aVal = (String) a.getOrDefault("reason", "");
                String bVal = (String) b.getOrDefault("reason", "");
                cmp = aVal.compareToIgnoreCase(bVal);
            } else {
                String aVal = (String) a.getOrDefault("statusDate", "");
                if (aVal.isBlank()) aVal = (String) a.getOrDefault("awardDate", "");
                String bVal = (String) b.getOrDefault("statusDate", "");
                if (bVal.isBlank()) bVal = (String) b.getOrDefault("awardDate", "");
                cmp = aVal.compareToIgnoreCase(bVal);
            }
            return isAsc ? cmp : -cmp;
        });

        int totalElements = processedList.size();
        List<Map<String, Object>> pagedContent;

        if (criteria.exportAll()) {
            pagedContent = processedList;
        } else {
            int page = criteria.page();
            int size = criteria.size();
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            pagedContent = processedList.subList(fromIndex, toIndex);
        }

        List<Map<String, Object>> topReasonsList = topReasonsMap.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
                .limit(20)
                .map(e -> Map.<String, Object>of("reason", e.getKey(), "count", e.getValue()))
                .toList();

        int page = criteria.page();
        int size = criteria.size();
        Map<String, Object> response = new HashMap<>();
        response.put("content", pagedContent);
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", (long) totalElements);
        response.put("totalPages", size > 0 ? (int) Math.ceil((double) totalElements / size) : 1);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCount", (long) totalElements);
        summary.put("totalAmount", totalAmountAll);
        summary.put("categoryCounts", categoryCounts);
        summary.put("typeCounts", typeCounts);
        summary.put("statusTypeCounts", statusTypeCounts);
        summary.put("topReasons", topReasonsList);

        response.put("summary", summary);
        return response;
    }

    public Map<String, Object> getReasonComparison(int page, int size, String search, String filterMode, boolean exportAll) {
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();

        andConditions.add(new Document("$or", Arrays.asList(
                new Document("status.reason", new Document("$exists", true).append("$ne", null).append("$ne", "")),
                new Document("keywordGroups.logicalName", "/uab/awards/estat_ajut"),
                new Document("status.typeDiscriminator", new Document("$in", Arrays.asList("DeclinedAwardStatus", "CurtailedAwardStatus", "TerminatedAwardStatus")))
        )));

        if (search != null && !search.isBlank()) {
            String s = search.trim();
            andConditions.add(new Document("$or", Arrays.asList(
                    new Document("title.ca_ES", new Document("$regex", s).append("$options", "i")),
                    new Document("title.es_ES", new Document("$regex", s).append("$options", "i")),
                    new Document("title.en_GB", new Document("$regex", s).append("$options", "i")),
                    new Document("status.reason", new Document("$regex", s).append("$options", "i")),
                    new Document("uuid", new Document("$regex", s).append("$options", "i")),
                    new Document("pureId", new Document("$regex", s).append("$options", "i"))
            )));
        }

        if (!andConditions.isEmpty()) {
            matchQuery.put("$and", andConditions);
        }

        List<Document> rawDocs = new ArrayList<>();
        try {
            mongoTemplate.getCollection("Awards").find(matchQuery).into(rawDocs);
        } catch (Exception ignored) {}

        List<Map<String, Object>> processedList = new ArrayList<>();
        int countHasReason = 0;
        int countDiscrepancies = 0;
        int countRenuncia = 0;

        for (Document doc : rawDocs) {
            Map<String, Object> item = new HashMap<>();
            item.put("uuid", doc.getString("uuid"));
            item.put("pureId", doc.get("pureId"));

            Document titleDoc = (Document) doc.get("title");
            String title = "";
            if (titleDoc != null) {
                if (titleDoc.containsKey("ca_ES")) title = titleDoc.getString("ca_ES");
                else if (titleDoc.containsKey("es_ES")) title = titleDoc.getString("es_ES");
                else if (titleDoc.containsKey("en_GB")) title = titleDoc.getString("en_GB");
            }
            item.put("title", title);
            item.put("awardDate", doc.get("awardDate"));

            Document statusDoc = (Document) doc.get("status");
            String statusReason = "";
            String typeDiscriminator = "";
            if (statusDoc != null) {
                statusReason = statusDoc.getString("reason");
                typeDiscriminator = statusDoc.getString("typeDiscriminator");
            }
            item.put("statusReason", statusReason != null ? statusReason : "");
            item.put("typeDiscriminator", typeDiscriminator != null ? typeDiscriminator : "");

            boolean kwResigned = false;
            String kwUri = "";
            String kwValue = "";
            List<?> kwGroups = (List<?>) doc.get("keywordGroups");
            if (kwGroups != null) {
                for (Object gObj : kwGroups) {
                    if (gObj instanceof Document gDoc && "/uab/awards/estat_ajut".equals(gDoc.getString("logicalName"))) {
                        List<?> containers = (List<?>) gDoc.get("keywordContainers");
                        if (containers != null) {
                            for (Object cObj : containers) {
                                if (cObj instanceof Document cDoc) {
                                    Document sKw = (Document) cDoc.get("structuredKeyword");
                                    if (sKw != null) {
                                        kwUri = sKw.getString("uri");
                                        Document term = (Document) sKw.get("term");
                                        if (term != null && term.containsKey("ca_ES")) {
                                            kwValue = term.getString("ca_ES");
                                        }
                                        if (kwUri != null && kwUri.contains("resigned")) {
                                            kwResigned = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item.put("keywordResigned", kwResigned);
            item.put("keywordUri", kwUri);
            item.put("keywordValue", kwValue);

            boolean hasReason = statusReason != null && !statusReason.trim().isEmpty();
            boolean isDeclinedStatus = "DeclinedAwardStatus".equals(typeDiscriminator);
            boolean isCurtailedOrTerminated = "CurtailedAwardStatus".equals(typeDiscriminator) || "TerminatedAwardStatus".equals(typeDiscriminator);

            boolean isRenunciaText = false;
            if (hasReason) {
                String rLower = statusReason.toLowerCase();
                isRenunciaText = rLower.contains("renunc") || rLower.contains("resign") || rLower.contains("declin")
                        || rLower.contains("personal") || rLower.contains("laboral") || rLower.contains("incompatib")
                        || rLower.contains("tesi") || rLower.contains("baja") || rLower.contains("desist") || rLower.contains("revoc");
            }

            boolean isDiscrepancy = false;
            String classification = "CONSISTENT";

            if (kwResigned && !isDeclinedStatus && !hasReason) {
                isDiscrepancy = true;
                classification = "KW_RESIGNED_BUT_NO_REASON";
            } else if (!kwResigned && isRenunciaText) {
                isDiscrepancy = true;
                classification = "REASON_SAYS_RENUNCIA_BUT_NO_KW";
            } else if (isDeclinedStatus && !kwResigned) {
                isDiscrepancy = true;
                classification = "STATUS_DECLINED_BUT_NO_KW";
            } else if (kwResigned && isDeclinedStatus && hasReason) {
                classification = "FULLY_ALIGNED_RESIGNATION";
            } else if (hasReason && !isRenunciaText) {
                classification = "OTHER_REASON";
            }

            item.put("classification", classification);
            item.put("isDiscrepancy", isDiscrepancy);
            item.put("isRenunciaText", isRenunciaText);

            if (hasReason) countHasReason++;
            if (isDiscrepancy) countDiscrepancies++;
            if (kwResigned || isRenunciaText || isDeclinedStatus) countRenuncia++;

            if (filterMode != null && !filterMode.isBlank()) {
                if ("discrepancies".equalsIgnoreCase(filterMode) && !isDiscrepancy) continue;
                if ("has_reason".equalsIgnoreCase(filterMode) && !hasReason) continue;
                if ("renuncia_only".equalsIgnoreCase(filterMode) && (!kwResigned && !isRenunciaText && !isDeclinedStatus)) continue;
                if ("kw_no_reason".equalsIgnoreCase(filterMode) && !"KW_RESIGNED_BUT_NO_REASON".equals(classification)) continue;
                if ("reason_no_kw".equalsIgnoreCase(filterMode) && !"REASON_SAYS_RENUNCIA_BUT_NO_KW".equals(classification)) continue;
            }

            processedList.add(item);
        }

        int totalFiltered = processedList.size();
        List<Map<String, Object>> paginatedContent;

        if (exportAll) {
            paginatedContent = processedList;
        } else {
            int fromIdx = Math.min(page * size, totalFiltered);
            int toIdx = Math.min(fromIdx + size, totalFiltered);
            paginatedContent = processedList.subList(fromIdx, toIdx);
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) totalFiltered / size) : 0;
        Map<String, Object> resp = new HashMap<>();
        resp.put("content", paginatedContent);
        resp.put("totalElements", totalFiltered);
        resp.put("totalPages", totalPages);
        resp.put("page", page);
        resp.put("size", size);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalWithAnyStatusData", rawDocs.size());
        stats.put("totalHasReason", countHasReason);
        stats.put("totalDiscrepancies", countDiscrepancies);
        stats.put("totalRenuncia", countRenuncia);
        resp.put("stats", stats);

        return resp;
    }

    public Map<String, Object> getDistinctReasons(String search, String statusType, String sortBy, String sortOrder) {
        List<Document> pipeline = new ArrayList<>();
        Document matchQuery = new Document("status.reason", new Document("$exists", true).append("$ne", null).append("$ne", ""));

        if (statusType != null && !statusType.isBlank() && !"all".equalsIgnoreCase(statusType)) {
            if ("terminated".equalsIgnoreCase(statusType)) {
                matchQuery.append("status.typeDiscriminator", new Document("$in", Arrays.asList("TerminatedAwardStatus", "CurtailedAwardStatus")));
            } else {
                matchQuery.append("status.typeDiscriminator", statusType.trim());
            }
        }

        pipeline.add(new Document("$match", matchQuery));

        pipeline.add(new Document("$group", new Document("_id", new Document("reason", "$status.reason").append("typeDiscriminator", "$status.typeDiscriminator"))
                .append("count", new Document("$sum", 1))
                .append("sampleTitle", new Document("$first", "$title.ca_ES"))
                .append("sampleTitleEs", new Document("$first", "$title.es_ES"))
                .append("sampleTitleEn", new Document("$first", "$title.en_GB"))
                .append("sampleUuid", new Document("$first", "$uuid"))
                .append("samplePureId", new Document("$first", "$pureId"))
                .append("sampleType", new Document("$first", "$type.term.ca_ES"))
                .append("sampleCategory", new Document("$first", "$categoria"))
        ));

        List<Document> rawGrouped = executeAggregation("Awards", pipeline);
        List<Map<String, Object>> processed = new ArrayList<>();
        int totalProjectsWithReason = 0;

        for (Document d : rawGrouped) {
            Document idDoc = (Document) d.get("_id");
            String reason = idDoc != null ? idDoc.getString("reason") : "";
            String stType = idDoc != null ? idDoc.getString("typeDiscriminator") : "";

            int cnt = d.getInteger("count", 0);
            totalProjectsWithReason += cnt;

            if (search != null && !search.isBlank()) {
                String s = search.trim().toLowerCase();
                if (!reason.toLowerCase().contains(s) && !stType.toLowerCase().contains(s)) {
                    continue;
                }
            }

            Map<String, Object> row = new HashMap<>();
            row.put("reason", reason);
            row.put("statusType", stType);
            row.put("count", cnt);
            row.put("sampleTitle", d.get("sampleTitle"));
            row.put("sampleTitleEs", d.get("sampleTitleEs"));
            row.put("sampleTitleEn", d.get("sampleTitleEn"));
            row.put("sampleUuid", d.get("sampleUuid"));
            row.put("samplePureId", d.get("samplePureId"));
            row.put("sampleType", d.get("sampleType"));
            row.put("sampleCategory", d.get("sampleCategory"));

            String rLower = reason.toLowerCase();
            boolean isRenuncia = rLower.contains("renunc") || rLower.contains("resign") || rLower.contains("declin")
                    || rLower.contains("personal") || rLower.contains("laboral") || rLower.contains("incompatib")
                    || rLower.contains("tesi") || rLower.contains("baja") || rLower.contains("desist") || rLower.contains("revoc");
            row.put("isRenuncia", isRenuncia);

            processed.add(row);
        }

        int dir = "asc".equalsIgnoreCase(sortOrder) ? 1 : -1;
        processed.sort((a, b) -> {
            if ("reason".equalsIgnoreCase(sortBy)) {
                return dir * ((String) a.get("reason")).compareToIgnoreCase((String) b.get("reason"));
            } else if ("statusType".equalsIgnoreCase(sortBy)) {
                return dir * ((String) a.get("statusType")).compareToIgnoreCase((String) b.get("statusType"));
            } else {
                return dir * Integer.compare((Integer) a.get("count"), (Integer) b.get("count"));
            }
        });

        Map<String, Object> res = new HashMap<>();
        res.put("totalDistinctReasons", processed.size());
        res.put("totalProjectsWithReason", totalProjectsWithReason);
        res.put("reasons", processed);
        return res;
    }

    /*
    ========================================================================
    4. CONCESSIONS & CALLS
    ========================================================================
    */

    public List<Document> getConcessionsByProgram() {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("$or", List.of(
                        new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
                        new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
                ))),
                new Document("$lookup", new Document()
                        .append("from", "Applications")
                        .append("localField", "applications.uuid")
                        .append("foreignField", "uuid")
                        .append("as", "appDocs")),
                new Document("$unwind", "$appDocs"),
                new Document("$lookup", new Document()
                        .append("from", "FundingOpportunities")
                        .append("localField", "appDocs.fundingOpportunity.uuid")
                        .append("foreignField", "uuid")
                        .append("as", "fOpp")),
                new Document("$unwind", "$fOpp"),
                new Document("$project", new Document()
                        .append("awardDate", 1)
                        .append("uuid", 1)
                        .append("collaborators", 1)
                        .append("fOppType", "$fOpp.type.term")
                        .append("keywordGroups", "$fOpp.keywordGroups")),
                new Document("$unwind", "$keywordGroups"),
                new Document("$match", new Document("keywordGroups.logicalName", "/uab/fundingopportunities/programes")),
                new Document("$unwind", "$keywordGroups.keywordContainers"),
                new Document("$project", new Document()
                        .append("uuid", 1)
                        .append("awardDate", 1)
                        .append("fOppType", 1)
                        .append("collaborators", 1)
                        .append("program", "$keywordGroups.keywordContainers.structuredKeyword.term")),
                new Document("$addFields", new Document("esLider", new Document("$let", new Document()
                        .append("vars", new Document("internalLeads", new Document("$filter", new Document()
                                .append("input", new Document("$ifNull", Arrays.asList("$collaborators", Collections.emptyList())))
                                .append("as", "c")
                                .append("cond", new Document("$and", Arrays.asList(
                                        new Document("$eq", Arrays.asList("$$c.leadCollaborator", true)),
                                        new Document("$eq", Arrays.asList("$$c.typeDiscriminator", "InternalCollaboratorAssociation"))
                                ))))).append("hasCollaborators", new Document("$gt", Arrays.asList(
                                new Document("$size", new Document("$ifNull", Arrays.asList("$collaborators", Collections.emptyList()))),
                                0
                        ))))
                        .append("in", new Document("$cond", Arrays.asList(
                                "$$hasCollaborators",
                                new Document("$gt", Arrays.asList(new Document("$size", "$$internalLeads"), 0)),
                                true
                        )))
                ))),
                new Document("$addFields", new Document("anyo", new Document("$cond", Arrays.asList(
                        new Document("$ifNull", Arrays.asList("$awardDate", false)),
                        new Document("$year", "$awardDate"),
                        null
                )))),
                new Document("$match", new Document("anyo", new Document("$ne", null))),
                new Document("$group", new Document()
                        .append("_id", new Document("anyo", "$anyo")
                                .append("program", "$program")
                                .append("fOppType", "$fOppType"))
                        .append("count", new Document("$sum", 1))
                        .append("liderCount", new Document("$sum", new Document("$cond", Arrays.asList("$esLider", 1, 0))))),
                new Document("$project", new Document()
                        .append("_id", 0)
                        .append("anyo", "$_id.anyo")
                        .append("program", "$_id.program")
                        .append("fOppType", "$_id.fOppType")
                        .append("count", "$count")
                        .append("liderCount", "$liderCount")),
                new Document("$sort", new Document("anyo", -1).append("count", -1))
        );

        return executeAggregation("Awards", pipeline);
    }

    public List<Document> getConcessionsAwards() {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("$or", List.of(
                        new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
                        new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
                ))),
                new Document("$lookup", new Document()
                        .append("from", "Applications")
                        .append("localField", "applications.uuid")
                        .append("foreignField", "uuid")
                        .append("as", "appDocs")),
                new Document("$unwind", "$appDocs"),
                new Document("$lookup", new Document()
                        .append("from", "FundingOpportunities")
                        .append("localField", "appDocs.fundingOpportunity.uuid")
                        .append("foreignField", "uuid")
                        .append("as", "fOpp")),
                new Document("$unwind", "$fOpp"),
                new Document("$project", new Document()
                        .append("awardDate", 1)
                        .append("uuid", 1)
                        .append("pureId", 1)
                        .append("title", 1)
                        .append("collaborators", 1)
                        .append("fOppType", "$fOpp.type.term")
                        .append("keywordGroups", "$fOpp.keywordGroups")),
                new Document("$unwind", "$keywordGroups"),
                new Document("$match", new Document("keywordGroups.logicalName", "/uab/fundingopportunities/programes")),
                new Document("$unwind", "$keywordGroups.keywordContainers"),
                new Document("$addFields", new Document("esLider", new Document("$let", new Document()
                        .append("vars", new Document("internalLeads", new Document("$filter", new Document()
                                .append("input", new Document("$ifNull", Arrays.asList("$collaborators", Collections.emptyList())))
                                .append("as", "c")
                                .append("cond", new Document("$and", Arrays.asList(
                                        new Document("$eq", Arrays.asList("$$c.leadCollaborator", true)),
                                        new Document("$eq", Arrays.asList("$$c.typeDiscriminator", "InternalCollaboratorAssociation"))
                                ))))).append("hasCollaborators", new Document("$gt", Arrays.asList(
                                new Document("$size", new Document("$ifNull", Arrays.asList("$collaborators", Collections.emptyList()))),
                                0
                        ))))
                        .append("in", new Document("$cond", Arrays.asList(
                                "$$hasCollaborators",
                                new Document("$gt", Arrays.asList(new Document("$size", "$$internalLeads"), 0)),
                                true
                        )))
                ))),
                new Document("$addFields", new Document("anyo", new Document("$cond", Arrays.asList(
                        new Document("$ifNull", Arrays.asList("$awardDate", false)),
                        new Document("$year", "$awardDate"),
                        null
                )))),
                new Document("$match", new Document("anyo", new Document("$ne", null))),
                new Document("$project", new Document()
                        .append("_id", 0)
                        .append("uuid", 1)
                        .append("pureId", 1)
                        .append("title", 1)
                        .append("anyo", 1)
                        .append("fOppType", 1)
                        .append("program", "$keywordGroups.keywordContainers.structuredKeyword.term")
                        .append("esLider", 1)
                        .append("fundings", 1)),
                new Document("$sort", new Document("anyo", -1).append("title.es_ES", 1))
        );

        List<Document> rawResults = executeAggregation("Awards", pipeline);
        List<Document> processed = new ArrayList<>();
        for (Document doc : rawResults) {
            double totalImport = 0.0;
            List<Document> fundings = castDocumentList(doc.get("fundings"));
            if (fundings != null && !fundings.isEmpty()) {
                for (Document f : fundings) {
                    List<Document> cols = castDocumentList(f.get("fundingCollaborators"));
                    if (cols != null) {
                        for (Document col : cols) {
                            Document part = (Document) col.get("institutionalPart");
                            if (part != null) {
                                Object val = part.get("value");
                                if (val instanceof Number n) {
                                    totalImport += n.doubleValue();
                                }
                            }
                        }
                    }
                }
            }
            doc.remove("fundings");
            doc.append("importe", totalImport);
            processed.add(doc);
        }

        return processed;
    }

    @SuppressWarnings("unchecked")
    private List<Document> castDocumentList(Object obj) {
        if (obj instanceof List<?> list) {
            List<Document> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Document d) result.add(d);
            }
            return result;
        }
        return Collections.emptyList();
    }

    public List<String> getFellowshipCallTypes() {
        return getCallTypesByAwardType("Beques i Ajuts a la Formació");
    }

    public List<String> getInternationalCallTypes() {
        return getCallTypesByAwardType("Projectes i Ajuts Internacionals");
    }

    public List<String> getIncorporacioCallTypes() {
        return getCallTypesByAwardType("Projectes i Ajuts a la Incorporació");
    }

    public List<String> getResearchProjectPrograms() {
        List<String> appUuids = mongoTemplate.getCollection("Awards")
                .distinct("applications.uuid",
                        new Document("type.term.ca_ES", "Projectes i Ajuts a la Recerca")
                                .append("workflow.step", "validated"),
                        String.class)
                .into(new ArrayList<>());

        if (appUuids.isEmpty()) return Collections.emptyList();

        List<Document> apps = mongoTemplate.getCollection("Applications")
                .find(new Document("uuid", new Document("$in", appUuids)))
                .projection(new Document("fundingOpportunity.uuid", 1))
                .into(new ArrayList<>());

        Set<String> fundingOppUuids = new HashSet<>();
        for (Document app : apps) {
            Document fo = (Document) app.get("fundingOpportunity");
            if (fo != null && fo.getString("uuid") != null) {
                fundingOppUuids.add(fo.getString("uuid"));
            }
        }
        if (fundingOppUuids.isEmpty()) return Collections.emptyList();

        List<Document> fOpps = mongoTemplate.getCollection("FundingOpportunities")
                .find(new Document("uuid", new Document("$in", fundingOppUuids)))
                .projection(new Document("keywordGroups", 1))
                .into(new ArrayList<>());

        Set<String> programs = new TreeSet<>();
        for (Document doc : fOpps) {
            List<?> kwGroups = (List<?>) doc.get("keywordGroups");
            if (kwGroups == null) continue;
            for (Object gObj : kwGroups) {
                if (!(gObj instanceof Document gDoc)) continue;
                if (!"/uab/fundingopportunities/programes".equals(gDoc.getString("logicalName"))) continue;
                List<?> containers = (List<?>) gDoc.get("keywordContainers");
                if (containers != null) {
                    for (Object cObj : containers) {
                        if (!(cObj instanceof Document cDoc)) continue;
                        Document skw = (Document) cDoc.get("structuredKeyword");
                        if (skw == null) continue;
                        Document term = (Document) skw.get("term");
                        if (term != null) {
                            String val = term.getString("ca_ES");
                            if (val == null || val.isBlank()) val = term.getString("es_ES");
                            if (val == null || val.isBlank()) val = term.getString("en_GB");
                            if (val != null && !val.isBlank()) programs.add(val);
                        }
                    }
                }
                List<?> classifications = (List<?>) gDoc.get("classifications");
                if (classifications != null) {
                    for (Object clObj : classifications) {
                        if (!(clObj instanceof Document clDoc)) continue;
                        Document term = (Document) clDoc.get("term");
                        if (term != null) {
                            String val = term.getString("ca_ES");
                            if (val == null || val.isBlank()) val = term.getString("es_ES");
                            if (val == null || val.isBlank()) val = term.getString("en_GB");
                            if (val != null && !val.isBlank()) programs.add(val);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(programs);
    }

    private List<String> getCallTypesByAwardType(String awardTypeCaEs) {
        List<String> appUuids = mongoTemplate.getCollection("Awards")
                .distinct("applications.uuid",
                        new Document("type.term.ca_ES", awardTypeCaEs).append("workflow.step", "validated"),
                        String.class)
                .into(new ArrayList<>());

        if (appUuids.isEmpty()) return Collections.emptyList();

        List<Document> apps = mongoTemplate.getCollection("Applications")
                .find(new Document("uuid", new Document("$in", appUuids)))
                .projection(new Document("fundingOpportunity.uuid", 1))
                .into(new ArrayList<>());

        Set<String> fundingOppUuids = new HashSet<>();
        for (Document app : apps) {
            Document fo = (Document) app.get("fundingOpportunity");
            if (fo != null && fo.getString("uuid") != null) {
                fundingOppUuids.add(fo.getString("uuid"));
            }
        }
        if (fundingOppUuids.isEmpty()) return Collections.emptyList();

        List<Document> fOpps = mongoTemplate.getCollection("FundingOpportunities")
                .find(new Document("uuid", new Document("$in", fundingOppUuids)))
                .projection(new Document("type", 1))
                .into(new ArrayList<>());

        Set<String> callTypes = new TreeSet<>();
        for (Document doc : fOpps) {
            Object typeObj = doc.get("type");
            if (typeObj instanceof Document tDoc) {
                Object termObj = tDoc.get("term");
                if (termObj instanceof Document termDoc) {
                    String caVal = termDoc.getString("ca_ES");
                    if (caVal == null || caVal.isBlank()) caVal = termDoc.getString("es_ES");
                    if (caVal == null || caVal.isBlank()) caVal = termDoc.getString("en_GB");
                    if (caVal != null && !caVal.isBlank()) {
                        callTypes.add(caVal);
                        continue;
                    }
                    Object textObj = termDoc.get("text");
                    if (textObj instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof Document td && "ca_ES".equals(td.getString("locale"))) {
                                String val = td.getString("value");
                                if (val != null && !val.isBlank()) {
                                    callTypes.add(val);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(callTypes);
    }

    /*
    ========================================================================
    5. POWERTABLE & STATISTICAL AGGREGATION DELEGATORS
    ========================================================================
    */

    public List<Document> getPowerTable(PowerTableQueryCriteria criteria) {
        return awardService.getPowerTable(criteria.desde(), criteria.hasta(), criteria.modoAnio(), criteria.collaboratorUuid());
    }

    public List<Document> getTipusPerCategoria() {
        return awardService.getTipusPerCategoria();
    }

    public List<Document> getAwardsByAmount() {
        return awardService.getAwardsByAmount();
    }

    public List<Document> getImportPerTipusAnio() {
        return awardService.getImportPerTipusAnio();
    }

    public Map<String, Object> getTotalStats() {
        return awardService.getTotalStats();
    }

    public List<Document> getMapConvenis(Integer desde, Integer hasta, String collaboratorUuid) {
        return awardService.getMapConvenis(desde, hasta, collaboratorUuid);
    }

    public int getXarxesPlataformesCount(Integer desde, Integer hasta, String collaboratorUuid) {
        return awardService.getXarxesPlataformesCount(desde, hasta, collaboratorUuid);
    }

    public List<Document> getAwardsLlistaInstitut(String collaboratorUuid, Integer desde, Integer hasta) {
        return awardService.getAwardsLlistaInstitut(collaboratorUuid, desde, hasta);
    }

    public List<Document> getIpsInstitut(String collaboratorUuid, Integer desde, Integer hasta) {
        return awardService.getIpsInstitut(collaboratorUuid, desde, hasta);
    }

    public List<Document> getProyectosPorAnio(Integer desde, Integer hasta, String collaboratorUuid,
                                             String deptUuid, String persona, String modoAnio,
                                             List<String> categoria, List<String> tipus) {
        return awardService.getProyectosPorAnio(desde, hasta, collaboratorUuid, deptUuid, persona, modoAnio, categoria, tipus);
    }

    private List<Document> executeAggregation(String collectionName, List<Document> pipeline) {
        List<Document> results = new ArrayList<>();
        try {
            mongoTemplate.getCollection(collectionName)
                    .aggregate(pipeline)
                    .allowDiskUse(true)
                    .into(results);
        } catch (Exception e) {
            System.err.println("[AwardFinancialEngine] Aggregation error on " + collectionName + ": " + e.getMessage());
        }
        return results;
    }
}
