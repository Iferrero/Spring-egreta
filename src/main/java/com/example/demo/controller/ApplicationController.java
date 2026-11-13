package com.example.demo.controller;

import com.example.demo.model.Application;
import com.example.demo.repository.ApplicationRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/applications")
@CrossOrigin(origins = "*")
public class ApplicationController {

    private final ApplicationRepository repository;
    private final MongoTemplate mongoTemplate;

    @Autowired
    public ApplicationController(ApplicationRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public Page<Application> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uuid"));

        String collectionName = resolveApplicationCollection();
        if (collectionName == null) {
            if (buscar == null || buscar.isBlank()) {
                return repository.findAll(pageable);
            }
            return repository.findByTitleContainingIgnoreCase(buscar, pageable);
        }

        Query query = new Query();
        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            query.addCriteria(new Criteria().orOperator(
                Criteria.where("title.value").regex(escaped, "i"),
                Criteria.where("title.ca_ES").regex(escaped, "i"),
                Criteria.where("title.es_ES").regex(escaped, "i"),
                Criteria.where("title.en_GB").regex(escaped, "i"),
                Criteria.where("title.text.value").regex(escaped, "i")
            ));
        }

        long total = mongoTemplate.count(query, collectionName);
        query.with(pageable);
        List<Application> items = mongoTemplate.find(query, Application.class, collectionName);
        return new PageImpl<>(items, pageable, total);
    }

    @GetMapping("/describe")
    public Map<String, Object> describeCollection(@RequestParam(defaultValue = "20") int sampleSize) {
        String collectionName = resolveApplicationCollection();
        if (collectionName == null) {
            return Map.of(
                "collection", "not-found",
                "totalDocuments", 0,
                "sampleSize", 0,
                "fieldPaths", List.of(),
                "samples", List.of()
            );
        }

        int safeSampleSize = Math.max(1, Math.min(sampleSize, 200));
        List<Document> docs = mongoTemplate
            .getCollection(collectionName)
            .find()
            .limit(safeSampleSize)
            .into(new ArrayList<>());

        Set<String> paths = new LinkedHashSet<>();
        List<Map<String, Object>> samples = new ArrayList<>();

        for (Document doc : docs) {
            collectFieldPaths(doc, "", paths);

            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("uuid", doc.get("uuid"));
            sample.put("title", doc.get("title"));
            sample.put("type", doc.get("type"));
            sample.put("workflow", doc.get("workflow"));
            samples.add(sample);
        }

        long totalDocuments = mongoTemplate.getCollection(collectionName).countDocuments();
        return Map.of(
            "collection", collectionName,
            "totalDocuments", totalDocuments,
            "sampleSize", docs.size(),
            "fieldPaths", new ArrayList<>(paths),
            "samples", samples
        );
    }

    public static class ProgramInfo {
        private String primaryProgram;
        private String programPath;
        private List<String> allPrograms = new ArrayList<>();

        public String getPrimaryProgram() { return primaryProgram; }
        public String getProgramPath() { return programPath; }
        public List<String> getAllPrograms() { return allPrograms; }
    }

    private Map<String, String> programSchemeCache = null;

    private Map<String, String> loadProgramClassificationScheme() {
        if (programSchemeCache != null) {
            return programSchemeCache;
        }
        Map<String, String> map = new HashMap<>();
        List<String> schemeCols = List.of("Classificationschemes", "ClassificationSchemes", "classificationschemes");
        for (String col : schemeCols) {
            if (!mongoTemplate.collectionExists(col)) continue;
            try {
                Document scheme = mongoTemplate.getCollection(col)
                    .find(new Document("baseUri", "/uab/fundingopportunities/programes"))
                    .first();
                if (scheme != null) {
                    List<?> contained = (List<?>) scheme.get("containedClassifications");
                    if (contained != null) {
                        for (Object obj : contained) {
                            if (obj instanceof Document d) {
                                String uri = d.getString("uri");
                                String term = extractLocalizedValue(d.get("term"));
                                if (uri != null && term != null && !term.isBlank()) {
                                    map.put(uri.trim(), term.trim());
                                }
                            }
                        }
                    }
                    break;
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        programSchemeCache = map;
        return map;
    }

    public ProgramInfo extractProgramInfo(Document fundingDoc, Map<String, String> schemeTerms) {
        ProgramInfo info = new ProgramInfo();
        if (fundingDoc == null) return info;
        List<?> kwGroups = (List<?>) fundingDoc.get("keywordGroups");
        if (kwGroups == null) return info;

        Set<String> programs = new LinkedHashSet<>();
        String bestPath = null;
        String bestPrimary = null;

        for (Object gObj : kwGroups) {
            if (!(gObj instanceof Document gDoc)) continue;
            if (!"/uab/fundingopportunities/programes".equals(gDoc.getString("logicalName"))) continue;

            List<?> containers = (List<?>) gDoc.get("keywordContainers");
            if (containers != null) {
                for (Object cObj : containers) {
                    if (!(cObj instanceof Document cDoc)) continue;
                    Document skw = (Document) cDoc.get("structuredKeyword");
                    if (skw == null) continue;
                    String uri = skw.getString("uri");
                    String termVal = extractLocalizedValue(skw.get("term"));
                    PathInfo p = resolvePathFromUri(uri, termVal, schemeTerms);
                    if (p != null) {
                        if (p.primary != null) programs.add(p.primary);
                        if (bestPrimary == null) bestPrimary = p.primary;
                        if (bestPath == null || (p.path != null && p.path.length() > bestPath.length())) {
                            bestPath = p.path;
                        }
                    }
                }
            }

            List<?> classifications = (List<?>) gDoc.get("classifications");
            if (classifications != null) {
                for (Object clObj : classifications) {
                    if (!(clObj instanceof Document clDoc)) continue;
                    String uri = clDoc.getString("uri");
                    String termVal = extractLocalizedValue(clDoc.get("term"));
                    PathInfo p = resolvePathFromUri(uri, termVal, schemeTerms);
                    if (p != null) {
                        if (p.primary != null) programs.add(p.primary);
                        if (bestPrimary == null) bestPrimary = p.primary;
                        if (bestPath == null || (p.path != null && p.path.length() > bestPath.length())) {
                            bestPath = p.path;
                        }
                    }
                }
            }
        }

        info.primaryProgram = bestPrimary != null ? bestPrimary : (bestPath != null ? bestPath : "Sense programa");
        info.programPath = bestPath != null ? bestPath : info.primaryProgram;
        info.allPrograms.addAll(programs);
        return info;
    }

    private static class PathInfo {
        String primary;
        String path;
    }

    private PathInfo resolvePathFromUri(String uri, String fallbackTerm, Map<String, String> schemeTerms) {
        if (uri == null || uri.isBlank()) {
            if (fallbackTerm != null && !fallbackTerm.isBlank()) {
                PathInfo pi = new PathInfo();
                pi.primary = fallbackTerm;
                pi.path = fallbackTerm;
                return pi;
            }
            return null;
        }

        PathInfo pi = new PathInfo();
        String base = "/uab/fundingopportunities/programes";
        if (uri.startsWith(base)) {
            String rel = uri.substring(base.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            if (!rel.isBlank()) {
                String[] segments = rel.split("/");
                List<String> terms = new ArrayList<>();
                String curr = base;
                for (String seg : segments) {
                    curr += "/" + seg;
                    String t = schemeTerms != null ? schemeTerms.get(curr) : null;
                    if (t != null && !t.isBlank()) {
                        terms.add(t);
                    }
                }
                if (!terms.isEmpty()) {
                    pi.primary = terms.get(0);
                    pi.path = String.join(" > ", terms);
                    return pi;
                }
            }
        }

        String matchedTerm = schemeTerms != null ? schemeTerms.get(uri) : null;
        if (matchedTerm != null) {
            pi.primary = matchedTerm;
            pi.path = matchedTerm;
            return pi;
        }

        if (fallbackTerm != null && !fallbackTerm.isBlank()) {
            pi.primary = fallbackTerm;
            pi.path = fallbackTerm;
            return pi;
        }

        return null;
    }

    public List<Map<String, Object>> statsByFundingOpportunity(boolean excludeResigned) {
        return statsByFundingOpportunity(excludeResigned, null, null);
    }

    public List<Map<String, Object>> statsByFundingOpportunity(boolean excludeResigned, String program) {
        return statsByFundingOpportunity(excludeResigned, program, null);
    }

    @GetMapping("/stats/by-funding-opportunity")
    public List<Map<String, Object>> statsByFundingOpportunity(
            @RequestParam(value = "excludeResigned", required = false, defaultValue = "false") boolean excludeResigned,
            @RequestParam(value = "program", required = false) String program,
            @RequestParam(value = "callType", required = false) String callType) {
        String applicationCollection = resolveApplicationCollection();
        if (applicationCollection == null) {
            return List.of();
        }

        String fundingCollection = resolveFundingOpportunityCollection();

        // Determine filter criteria: call type vs program keyword
        Set<String> filterCallTypes = new HashSet<>();
        String filterProgramText = null;

        if (callType != null && !callType.isBlank() && !"ALL".equalsIgnoreCase(callType)) {
            filterCallTypes.addAll(parseProgramFilter(callType));
        }

        if (program != null && !program.isBlank() && !"ALL".equalsIgnoreCase(program)) {
            Set<String> progTokens = parseProgramFilter(program);
            boolean allAreCallTypes = !progTokens.isEmpty() && progTokens.stream().allMatch(t -> "PJE".equals(t) || "PEI".equals(t));
            if (allAreCallTypes) {
                filterCallTypes.addAll(progTokens);
            } else {
                filterProgramText = program.trim().toLowerCase(Locale.ROOT);
            }
        }

        // Restrict the aggregation to the funding opportunities of the requested call type(s)
        // instead of aggregating every application and discarding the rest afterwards.
        Document matchStage = new Document("fundingOpportunity.uuid", new Document("$ne", null));
        if (!filterCallTypes.isEmpty()) {
            Set<String> callTypeFundingUuids = findFundingUuidsByCallType(fundingCollection, filterCallTypes);
            if (callTypeFundingUuids.isEmpty()) {
                return List.of();
            }
            matchStage = new Document("fundingOpportunity.uuid", new Document("$in", new ArrayList<>(callTypeFundingUuids)));
        }

        List<String> resignedAppUuids = getResignedApplicationUuids();
        if (excludeResigned && !resignedAppUuids.isEmpty()) {
            matchStage.append("uuid", new Document("$nin", resignedAppUuids));
        }

        Document firstProjectSpec = new Document("fundingUuid", "$fundingOpportunity.uuid")
            .append("uuid", 1)
            .append("sent", 1)
            .append("applicationDateRaw", "$applicationDate")
            .append("replyText", buildReplyTextExpression())
            .append("piUuids", new Document("$let", new Document("vars", new Document("applicantsArr", new Document("$cond", Arrays.asList(new Document("$isArray", "$applicants"), "$applicants", List.of())))).append("in", new Document("$let", new Document("vars", new Document("pis", new Document("$filter", new Document("input", "$$applicantsArr").append("as", "app").append("cond", new Document("$eq", Arrays.asList("$$app.role.uri", "/dk/atira/pure/application/roles/application/pi")))))).append("in", new Document("$cond", Arrays.asList(new Document("$gt", Arrays.asList(new Document("$size", "$$pis"), 0)), "$$pis.person.uuid", "$$applicantsArr.person.uuid")))))))
            .append("benUuids", new Document("$let", new Document("vars", new Document("applicantsArr", new Document("$cond", Arrays.asList(new Document("$isArray", "$applicants"), "$applicants", List.of())))).append("in", new Document("$let", new Document("vars", new Document("bens", new Document("$filter", new Document("input", "$$applicantsArr").append("as", "app").append("cond", new Document("$or", Arrays.asList(new Document("$in", Arrays.asList("$$app.role.uri", Arrays.asList("/dk/atira/pure/application/roles/application/ben", "/dk/atira/pure/application/roles/application/bec", "/dk/atira/pure/application/roles/application/can", "/dk/atira/pure/application/roles/application/applicant"))), new Document("$in", Arrays.asList(new Document("$toLower", "$$app.role.term.ca_ES"), Arrays.asList("beneficiari/a", "becari/a", "candidat/a", "beneficiario/a", "becario/a", "candidato/a"))))))))).append("in", new Document("$cond", Arrays.asList(new Document("$gt", Arrays.asList(new Document("$size", "$$bens"), 0)), "$$bens.person.uuid", new Document("$let", new Document("vars", new Document("pis", new Document("$filter", new Document("input", "$$applicantsArr").append("as", "app").append("cond", new Document("$eq", Arrays.asList("$$app.role.uri", "/dk/atira/pure/application/roles/application/pi")))))).append("in", new Document("$cond", Arrays.asList(new Document("$gt", Arrays.asList(new Document("$size", "$$pis"), 0)), "$$pis.person.uuid", "$$applicantsArr.person.uuid")))))))))))
            .append("isResigned", new Document("$cond", Arrays.asList(new Document("$in", Arrays.asList("$uuid", resignedAppUuids)), 1, 0)));

        List<Document> pipeline = List.of(
            new Document("$match", matchStage),
            new Document("$project", firstProjectSpec),
            new Document("$lookup", new Document("from", "Persons").append("localField", "piUuids").append("foreignField", "uuid").append("as", "piPersons")),
            new Document("$lookup", new Document("from", "Persons").append("localField", "benUuids").append("foreignField", "uuid").append("as", "benPersons")),
            new Document("$project", new Document("fundingUuid", 1).append("uuid", 1).append("sent", 1).append("applicationDateRaw", 1).append("replyText", 1).append("genderVal", new Document("$arrayElemAt", Arrays.asList("$piPersons.gender", 0))).append("sexVal", new Document("$arrayElemAt", Arrays.asList("$piPersons.sex", 0))).append("benGenderVal", new Document("$arrayElemAt", Arrays.asList("$benPersons.gender", 0))).append("benSexVal", new Document("$arrayElemAt", Arrays.asList("$benPersons.sex", 0))).append("isResigned", 1)),
            new Document("$project", new Document("fundingUuid", 1).append("uuid", 1).append("sent", 1).append("applicationDateRaw", 1).append("rejected", buildRejectedExpression()).append("accepted", buildAcceptedExpression()).append("rawGender", new Document("$toLower", new Document("$ifNull", Arrays.asList("$genderVal.uri", new Document("$ifNull", Arrays.asList("$genderVal.term.en_GB", new Document("$ifNull", Arrays.asList("$genderVal.term.ca_ES", new Document("$ifNull", Arrays.asList("$genderVal.term.es_ES", new Document("$ifNull", Arrays.asList("$genderVal", new Document("$ifNull", Arrays.asList("$sexVal.term.en_GB", new Document("$ifNull", Arrays.asList("$sexVal.term.ca_ES", new Document("$ifNull", Arrays.asList("$sexVal", ""))))))))))))))))))   .append("rawBenGender", new Document("$toLower", new Document("$ifNull", Arrays.asList("$benGenderVal.uri", new Document("$ifNull", Arrays.asList("$benGenderVal.term.en_GB", new Document("$ifNull", Arrays.asList("$benGenderVal.term.ca_ES", new Document("$ifNull", Arrays.asList("$benGenderVal.term.es_ES", new Document("$ifNull", Arrays.asList("$benGenderVal", new Document("$ifNull", Arrays.asList("$benSexVal.term.en_GB", new Document("$ifNull", Arrays.asList("$benSexVal.term.ca_ES", new Document("$ifNull", Arrays.asList("$benSexVal", ""))))))))))))))))))   .append("isResigned", 1)),
            new Document("$project", new Document("fundingUuid", 1).append("uuid", 1).append("sent", 1).append("applicationDateRaw", 1).append("rejected", 1).append("accepted", 1).append("isFemale", new Document("$cond", Arrays.asList(new Document("$or", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawGender").append("regex", "female|mujer|femeni|dona")), new Document("$eq", Arrays.asList("$rawGender", "f")))), 1, 0))).append("isMale", new Document("$cond", Arrays.asList(new Document("$and", Arrays.asList(new Document("$not", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawGender").append("regex", "female")))), new Document("$or", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawGender").append("regex", "male|hombre|masculi|home")), new Document("$eq", Arrays.asList("$rawGender", "m")))))), 1, 0))).append("isBenFemale", new Document("$cond", Arrays.asList(new Document("$or", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawBenGender").append("regex", "female|mujer|femeni|dona")), new Document("$eq", Arrays.asList("$rawBenGender", "f")))), 1, 0))).append("isResigned", 1)),
            new Document("$group", new Document("_id", "$fundingUuid").append("sent", new Document("$sum", 1)).append("accepted", new Document("$sum", "$accepted")).append("rejected", new Document("$sum", "$rejected")).append("acceptedFemale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isFemale", 1)), "$accepted", 0)))).append("acceptedMale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isMale", 1)), "$accepted", 0)))).append("acceptedFemaleBeneficiary", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isBenFemale", 1)), "$accepted", 0)))).append("rejectedFemale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isFemale", 1)), "$rejected", 0)))).append("rejectedMale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isMale", 1)), "$rejected", 0)))).append("resigned", new Document("$sum", "$isResigned")).append("applicationDateRaw", new Document("$push", "$applicationDateRaw")).append("acceptedAppUuids", new Document("$push", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$accepted", 1)), "$uuid", "$$REMOVE"))))),
            new Document("$project", new Document("_id", 0).append("fundingUuid", "$_id").append("sent", 1).append("accepted", 1).append("rejected", 1).append("acceptedFemale", 1).append("acceptedMale", 1).append("acceptedFemaleBeneficiary", 1).append("rejectedFemale", 1).append("rejectedMale", 1).append("resigned", 1).append("applicationDateRaw", 1).append("acceptedAppUuids", 1))
        );

        List<Document> stats = mongoTemplate
            .getCollection(applicationCollection)
            .aggregate(pipeline)
            .into(new ArrayList<>());

        if (stats.isEmpty()) {
            return List.of();
        }

        Map<String, Document> fundingByUuid = new LinkedHashMap<>();

        if (fundingCollection != null) {
            List<String> uuids = stats.stream()
                .map(d -> d.getString("fundingUuid"))
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();

            if (!uuids.isEmpty()) {
                // keywordGroups holds most of each document's weight; only the "programes" group is used,
                // so let MongoDB drop the others before transferring.
                Document proj = new Document("uuid", 1)
                    .append("title", 1)
                    .append("type", 1)
                    .append("openingDate", 1)
                    .append("submissionPeriod", 1)
                    .append("submissionYear", 1)
                    .append("pureId", 1)
                    .append("keywordGroups", new Document("$filter", new Document("input", "$keywordGroups")
                        .append("as", "g")
                        .append("cond", new Document("$eq", Arrays.asList("$$g.logicalName", PROGRAM_KEYWORD_GROUP)))));
                try (var cursor = mongoTemplate
                    .getCollection(fundingCollection)
                    .aggregate(List.of(
                        new Document("$match", new Document("uuid", new Document("$in", uuids))),
                        new Document("$project", proj)))
                    .cursor()) {
                    while (cursor.hasNext()) {
                        try {
                            Document doc = cursor.next();
                            String uuid = doc.getString("uuid");
                            if (uuid != null && !uuid.isBlank()) {
                                fundingByUuid.put(uuid, doc);
                            }
                        } catch (Exception e) {
                            // Skip corrupted BSON document
                        }
                    }
                } catch (Exception e) {
                    // Ignore query failure
                }
            }
        }

        Map<String, String> schemeTerms = loadProgramClassificationScheme();

        Map<String, Boolean> leaderByAppUuid = getAwardLeaderByApplicationUuid();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document row : stats) {
            String fundingUuid = row.getString("fundingUuid");
            int sent = toInt(row.get("sent"));
            int accepted = toInt(row.get("accepted"));
            int rejected = toInt(row.get("rejected"));
            int pending = Math.max(sent - accepted - rejected, 0);

            Document fundingDoc = fundingByUuid.get(fundingUuid);
            String fundingTitle = fundingDoc != null ? extractLocalizedValue(fundingDoc.get("title")) : null;
            String fundingType = fundingDoc != null ? extractFundingType(fundingDoc.get("type")) : null;
            String fundingTypeUri = fundingDoc != null ? extractFundingTypeUri(fundingDoc.get("type")) : null;
            String callTypeCode = resolveProgramCode(fundingTypeUri, fundingType);

            if (!filterCallTypes.isEmpty() && (callTypeCode == null || !filterCallTypes.contains(callTypeCode))) {
                continue;
            }

            ProgramInfo pInfo = extractProgramInfo(fundingDoc, schemeTerms);

            if (filterProgramText != null) {
                boolean matchesProg = false;
                if (pInfo.primaryProgram != null && pInfo.primaryProgram.toLowerCase(Locale.ROOT).contains(filterProgramText)) {
                    matchesProg = true;
                } else if (pInfo.programPath != null && pInfo.programPath.toLowerCase(Locale.ROOT).contains(filterProgramText)) {
                    matchesProg = true;
                } else {
                    for (String p : pInfo.allPrograms) {
                        if (p != null && p.toLowerCase(Locale.ROOT).contains(filterProgramText)) {
                            matchesProg = true;
                            break;
                        }
                    }
                }
                if (!matchesProg) {
                    continue;
                }
            }

            Integer fundingOpeningYear = fundingDoc != null ? extractFundingYear(fundingDoc) : null;
            Integer applicationYear = extractFirstYearFromCollection(row.get("applicationDateRaw"));
            Integer fundingYear = fundingOpeningYear != null ? fundingOpeningYear : applicationYear;
            if (fundingYear == null) {
                fundingYear = extractYearFromTitle(fundingTitle);
            }

            Long pureId = null;
            if (fundingDoc != null && fundingDoc.get("pureId") != null) {
                try {
                    pureId = ((Number) fundingDoc.get("pureId")).longValue();
                } catch (Exception e) {
                    // Ignore
                }
            }

            int[] roleCounts = countLeaderPartner(row.get("acceptedAppUuids"), leaderByAppUuid);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fundingUuid", fundingUuid);
            item.put("fundingTitle", fundingTitle != null ? fundingTitle : "Sin título");
            item.put("fundingType", fundingType != null ? fundingType : "Desconocido");
            item.put("fundingTypeUri", fundingTypeUri);
            item.put("callTypeCode", callTypeCode);
            item.put("programCode", callTypeCode); // backwards compatibility
            item.put("program", pInfo.primaryProgram);
            item.put("programPath", pInfo.programPath);
            item.put("programs", pInfo.allPrograms);
            item.put("fundingYear", fundingYear);
            item.put("pureId", pureId);
            item.put("sent", sent);
            item.put("accepted", accepted);
            item.put("acceptedLeader", roleCounts[0]);
            item.put("acceptedPartner", roleCounts[1]);
            item.put("rejected", rejected);
            item.put("pending", pending);
            item.put("acceptedFemale", toInt(row.get("acceptedFemale")));
            item.put("acceptedMale", toInt(row.get("acceptedMale")));
            item.put("acceptedFemaleBeneficiary", toInt(row.get("acceptedFemaleBeneficiary")));
            item.put("rejectedFemale", toInt(row.get("rejectedFemale")));
            item.put("rejectedMale", toInt(row.get("rejectedMale")));
            item.put("resigned", toInt(row.get("resigned")));
            result.add(item);
        }

        result.sort((a, b) -> Integer.compare((int) b.get("sent"), (int) a.get("sent")));
        return result;
    }

    @GetMapping("/stats/by-funding-opportunity/{fundingUuid}/breakdown")
    public Map<String, Object> breakdownByFundingOpportunity(
            @PathVariable String fundingUuid,
            @RequestParam(value = "excludeResigned", required = false, defaultValue = "false") boolean excludeResigned) {
        String applicationCollection = resolveApplicationCollection();
        if (applicationCollection == null || fundingUuid == null || fundingUuid.isBlank()) {
            return Map.of(
                "fundingUuid", fundingUuid != null ? fundingUuid : "",
                "total", Map.of("sent", 0, "accepted", 0, "rejected", 0, "pending", 0, "resigned", 0),
                "byAmbit", List.of(),
                "byDepartment", List.of()
            );
        }

        List<String> resignedAppUuids = getResignedApplicationUuids();

        Document matchStage = new Document("fundingOpportunity.uuid", fundingUuid);
        if (excludeResigned && !resignedAppUuids.isEmpty()) {
            matchStage.append("uuid", new Document("$nin", resignedAppUuids));
        }

        Document piUuidsExpr = new Document("$let", new Document("vars",
            new Document("applicantsArr", new Document("$cond", Arrays.asList(new Document("$isArray", "$applicants"), "$applicants", List.of()))))
            .append("in", new Document("$let", new Document("vars",
                new Document("pis", new Document("$filter", new Document("input", "$$applicantsArr")
                    .append("as", "app")
                    .append("cond", new Document("$eq", Arrays.asList("$$app.role.uri", "/dk/atira/pure/application/roles/application/pi"))))))
                .append("in", new Document("$cond", Arrays.asList(
                    new Document("$gt", Arrays.asList(new Document("$size", "$$pis"), 0)),
                    "$$pis.person.uuid",
                    "$$applicantsArr.person.uuid"))))));

        List<Document> pipeline = List.of(
            new Document("$match", matchStage),
            new Document("$project", new Document("managingOrgUuid", new Document("$ifNull", Arrays.asList("$managingOrganization.uuid", "_UNKNOWN_")))
                .append("uuid", 1)
                .append("replyText", buildReplyTextExpression())
                .append("piUuids", piUuidsExpr)
                .append("isResigned", new Document("$cond", Arrays.asList(new Document("$in", Arrays.asList("$uuid", resignedAppUuids)), 1, 0)))),
            new Document("$lookup", new Document("from", "Persons").append("localField", "piUuids").append("foreignField", "uuid").append("as", "piPersons")),
            new Document("$project", new Document("managingOrgUuid", 1)
                .append("uuid", 1)
                .append("rejected", buildRejectedExpression())
                .append("accepted", buildAcceptedExpression())
                .append("isResigned", 1)
                .append("genderVal", new Document("$arrayElemAt", Arrays.asList("$piPersons.gender", 0)))
                .append("sexVal", new Document("$arrayElemAt", Arrays.asList("$piPersons.sex", 0)))),
            new Document("$project", new Document("managingOrgUuid", 1)
                .append("uuid", 1)
                .append("rejected", 1)
                .append("accepted", 1)
                .append("isResigned", 1)
                .append("rawGender", new Document("$toLower", new Document("$ifNull", Arrays.asList("$genderVal.uri", new Document("$ifNull", Arrays.asList("$genderVal.term.en_GB", new Document("$ifNull", Arrays.asList("$genderVal.term.ca_ES", new Document("$ifNull", Arrays.asList("$genderVal.term.es_ES", new Document("$ifNull", Arrays.asList("$genderVal", new Document("$ifNull", Arrays.asList("$sexVal.term.en_GB", new Document("$ifNull", Arrays.asList("$sexVal.term.ca_ES", new Document("$ifNull", Arrays.asList("$sexVal", ""))))))))))))))))))),
            new Document("$project", new Document("managingOrgUuid", 1)
                .append("uuid", 1)
                .append("rejected", 1)
                .append("accepted", 1)
                .append("isResigned", 1)
                .append("isFemale", new Document("$cond", Arrays.asList(new Document("$or", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawGender").append("regex", "female|mujer|femeni|dona")), new Document("$eq", Arrays.asList("$rawGender", "f")))), 1, 0)))
                .append("isMale", new Document("$cond", Arrays.asList(new Document("$and", Arrays.asList(new Document("$not", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawGender").append("regex", "female")))), new Document("$or", Arrays.asList(new Document("$regexMatch", new Document("input", "$rawGender").append("regex", "male|hombre|masculi|home")), new Document("$eq", Arrays.asList("$rawGender", "m")))))), 1, 0)))),
            new Document("$group", new Document("_id", "$managingOrgUuid")
                .append("sent", new Document("$sum", 1))
                .append("accepted", new Document("$sum", "$accepted"))
                .append("rejected", new Document("$sum", "$rejected"))
                .append("resigned", new Document("$sum", "$isResigned"))
                .append("sentFemale", new Document("$sum", "$isFemale"))
                .append("sentMale", new Document("$sum", "$isMale"))
                .append("acceptedFemale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isFemale", 1)), "$accepted", 0))))
                .append("acceptedMale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isMale", 1)), "$accepted", 0))))
                .append("rejectedFemale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isFemale", 1)), "$rejected", 0))))
                .append("rejectedMale", new Document("$sum", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$isMale", 1)), "$rejected", 0))))
                .append("acceptedAppUuids", new Document("$push", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$accepted", 1)), "$uuid", "$$REMOVE")))))
        );

        List<Document> rows = mongoTemplate
            .getCollection(applicationCollection)
            .aggregate(pipeline)
            .into(new ArrayList<>());

        Set<String> orgUuids = new HashSet<>();
        for (Document row : rows) {
            String orgUuid = row.getString("_id");
            if (orgUuid != null && !orgUuid.isBlank() && !"_UNKNOWN_".equals(orgUuid)) {
                orgUuids.add(orgUuid);
            }
        }

        Map<String, String> orgNames = resolveOrganizationNames(orgUuids);
        Map<String, String> orgAmbits = resolveOrganizationAmbits(orgUuids);
        Map<String, Boolean> leaderByAppUuid = getAwardLeaderByApplicationUuid();

        List<Map<String, Object>> byDepartment = new ArrayList<>();
        Map<String, int[]> ambitTotals = new LinkedHashMap<>();

        int totalSent = 0;
        int totalAccepted = 0;
        int totalAcceptedLeader = 0;
        int totalAcceptedPartner = 0;
        int totalRejected = 0;
        int totalPending = 0;
        int totalResigned = 0;

        int totalSentFemale = 0;
        int totalSentMale = 0;
        int totalAcceptedFemale = 0;
        int totalAcceptedMale = 0;
        int totalRejectedFemale = 0;
        int totalRejectedMale = 0;

        for (Document row : rows) {
            String orgUuid = row.getString("_id");
            int sent = toInt(row.get("sent"));
            int accepted = toInt(row.get("accepted"));
            int[] roleCounts = countLeaderPartner(row.get("acceptedAppUuids"), leaderByAppUuid);
            int acceptedLeader = roleCounts[0];
            int acceptedPartner = roleCounts[1];
            int rejected = toInt(row.get("rejected"));
            int pending = Math.max(sent - accepted - rejected, 0);
            int resigned = toInt(row.get("resigned"));

            int sentFemale = toInt(row.get("sentFemale"));
            int sentMale = toInt(row.get("sentMale"));
            int acceptedFemale = toInt(row.get("acceptedFemale"));
            int acceptedMale = toInt(row.get("acceptedMale"));
            int rejectedFemale = toInt(row.get("rejectedFemale"));
            int rejectedMale = toInt(row.get("rejectedMale"));

            totalSent += sent;
            totalAccepted += accepted;
            totalAcceptedLeader += acceptedLeader;
            totalAcceptedPartner += acceptedPartner;
            totalRejected += rejected;
            totalPending += pending;
            totalResigned += resigned;

            totalSentFemale += sentFemale;
            totalSentMale += sentMale;
            totalAcceptedFemale += acceptedFemale;
            totalAcceptedMale += acceptedMale;
            totalRejectedFemale += rejectedFemale;
            totalRejectedMale += rejectedMale;

            boolean isUnknown = orgUuid == null || orgUuid.isBlank() || "_UNKNOWN_".equals(orgUuid);
            String deptName = isUnknown ? "Sense departament assignat" : orgNames.getOrDefault(orgUuid, orgUuid);
            String ambit = isUnknown ? "Sense àmbit assignat" : orgAmbits.getOrDefault(orgUuid, "Sense àmbit assignat");

            Map<String, Object> deptItem = new LinkedHashMap<>();
            deptItem.put("orgUuid", orgUuid);
            deptItem.put("departmentName", deptName);
            deptItem.put("ambit", ambit);
            deptItem.put("sent", sent);
            deptItem.put("accepted", accepted);
            deptItem.put("acceptedLeader", acceptedLeader);
            deptItem.put("acceptedPartner", acceptedPartner);
            deptItem.put("rejected", rejected);
            deptItem.put("pending", pending);
            deptItem.put("resigned", resigned);
            deptItem.put("sentFemale", sentFemale);
            deptItem.put("sentMale", sentMale);
            deptItem.put("acceptedFemale", acceptedFemale);
            deptItem.put("acceptedMale", acceptedMale);
            deptItem.put("rejectedFemale", rejectedFemale);
            deptItem.put("rejectedMale", rejectedMale);
            byDepartment.add(deptItem);

            int[] acc = ambitTotals.computeIfAbsent(ambit, k -> new int[13]);
            acc[0] += sent;
            acc[1] += accepted;
            acc[2] += rejected;
            acc[3] += pending;
            acc[4] += resigned;
            acc[5] += acceptedLeader;
            acc[6] += acceptedPartner;
            acc[7] += sentFemale;
            acc[8] += sentMale;
            acc[9] += acceptedFemale;
            acc[10] += acceptedMale;
            acc[11] += rejectedFemale;
            acc[12] += rejectedMale;
        }

        byDepartment.sort((a, b) -> Integer.compare((int) b.get("sent"), (int) a.get("sent")));

        List<Map<String, Object>> byAmbit = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : ambitTotals.entrySet()) {
            int[] v = entry.getValue();
            Map<String, Object> ambitItem = new LinkedHashMap<>();
            ambitItem.put("ambit", entry.getKey());
            ambitItem.put("sent", v[0]);
            ambitItem.put("accepted", v[1]);
            ambitItem.put("rejected", v[2]);
            ambitItem.put("pending", v[3]);
            ambitItem.put("resigned", v[4]);
            ambitItem.put("acceptedLeader", v[5]);
            ambitItem.put("acceptedPartner", v[6]);
            ambitItem.put("sentFemale", v[7]);
            ambitItem.put("sentMale", v[8]);
            ambitItem.put("acceptedFemale", v[9]);
            ambitItem.put("acceptedMale", v[10]);
            ambitItem.put("rejectedFemale", v[11]);
            ambitItem.put("rejectedMale", v[12]);
            byAmbit.add(ambitItem);
        }
        byAmbit.sort((a, b) -> Integer.compare((int) b.get("sent"), (int) a.get("sent")));

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("sent", totalSent);
        total.put("accepted", totalAccepted);
        total.put("acceptedLeader", totalAcceptedLeader);
        total.put("acceptedPartner", totalAcceptedPartner);
        total.put("rejected", totalRejected);
        total.put("pending", totalPending);
        total.put("resigned", totalResigned);
        total.put("sentFemale", totalSentFemale);
        total.put("sentMale", totalSentMale);
        total.put("acceptedFemale", totalAcceptedFemale);
        total.put("acceptedMale", totalAcceptedMale);
        total.put("rejectedFemale", totalRejectedFemale);
        total.put("rejectedMale", totalRejectedMale);

        Map<String, Object> byGender = new LinkedHashMap<>();
        int pendingFemale = Math.max(totalSentFemale - totalAcceptedFemale - totalRejectedFemale, 0);
        int pendingMale = Math.max(totalSentMale - totalAcceptedMale - totalRejectedMale, 0);

        int sentUnknown = Math.max(totalSent - totalSentFemale - totalSentMale, 0);
        int acceptedUnknown = Math.max(totalAccepted - totalAcceptedFemale - totalAcceptedMale, 0);
        int rejectedUnknown = Math.max(totalRejected - totalRejectedFemale - totalRejectedMale, 0);
        int pendingUnknown = Math.max(totalPending - pendingFemale - pendingMale, 0);

        byGender.put("female", Map.of(
            "sent", totalSentFemale,
            "accepted", totalAcceptedFemale,
            "rejected", totalRejectedFemale,
            "pending", pendingFemale
        ));
        byGender.put("male", Map.of(
            "sent", totalSentMale,
            "accepted", totalAcceptedMale,
            "rejected", totalRejectedMale,
            "pending", pendingMale
        ));
        byGender.put("unknown", Map.of(
            "sent", sentUnknown,
            "accepted", acceptedUnknown,
            "rejected", rejectedUnknown,
            "pending", pendingUnknown
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fundingUuid", fundingUuid);
        response.put("total", total);
        response.put("byGender", byGender);
        response.put("byAmbit", byAmbit);
        response.put("byDepartment", byDepartment);
        return response;
    }

    @GetMapping("/stats/by-funding-opportunity/{fundingUuid}/department/{orgUuid}/ips")
    public List<Map<String, Object>> departmentIpsByFundingOpportunity(
            @PathVariable String fundingUuid,
            @PathVariable String orgUuid,
            @RequestParam(value = "excludeResigned", required = false, defaultValue = "false") boolean excludeResigned) {
        String applicationCollection = resolveApplicationCollection();
        if (applicationCollection == null || fundingUuid == null || fundingUuid.isBlank() || orgUuid == null || orgUuid.isBlank()) {
            return List.of();
        }

        List<String> resignedAppUuids = getResignedApplicationUuids();

        Document matchStage = new Document("fundingOpportunity.uuid", fundingUuid);
        if ("_UNKNOWN_".equals(orgUuid)) {
            matchStage.append("$or", Arrays.asList(
                new Document("managingOrganization", null),
                new Document("managingOrganization.uuid", null),
                new Document("managingOrganization.uuid", "")
            ));
        } else {
            matchStage.append("managingOrganization.uuid", orgUuid);
        }
        if (excludeResigned && !resignedAppUuids.isEmpty()) {
            matchStage.append("uuid", new Document("$nin", resignedAppUuids));
        }

        Document piUuidsExpr = new Document("$let", new Document("vars",
            new Document("applicantsArr", new Document("$cond", Arrays.asList(new Document("$isArray", "$applicants"), "$applicants", List.of()))))
            .append("in", new Document("$let", new Document("vars",
                new Document("pis", new Document("$filter", new Document("input", "$$applicantsArr")
                    .append("as", "app")
                    .append("cond", new Document("$eq", Arrays.asList("$$app.role.uri", "/dk/atira/pure/application/roles/application/pi"))))))
                .append("in", new Document("$cond", Arrays.asList(
                    new Document("$gt", Arrays.asList(new Document("$size", "$$pis"), 0)),
                    "$$pis.person.uuid",
                    "$$applicantsArr.person.uuid"))))));

        List<Document> pipeline = List.of(
            new Document("$match", matchStage),
            new Document("$project", new Document("piUuids", piUuidsExpr)
                .append("uuid", 1)
                .append("replyText", buildReplyTextExpression())
                .append("isResigned", new Document("$cond", Arrays.asList(new Document("$in", Arrays.asList("$uuid", resignedAppUuids)), 1, 0)))),
            new Document("$project", new Document("piUuids", new Document("$cond", Arrays.asList(
                    new Document("$gt", Arrays.asList(new Document("$size", new Document("$ifNull", Arrays.asList("$piUuids", List.of()))), 0)),
                    "$piUuids",
                    List.of("_UNKNOWN_"))))
                .append("uuid", 1)
                .append("rejected", buildRejectedExpression())
                .append("accepted", buildAcceptedExpression())
                .append("isResigned", 1)),
            new Document("$unwind", "$piUuids"),
            new Document("$group", new Document("_id", new Document("$ifNull", Arrays.asList("$piUuids", "_UNKNOWN_")))
                .append("sent", new Document("$sum", 1))
                .append("accepted", new Document("$sum", "$accepted"))
                .append("rejected", new Document("$sum", "$rejected"))
                .append("resigned", new Document("$sum", "$isResigned"))
                .append("acceptedAppUuids", new Document("$push", new Document("$cond", Arrays.asList(new Document("$eq", Arrays.asList("$accepted", 1)), "$uuid", "$$REMOVE")))))
        );

        List<Document> rows = mongoTemplate
            .getCollection(applicationCollection)
            .aggregate(pipeline)
            .into(new ArrayList<>());

        Set<String> personUuids = new HashSet<>();
        for (Document row : rows) {
            String pUuid = row.getString("_id");
            if (pUuid != null && !pUuid.isBlank() && !"_UNKNOWN_".equals(pUuid)) {
                personUuids.add(pUuid);
            }
        }

        Map<String, Map<String, String>> personMeta = resolvePersonMetadata(personUuids);
        Map<String, Boolean> leaderByAppUuid = getAwardLeaderByApplicationUuid();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document row : rows) {
            String pUuid = row.getString("_id");
            int sent = toInt(row.get("sent"));
            int accepted = toInt(row.get("accepted"));
            int[] roleCounts = countLeaderPartner(row.get("acceptedAppUuids"), leaderByAppUuid);
            int rejected = toInt(row.get("rejected"));
            int pending = Math.max(sent - accepted - rejected, 0);
            int resigned = toInt(row.get("resigned"));

            boolean isUnknown = pUuid == null || pUuid.isBlank() || "_UNKNOWN_".equals(pUuid);
            Map<String, String> meta = isUnknown ? Map.of() : personMeta.getOrDefault(pUuid, Map.of());
            String fullName = isUnknown ? "Sense IP assignat" : meta.getOrDefault("fullName", pUuid);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("personUuid", pUuid);
            item.put("fullName", fullName);
            item.put("gender", meta.getOrDefault("gender", "U"));
            item.put("orcid", meta.get("orcid"));
            item.put("sent", sent);
            item.put("accepted", accepted);
            item.put("acceptedLeader", roleCounts[0]);
            item.put("acceptedPartner", roleCounts[1]);
            item.put("rejected", rejected);
            item.put("pending", pending);
            item.put("resigned", resigned);
            result.add(item);
        }

        result.sort((a, b) -> {
            int cmp = Integer.compare((int) b.get("sent"), (int) a.get("sent"));
            if (cmp != 0) return cmp;
            cmp = Integer.compare((int) b.get("accepted"), (int) a.get("accepted"));
            if (cmp != 0) return cmp;
            return String.valueOf(a.get("fullName")).compareToIgnoreCase(String.valueOf(b.get("fullName")));
        });
        return result;
    }

    private Map<String, Map<String, String>> resolvePersonMetadata(Set<String> personUuids) {
        Map<String, Map<String, String>> metaMap = new HashMap<>();
        if (personUuids == null || personUuids.isEmpty() || !mongoTemplate.collectionExists("Persons")) {
            return metaMap;
        }
        try (var cursor = mongoTemplate.getCollection("Persons")
                .find(new Document("uuid", new Document("$in", new ArrayList<>(personUuids))))
                .projection(new Document("uuid", 1).append("name", 1).append("orcid", 1).append("gender", 1).append("sex", 1))
                .cursor()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String uuid = doc.getString("uuid");
                if (uuid == null || uuid.isBlank()) continue;
                String fullName = uuid;
                Object nameObj = doc.get("name");
                if (nameObj instanceof Document nameDoc) {
                    String first = nameDoc.getString("firstName");
                    String last = nameDoc.getString("lastName");
                    if (last != null && !last.isBlank() && first != null && !first.isBlank()) {
                        fullName = last.trim() + ", " + first.trim();
                    } else if (last != null && !last.isBlank()) {
                        fullName = last.trim();
                    } else if (first != null && !first.isBlank()) {
                        fullName = first.trim();
                    }
                }
                Map<String, String> info = new HashMap<>();
                info.put("fullName", fullName);
                String orcid = doc.getString("orcid");
                if (orcid != null && !orcid.isBlank()) {
                    info.put("orcid", orcid.trim());
                }

                String genderRaw = extractGenderString(doc);
                String genderCode = "U";
                if (genderRaw != null) {
                    String g = genderRaw.toLowerCase(Locale.ROOT);
                    if (g.contains("female") || g.contains("mujer") || g.contains("femeni") || g.contains("dona") || "f".equals(g)) {
                        genderCode = "F";
                    } else if (g.contains("male") || g.contains("hombre") || g.contains("masculi") || g.contains("home") || "m".equals(g)) {
                        genderCode = "M";
                    }
                }
                info.put("gender", genderCode);
                metaMap.put(uuid, info);
            }
        } catch (Exception e) {
            // Ignore query failure
        }
        return metaMap;
    }

    private String extractGenderString(Document personDoc) {
        Object g = personDoc.get("gender");
        if (g == null) g = personDoc.get("sex");
        if (g == null) return null;
        if (g instanceof Document d) {
            String uri = d.getString("uri");
            if (uri != null) return uri;
            Object term = d.get("term");
            if (term instanceof Document td) {
                String en = td.getString("en_GB");
                if (en != null) return en;
                String ca = td.getString("ca_ES");
                if (ca != null) return ca;
                String es = td.getString("es_ES");
                if (es != null) return es;
            }
            return extractLocalizedValue(d);
        }
        return g.toString();
    }

    private Map<String, Boolean> getAwardLeaderByApplicationUuid() {
        Map<String, Boolean> leaderByAppUuid = new HashMap<>();
        if (!mongoTemplate.collectionExists("Awards")) {
            return leaderByAppUuid;
        }
        try (var cursor = mongoTemplate.getCollection("Awards")
                .find(new Document("applications.uuid", new Document("$exists", true)))
                .projection(new Document("applications.uuid", 1).append("collaborators", 1))
                .cursor()) {
            while (cursor.hasNext()) {
                Document award = cursor.next();
                boolean isLeader = isUabLeader(award);
                Object appsObj = award.get("applications");
                if (appsObj instanceof List<?> apps) {
                    for (Object appObj : apps) {
                        if (appObj instanceof Document appDoc) {
                            String appUuid = appDoc.getString("uuid");
                            if (appUuid != null && !appUuid.isBlank()) {
                                leaderByAppUuid.merge(appUuid, isLeader, (a, b) -> a || b);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore query failure
        }
        return leaderByAppUuid;
    }

    private boolean isUabLeader(Document award) {
        Object collabsObj = award.get("collaborators");
        if (!(collabsObj instanceof List<?> collabs) || collabs.isEmpty()) {
            return true;
        }
        for (Object obj : collabs) {
            if (obj instanceof Document c) {
                if (Boolean.TRUE.equals(c.getBoolean("leadCollaborator"))
                        && "InternalCollaboratorAssociation".equals(c.getString("typeDiscriminator"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int[] countLeaderPartner(Object acceptedAppUuidsObj, Map<String, Boolean> leaderByAppUuid) {
        int leader = 0;
        int partner = 0;
        if (acceptedAppUuidsObj instanceof List<?> list && leaderByAppUuid != null) {
            for (Object obj : list) {
                if (obj instanceof String appUuid) {
                    Boolean isLeader = leaderByAppUuid.get(appUuid);
                    if (Boolean.TRUE.equals(isLeader)) {
                        leader++;
                    } else if (Boolean.FALSE.equals(isLeader)) {
                        partner++;
                    }
                }
            }
        }
        return new int[]{leader, partner};
    }

    private List<String> getResignedApplicationUuids() {
        List<String> resignedAppUuids = new ArrayList<>();
        List<Document> resignedAwards = mongoTemplate.getCollection("Awards")
            .find(new Document("$or", Arrays.asList(
                new Document("keywordGroups.keywordContainers.structuredKeyword.uri", "/uab/awards/estat_ajut/resigned"),
                new Document("status.typeDiscriminator", "DeclinedAwardStatus")
            )))
            .projection(new Document("applications.uuid", 1))
            .into(new ArrayList<>());
        for (Document doc : resignedAwards) {
            List<?> apps = (List<?>) doc.get("applications");
            if (apps != null) {
                for (Object appObj : apps) {
                    if (appObj instanceof Document appDoc) {
                        String uuid = appDoc.getString("uuid");
                        if (uuid != null && !uuid.isBlank()) {
                            resignedAppUuids.add(uuid);
                        }
                    }
                }
            }
        }
        return resignedAppUuids;
    }

    private Document buildReplyTextExpression() {
        return new Document("$toLower", new Document("$ifNull", Arrays.asList("$funderReply.key", new Document("$ifNull", Arrays.asList("$funderReply.description.en_GB", new Document("$ifNull", Arrays.asList("$funderReply.description.es_ES", new Document("$ifNull", Arrays.asList("$funderReply.description.ca_ES", new Document("$ifNull", Arrays.asList("$funderReply.en_GB", new Document("$ifNull", Arrays.asList("$funderReply.es_ES", new Document("$ifNull", Arrays.asList("$funderReply.ca_ES", new Document("$ifNull", Arrays.asList("$funderReply", "")))))))))))))))));
    }

    private Document buildRejectedExpression() {
        return new Document("$cond", Arrays.asList(
            new Document("$regexMatch", new Document("input", "$replyText").append("regex", "reject|deneg|declin|desestim|rebutj|unfavorable|refused|not funded|no funded")),
            1,
            0
        ));
    }

    private Document buildAcceptedExpression() {
        return new Document("$cond", Arrays.asList(
            new Document("$and", Arrays.asList(
                new Document("$not", Arrays.asList(new Document("$regexMatch", new Document("input", "$replyText").append("regex", "reject|deneg|declin|desestim|rebutj|unfavorable|refused|not funded|no funded")))),
                new Document("$regexMatch", new Document("input", "$replyText").append("regex", "accept|approved|award|granted|conced|aprobad|admis|seleccion|favorable"))
            )),
            1,
            0
        ));
    }

    private Map<String, String> resolveOrganizationNames(Set<String> orgUuids) {
        Map<String, String> names = new HashMap<>();
        if (orgUuids == null || orgUuids.isEmpty() || !mongoTemplate.collectionExists("Organizations")) {
            return names;
        }
        try (var cursor = mongoTemplate.getCollection("Organizations")
                .find(new Document("uuid", new Document("$in", new ArrayList<>(orgUuids))))
                .projection(new Document("uuid", 1).append("name", 1))
                .cursor()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String uuid = doc.getString("uuid");
                String name = extractLocalizedValue(doc.get("name"));
                if (uuid != null && name != null) {
                    names.put(uuid, name);
                }
            }
        } catch (Exception e) {
            // Ignore query failure
        }
        return names;
    }

    private Map<String, String> resolveOrganizationAmbits(Set<String> orgUuids) {
        Map<String, String> ambits = new HashMap<>();
        if (orgUuids == null || orgUuids.isEmpty() || !mongoTemplate.collectionExists("v_orga_ambit")) {
            return ambits;
        }
        try (var cursor = mongoTemplate.getCollection("v_orga_ambit")
                .find(new Document("uuid", new Document("$in", new ArrayList<>(orgUuids))))
                .projection(new Document("uuid", 1).append("ambit", 1))
                .cursor()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String uuid = doc.getString("uuid");
                String ambit = doc.getString("ambit");
                if (uuid != null && ambit != null && !ambit.isBlank()) {
                    ambits.put(uuid, ambit.trim());
                }
            }
        } catch (Exception e) {
            // Ignore query failure
        }
        return ambits;
    }

    private static final String PROGRAM_KEYWORD_GROUP = "/uab/fundingopportunities/programes";

    /** UUIDs of the funding opportunities whose type resolves to one of the given call types (PJE/PEI). */
    private Set<String> findFundingUuidsByCallType(String fundingCollection, Set<String> callTypes) {
        Set<String> uuids = new HashSet<>();
        if (fundingCollection == null) {
            return uuids;
        }
        try (var cursor = mongoTemplate
            .getCollection(fundingCollection)
            .find()
            .projection(new Document("uuid", 1).append("type", 1))
            .cursor()) {
            while (cursor.hasNext()) {
                try {
                    Document doc = cursor.next();
                    String uuid = doc.getString("uuid");
                    if (uuid == null || uuid.isBlank()) {
                        continue;
                    }
                    Object rawType = doc.get("type");
                    String code = resolveProgramCode(extractFundingTypeUri(rawType), extractFundingType(rawType));
                    if (code != null && callTypes.contains(code)) {
                        uuids.add(uuid);
                    }
                } catch (Exception e) {
                    // Skip corrupted BSON document
                }
            }
        } catch (Exception e) {
            // Ignore query failure
        }
        return uuids;
    }

    private Set<String> parseProgramFilter(String program) {
        if (program == null || program.isBlank()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String token : program.split(",")) {
            String clean = token.trim().toUpperCase(Locale.ROOT);
            if (!clean.isEmpty()) {
                set.add(clean);
            }
        }
        return set;
    }

    private String extractFundingTypeUri(Object rawType) {
        if (rawType instanceof Map<?, ?> typeMap) {
            Object uri = typeMap.get("uri");
            if (uri instanceof String str && !str.isBlank()) {
                return str.trim();
            }
        }
        return null;
    }

    private String resolveProgramCode(String typeUri, String typeLabel) {
        if (typeUri != null) {
            String lowerUri = typeUri.toLowerCase(Locale.ROOT);
            if (lowerUri.endsWith("/pje") || lowerUri.contains("/pje/")) {
                return "PJE";
            }
            if (lowerUri.endsWith("/pei") || lowerUri.contains("/pei/")) {
                return "PEI";
            }
        }
        if (typeLabel != null) {
            String lowerLabel = typeLabel.trim().toLowerCase(Locale.ROOT);
            if (lowerLabel.startsWith("pje")) {
                return "PJE";
            }
            if (lowerLabel.startsWith("pei")) {
                return "PEI";
            }
        }
        return null;
    }

    private String resolveApplicationCollection() {
        List<String> candidates = List.of(
            "Applications",
            "applications",
            "Application",
            "application"
        );

        for (String name : candidates) {
            if (!mongoTemplate.collectionExists(name)) {
                continue;
            }
            if (mongoTemplate.getCollection(name).countDocuments() > 0) {
                return name;
            }
        }

        for (String name : candidates) {
            if (mongoTemplate.collectionExists(name)) {
                return name;
            }
        }

        return null;
    }

    private String resolveFundingOpportunityCollection() {
        List<String> candidates = List.of(
            "FundingOpportunities",
            "Fundingopportunities",
            "fundingOpportunities",
            "fundingopportunities",
            "FundingOpportunity",
            "fundingopportunity"
        );

        for (String name : candidates) {
            if (!mongoTemplate.collectionExists(name)) {
                continue;
            }
            if (mongoTemplate.getCollection(name).countDocuments() > 0) {
                return name;
            }
        }

        for (String name : candidates) {
            if (mongoTemplate.collectionExists(name)) {
                return name;
            }
        }

        return null;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private String extractFundingType(Object rawType) {
        if (!(rawType instanceof Map<?, ?> typeMap)) {
            return extractLocalizedValue(rawType);
        }
        return extractLocalizedValue(typeMap.get("term"));
    }

    private Integer extractFundingYear(Document fundingDoc) {
        String[] directYearKeys = {"year", "anio", "anyo", "callYear", "convocatoriaYear", "submissionYear", "awardYear"};
        for (String key : directYearKeys) {
            Integer year = toInteger(fundingDoc.get(key));
            if (year != null) {
                return year;
            }
        }

        String[] nestedYearPaths = {
            "openingDate.year",
            "awardDate.year",
            "publicationDate.year",
            "periodStartDate.year",
            "periodStartDateReal.year",
            "periodEndDate.year",
            "periodEndDateReal.year",
            "startDate.year",
            "openDate.year",
            "callDate.year"
        };
        for (String path : nestedYearPaths) {
            Integer year = toInteger(getValueByPath(fundingDoc, path));
            if (year != null) {
                return year;
            }
        }

        String[] structuredDatePaths = {
            "openingDate",
            "awardDate",
            "publicationDate",
            "periodStartDate",
            "periodStartDateReal",
            "periodEndDate",
            "periodEndDateReal",
            "startDate",
            "openDate",
            "callDate"
        };
        for (String path : structuredDatePaths) {
            Integer year = extractYearFromDateValue(getValueByPath(fundingDoc, path));
            if (year != null) {
                return year;
            }
        }

        return null;
    }

    private Integer extractYearFromDateValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            long raw = number.longValue();
            if (raw > 10000000000L) {
                return java.time.Instant.ofEpochMilli(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .getYear();
            }
            if (raw > 1000000000L) {
                return java.time.Instant.ofEpochSecond(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .getYear();
            }
            return toInteger(value);
        }

        if (value instanceof Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).getYear();
        }

        if (value instanceof Map<?, ?> map) {
            Integer fromYear = toInteger(map.get("year"));
            if (fromYear != null) {
                return fromYear;
            }

            String[] nestedKeys = {"$date", "value", "date", "es_ES", "ca_ES", "en_GB", "term"};
            for (String key : nestedKeys) {
                Integer nested = extractYearFromDateValue(map.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                Integer year = extractYearFromDateValue(item);
                if (year != null) {
                    return year;
                }
            }
            return null;
        }

        if (value instanceof String str) {
            java.util.regex.Matcher matcher = Pattern.compile("(19|20)\\d{2}").matcher(str);
            if (matcher.find()) {
                return toInteger(matcher.group());
            }
        }

        return toInteger(value);
    }

    private Integer extractFirstYearFromCollection(Object values) {
        if (values instanceof List<?> list) {
            for (Object value : list) {
                Integer year = extractYearFromDateValue(value);
                if (year != null) {
                    return year;
                }
            }
            return null;
        }

        return extractYearFromDateValue(values);
    }

    private Integer extractYearFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        java.util.regex.Matcher matcher = Pattern.compile("(19|20)\\d{2}").matcher(title);
        Integer candidate = null;
        while (matcher.find()) {
            Integer year = toInteger(matcher.group());
            if (year != null) {
                candidate = year;
                break;
            }
        }

        return candidate;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            int candidate = number.intValue();
            if (candidate >= 1900 && candidate <= 3000) {
                return candidate;
            }
            return null;
        }

        if (value instanceof String str) {
            String clean = str.trim();
            if (clean.matches("^(19|20)\\d{2}$")) {
                return Integer.parseInt(clean);
            }
        }

        return null;
    }

    private Object getValueByPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        String[] segments = path.split("\\.");
        Object current = root;

        for (String segment : segments) {
            if (current instanceof Document doc) {
                current = doc.get(segment);
                continue;
            }

            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                continue;
            }

            return null;
        }

        return current;
    }

    private String extractLocalizedValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String str) {
            String clean = str.trim();
            return clean.isBlank() ? null : clean;
        }

        if (value instanceof Map<?, ?> map) {
            Object text = map.get("text");
            String fromTextArray = extractFromTextArray(text);
            if (fromTextArray != null) {
                return fromTextArray;
            }

            String[] keys = {"ca_ES", "es_ES", "en_GB", "value", "term", "text"};
            for (String key : keys) {
                String nested = extractLocalizedValue(map.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nested = extractLocalizedValue(item);
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String extractFromTextArray(Object text) {
        if (!(text instanceof List<?> list)) {
            return null;
        }

        String[] locales = {"ca_ES", "es_ES", "en_GB"};
        for (String locale : locales) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    continue;
                }
                Object localeValue = itemMap.get("locale");
                if (!(localeValue instanceof String localeStr) || !locale.equals(localeStr)) {
                    continue;
                }
                String resolved = extractLocalizedValue(itemMap.get("value"));
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }
            String resolved = extractLocalizedValue(itemMap.get("value"));
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    private void collectFieldPaths(Object value, String prefix, Set<String> paths) {
        if (value == null) {
            return;
        }

        if (value instanceof Document doc) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String path = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
                paths.add(path);
                collectFieldPaths(entry.getValue(), path, paths);
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                String path = prefix.isBlank() ? key : prefix + "." + key;
                paths.add(path);
                collectFieldPaths(entry.getValue(), path, paths);
            }
            return;
        }

        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return;
            }
            String listPath = prefix + "[]";
            paths.add(listPath);
            collectFieldPaths(list.get(0), listPath, paths);
        }
    }
}
