package com.example.demo.award.engine;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;

@Component
public class AwardClassificationEngine {

    private final MongoTemplate mongoTemplate;
    private final String egretaApiKey;

    public AwardClassificationEngine(MongoTemplate mongoTemplate,
                                    @Value("${app.api.key:}") String egretaApiKey) {
        this.mongoTemplate = mongoTemplate;
        this.egretaApiKey = egretaApiKey;
    }

    private Map<String, Document> suggestedNatureCache = null;

    public synchronized Map<String, Document> getSuggestedNatureMap() {
        if (suggestedNatureCache != null) {
            return suggestedNatureCache;
        }

        suggestedNatureCache = new HashMap<>();
        try {
            List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("$or", Arrays.asList(
                    new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
                    new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
                ))),
                new Document("$match", new Document("categoria", new Document("$regex", "^Ajudes competitives"))),
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
                new Document("$match", new Document("natureTypes.0", new Document("$exists", true))),
                new Document("$project", new Document()
                    .append("fOppType", new Document("$ifNull", Arrays.asList(
                        "$fOpp.type.term.ca_ES",
                        new Document("$let", new Document()
                            .append("vars", new Document("caText", new Document("$filter", new Document()
                                .append("input", new Document("$ifNull", Arrays.asList("$fOpp.type.term.text", Arrays.asList())))
                                .append("as", "t")
                                .append("cond", new Document("$eq", Arrays.asList("$$t.locale", "ca_ES"))))))
                            .append("in", new Document("$cond", Arrays.asList(
                                new Document("$gt", Arrays.asList(new Document("$size", "$$caText"), 0)),
                                new Document("$arrayElemAt", Arrays.asList("$$caText.value", 0)),
                                null
                            ))))
                    )))
                    .append("nature", new Document("$arrayElemAt", Arrays.asList("$natureTypes", 0)))),
                new Document("$match", new Document("fOppType", new Document("$ne", null)).append("nature", new Document("$ne", null))),
                new Document("$group", new Document()
                    .append("_id", new Document("fOppType", "$fOppType").append("natureUri", "$nature.uri"))
                    .append("count", new Document("$sum", 1))
                    .append("natureDoc", new Document("$first", "$nature"))),
                new Document("$sort", new Document("count", -1)),
                new Document("$group", new Document()
                    .append("_id", "$_id.fOppType")
                    .append("suggestedNature", new Document("$first", "$natureDoc")))
            );

            List<Document> results = new ArrayList<>();
            mongoTemplate.getCollection("Awards")
                    .aggregate(pipeline)
                    .into(results);

            for (Document doc : results) {
                String fOppType = doc.getString("_id");
                Document suggested = (Document) doc.get("suggestedNature");
                if (fOppType != null && suggested != null) {
                    suggestedNatureCache.put(fOppType, suggested);
                }
            }
        } catch (Exception e) {
            // Silently catch
        }
        return suggestedNatureCache;
    }

    public String extractCaEsFromType(Object typeObj) {
        if (typeObj == null) return null;
        if (typeObj instanceof String s) return s;
        if (!(typeObj instanceof Map<?, ?> typeMap)) return null;

        Object termObj = typeMap.get("term");
        if (termObj instanceof Map<?, ?> termMap) {
            Object ca = termMap.get("ca_ES");
            if (ca != null && !String.valueOf(ca).isBlank()) return String.valueOf(ca);
            Object textObj = termMap.get("text");
            if (textObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> td && "ca_ES".equals(td.get("locale"))) {
                        Object val = td.get("value");
                        if (val != null) return String.valueOf(val);
                    }
                }
            }
        } else if (termObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> td && "ca_ES".equals(td.get("locale"))) {
                    Object val = td.get("value");
                    if (val != null) return String.valueOf(val);
                }
            }
        } else if (termObj instanceof String s) {
            return s;
        }
        return null;
    }

    private String extractNatureUri(Object natureObj) {
        if (natureObj == null) return null;
        if (natureObj instanceof Map<?, ?> map) {
            Object uriObj = map.get("uri");
            if (uriObj != null && !String.valueOf(uriObj).isBlank()) {
                return String.valueOf(uriObj);
            }
        }
        return null;
    }

    private Document buildMongoNatureDoc(Object natureObj) {
        if (natureObj == null) return null;
        String uri = extractNatureUri(natureObj);
        String termCa = getCaEsTerm(natureObj);
        if (termCa == null || "Sense títol".equals(termCa) || termCa.isBlank()) {
            termCa = extractCaEsFromType(natureObj);
        }
        if (termCa == null || termCa.isBlank() || "Sense títol".equals(termCa)) {
            termCa = uri != null ? uri : "Sense títol";
        }
        Document doc = new Document();
        if (uri != null) {
            doc.append("uri", uri);
        }
        Document termDoc = new Document("ca_ES", termCa);
        List<Document> textList = new ArrayList<>();
        textList.add(new Document("locale", "ca_ES").append("value", termCa));
        termDoc.append("text", textList);
        doc.append("term", termDoc);
        return doc;
    }

    public Map<String, Object> updateAwardNature(
            String uuid,
            String env,
            Document natureDoc) {
        
        boolean egretaSuccess = syncAwardNatureToEgreta(uuid, natureDoc, env);
        
        Map<String, Object> resp = new HashMap<>();
        if (egretaSuccess) {
            Document mongoDoc = buildMongoNatureDoc(natureDoc);
            mongoTemplate.getCollection("Awards")
                    .updateOne(new Document("uuid", uuid),
                            new Document("$set", new Document("natureTypes", Arrays.asList(mongoDoc))));

            suggestedNatureCache = null; // Clear cache on update
            allNatures = null;
            resp.put("success", true);
        } else {
            resp.put("success", false);
            resp.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "). Comproveu que el registre existeixi en aquest entorn.");
        }
        return resp;
    }

    public Map<String, Object> batchUpdateNature(
            String fundingType,
            String env,
            Document natureDoc) {

        List<Document> basePipeline = new ArrayList<>();
        
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();
        andConditions.add(new Document("$or", Arrays.asList(
            new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
            new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        )));
        andConditions.add(new Document("categoria", new Document("$regex", "^Ajudes competitives")));
        andConditions.add(new Document("natureTypes.0", new Document("$exists", false)));
        matchQuery.put("$and", andConditions);
        basePipeline.add(new Document("$match", matchQuery));

        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "Applications")
            .append("localField", "applications.uuid")
            .append("foreignField", "uuid")
            .append("as", "appDocs")));
        basePipeline.add(new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));

        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "FundingOpportunities")
            .append("localField", "appDocs.fundingOpportunity.uuid")
            .append("foreignField", "uuid")
            .append("as", "fOpp")));
        basePipeline.add(new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));

        basePipeline.add(new Document("$project", new Document("uuid", 1).append("fOppType", "$fOpp.type")));

        List<Document> data = new ArrayList<>();
        try {
            mongoTemplate.getCollection("Awards").aggregate(basePipeline).into(data);
        } catch (Exception e) {
            // Silently catch
        }

        List<String> uuidsToUpdate = new ArrayList<>();
        for (Document d : data) {
            String fType = extractCaEsFromType(d.get("fOppType"));
            if (fType == null || fType.isBlank()) {
                fType = "Sense convocatòria / Conveni direct";
            }
            if (fType.equals(fundingType)) {
                String uuid = d.getString("uuid");
                if (uuid != null) {
                    uuidsToUpdate.add(uuid);
                }
            }
        }

        int successCount = 0;
        int failCount = 0;
        List<String> failedUuids = new ArrayList<>();
        Document mongoDoc = buildMongoNatureDoc(natureDoc);

        for (String uuid : uuidsToUpdate) {
            boolean egretaSuccess = syncAwardNatureToEgreta(uuid, natureDoc, env);
            if (egretaSuccess) {
                mongoTemplate.getCollection("Awards")
                        .updateOne(new Document("uuid", uuid),
                                new Document("$set", new Document("natureTypes", Arrays.asList(mongoDoc))));
                successCount++;
            } else {
                failCount++;
                failedUuids.add(uuid);
            }
        }

        if (successCount > 0) {
            suggestedNatureCache = null;
            allNatures = null;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("successCount", successCount);
        resp.put("failCount", failCount);
        resp.put("failedUuids", failedUuids);
        return resp;
    }

    public Map<String, Object> batchUpdateNatureByUuids(
            String env,
            Map<String, Object> payload) {

        @SuppressWarnings("unchecked")
        List<String> uuids = (List<String>) payload.get("uuids");
        Object natureObj = payload.get("natureDoc");
        Document natureDoc = null;
        if (natureObj instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> castedMap = (Map<String, Object>) map;
            natureDoc = new Document(castedMap);
        }

        Map<String, Object> resp = new HashMap<>();
        if (uuids == null || uuids.isEmpty() || natureDoc == null) {
            resp.put("success", false);
            resp.put("message", "Cal proporcionar la llista d'UUIDs i el document de naturalesa.");
            return resp;
        }

        int successCount = 0;
        int failCount = 0;
        List<String> failedUuids = new ArrayList<>();
        Document mongoDoc = buildMongoNatureDoc(natureDoc);

        for (String uuid : uuids) {
            boolean egretaSuccess = syncAwardNatureToEgreta(uuid, natureDoc, env);
            if (egretaSuccess) {
                mongoTemplate.getCollection("Awards")
                        .updateOne(new Document("uuid", uuid),
                                new Document("$set", new Document("natureTypes", Arrays.asList(mongoDoc))));
                successCount++;
            } else {
                failCount++;
                failedUuids.add(uuid);
            }
        }

        if (successCount > 0) {
            suggestedNatureCache = null;
            allNatures = null;
        }

        resp.put("success", true);
        resp.put("total", uuids.size());
        resp.put("successCount", successCount);
        resp.put("failCount", failCount);
        resp.put("failedUuids", failedUuids);
        return resp;
    }

    public Map<String, Object> batchApplySuggestedByUuids(
            String env,
            List<String> uuids) {

        Map<String, Object> resp = new HashMap<>();
        if (uuids == null || uuids.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "Cal proporcionar la llista d'UUIDs.");
            return resp;
        }

        Map<String, Document> suggestionsMap = getSuggestedNatureMap();
        int successCount = 0;
        int failCount = 0;
        int noSuggestionCount = 0;
        List<String> failedUuids = new ArrayList<>();

        for (String uuid : uuids) {
            List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("uuid", uuid)),
                new Document("$lookup", new Document()
                    .append("from", "Applications")
                    .append("localField", "applications.uuid")
                    .append("foreignField", "uuid")
                    .append("as", "appDocs")),
                new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)),
                new Document("$lookup", new Document()
                    .append("from", "FundingOpportunities")
                    .append("localField", "appDocs.fundingOpportunity.uuid")
                    .append("foreignField", "uuid")
                    .append("as", "fOpp")),
                new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)),
                new Document("$project", new Document("fOppType", "$fOpp.type"))
            );

            List<Document> res = new ArrayList<>();
            try {
                mongoTemplate.getCollection("Awards").aggregate(pipeline).into(res);
            } catch (Exception e) {
                // Ignore
            }

            Document suggested = null;
            if (!res.isEmpty()) {
                Object fOppType = res.get(0).get("fOppType");
                String fTypeStr = extractCaEsFromType(fOppType);
                if (fTypeStr != null) {
                    suggested = suggestionsMap.get(fTypeStr);
                }
                if (suggested == null && fOppType instanceof Document fOppTypeDoc) {
                    String callTypeUri = fOppTypeDoc.getString("uri");
                    suggested = findNatureByUriSimilarity(callTypeUri);
                }
            }

            if (suggested == null) {
                noSuggestionCount++;
                continue;
            }

            boolean egretaSuccess = syncAwardNatureToEgreta(uuid, suggested, env);
            if (egretaSuccess) {
                Document mongoDoc = buildMongoNatureDoc(suggested);
                mongoTemplate.getCollection("Awards")
                        .updateOne(new Document("uuid", uuid),
                                new Document("$set", new Document("natureTypes", Arrays.asList(mongoDoc))));
                successCount++;
            } else {
                failCount++;
                failedUuids.add(uuid);
            }
        }

        if (successCount > 0) {
            suggestedNatureCache = null;
            allNatures = null;
        }

        resp.put("success", true);
        resp.put("total", uuids.size());
        resp.put("successCount", successCount);
        resp.put("failCount", failCount);
        resp.put("noSuggestionCount", noSuggestionCount);
        resp.put("failedUuids", failedUuids);
        return resp;
    }

    private boolean syncAwardNatureToEgreta(String uuid, Document natureDoc, String env) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", egretaApiKey);
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(env) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "awards/" + uuid;

            String uri = extractNatureUri(natureDoc);
            if (uri == null || uri.isBlank()) {
                System.err.println("No URI found in natureDoc for award UUID: " + uuid);
                return false;
            }

            // 1. GET current award document
            org.springframework.http.ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta award UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new java.util.LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify natureTypes - Pure WS API expects ClassificationRef with only "uri"
            Map<String, Object> classificationRef = new LinkedHashMap<>();
            classificationRef.put("uri", uri);

            List<Object> natureList = new ArrayList<>();
            natureList.add(classificationRef);
            data.put("natureTypes", natureList);
            
            // 3. PUT updated award document back
            org.springframework.http.HttpEntity<Map<String, Object>> putEntity = new org.springframework.http.HttpEntity<>(data, headers);
            org.springframework.http.ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            System.err.println("Error syncing award nature to Egreta (HTTP " + e.getStatusCode() + "): " + e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            System.err.println("Error syncing award nature to Egreta: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> getProposedRelations() {
        Map<String, Document> suggestionsMap = getSuggestedNatureMap();
        
        List<Document> basePipeline = new ArrayList<>();
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();
        andConditions.add(new Document("$or", Arrays.asList(
            new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
            new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        )));
        andConditions.add(new Document("categoria", new Document("$regex", "^Ajudes competitives")));
        andConditions.add(new Document("natureTypes.0", new Document("$exists", false)));
        matchQuery.put("$and", andConditions);
        basePipeline.add(new Document("$match", matchQuery));

        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "Applications")
            .append("localField", "applications.uuid")
            .append("foreignField", "uuid")
            .append("as", "appDocs")));
        basePipeline.add(new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));

        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "FundingOpportunities")
            .append("localField", "appDocs.fundingOpportunity.uuid")
            .append("foreignField", "uuid")
            .append("as", "fOpp")));
        basePipeline.add(new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));

        basePipeline.add(new Document("$project", new Document("fOppType", "$fOpp.type")));

        List<Document> data = new ArrayList<>();
        try {
            mongoTemplate.getCollection("Awards").aggregate(basePipeline).into(data);
        } catch (Exception e) {
            // Silently catch
        }

        Map<String, Integer> pendingCounts = new HashMap<>();
        Map<String, String> fundingTypeParentMap = new HashMap<>();
        Map<String, String> fundingTypeUriMap = new HashMap<>();
        for (Document d : data) {
            String fundingType = extractCaEsFromType(d.get("fOppType"));
            if (fundingType == null || fundingType.isBlank()) {
                continue;
            }
            pendingCounts.put(fundingType, pendingCounts.getOrDefault(fundingType, 0) + 1);
            if (d.get("fOppType") instanceof Document typeDoc) {
                String uri = typeDoc.getString("uri");
                if (uri != null && !uri.isBlank()) {
                    fundingTypeUriMap.put(fundingType, uri);
                    String[] parts = uri.split("/");
                    if (parts.length >= 2) {
                        fundingTypeParentMap.put(fundingType, parts[parts.length - 2].toUpperCase());
                    }
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> allFundingTypes = new HashSet<>();
        allFundingTypes.addAll(suggestionsMap.keySet());
        
        for (Document d : data) {
            String fundingType = extractCaEsFromType(d.get("fOppType"));
            if (fundingType != null && !fundingType.isBlank()) {
                allFundingTypes.add(fundingType);
            }
        }

        for (String fType : allFundingTypes) {
            Document suggested = suggestionsMap.get(fType);
            if (suggested == null) {
                for (Document d : data) {
                    String dFType = extractCaEsFromType(d.get("fOppType"));
                    if (fType.equals(dFType) && d.get("fOppType") instanceof Document fOppTypeDoc) {
                        String callTypeUri = fOppTypeDoc.getString("uri");
                        suggested = findNatureByUriSimilarity(callTypeUri);
                        if (suggested != null) break;
                    }
                }
            }

            if (suggested != null) {
                Map<String, Object> row = new HashMap<>();
                row.put("fundingType", fType);
                row.put("parentType", fundingTypeParentMap.get(fType));
                row.put("uri", fundingTypeUriMap.get(fType));
                row.put("nature", suggested);
                row.put("pendingCount", pendingCounts.getOrDefault(fType, 0));
                result.add(row);
            }
        }

        result.sort((a, b) -> {
            int pA = (int) a.get("pendingCount");
            int pB = (int) b.get("pendingCount");
            if (pA != pB) {
                return Integer.compare(pB, pA);
            }
            return ((String) a.get("fundingType")).compareTo((String) b.get("fundingType"));
        });

        return result;
    }

    public Map<String, Object> batchUpdateAllProposals(String env) {
        Map<String, Document> suggestionsMap = getSuggestedNatureMap();
        
        List<Document> basePipeline = new ArrayList<>();
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();
        andConditions.add(new Document("$or", Arrays.asList(
            new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
            new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        )));
        andConditions.add(new Document("categoria", new Document("$regex", "^Ajudes competitives")));
        andConditions.add(new Document("natureTypes.0", new Document("$exists", false)));
        matchQuery.put("$and", andConditions);
        basePipeline.add(new Document("$match", matchQuery));

        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "Applications")
            .append("localField", "applications.uuid")
            .append("foreignField", "uuid")
            .append("as", "appDocs")));
        basePipeline.add(new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));

        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "FundingOpportunities")
            .append("localField", "appDocs.fundingOpportunity.uuid")
            .append("foreignField", "uuid")
            .append("as", "fOpp")));
        basePipeline.add(new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));

        basePipeline.add(new Document("$project", new Document("uuid", 1).append("fOppType", "$fOpp.type")));

        List<Document> data = new ArrayList<>();
        try {
            mongoTemplate.getCollection("Awards").aggregate(basePipeline).into(data);
        } catch (Exception e) {
            // Silently catch
        }

        int successCount = 0;
        int failCount = 0;
        List<String> failedUuids = new ArrayList<>();

        for (Document d : data) {
            String fundingType = extractCaEsFromType(d.get("fOppType"));
            if (fundingType == null || fundingType.isBlank()) {
                continue;
            }
            
            Document natureDoc = suggestionsMap.get(fundingType);
            if (natureDoc == null && d.get("fOppType") instanceof Document fOppTypeDoc) {
                String callTypeUri = fOppTypeDoc.getString("uri");
                natureDoc = findNatureByUriSimilarity(callTypeUri);
            }
            
            if (natureDoc != null) {
                String uuid = d.getString("uuid");
                if (uuid != null) {
                    boolean egretaSuccess = syncAwardNatureToEgreta(uuid, natureDoc, env);
                    if (egretaSuccess) {
                        Document mongoDoc = buildMongoNatureDoc(natureDoc);
                        mongoTemplate.getCollection("Awards")
                                .updateOne(new Document("uuid", uuid),
                                        new Document("$set", new Document("natureTypes", Arrays.asList(mongoDoc))));
                        successCount++;
                    } else {
                        failCount++;
                        failedUuids.add(uuid);
                    }
                }
            }
        }

        if (successCount > 0) {
            suggestedNatureCache = null;
            allNatures = null;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("successCount", successCount);
        resp.put("failCount", failCount);
        resp.put("failedUuids", failedUuids);
        return resp;
    }

    public List<Document> getAvailableNatures() {
        try {
            Document scheme = mongoTemplate.getCollection("Classificationschemes")
                    .find(new Document("baseUri", "/dk/atira/pure/upm/nature"))
                    .first();
            if (scheme != null) {
                List<Document> contained = (List<Document>) scheme.get("containedClassifications");
                if (contained != null) {
                    return contained;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return getAllNatures(); // Fallback
    }

    public List<Map<String, Object>> getNatureHierarchy() {
        List<Map<String, Object>> hierarchy = new ArrayList<>();
        try {
            Document scheme = mongoTemplate.getCollection("Classificationschemes")
                    .find(new Document("baseUri", "/dk/atira/pure/upm/nature"))
                    .first();
            
            if (scheme != null) {
                List<Document> contained = (List<Document>) scheme.get("containedClassifications");
                if (contained != null) {
                    // Create a map of URI -> Document for quick lookup
                    Map<String, Document> uriToDoc = new HashMap<>();
                    for (Document c : contained) {
                        String uri = c.getString("uri");
                        if (uri != null) {
                            uriToDoc.put(uri, c);
                        }
                    }

                    // Build children mapping: parentUri -> list of child documents
                    Map<String, List<Document>> parentToChildren = new HashMap<>();
                    Set<String> allChildUris = new HashSet<>();

                    for (Document c : contained) {
                        String uri = c.getString("uri");
                        List<Document> relations = (List<Document>) c.get("classificationRelations");
                        if (relations != null) {
                            for (Document r : relations) {
                                Document relType = (Document) r.get("relationType");
                                String relTypeUri = relType != null ? relType.getString("uri") : "";
                                if (relTypeUri != null && relTypeUri.contains("/hierarchies/child")) {
                                    Document relatedTo = (Document) r.get("relatedTo");
                                    String childUri = relatedTo != null ? relatedTo.getString("uri") : null;
                                    if (childUri != null && uriToDoc.containsKey(childUri)) {
                                        allChildUris.add(childUri);
                                        parentToChildren.computeIfAbsent(uri, k -> new ArrayList<>()).add(uriToDoc.get(childUri));
                                    }
                                }
                            }
                        }
                    }

                    // Build parent nodes (nodes that have children)
                    for (Map.Entry<String, List<Document>> entry : parentToChildren.entrySet()) {
                        String parentUri = entry.getKey();
                        Document parentDoc = uriToDoc.get(parentUri);
                        if (parentDoc != null) {
                            Map<String, Object> parentNode = new HashMap<>();
                            parentNode.put("uri", parentUri);
                            parentNode.put("term", getCaEsTerm(parentDoc));
                            
                            List<Map<String, Object>> childNodes = new ArrayList<>();
                            for (Document childDoc : entry.getValue()) {
                                Map<String, Object> childNode = new HashMap<>();
                                childNode.put("uri", childDoc.getString("uri"));
                                childNode.put("term", getCaEsTerm(childDoc));
                                childNodes.add(childNode);
                            }
                            
                            // Sort children alphabetically
                            childNodes.sort((a, b) -> ((String) a.get("term")).compareToIgnoreCase((String) b.get("term")));
                            
                            parentNode.put("children", childNodes);
                            hierarchy.add(parentNode);
                        }
                    }

                    // Sort parent nodes alphabetically
                    hierarchy.sort((a, b) -> ((String) a.get("term")).compareToIgnoreCase((String) b.get("term")));

                    // Build flat nodes (nodes that have no children and are not children of any parent)
                    List<Map<String, Object>> flatNodes = new ArrayList<>();
                    for (Document c : contained) {
                        String uri = c.getString("uri");
                        if (!parentToChildren.containsKey(uri) && !allChildUris.contains(uri)) {
                            // Only include enabled classifications
                            Boolean disabled = c.getBoolean("disabled");
                            if (disabled == null || !disabled) {
                                Map<String, Object> flatNode = new HashMap<>();
                                flatNode.put("uri", uri);
                                flatNode.put("term", getCaEsTerm(c));
                                flatNodes.add(flatNode);
                            }
                        }
                    }

                    // Sort flat nodes alphabetically
                    flatNodes.sort((a, b) -> ((String) a.get("term")).compareToIgnoreCase((String) b.get("term")));

                    if (!flatNodes.isEmpty()) {
                        Map<String, Object> flatGroup = new HashMap<>();
                        flatGroup.put("uri", "flat_group");
                        flatGroup.put("term", "Altres Natures");
                        flatGroup.put("children", flatNodes);
                        hierarchy.add(flatGroup);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return hierarchy;
    }

    private String getCaEsTerm(Object docObj) {
        if (docObj == null) return "Sense títol";
        if (docObj instanceof String s && !s.isBlank()) return s;
        if (!(docObj instanceof Map<?, ?> map)) return "Sense títol";

        Object termObj = map.get("term");
        if (termObj instanceof Map<?, ?> termMap) {
            Object caObj = termMap.get("ca_ES");
            if (caObj != null && !String.valueOf(caObj).isBlank()) {
                return String.valueOf(caObj);
            }
            Object textObj = termMap.get("text");
            if (textObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> td && "ca_ES".equals(td.get("locale"))) {
                        Object val = td.get("value");
                        if (val != null) return String.valueOf(val);
                    }
                }
                if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> firstTd) {
                    Object val = firstTd.get("value");
                    if (val != null) return String.valueOf(val);
                }
            }
        } else if (termObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> td && "ca_ES".equals(td.get("locale"))) {
                    Object val = td.get("value");
                    if (val != null) return String.valueOf(val);
                }
            }
            if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> firstTd) {
                Object val = firstTd.get("value");
                if (val != null) return String.valueOf(val);
            }
        } else if (termObj instanceof String s && !s.isBlank()) {
            return s;
        }

        Object uriObj = map.get("uri");
        if (uriObj != null && !String.valueOf(uriObj).isBlank()) {
            return String.valueOf(uriObj);
        }
        return "Sense títol";
    }

    private List<Document> allNatures = null;

    private synchronized List<Document> getAllNatures() {
        if (allNatures != null) {
            return allNatures;
        }
        allNatures = new ArrayList<>();
        try {
            Document scheme = mongoTemplate.getCollection("Classificationschemes")
                    .find(new Document("baseUri", "/dk/atira/pure/upm/nature"))
                    .first();
            if (scheme != null) {
                List<Document> contained = (List<Document>) scheme.get("containedClassifications");
                if (contained != null && !contained.isEmpty()) {
                    allNatures.addAll(contained);
                }
            }
        } catch (Exception e) {
            // Silently catch
        }
        if (allNatures.isEmpty()) {
            try {
                allNatures = mongoTemplate.getCollection("Awards")
                        .distinct("natureTypes", new Document(), Document.class)
                        .into(new ArrayList<>());
            } catch (Exception e) {
                // Silently catch
            }
        }
        return allNatures;
    }

    public Document findNatureByUriSimilarity(String callTypeUri) {
        if (callTypeUri == null || callTypeUri.isBlank()) return null;
        
        String suffix = "";
        int idx = callTypeUri.indexOf("externalfundingopportunity/");
        if (idx >= 0) {
            suffix = callTypeUri.substring(idx + "externalfundingopportunity/".length());
        } else {
            idx = callTypeUri.indexOf("fundingopportunitytypes/");
            if (idx >= 0) {
                suffix = callTypeUri.substring(idx + "fundingopportunitytypes/".length());
            } else {
                suffix = callTypeUri.substring(callTypeUri.lastIndexOf("/") + 1);
            }
        }
        
        if (suffix.isEmpty()) return null;
        suffix = suffix.replaceAll("^/+", "").replaceAll("/+$", "").toLowerCase();
        
        String normSuffixSlash = suffix;
        String normSuffixUnder = suffix.replace("/", "_").replace("-", "_");
        
        List<Document> natures = getAllNatures();
        // 1. Exact match
        for (Document nat : natures) {
            String nUri = nat.getString("uri");
            if (nUri == null) continue;
            
            String nSuffix = "";
            int nIdx = nUri.indexOf("nature/");
            if (nIdx >= 0) {
                nSuffix = nUri.substring(nIdx + "nature/".length());
            } else {
                nSuffix = nUri.substring(nUri.lastIndexOf("/") + 1);
            }
            nSuffix = nSuffix.replaceAll("^/+", "").replaceAll("/+$", "").toLowerCase();
            String nSuffixNorm = nSuffix.replace("/", "_").replace("-", "_");
            
            if (nSuffixNorm.equals(normSuffixUnder) || nSuffixNorm.equals(normSuffixSlash)) {
                return nat;
            }
        }

        // 2. Suffix / Substring match (e.g. "pro_zoo" with "zoo" or "pro/zoo")
        for (Document nat : natures) {
            String nUri = nat.getString("uri");
            if (nUri == null) continue;
            
            String nSuffix = "";
            int nIdx = nUri.indexOf("nature/");
            if (nIdx >= 0) {
                nSuffix = nUri.substring(nIdx + "nature/".length());
            } else {
                nSuffix = nUri.substring(nUri.lastIndexOf("/") + 1);
            }
            nSuffix = nSuffix.replaceAll("^/+", "").replaceAll("/+$", "").toLowerCase();
            String nSuffixNorm = nSuffix.replace("/", "_").replace("-", "_");
            
            if (nSuffixNorm.endsWith("_" + normSuffixUnder) || normSuffixUnder.endsWith("_" + nSuffixNorm) ||
                nSuffixNorm.contains(normSuffixUnder) || normSuffixUnder.contains(nSuffixNorm)) {
                return nat;
            }
        }
        return null;
    }



    public List<Map<String, Object>> getNatureFundingRelation() {
        List<Document> basePipeline = new ArrayList<>();

        // Match valid competitive awards
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();
        andConditions.add(new Document("$or", Arrays.asList(
            new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
            new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        )));
        andConditions.add(new Document("categoria", new Document("$regex", "^Ajudes competitives")));
        matchQuery.put("$and", andConditions);
        basePipeline.add(new Document("$match", matchQuery));

        // Lookup applications
        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "Applications")
            .append("localField", "applications.uuid")
            .append("foreignField", "uuid")
            .append("as", "appDocs")));
        basePipeline.add(new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));

        // Lookup FundingOpportunities
        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "FundingOpportunities")
            .append("localField", "appDocs.fundingOpportunity.uuid")
            .append("foreignField", "uuid")
            .append("as", "fOpp")));
        basePipeline.add(new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));

        // Project
        Document projection = new Document()
            .append("natureTypes", 1)
            .append("fOppType", "$fOpp.type");
        basePipeline.add(new Document("$project", projection));

        List<Document> data = new ArrayList<>();
        try {
            mongoTemplate.getCollection("Awards")
                    .aggregate(basePipeline)
                    .into(data);
        } catch (Exception e) {
            // Silently catch
        }

        Map<String, Map<String, int[]>> counts = new HashMap<>();
        Map<String, Document> suggestionsMap = getSuggestedNatureMap();

        for (Document d : data) {
            String fundingType = extractCaEsFromType(d.get("fOppType"));
            if (fundingType == null || fundingType.isBlank()) {
                continue;
            }

            List<?> natureList = (List<?>) d.get("natureTypes");
            boolean isConfirmed = (natureList != null && !natureList.isEmpty());

            if (isConfirmed) {
                for (Object natObj : natureList) {
                    String natureType = extractCaEsFromType(natObj);
                    if (natureType == null || natureType.isBlank()) {
                        continue;
                    }
                    counts.computeIfAbsent(fundingType, k -> new HashMap<>())
                          .computeIfAbsent(natureType, k -> new int[2])[0]++;
                }
            } else {
                Document suggested = null;
                suggested = suggestionsMap.get(fundingType);
                if (suggested == null && d.get("fOppType") instanceof Document fOppTypeDoc) {
                    String callTypeUri = fOppTypeDoc.getString("uri");
                    suggested = findNatureByUriSimilarity(callTypeUri);
                }

                if (suggested != null) {
                    String natureType = extractCaEsFromType(suggested);
                    if (natureType != null && !natureType.isBlank()) {
                        counts.computeIfAbsent(fundingType, k -> new HashMap<>())
                              .computeIfAbsent(natureType, k -> new int[2])[1]++;
                    }
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, int[]>> entry : counts.entrySet()) {
            String fundingType = entry.getKey();
            for (Map.Entry<String, int[]> subEntry : entry.getValue().entrySet()) {
                String natureType = subEntry.getKey();
                int[] val = subEntry.getValue();

                Map<String, Object> row = new HashMap<>();
                row.put("fundingType", fundingType);
                row.put("natureType", natureType);
                row.put("confirmed", val[0]);
                row.put("suggested", val[1]);
                result.add(row);
            }
        }
        return result;
    }

    private boolean isStrictBeneficiaryRole(String uri, String ca, String es) {
        List<String> uris = Arrays.asList(
            "/dk/atira/pure/award/roles/award/ben",
            "/dk/atira/pure/award/roles/award/bec"
        );
        List<String> terms = Arrays.asList(
            "beneficiari/a", "becari/a",
            "beneficiario/a", "becario/a"
        );
        if (uri != null && uris.contains(uri.toLowerCase())) return true;
        if (ca != null && terms.contains(ca.toLowerCase())) return true;
        if (es != null && terms.contains(es.toLowerCase())) return true;
        return false;
    }

    private boolean isCandidateRole(String uri, String ca, String es) {
        if (uri != null && uri.toLowerCase().contains("/can")) return true;
        if (ca != null && ca.toLowerCase().contains("candidat")) return true;
        if (es != null && es.toLowerCase().contains("candidat")) return true;
        return false;
    }

    private boolean isBeneficiaryRole(String uri, String ca, String es) {
        List<String> uris = Arrays.asList(
            "/dk/atira/pure/award/roles/award/ben",
            "/dk/atira/pure/award/roles/award/bec",
            "/dk/atira/pure/award/roles/award/can"
        );
        List<String> terms = Arrays.asList(
            "beneficiari/a", "becari/a", "candidat/a",
            "beneficiario/a", "becario/a", "candidato/a"
        );
        if (uri != null && uris.contains(uri)) return true;
        if (ca != null && terms.contains(ca.toLowerCase())) return true;
        if (es != null && terms.contains(es.toLowerCase())) return true;
        return false;
    }

    private boolean isInvestigatorRole(String uri, String ca, String es) {
        List<String> uris = Arrays.asList(
            "/dk/atira/pure/award/roles/award/pi",
            "/dk/atira/pure/award/roles/award/copi",
            "/dk/atira/pure/award/roles/award/pi2",
            "/dk/atira/pure/award/roles/award/inv"
        );
        List<String> terms = Arrays.asList(
            "investigador/a principal", "co-investigador/a principal", "investigador/a",
            "co-investigador/a principal (extern uab)", "investigador/a principal (externo uab)"
        );
        if (uri != null && uris.contains(uri)) return true;
        if (ca != null && terms.contains(ca.toLowerCase())) return true;
        if (es != null && terms.contains(es.toLowerCase())) return true;
        return false;
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

        if (value instanceof Document doc) {
            return extractTextValueFromMap(doc);
        }

        if (value instanceof Map<?, ?> map) {
            return extractTextValueFromMap(map);
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nested = extractTextValue(item);
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String extractTextValueFromMap(Map<?, ?> map) {
        String[] keys = {"ca_ES", "es_ES", "en_GB", "value", "text", "term", "label", "name"};
        for (String key : keys) {
            String nested = extractTextValue(map.get(key));
            if (nested != null && !nested.isBlank()) {
                return nested;
            }
        }

        Object locale = map.get("locale");
        Object value = map.get("value");
        if (locale instanceof String && value instanceof String str && !str.isBlank()) {
            return str;
        }

        return null;
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
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase();
    }

    /*
    ===============================
    COST CODE → CONVOCATÒRIES
    ===============================
    */



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

}
