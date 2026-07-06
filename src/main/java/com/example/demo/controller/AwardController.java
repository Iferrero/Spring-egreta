package com.example.demo.controller;

import com.example.demo.model.Award;
import com.example.demo.repository.AwardRepository;
import com.example.demo.service.AwardService;
import com.example.demo.service.ResearchOutputJournalLinkService;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeMap;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.text.Normalizer;

@RestController
@RequestMapping("/api/awards")
@CrossOrigin(origins = "*")
public class AwardController {

    private final AwardRepository repository;
    private final AwardService service;
    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputService;

    @Autowired
    public AwardController(AwardRepository repository,
                           AwardService service,
                           MongoTemplate mongoTemplate,
                           ResearchOutputJournalLinkService researchOutputService) {

        this.repository = repository;
        this.service = service;
        this.mongoTemplate = mongoTemplate;
        this.researchOutputService = researchOutputService;
    }

    /*
    ===============================
    LISTADO BÁSICO
    ===============================
    */

    @GetMapping
    public PagedModel<Award> getAwards(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return new PagedModel<>(
                repository.findValidated(PageRequest.of(page, size)));
    }

    @GetMapping("/detailed-list")
    public Map<String, Object> getDetailedList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String hasConvocatoria,
            @RequestParam(required = false) String hasNature) {

        List<Document> basePipeline = new ArrayList<>();

        // 1. Initial match query for valid awards
        Document matchQuery = new Document();
        List<Document> andConditions = new ArrayList<>();

        andConditions.add(new Document("$or", Arrays.asList(
            new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
            new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        )));

        // Filter only competitive awards
        andConditions.add(new Document("categoria", new Document("$regex", "^Ajudes competitives")));

        if (search != null && !search.isBlank()) {
            String cleanSearch = search.trim();
            andConditions.add(new Document("$or", Arrays.asList(
                new Document("title.ca_ES", new Document("$regex", cleanSearch).append("$options", "i")),
                new Document("title.es_ES", new Document("$regex", cleanSearch).append("$options", "i")),
                new Document("title.en_GB", new Document("$regex", cleanSearch).append("$options", "i")),
                new Document("uuid", new Document("$regex", cleanSearch).append("$options", "i")),
                new Document("pureId", new Document("$regex", cleanSearch).append("$options", "i"))
            )));
        }

        if (hasNature != null && !hasNature.isBlank() && !"all".equalsIgnoreCase(hasNature)) {
            if ("yes".equalsIgnoreCase(hasNature)) {
                andConditions.add(new Document("natureTypes", new Document("$exists", true).append("$not", new Document("$size", 0))));
            } else if ("no".equalsIgnoreCase(hasNature)) {
                andConditions.add(new Document("$or", Arrays.asList(
                    new Document("natureTypes", new Document("$exists", false)),
                    new Document("natureTypes", null),
                    new Document("natureTypes", new Document("$size", 0))
                )));
            }
        }

        matchQuery.put("$and", andConditions);
        basePipeline.add(new Document("$match", matchQuery));

        // 2. Lookup applications
        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "Applications")
            .append("localField", "applications.uuid")
            .append("foreignField", "uuid")
            .append("as", "appDocs")));
        
        basePipeline.add(new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));

        // 3. Lookup FundingOpportunities
        basePipeline.add(new Document("$lookup", new Document()
            .append("from", "FundingOpportunities")
            .append("localField", "appDocs.fundingOpportunity.uuid")
            .append("foreignField", "uuid")
            .append("as", "fOpp")));
        
        basePipeline.add(new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));

        // 4. Filter by convocatoria presence if requested
        if (hasConvocatoria != null && !hasConvocatoria.isBlank() && !"all".equalsIgnoreCase(hasConvocatoria)) {
            if ("yes".equalsIgnoreCase(hasConvocatoria)) {
                basePipeline.add(new Document("$match", new Document("fOpp.uuid", new Document("$ne", null))));
            } else if ("no".equalsIgnoreCase(hasConvocatoria)) {
                basePipeline.add(new Document("$match", new Document("fOpp.uuid", null)));
            }
        }

        // Count total matching records using aggregation count
        List<Document> countPipeline = new ArrayList<>(basePipeline);
        countPipeline.add(new Document("$count", "total"));
        
        long totalElements = 0;
        List<Document> countResults = new ArrayList<>();
        mongoTemplate.getCollection("Awards")
                .aggregate(countPipeline)
                .into(countResults);
        if (!countResults.isEmpty()) {
            totalElements = ((Number) countResults.get(0).get("total")).longValue();
        }

        // Fetch paginated results
        List<Document> dataPipeline = new ArrayList<>(basePipeline);
        dataPipeline.add(new Document("$sort", new Document("awardDate", -1).append("uuid", 1)));
        dataPipeline.add(new Document("$skip", page * size));
        dataPipeline.add(new Document("$limit", size));

        Document projection = new Document()
            .append("uuid", 1)
            .append("pureId", 1)
            .append("title", 1)
            .append("awardDate", 1)
            .append("type", 1)
            .append("natureTypes", 1)
            .append("fOppUuid", "$fOpp.uuid")
            .append("fOppTitle", "$fOpp.title")
            .append("fOppType", "$fOpp.type");
        dataPipeline.add(new Document("$project", projection));

        List<Document> data = new ArrayList<>();
        mongoTemplate.getCollection("Awards")
                .aggregate(dataPipeline)
                .into(data);

        // Apply suggestions for awards without nature
        Map<String, Document> suggestionsMap = getSuggestedNatureMap();
        for (Document award : data) {
            Object natureObj = award.get("natureTypes");
            boolean hasAnyNature = false;
            if (natureObj instanceof List<?> list && !list.isEmpty()) {
                hasAnyNature = true;
            }
            if (!hasAnyNature) {
                Document suggested = null;
                String fOppType = extractCaEsFromType(award.get("fOppType"));
                if (fOppType != null) {
                    suggested = suggestionsMap.get(fOppType);
                }
                
                if (suggested == null && award.get("fOppType") instanceof Document fOppTypeDoc) {
                    String callTypeUri = fOppTypeDoc.getString("uri");
                    suggested = findNatureByUriSimilarity(callTypeUri);
                }
                
                if (suggested != null) {
                    award.put("suggestedNature", suggested);
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", data);
        response.put("totalElements", totalElements);
        response.put("page", page);
        response.put("size", size);
        response.put("totalPages", (int) Math.ceil((double) totalElements / size));

        return response;
    }

    private Map<String, Document> suggestedNatureCache = null;

    private synchronized Map<String, Document> getSuggestedNatureMap() {
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
                new Document("$match", new Document("natureTypes", new Document("$exists", true).append("$not", new Document("$size", 0)))),
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

    private String extractCaEsFromType(Object typeObj) {
        if (!(typeObj instanceof Document typeDoc)) return null;
        Object termObj = typeDoc.get("term");
        if (termObj instanceof Document termDoc) {
            String ca = termDoc.getString("ca_ES");
            if (ca != null && !ca.isBlank()) return ca;
            Object textObj = termDoc.get("text");
            if (textObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Document td && "ca_ES".equals(td.getString("locale"))) {
                        return td.getString("value");
                    }
                }
            }
        } else if (termObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Document td && "ca_ES".equals(td.getString("locale"))) {
                    return td.getString("value");
                }
            }
        }
        return null;
    }

    @PostMapping("/{uuid}/nature")
    public Map<String, Object> updateAwardNature(
            @PathVariable String uuid,
            @RequestParam(defaultValue = "test") String env,
            @RequestBody Document natureDoc) {
        
        boolean egretaSuccess = syncAwardNatureToEgreta(uuid, natureDoc, env);
        
        Map<String, Object> resp = new HashMap<>();
        if (egretaSuccess) {
            mongoTemplate.getCollection("Awards")
                    .updateOne(new Document("uuid", uuid),
                            new Document("$set", new Document("natureTypes", Arrays.asList(natureDoc))));

            suggestedNatureCache = null; // Clear cache on update
            allNatures = null;
            resp.put("success", true);
        } else {
            resp.put("success", false);
            resp.put("message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "). Comproveu que el registre existeixi en aquest entorn.");
        }
        return resp;
    }

    private boolean syncAwardNatureToEgreta(String uuid, Document natureDoc, String env) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Type", "application/json;charset=utf-8");
            headers.set("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f");
            headers.set("Accept", "application/json");

            String baseUrl = "prod".equalsIgnoreCase(env) ? "https://egreta.uab.cat/ws/api/" : "https://egretat.uab.cat/ws/api/";
            String url = baseUrl + "awards/" + uuid;

            // 1. GET current award document
            org.springframework.http.ResponseEntity<Map> getResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), Map.class);
            
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta award UUID: " + uuid + ", Status: " + getResp.getStatusCode());
                return false;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new java.util.LinkedHashMap<>(getResp.getBody());
            
            // 2. Modify natureTypes
            List<Object> natureList = new ArrayList<>();
            natureList.add(natureDoc);
            data.put("natureTypes", natureList);
            
            // 3. PUT updated award document back
            org.springframework.http.HttpEntity<Map<String, Object>> putEntity = new org.springframework.http.HttpEntity<>(data, headers);
            org.springframework.http.ResponseEntity<Map> putResp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.PUT, putEntity, Map.class);
            
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing award nature to Egreta: " + e.getMessage());
            return false;
        }
    }

    @GetMapping("/natures")
    public List<Document> getAvailableNatures() {
        return getAllNatures();
    }

    private List<Document> allNatures = null;

    private synchronized List<Document> getAllNatures() {
        if (allNatures != null) {
            return allNatures;
        }
        allNatures = new ArrayList<>();
        try {
            allNatures = mongoTemplate.getCollection("Awards")
                    .distinct("natureTypes", new Document(), Document.class)
                    .into(new ArrayList<>());
        } catch (Exception e) {
            // Silently catch
        }
        return allNatures;
    }

    private Document findNatureByUriSimilarity(String callTypeUri) {
        if (callTypeUri == null || callTypeUri.isBlank()) return null;
        
        String suffix = "";
        int idx = callTypeUri.indexOf("externalfundingopportunity/");
        if (idx >= 0) {
            suffix = callTypeUri.substring(idx + "externalfundingopportunity/".length());
        } else {
            idx = callTypeUri.indexOf("fundingopportunitytypes/");
            if (idx >= 0) {
                suffix = callTypeUri.substring(idx + "fundingopportunitytypes/".length());
            }
        }
        
        if (suffix.isEmpty()) return null;
        suffix = suffix.replaceAll("^/+", "").replaceAll("/+$", "").toLowerCase();
        
        String normSuffixSlash = suffix;
        String normSuffixUnder = suffix.replace("/", "_").replace("-", "_");
        
        List<Document> natures = getAllNatures();
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
        return null;
    }


    /*
    ===============================
    ESTADÍSTICAS GENERALES
    ===============================
    */

    @GetMapping("/stats/categories")
    public List<String> getCategorias() {
        return service.getCategorias();
    }

    @GetMapping("/stats/tipus")
    public List<String> getTipus() {
        return service.getTipus();
    }

    @GetMapping("/stats/tipus-per-categoria")
    public List<Document> getTipusPerCategoria() {
        return service.getTipusPerCategoria();
    }

    @GetMapping("/stats/import-per-tipus-anio")
    public List<Document> getImportPerTipusAnio() {
        return service.getImportPerTipusAnio();
    }

    @GetMapping("/stats/total")
    public Map<String, Object> getTotalStats() {
        return service.getTotalStats();
    }

    /*
    ===============================
    POWER TABLE
    ===============================
    */

    @GetMapping("/stats/powertable")
    public List<Document> getPowerTable(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "awardDate") String modoAnio,
            @RequestParam(required = false) String collaboratorUuid) {

        return service.getPowerTable(desde, hasta, modoAnio, collaboratorUuid);
    }

    @GetMapping("/stats/map-convenis")
    public List<Document> getMapConvenis(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String collaboratorUuid) {

        return service.getMapConvenis(desde, hasta, collaboratorUuid);
    }

    @GetMapping("/stats/xarxes-plataformes")
    public int getXarxesPlataformesCount(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String collaboratorUuid) {

        return service.getXarxesPlataformesCount(desde, hasta, collaboratorUuid);
    }


    @GetMapping("/stats/llista-ajuts-institut")
    public List<Document> getLlistaAjutsInstitut(
            @RequestParam String collaboratorUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {

        return service.getAwardsLlistaInstitut(collaboratorUuid, desde, hasta);
    }

    @GetMapping("/stats/ips-institut")
    public List<Document> getIpsInstitut(
            @RequestParam String collaboratorUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return service.getIpsInstitut(collaboratorUuid, desde, hasta);
    }

    @GetMapping("/stats/powertable/category-debug")
    public List<Map> getPowerTableCategoryDebug(
            @RequestParam(defaultValue = "50") int limit) {

        return service.getPowerTableCategoryDebug(limit);
    }

    /*
    ===============================
    PERSONA RESUMEN
    ===============================
    */

    @GetMapping("/stats/persona-resumen")
    public List<Document> getPersonaResumen(

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(required = false)
            String persona,

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            String gestionadosPorDept,

            @RequestParam(required = false)
            List<String> categoria,

            @RequestParam(required = false)
            List<String> tipus) {

        return service.getPersonaResumen(
                collaboratorUuid,
                deptUuid,
                persona,
                desde,
                hasta,
                modoAnio,
                gestionadosPorDept,
                categoria,
                tipus);
    }

    /*
    ===============================
    AWARDS POR PERSONA
    ===============================
    */

    @GetMapping("/stats/persona-awards")
    public List<Document> getAwardsByPersona(

            @RequestParam String personUuid,

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            List<String> categoria,

            @RequestParam(required = false)
            List<String> tipus) {

        return service.getAwardsByPersona(
                personUuid,
                desde,
                hasta,
                collaboratorUuid,
                deptUuid,
                modoAnio,
                categoria,
                tipus);
    }

    /*
    ===============================
    PROYECTOS POR AÑO
    ===============================
    */

    @GetMapping("/stats/proyectos-anio")
    public List<Document> getProyectosPorAnio(

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(required = false)
            String persona,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            List<String> categoria,

            @RequestParam(required = false)
            List<String> tipus) {

        return service.getProyectosPorAnio(
                desde,
                hasta,
                collaboratorUuid,
                deptUuid,
                persona,
                modoAnio,
                categoria,
                tipus
                );
    }

    /*
    ===============================
    CONCESIONES POR PROGRAMA
    ===============================
    */

    @GetMapping("/stats/concessions-by-program")
    public List<Document> getConcessionsByProgram() {
        List<Document> pipeline = List.of(
            new Document("$match", new Document("$or", List.of(
                new Document("type.term.ca_ES", "Conveni extern a la UAB")
                    .append("workflow.step", "approved"),
                new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB"))
                    .append("workflow.step", "validated")
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

        List<Document> results = new ArrayList<>();
        mongoTemplate.getCollection("Awards")
            .aggregate(pipeline)
            .into(results);
        return results;
    }

    @GetMapping("/stats/concessions-awards")
    public List<Document> getConcessionsAwards() {
        List<Document> pipeline = List.of(
            new Document("$match", new Document("$or", List.of(
                new Document("type.term.ca_ES", "Conveni extern a la UAB")
                    .append("workflow.step", "approved"),
                new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB"))
                    .append("workflow.step", "validated")
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
                .append("fundings", 1)
                .append("fOppType", "$fOpp.type.term")
                .append("keywordGroups", "$fOpp.keywordGroups")),
            new Document("$unwind", "$keywordGroups"),
            new Document("$match", new Document("keywordGroups.logicalName", "/uab/fundingopportunities/programes")),
            new Document("$unwind", "$keywordGroups.keywordContainers"),
            new Document("$project", new Document()
                .append("uuid", 1)
                .append("pureId", 1)
                .append("title", 1)
                .append("awardDate", 1)
                .append("fOppType", 1)
                .append("collaborators", 1)
                .append("fundings", 1)
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
            new Document("$project", new Document()
                .append("uuid", 1)
                .append("pureId", 1)
                .append("title", 1)
                .append("anyo", 1)
                .append("fOppType", 1)
                .append("program", 1)
                .append("esLider", 1)
                .append("fundings", 1)),
            new Document("$sort", new Document("anyo", -1).append("title.es_ES", 1))
        );

        List<Document> rawResults = new ArrayList<>();
        mongoTemplate.getCollection("Awards")
            .aggregate(pipeline)
            .into(rawResults);

        List<Document> processed = new ArrayList<>();
        for (Document doc : rawResults) {
            double totalImport = 0.0;
            List<Document> fundings = castList(doc.get("fundings"));
            if (fundings != null && !fundings.isEmpty()) {
                for (Document f : fundings) {
                    List<Document> cols = castList(f.get("fundingCollaborators"));
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

    /*
    ===============================
    PAÍSES
    ===============================
    */

    /** Diagnostic endpoint: returns counts at each step of the StudentTheses country filter. */
    @GetMapping("/debug/tesis")
    public Map<String, Object> debugTesis(
            @RequestParam String countryCode,
            @RequestParam(required = false, defaultValue = "2020") int startYear,
            @RequestParam(required = false, defaultValue = "2026") int endYear) {

        String code = countryCode.trim().toUpperCase();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("countryCode", code);

        // 1. ExternalOrganizations with this country
        List<String> funderUuids = new ArrayList<>();
        mongoTemplate.getCollection("ExternalOrganizations")
                .find(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")))
                .projection(new Document("uuid", 1).append("address.country.uri", 1))
                .limit(5)
                .forEach(doc -> funderUuids.add(doc.getString("uuid") + " | " + ((Document)((Document) doc.getOrDefault("address", new Document())).getOrDefault("country", new Document())).getString("uri")));
        long totalFunders = mongoTemplate.getCollection("ExternalOrganizations")
                .countDocuments(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")));
        result.put("funderOrgs_total", totalFunders);
        result.put("funderOrgs_sample", funderUuids);

        // 2. Persons with this nationality
        long totalPersons = mongoTemplate.getCollection("Persons").countDocuments(
                new Document("$or", List.of(
                        new Document("nationality.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityType.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityTypes.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i"))
                )));
        result.put("persons_total", totalPersons);

        // 3. StudentTheses total & workflow steps
        long thesesTotal = mongoTemplate.getCollection("StudentTheses").countDocuments(new Document());
        result.put("studentTheses_total", thesesTotal);

        List<Document> workflowPipeline = List.of(
                new Document("$group", new Document("_id", "$workflow.step").append("count", new Document("$sum", 1)))
        );
        List<String> steps = new ArrayList<>();
        mongoTemplate.getCollection("StudentTheses").aggregate(workflowPipeline)
                .forEach(d -> steps.add(d.getString("_id") + ": " + d.getInteger("count")));
        result.put("studentTheses_workflowSteps", steps);

        // 4. StudentTheses with workflow.step=validated in year range
        long thesesValidated = mongoTemplate.getCollection("StudentTheses").countDocuments(
                new Document("workflow.step", "validated")
                        .append("awardDate.year", new Document("$gte", startYear).append("$lte", endYear)));
        result.put("studentTheses_validated_inRange", thesesValidated);

        // 5. Sample managingOrganization from recent approved theses
        List<String> sampleManagingOrgs = new ArrayList<>();
        mongoTemplate.getCollection("StudentTheses")
                .find(new Document("workflow.step", "approved"))
                .projection(new Document("managingOrganization", 1).append("awardDate", 1))
                .limit(5)
                .forEach(th -> {
                    Document mo = (Document) th.get("managingOrganization");
                    if (mo != null) sampleManagingOrgs.add("uuid:" + mo.getString("uuid") + " sysName:" + mo.getString("systemName"));
                });
        result.put("sample_managingOrg", sampleManagingOrgs);

        // 6. Check if those UUIDs exist in OrganizationalUnits
        List<String> ouCheck = new ArrayList<>();
        for (String entry : sampleManagingOrgs) {
            String uuid = entry.replaceFirst("uuid:", "").replaceFirst(" sysName:.*", "").trim();
            if (uuid != null && !uuid.isBlank() && !"null".equals(uuid)) {
                long found = mongoTemplate.getCollection("Organizations")
                        .countDocuments(new Document("uuid", uuid));
                ouCheck.add(uuid + " -> OrganizationalUnits found: " + found);
            }
        }
        result.put("orgUnits_lookup", ouCheck);

        // 7. Sample supervisorOrganizations UUIDs from recent approved theses
        List<String> sampleSupervisorOrgs = new ArrayList<>();
        mongoTemplate.getCollection("StudentTheses")
                .find(new Document("workflow.step", "approved"))
                .projection(new Document("supervisorOrganizations", 1).append("supervisors", 1))
                .limit(3)
                .forEach(th -> {
                    List<?> sOrgs = (List<?>) th.get("supervisorOrganizations");
                    if (sOrgs != null) sOrgs.forEach(o -> { if (o instanceof Document) sampleSupervisorOrgs.add("supervisorOrg: " + ((Document)o).getString("uuid") + " sysName:" + ((Document)o).getString("systemName")); });
                    List<?> sups = (List<?>) th.get("supervisors");
                    if (sups != null) sups.forEach(s -> { if (s instanceof Document) { List<?> orgs = (List<?>)((Document)s).get("organizations"); if (orgs != null) orgs.forEach(o -> { if (o instanceof Document) sampleSupervisorOrgs.add("supervisor.org: " + ((Document)o).getString("uuid") + " sysName:" + ((Document)o).getString("systemName")); }); } });
                });
        result.put("sample_supervisorOrgs", sampleSupervisorOrgs);

        return result;
    }

    /**
     * Returns distinct countries from ExternalOrganizations, sorted alphabetically.
     * Uses server-side $group aggregation for speed.
     * Each entry: { countryCode, countryName }
     */
    @GetMapping("/countries")
    public List<Map<String, Object>> getCountries() {
        List<Document> pipeline = Arrays.asList(
            new Document("$match", new Document("address.country.uri",
                    new Document("$exists", true).append("$ne", null))),
            new Document("$group", new Document("_id", "$address.country.uri")
                    .append("term", new Document("$first", "$address.country.term")))
        );

        Map<String, String> codeToName = new LinkedHashMap<>();
        mongoTemplate.getCollection("ExternalOrganizations")
                .aggregate(pipeline)
                .forEach(doc -> {
                    String uri = doc.getString("_id");
                    String code = countryCodeFromUri(uri);
                    if (code == null) return;
                    Document term = (Document) doc.get("term");
                    String name = term != null
                            ? (term.containsKey("ca_ES") ? term.getString("ca_ES")
                            : term.containsKey("es_ES") ? term.getString("es_ES")
                            : term.containsKey("en_GB") ? term.getString("en_GB") : code)
                            : code;
                    codeToName.put(code, name);
                });

        return codeToName.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue(
                        java.text.Collator.getInstance(new Locale("ca", "ES"))))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("countryCode", e.getKey());
                    row.put("countryName", e.getValue());
                    return row;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Returns paginated validated awards linked to a given country code,
     * either through the funder or through any award holder's nationality.
     */
    @GetMapping("/by-country")
    public Map<String, Object> getAwardsByCountry(
            @RequestParam String countryCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String code = countryCode.trim().toUpperCase();

        // 1. Funder UUIDs from ExternalOrganizations matching this country
        Set<String> funderUuids = new HashSet<>();
        mongoTemplate.getCollection("ExternalOrganizations")
                .find(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")))
                .projection(new Document("uuid", 1))
                .forEach(org -> {
                    String uuid = org.getString("uuid");
                    if (uuid != null) funderUuids.add(uuid);
                });

        // 2. Person UUIDs matching this country nationality
        Set<String> personUuids = new HashSet<>();
        mongoTemplate.getCollection("Persons")
                .find(new Document("$or", List.of(
                        new Document("nationality.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityType.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityTypes.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i"))
                )))
                .projection(new Document("uuid", 1))
                .forEach(p -> {
                    String uuid = p.getString("uuid");
                    if (uuid != null) personUuids.add(uuid);
                });

        // 3. Build match: awards where funder uuid OR holder uuid matches
        List<Document> orConditions = new ArrayList<>();
        if (!funderUuids.isEmpty())
            orConditions.add(new Document("fundings.funder.uuid", new Document("$in", new ArrayList<>(funderUuids))));
        if (!personUuids.isEmpty())
            orConditions.add(new Document("awardHolders.person.uuid", new Document("$in", new ArrayList<>(personUuids))));

        Document matchFilter = new Document();
        matchFilter.append("$or", Arrays.asList(
            new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
            new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
        ));
        if (!orConditions.isEmpty()) {
            matchFilter = new Document("$and", Arrays.asList(
                matchFilter,
                new Document("$or", orConditions)
            ));
        } else {
            // no orgs and no persons → return empty
            return Map.of("content", List.of(), "totalElements", 0, "totalPages", 0, "page", page);
        }

        long total = mongoTemplate.getCollection("Awards").countDocuments(matchFilter);
        List<Document> items = mongoTemplate.getCollection("Awards")
                .find(matchFilter)
                .sort(new Document("title.es_ES", 1))
                .skip(page * size)
                .limit(size)
                .into(new ArrayList<>());

        // Resolve funder names for result items
        Set<String> resultFunderUuids = new HashSet<>();
        for (Document aw : items) {
            @SuppressWarnings("unchecked")
            List<Document> fundings = (List<Document>) aw.get("fundings");
            if (fundings != null) {
                for (Document f : fundings) {
                    Document funder = (Document) f.get("funder");
                    if (funder != null && funder.getString("uuid") != null)
                        resultFunderUuids.add(funder.getString("uuid"));
                }
            }
        }
        Map<String, String> funderNames = new HashMap<>();
        if (!resultFunderUuids.isEmpty()) {
            mongoTemplate.getCollection("ExternalOrganizations")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(resultFunderUuids))))
                    .projection(new Document("uuid", 1).append("name", 1))
                    .forEach(org -> {
                        Document nameDoc = (Document) org.get("name");
                        String name = nameDoc == null ? "" :
                                nameDoc.containsKey("es_ES") ? nameDoc.getString("es_ES") :
                                nameDoc.containsKey("ca_ES") ? nameDoc.getString("ca_ES") :
                                nameDoc.containsKey("en_GB") ? nameDoc.getString("en_GB") : "";
                        funderNames.put(org.getString("uuid"), name);
                    });
        }

        // Build simplified response
        List<Map<String, Object>> content = new ArrayList<>();
        for (Document aw : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            Document titleDoc = (Document) aw.get("title");
            String title = titleDoc == null ? "" :
                    titleDoc.containsKey("es_ES") ? titleDoc.getString("es_ES") :
                    titleDoc.containsKey("ca_ES") ? titleDoc.getString("ca_ES") :
                    titleDoc.containsKey("en_GB") ? titleDoc.getString("en_GB") : "";
            row.put("uuid", aw.getString("uuid"));
            row.put("title", title);

            // Funders for this award
            @SuppressWarnings("unchecked")
            List<Document> fundings = (List<Document>) aw.get("fundings");
            List<String> funderList = new ArrayList<>();
            if (fundings != null) {
                for (Document f : fundings) {
                    Document funder = (Document) f.get("funder");
                    if (funder != null) {
                        String name = funderNames.getOrDefault(funder.getString("uuid"), "");
                        if (!name.isBlank()) funderList.add(name);
                    }
                }
            }
            row.put("funders", funderList.stream().distinct().collect(java.util.stream.Collectors.toList()));

            // IPs
            @SuppressWarnings("unchecked")
            List<Document> holders = (List<Document>) aw.get("awardHolders");
            List<String> ips = new ArrayList<>();
            if (holders != null) {
                for (Document h : holders) {
                    Document roleDoc = (Document) h.get("role");
                    if (roleDoc == null) continue;
                    Document term = (Document) roleDoc.get("term");
                    if (term == null) continue;
                    String roleCa = term.getString("ca_ES");
                    String roleEn = term.getString("en_GB");
                    boolean isIp = "Investigador/a Principal".equals(roleCa) || "Principal Investigator".equals(roleEn);
                    if (isIp) {
                        Document hn = (Document) h.get("name");
                        String fn = hn != null ? hn.getString("firstName") : "";
                        String ln = hn != null ? hn.getString("lastName") : "";
                        String fullName = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                        if (!fullName.isBlank()) ips.add(fullName);
                    }
                }
            }
            row.put("ips", ips);

            // Dates
            Document period = (Document) aw.get("actualPeriod");
            row.put("startDate", period != null ? period.get("startDate") : null);
            row.put("endDate", period != null ? period.get("endDate") : null);

            content.add(row);
        }

        int totalPages = (int) Math.ceil((double) total / size);
        return Map.of(
                "content", content,
                "totalElements", total,
                "totalPages", totalPages,
                "page", page
        );
    }

    // ---- helpers ----

    /*
    ===============================
    INFORME WORD PER PAÍS
    ===============================
    */

    /**
     * Generates a Word document with awards (projectes and/or convenis) for a given country.
     * Query params:
     *   countryCode  – ISO-2 code (e.g. ES)
     *   startDate    – ISO date (default 1968-01-01)
     *   endDate      – ISO date (default today)
     *   projectes    – true/false (include competitive awards)
     *   convenis     – true/false (include convenis)
     */
    @GetMapping("/reports/word/country")
    public void generarInformeWordPais(
            @RequestParam String countryCode,
            @RequestParam(required = false, defaultValue = "1968-01-01") String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "true") boolean projectes,
            @RequestParam(required = false, defaultValue = "false") boolean convenis,
            @RequestParam(required = false, defaultValue = "false") boolean beques,
            @RequestParam(required = false, defaultValue = "false") boolean xarxes,
            @RequestParam(required = false, defaultValue = "false") boolean tesis,
            @RequestParam(required = false, defaultValue = "false") boolean articles,
            @RequestParam(required = false, defaultValue = "false") boolean llibres,
            @RequestParam(required = false, defaultValue = "false") boolean capitols,
            HttpServletResponse response) throws Exception {

        String code = countryCode.trim().toUpperCase();
        String effectiveEnd = (endDate == null || endDate.isBlank())
                ? LocalDate.now().toString() : endDate;

        Date startDateD = Date.from(LocalDate.parse(startDate).atStartOfDay(ZoneId.of("UTC")).toInstant());
        Date endDateD   = Date.from(LocalDate.parse(effectiveEnd).atStartOfDay(ZoneId.of("UTC")).toInstant());

        // --- Resolve funder UUIDs for this country ---
        Set<String> funderUuids = new HashSet<>();
        Map<String, String> funderNamesMap = new HashMap<>();
        mongoTemplate.getCollection("ExternalOrganizations")
                .find(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")))
                .projection(new Document("uuid", 1).append("name", 1).append("address.country", 1))
                .forEach(org -> {
                    String uuid = org.getString("uuid");
                    if (uuid == null) return;
                    funderUuids.add(uuid);
                    Document nd = (Document) org.get("name");
                    String name = nd == null ? "" :
                            nd.containsKey("ca_ES") ? nd.getString("ca_ES") :
                            nd.containsKey("es_ES") ? nd.getString("es_ES") :
                            nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                    funderNamesMap.put(uuid, name);
                    // resolve display name for country
                });

        // Resolve country display name from first matching org
        String[] countryNameHolder = {code};
        mongoTemplate.getCollection("ExternalOrganizations")
                .find(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")))
                .limit(1)
                .forEach(org -> {
                    Document addr = (Document) org.get("address");
                    if (addr == null) return;
                    Document country = (Document) addr.get("country");
                    if (country == null) return;
                    Document term = (Document) country.get("term");
                    if (term == null) return;
                    String n = term.containsKey("ca_ES") ? term.getString("ca_ES") :
                               term.containsKey("es_ES") ? term.getString("es_ES") :
                               term.containsKey("en_GB") ? term.getString("en_GB") : code;
                    if (n != null) countryNameHolder[0] = n;
                });
        String countryName = countryNameHolder[0];

        // --- Resolve person UUIDs for this country ---
        Set<String> personUuids = new HashSet<>();
        mongoTemplate.getCollection("Persons")
                .find(new Document("$or", List.of(
                        new Document("nationality.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityType.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityTypes.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i"))
                )))
                .projection(new Document("uuid", 1))
                .forEach(p -> { String u = p.getString("uuid"); if (u != null) personUuids.add(u); });

        // Build $or conditions
        List<Document> countryOr = new ArrayList<>();
        if (!funderUuids.isEmpty())
            countryOr.add(new Document("fundings.funder.uuid", new Document("$in", new ArrayList<>(funderUuids))));
        if (!personUuids.isEmpty())
            countryOr.add(new Document("awardHolders.person.uuid", new Document("$in", new ArrayList<>(personUuids))));

        // --- Load template ---
        InputStream tplIs = getClass().getClassLoader().getResourceAsStream("informe_pais.docx");
        try (XWPFDocument doc = new XWPFDocument(tplIs); OutputStream out = response.getOutputStream()) {

            // Clear template body
            CTBody b = doc.getDocument().getBody();
            for (int i = b.sizeOfPArray() - 1; i >= 0; i--) b.removeP(i);
            for (int i = b.sizeOfTblArray() - 1; i >= 0; i--) b.removeTbl(i);

            // Fill header bookmark "pais" with country name
            for (XWPFHeader hdr : doc.getHeaderList()) {
                List<XWPFParagraph> hdrParas = new ArrayList<>();
                hdrParas.addAll(hdr.getParagraphs());
                for (XWPFTable hdrTbl : hdr.getTables()) {
                    for (XWPFTableRow hdrRow : hdrTbl.getRows()) {
                        for (XWPFTableCell hdrCell : hdrRow.getTableCells()) {
                            hdrParas.addAll(hdrCell.getParagraphs());
                        }
                    }
                }
                for (XWPFParagraph hp : hdrParas) {
                    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark bm : hp.getCTP().getBookmarkStartList()) {
                        if ("pais".equals(bm.getName())) {
                            XWPFRun hr = hp.createRun();
                            hr.setBold(true);
                            hr.setColor("FFFFFF");
                            hr.setFontFamily("Calibri");
                            hr.setFontSize(11);
                            hr.setText(countryName);
                            org.w3c.dom.Node next = bm.getDomNode().getNextSibling();
                            while (next != null && !next.getNodeName().contains("bookmarkEnd")) {
                                org.w3c.dom.Node toRemove = next;
                                next = next.getNextSibling();
                                hp.getCTP().getDomNode().removeChild(toRemove);
                            }
                            hp.getCTP().getDomNode().insertBefore(hr.getCTR().getDomNode(), bm.getDomNode());
                        }
                    }
                }
            }

            // Intro
            XWPFParagraph pDate = doc.createParagraph();
            pDate.setAlignment(ParagraphAlignment.BOTH);
            XWPFRun rDate = pDate.createRun();
            rDate.setFontFamily("Calibri"); rDate.setFontSize(9);
            rDate.setText("Data d'extracció: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));

            XWPFParagraph pPeriod = doc.createParagraph();
            pPeriod.setAlignment(ParagraphAlignment.BOTH);
            XWPFRun rPeriod = pPeriod.createRun();
            rPeriod.setFontFamily("Calibri"); rPeriod.setFontSize(9);
            rPeriod.setText("Període: " + startDate + " — " + effectiveEnd);

            XWPFParagraph pCountry = doc.createParagraph();
            pCountry.setAlignment(ParagraphAlignment.BOTH);
            XWPFRun rCountry = pCountry.createRun();
            rCountry.setFontFamily("Calibri"); rCountry.setFontSize(9);
            rCountry.setBold(true);
            rCountry.setText("País: " + countryName + " (" + code + ")");

            doc.createParagraph();
            boolean[] actRealizWritten = {false};

            // ---- Section: Projectes (Ajudes competitives) ----
            if (projectes && !countryOr.isEmpty()) {
                Document projFilter = new Document("workflow.step", "validated")
                        .append("categoria", new Document("$regex", "^Ajudes competitives"))
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> projRaw = mongoTemplate.getCollection("Awards")
                        .find(projFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                // Batch-fetch funder names
                Set<String> pfUuids = new HashSet<>();
                for (Document aw : projRaw) {
                    List<Document> awf = castList(aw.get("fundings"));
                    if (awf != null) for (Document f : awf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) pfUuids.add(fd.getString("uuid")); }
                }
                if (!pfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(pfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Projectes de recerca");
                doc.createParagraph();

                int idx = 1;
                for (Document aw : projRaw) {
                    writeAwardBlock(doc, aw, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr = secFoot.createRun();
                fr.setFontFamily("Calibri"); fr.setFontSize(9); fr.setItalic(true);
                fr.setText("Total projectes: " + projRaw.size());
                doc.createParagraph();
            }

            // ---- Section: Convenis ----
            if (convenis && !countryOr.isEmpty()) {
                Document convFilter = new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", "Concessió conveni")
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> convRaw = mongoTemplate.getCollection("Awards")
                        .find(convFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                Set<String> cfUuids = new HashSet<>();
                for (Document cv : convRaw) {
                    List<Document> cvf = castList(cv.get("fundings"));
                    if (cvf != null) for (Document f : cvf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) cfUuids.add(fd.getString("uuid")); }
                }
                if (!cfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(cfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Convenis");
                doc.createParagraph();

                int idx = 1;
                for (Document cv : convRaw) {
                    writeAwardBlock(doc, cv, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr = secFoot.createRun();
                fr.setFontFamily("Calibri"); fr.setFontSize(9); fr.setItalic(true);
                fr.setText("Total convenis: " + convRaw.size());
            }

            // ---- Section: Beques ----
            if (beques && !countryOr.isEmpty()) {
                Document becaFilter = new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", "Beques")
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> becaRaw = mongoTemplate.getCollection("Awards")
                        .find(becaFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                Set<String> bfUuids = new HashSet<>();
                for (Document bw : becaRaw) {
                    List<Document> bwf = castList(bw.get("fundings"));
                    if (bwf != null) for (Document f : bwf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) bfUuids.add(fd.getString("uuid")); }
                }
                if (!bfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(bfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Beques");
                doc.createParagraph();

                int idx = 1;
                for (Document bw : becaRaw) {
                    writeAwardBlock(doc, bw, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr2 = secFoot.createRun();
                fr2.setFontFamily("Calibri"); fr2.setFontSize(9); fr2.setItalic(true);
                fr2.setText("Total beques: " + becaRaw.size());
            }

            // ---- Section: Xarxes ----
            if (xarxes && !countryOr.isEmpty()) {
                Document xarxesFilter = new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", "Grups i Xarxes de Recerca")
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> xarxesRaw = mongoTemplate.getCollection("Awards")
                        .find(xarxesFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                Set<String> xfUuids = new HashSet<>();
                for (Document xw : xarxesRaw) {
                    List<Document> xwf = castList(xw.get("fundings"));
                    if (xwf != null) for (Document f : xwf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) xfUuids.add(fd.getString("uuid")); }
                }
                if (!xfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(xfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Grups i Xarxes de Recerca");
                doc.createParagraph();

                int idx = 1;
                for (Document xw : xarxesRaw) {
                    writeAwardBlock(doc, xw, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr3 = secFoot.createRun();
                fr3.setFontFamily("Calibri"); fr3.setFontSize(9); fr3.setItalic(true);
                fr3.setText("Total xarxes: " + xarxesRaw.size());
            }

            // ---- Section: Tesis doctorals ----
            if (tesis) {
                int startYear = LocalDate.parse(startDate).getYear();
                int endYear   = LocalDate.parse(effectiveEnd).getYear();

                List<Document> thesisOr = new ArrayList<>();
                if (!funderUuids.isEmpty()) {
                    thesisOr.add(new Document("awardingInstitutions.externalOrganizationRef.uuid",
                            new Document("$in", new ArrayList<>(funderUuids))));
                    // direct relation thesis → external orgs (equiv. relationExternalorganisationsStudentthesises)
                    thesisOr.add(new Document("supervisorOrganizations.uuid",
                            new Document("$in", new ArrayList<>(funderUuids))));
                    // supervisor affiliated with an org from that country
                    thesisOr.add(new Document("supervisors.organizations.uuid",
                            new Document("$in", new ArrayList<>(funderUuids))));
                }
                if (!personUuids.isEmpty()) {
                    thesisOr.add(new Document("supervisors.person.uuid",
                            new Document("$in", new ArrayList<>(personUuids))));
                }

                Document thesisTypeFilter = new Document("$or", Arrays.asList(
                        new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
                ));
                List<Document> andClauses = new ArrayList<>();
                andClauses.add(thesisTypeFilter);
                if (!thesisOr.isEmpty())
                    andClauses.add(new Document("$or", thesisOr));
                Document thesisFilter = new Document("workflow.step", "approved")
                        .append("awardDate.year", new Document("$gte", startYear).append("$lte", endYear))
                        .append("$and", andClauses);

                List<Document> thesesRaw = mongoTemplate.getCollection("StudentTheses")
                        .find(thesisFilter)
                        .sort(new Document("awardDate.year", -1).append("awardDate.month", -1).append("awardDate.day", -1))
                        .into(new ArrayList<>());

                // Batch-fetch managing organization names
                Map<String, String> orgNamesMap = new HashMap<>();
                Set<String> managingUuids = new HashSet<>();
                for (Document th : thesesRaw) {
                    Document mo = (Document) th.get("managingOrganization");
                    if (mo != null && mo.getString("uuid") != null) managingUuids.add(mo.getString("uuid"));
                }
                if (!managingUuids.isEmpty()) {
                    mongoTemplate.getCollection("Organizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(managingUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" :
                                        nd.containsKey("ca_ES") ? nd.getString("ca_ES") :
                                        nd.containsKey("es_ES") ? nd.getString("es_ES") :
                                        nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                orgNamesMap.put(org.getString("uuid"), n);
                            });
                }

                // Group by year (already sorted desc)
                LinkedHashMap<Integer, List<Document>> thByYear = new LinkedHashMap<>();
                for (Document th : thesesRaw) {
                    Document ad = (Document) th.get("awardDate");
                    int yr = (ad != null && ad.get("year") != null) ? ((Number) ad.get("year")).intValue() : 0;
                    thByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(th);
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph tSecTitle = doc.createParagraph();
                tSecTitle.setStyle("Ttol2");
                tSecTitle.createRun().setText("Tesis doctorals");
                doc.createParagraph();

                int tIdx = 1;
                for (Map.Entry<Integer, List<Document>> yearEntry : thByYear.entrySet()) {
                    // Year heading
                    XWPFParagraph yearPara = doc.createParagraph();
                    yearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun yearRun = yearPara.createRun();
                    yearRun.setBold(true); yearRun.setFontFamily("Calibri"); yearRun.setFontSize(9);
                    yearRun.setText(yearEntry.getKey() > 0 ? String.valueOf(yearEntry.getKey()) : "(any desconegut)");

                    for (Document th : yearEntry.getValue()) {
                        Document thTitleDoc = (Document) th.get("title");
                        String thTitle = thTitleDoc != null ? thTitleDoc.getString("value") : "";
                        if (thTitle == null || thTitle.isBlank()) thTitle = "(sense títol)";

                        // Author: first contributor
                        String autor = "";
                        List<Document> thContribs = castList(th.get("contributors"));
                        if (thContribs != null) {
                            autor = thContribs.stream()
                                    .map(c -> { Document n = (Document) c.get("name"); if (n == null) return ""; String ln = n.getString("lastName"); String fn = n.getString("firstName"); return (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : ""); })
                                    .filter(s -> !s.isBlank()).findFirst().orElse("");
                        }

                        // Date DD-MM-YYYY
                        String dataLectura = "";
                        Document thAwardDate = (Document) th.get("awardDate");
                        if (thAwardDate != null) {
                            int dy = thAwardDate.get("day") != null ? ((Number) thAwardDate.get("day")).intValue() : 1;
                            int mo = thAwardDate.get("month") != null ? ((Number) thAwardDate.get("month")).intValue() : 1;
                            int yr2 = thAwardDate.get("year") != null ? ((Number) thAwardDate.get("year")).intValue() : 0;
                            if (yr2 > 0) dataLectura = String.format("%02d-%02d-%04d", dy, mo, yr2);
                        }

                        // Supervisors joined with " & "
                        List<String> directors = new ArrayList<>();
                        List<Document> thSupervisors = castList(th.get("supervisors"));
                        if (thSupervisors != null) {
                            for (Document sv : thSupervisors) {
                                Document n = (Document) sv.get("name");
                                if (n == null) continue;
                                String ln = n.getString("lastName"); String fn = n.getString("firstName");
                                String full = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                                if (!full.isBlank()) directors.add(full);
                            }
                        }

                        // Managing organization name
                        String centre = "";
                        Document mo = (Document) th.get("managingOrganization");
                        if (mo != null && mo.getString("uuid") != null) {
                            centre = orgNamesMap.getOrDefault(mo.getString("uuid"), "");
                        }

                        XWPFParagraph tTitlePara = doc.createParagraph();
                        tTitlePara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun tTitleRun = tTitlePara.createRun();
                        tTitleRun.setBold(true); tTitleRun.setFontFamily("Calibri"); tTitleRun.setFontSize(9);
                        tTitleRun.setText(tIdx + ".- " + thTitle);

                        wordLabelValue(doc, "Autor", autor);
                        wordLabelValue(doc, "Data de lectura", dataLectura);
                        wordLabelValue(doc, "Supervisors", String.join(" & ", directors));
                        if (!centre.isBlank()) wordLabelValue(doc, "Centre de lectura", centre);
                        doc.createParagraph();
                        tIdx++;
                    }
                }

                XWPFParagraph tSecFoot = doc.createParagraph();
                XWPFRun fr4 = tSecFoot.createRun();
                fr4.setFontFamily("Calibri"); fr4.setFontSize(9); fr4.setItalic(true);
                fr4.setText("Total tesis doctorals: " + thesesRaw.size());
            }

            boolean[] prodCientWritten = {false};

            // ---- Section: Articles ----
            if (articles && !personUuids.isEmpty()) {
                int startYear = LocalDate.parse(startDate).getYear();
                int endYear   = LocalDate.parse(effectiveEnd).getYear();

                Document artFilter = new Document("workflow.step", "approved")
                        .append("$or", Arrays.asList(
                                new Document("publicationDate.year",
                                        new Document("$gte", startYear).append("$lte", endYear)),
                                new Document("$and", Arrays.asList(
                                        new Document("publicationDate.year", new Document("$exists", false)),
                                        new Document("submissionYear",
                                                new Document("$gte", startYear).append("$lte", endYear))
                                ))
                        ))
                        .append("$or", Arrays.asList(
                                new Document("contributors.person.uuid",
                                        new Document("$in", new ArrayList<>(personUuids))),
                                new Document("contributors.externalPerson.uuid",
                                        new Document("$in", new ArrayList<>(personUuids)))
                        ));

                // Build filter correctly: year AND (contributor IN personUuids)
                Document yearFilter = new Document("$or", Arrays.asList(
                        new Document("publicationDate.year",
                                new Document("$gte", startYear).append("$lte", endYear)),
                        new Document("$and", Arrays.asList(
                                new Document("publicationDate.year", new Document("$exists", false)),
                                new Document("submissionYear",
                                        new Document("$gte", startYear).append("$lte", endYear))
                        ))
                ));
                Document contribFilter = new Document("$or", Arrays.asList(
                        new Document("contributors.person.uuid",
                                new Document("$in", new ArrayList<>(personUuids))),
                        new Document("contributors.externalPerson.uuid",
                                new Document("$in", new ArrayList<>(personUuids)))
                ));

                Document articleFilter = new Document("workflow.step", "approved")
                        .append("$and", Arrays.asList(yearFilter, contribFilter));

                List<Document> articlesRaw = mongoTemplate.getCollection("Researchoutputs")
                        .find(articleFilter)
                        .into(new ArrayList<>());

                // Sort by year desc, month desc, day desc
                articlesRaw.sort((artX, artY) -> {
                    Document pdX = artX.get("publicationDate") instanceof Document ? (Document) artX.get("publicationDate") : new Document();
                    Document pdY = artY.get("publicationDate") instanceof Document ? (Document) artY.get("publicationDate") : new Document();
                    int yX = pdX.containsKey("year") ? pdX.getInteger("year", 0) : artX.getInteger("submissionYear", 0);
                    int yY = pdY.containsKey("year") ? pdY.getInteger("year", 0) : artY.getInteger("submissionYear", 0);
                    if (yY != yX) return Integer.compare(yY, yX);
                    int mX = pdX.getInteger("month", 0);
                    int mY = pdY.getInteger("month", 0);
                    if (mY != mX) return Integer.compare(mY, mX);
                    return Integer.compare(pdY.getInteger("day", 0), pdX.getInteger("day", 0));
                });

                // Group by year preserving sort order (year desc)
                LinkedHashMap<Integer, List<Document>> artByYear = new LinkedHashMap<>();
                for (Document pub : articlesRaw) {
                    Document pd = pub.get("publicationDate") instanceof Document ? (Document) pub.get("publicationDate") : new Document();
                    int yr = pd.containsKey("year") ? pd.getInteger("year", 0) : pub.getInteger("submissionYear", 0);
                    artByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(pub);
                }

                if (!prodCientWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph artGrpTitle = doc.createParagraph();
                    artGrpTitle.setStyle("Ttol1");
                    artGrpTitle.createRun().setText("Producció científica");
                    doc.createParagraph();
                    prodCientWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph artSecTitle = doc.createParagraph();
                artSecTitle.setStyle("Ttol2");
                artSecTitle.createRun().setText("Articles");
                doc.createParagraph();

                for (Map.Entry<Integer, List<Document>> artEntry : artByYear.entrySet()) {
                    // Year heading
                    XWPFParagraph yearPara = doc.createParagraph();
                    yearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun yearRun = yearPara.createRun();
                    yearRun.setBold(true); yearRun.setFontFamily("Calibri"); yearRun.setFontSize(9);
                    yearRun.setText(artEntry.getKey() > 0 ? String.valueOf(artEntry.getKey()) : "(any desconegut)");

                    for (Document pub : artEntry.getValue()) {
                        String apa = researchOutputService.formatApaForDocument(pub);
                        XWPFParagraph artPara = doc.createParagraph();
                        artPara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun artRun = artPara.createRun();
                        artRun.setFontFamily("Calibri"); artRun.setFontSize(9);
                        artRun.setText(apa != null ? apa : "(sense dades)");
                        doc.createParagraph();
                    }
                    doc.createParagraph();
                }

                XWPFParagraph artSecFoot = doc.createParagraph();
                XWPFRun fr5 = artSecFoot.createRun();
                fr5.setFontFamily("Calibri"); fr5.setFontSize(9); fr5.setItalic(true);
                fr5.setText("Total articles: " + articlesRaw.size());
            }

            // ---- Section: Llibres ----
            if (llibres && !personUuids.isEmpty()) {
                int llStartYear = LocalDate.parse(startDate).getYear();
                int llEndYear   = LocalDate.parse(effectiveEnd).getYear();

                Document llYearFilter = new Document("$or", Arrays.asList(
                        new Document("publicationDate.year",
                                new Document("$gte", llStartYear).append("$lte", llEndYear)),
                        new Document("$and", Arrays.asList(
                                new Document("publicationDate.year", new Document("$exists", false)),
                                new Document("submissionYear",
                                        new Document("$gte", llStartYear).append("$lte", llEndYear))
                        ))
                ));
                Document llContribFilter = new Document("$or", Arrays.asList(
                        new Document("contributors.person.uuid",
                                new Document("$in", new ArrayList<>(personUuids))),
                        new Document("contributors.externalPerson.uuid",
                                new Document("$in", new ArrayList<>(personUuids)))
                ));
                Document llTypeFilter = new Document("$or", Arrays.asList(
                        new Document("type.uri", new Document("$regex", "researchoutputtypes/book/").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "^authored book$|^edited book$|^book$|^monograph$").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "^llibre").append("$options", "i")),
                        new Document("type.term.es_ES", new Document("$regex", "^libro").append("$options", "i"))
                ));

                Document llibresFilter = new Document("workflow.step", "approved")
                        .append("$and", Arrays.asList(llYearFilter, llContribFilter, llTypeFilter));

                List<Document> llibresRaw = mongoTemplate.getCollection("Researchoutputs")
                        .find(llibresFilter)
                        .into(new ArrayList<>());

                llibresRaw.sort((docA, docB) -> {
                    Document pdA = docA.get("publicationDate") instanceof Document ? (Document) docA.get("publicationDate") : new Document();
                    Document pdB = docB.get("publicationDate") instanceof Document ? (Document) docB.get("publicationDate") : new Document();
                    int yA = pdA.containsKey("year") ? pdA.getInteger("year", 0) : docA.getInteger("submissionYear", 0);
                    int yB = pdB.containsKey("year") ? pdB.getInteger("year", 0) : docB.getInteger("submissionYear", 0);
                    if (yB != yA) return Integer.compare(yB, yA);
                    int mA = pdA.getInteger("month", 0); int mB = pdB.getInteger("month", 0);
                    if (mB != mA) return Integer.compare(mB, mA);
                    return Integer.compare(pdB.getInteger("day", 0), pdA.getInteger("day", 0));
                });

                LinkedHashMap<Integer, List<Document>> llibresByYear = new LinkedHashMap<>();
                for (Document pub : llibresRaw) {
                    Document pd = pub.get("publicationDate") instanceof Document ? (Document) pub.get("publicationDate") : new Document();
                    int yr = pd.containsKey("year") ? pd.getInteger("year", 0) : pub.getInteger("submissionYear", 0);
                    llibresByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(pub);
                }

                if (!prodCientWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grpTitleLL = doc.createParagraph();
                    grpTitleLL.setStyle("Ttol1"); grpTitleLL.createRun().setText("Producció científica");
                    doc.createParagraph();
                    prodCientWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph llSecTitle = doc.createParagraph();
                llSecTitle.setStyle("Ttol2");
                llSecTitle.createRun().setText("Llibres");
                doc.createParagraph();

                for (Map.Entry<Integer, List<Document>> llEntry : llibresByYear.entrySet()) {
                    XWPFParagraph llYearPara = doc.createParagraph();
                    llYearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun llYearRun = llYearPara.createRun();
                    llYearRun.setBold(true); llYearRun.setFontFamily("Calibri"); llYearRun.setFontSize(9);
                    llYearRun.setText(llEntry.getKey() > 0 ? String.valueOf(llEntry.getKey()) : "(any desconegut)");

                    for (Document pub : llEntry.getValue()) {
                        String apa = researchOutputService.formatApaForDocument(pub);
                        XWPFParagraph llPara = doc.createParagraph();
                        llPara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun llRun = llPara.createRun();
                        llRun.setFontFamily("Calibri"); llRun.setFontSize(9);
                        llRun.setText(apa != null ? apa : "(sense dades)");
                        doc.createParagraph();
                    }
                    doc.createParagraph();
                }

                XWPFParagraph llSecFoot = doc.createParagraph();
                XWPFRun frLL = llSecFoot.createRun();
                frLL.setFontFamily("Calibri"); frLL.setFontSize(9); frLL.setItalic(true);
                frLL.setText("Total llibres: " + llibresRaw.size());
            }

            // ---- Section: Capítols de llibre ----
            if (capitols && !personUuids.isEmpty()) {
                int capStartYear = LocalDate.parse(startDate).getYear();
                int capEndYear   = LocalDate.parse(effectiveEnd).getYear();

                Document capYearFilter = new Document("$or", Arrays.asList(
                        new Document("publicationDate.year",
                                new Document("$gte", capStartYear).append("$lte", capEndYear)),
                        new Document("$and", Arrays.asList(
                                new Document("publicationDate.year", new Document("$exists", false)),
                                new Document("submissionYear",
                                        new Document("$gte", capStartYear).append("$lte", capEndYear))
                        ))
                ));
                Document capContribFilter = new Document("$or", Arrays.asList(
                        new Document("contributors.person.uuid",
                                new Document("$in", new ArrayList<>(personUuids))),
                        new Document("contributors.externalPerson.uuid",
                                new Document("$in", new ArrayList<>(personUuids)))
                ));
                Document capTypeFilter = new Document("$or", Arrays.asList(
                        new Document("type.uri", new Document("$regex", "contributiontobookanthology").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "chapter|contribution to book").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "cap.tol.*llibre|contribuci.*llibre").append("$options", "i")),
                        new Document("type.term.es_ES", new Document("$regex", "cap.tulo.*libro|contribuci.*libro").append("$options", "i"))
                ));

                Document capitolsFilter = new Document("workflow.step", "approved")
                        .append("$and", Arrays.asList(capYearFilter, capContribFilter, capTypeFilter));

                List<Document> capitolsRaw = mongoTemplate.getCollection("Researchoutputs")
                        .find(capitolsFilter)
                        .into(new ArrayList<>());

                capitolsRaw.sort((docA, docB) -> {
                    Document pdA = docA.get("publicationDate") instanceof Document ? (Document) docA.get("publicationDate") : new Document();
                    Document pdB = docB.get("publicationDate") instanceof Document ? (Document) docB.get("publicationDate") : new Document();
                    int yA = pdA.containsKey("year") ? pdA.getInteger("year", 0) : docA.getInteger("submissionYear", 0);
                    int yB = pdB.containsKey("year") ? pdB.getInteger("year", 0) : docB.getInteger("submissionYear", 0);
                    if (yB != yA) return Integer.compare(yB, yA);
                    int mA = pdA.getInteger("month", 0); int mB = pdB.getInteger("month", 0);
                    if (mB != mA) return Integer.compare(mB, mA);
                    return Integer.compare(pdB.getInteger("day", 0), pdA.getInteger("day", 0));
                });

                LinkedHashMap<Integer, List<Document>> capitolsByYear = new LinkedHashMap<>();
                for (Document pub : capitolsRaw) {
                    Document pd = pub.get("publicationDate") instanceof Document ? (Document) pub.get("publicationDate") : new Document();
                    int yr = pd.containsKey("year") ? pd.getInteger("year", 0) : pub.getInteger("submissionYear", 0);
                    capitolsByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(pub);
                }

                if (!prodCientWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grpTitleCap = doc.createParagraph();
                    grpTitleCap.setStyle("Ttol1"); grpTitleCap.createRun().setText("Producció científica");
                    doc.createParagraph();
                    prodCientWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph capSecTitle = doc.createParagraph();
                capSecTitle.setStyle("Ttol2");
                capSecTitle.createRun().setText("Capítols de llibre");
                doc.createParagraph();

                for (Map.Entry<Integer, List<Document>> capEntry : capitolsByYear.entrySet()) {
                    XWPFParagraph capYearPara = doc.createParagraph();
                    capYearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun capYearRun = capYearPara.createRun();
                    capYearRun.setBold(true); capYearRun.setFontFamily("Calibri"); capYearRun.setFontSize(9);
                    capYearRun.setText(capEntry.getKey() > 0 ? String.valueOf(capEntry.getKey()) : "(any desconegut)");

                    for (Document pub : capEntry.getValue()) {
                        String apa = researchOutputService.formatApaForDocument(pub);
                        XWPFParagraph capPara = doc.createParagraph();
                        capPara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun capRun = capPara.createRun();
                        capRun.setFontFamily("Calibri"); capRun.setFontSize(9);
                        capRun.setText(apa != null ? apa : "(sense dades)");
                        doc.createParagraph();
                    }
                    doc.createParagraph();
                }

                XWPFParagraph capSecFoot = doc.createParagraph();
                XWPFRun frCap = capSecFoot.createRun();
                frCap.setFontFamily("Calibri"); frCap.setFontSize(9); frCap.setItalic(true);
                frCap.setText("Total capítols de llibre: " + capitolsRaw.size());
            }

            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            String filename = "informe-pais-" + code + ".docx";
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            doc.write(out);
        }
    }

    private void writeAwardBlock(XWPFDocument doc, Document aw, int idx, Map<String, String> funderNamesMap) {
        String awTitle = nestedStr(aw, "title", "ca_ES");
        if (awTitle.isEmpty()) awTitle = nestedStr(aw, "title", "es_ES");
        if (awTitle.isEmpty()) awTitle = nestedStr(aw, "title", "en_GB");

        List<String> ipOnlyList = new ArrayList<>();
        List<String> coipList = new ArrayList<>();
        List<String> equip = new ArrayList<>();
        List<Document> holders = castList(aw.get("awardHolders"));
        if (holders != null) {
            for (Document h : holders) {
                Document hn = (Document) h.get("name");
                Document rd = (Document) h.get("role");
                String fn = hn != null ? hn.getString("firstName") : "";
                String ln = hn != null ? hn.getString("lastName") : "";
                String full = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                boolean isIp = false;
                boolean isPureIp = false;
                if (rd != null) {
                    Document td = (Document) rd.get("term");
                    if (td != null) {
                        String roleCa = td.getString("ca_ES") != null ? td.getString("ca_ES") : "";
                        String roleEn = td.getString("en_GB") != null ? td.getString("en_GB") : "";
                        isIp = roleCa.contains("Principal") || roleEn.contains("Principal");
                        isPureIp = isIp && !roleCa.toLowerCase().startsWith("co");
                    }
                }
                if (isIp && !full.isBlank()) {
                    if (isPureIp) ipOnlyList.add(full); else coipList.add(full);
                } else if (!full.isBlank()) {
                    equip.add(full);
                }
            }
        }
        List<String> ipAllList = new ArrayList<>(ipOnlyList);
        ipAllList.addAll(coipList);
        String ip = String.join(" & ", ipAllList);

        String funderName = "";
        double totalImport = 0.0;
        List<Document> fundings = castList(aw.get("fundings"));
        if (fundings != null && !fundings.isEmpty()) {
            Document fd = (Document) fundings.get(0).get("funder");
            if (fd != null) funderName = funderNamesMap.getOrDefault(fd.getString("uuid"), "");
            for (Document f : fundings) {
                List<Document> cols = castList(f.get("fundingCollaborators"));
                if (cols != null) for (Document col : cols) {
                    Document part = (Document) col.get("institutionalPart");
                    if (part != null) { Object val = part.get("value"); if (val instanceof Number n) totalImport += n.doubleValue(); }
                }
            }
        }

        Document period = (Document) aw.get("actualPeriod");
        String awStart = period != null ? wordFormatDate(period.get("startDate")) : "";
        String awEnd   = period != null ? wordFormatDate(period.get("endDate")) : "";

        String codiOficial = "";
        List<Document> identifiers = castList(aw.get("identifiers"));
        if (identifiers != null) {
            codiOficial = identifiers.stream()
                    .filter(id -> { Document t = (Document) id.get("type"); return t != null && "/dk/atira/pure/upm/classifiedsource/referencecode".equals(t.getString("uri")); })
                    .map(id -> id.getString("id") != null ? id.getString("id") : id.getString("value"))
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst().orElse("");
        }

        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.BOTH);
        XWPFRun tr = titlePara.createRun();
        tr.setBold(true); tr.setFontFamily("Calibri"); tr.setFontSize(9);
        tr.setText(idx + ".- " + awTitle);

        wordLabelValue(doc, "Investigador principal", ip);
        wordLabelValue(doc, "Equip investigador", String.join("; ", equip));
        wordLabelValue(doc, "Entitat finançadora", funderName);
        if (totalImport > 0) wordLabelValue(doc, "Import", String.format(Locale.GERMAN, "%,.2f €", totalImport));
        wordLabelValue(doc, "Data d'inici/fi", awStart + " → " + awEnd);
        if (!codiOficial.isBlank()) wordLabelValue(doc, "Codi oficial", codiOficial);
        doc.createParagraph();
    }

    private void wordLabelValue(XWPFDocument doc, String label, String value) {
        if (value == null || value.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.BOTH);
        XWPFRun lbl = p.createRun();
        lbl.setItalic(true); lbl.setFontFamily("Calibri"); lbl.setFontSize(9);
        lbl.setText(label + ": ");
        XWPFRun val = p.createRun();
        val.setFontFamily("Calibri"); val.setFontSize(9);
        val.setText(value);
    }

    private String wordFormatDate(Object raw) {
        if (raw == null) return "";
        if (raw instanceof Date d) {
            LocalDate ld = d.toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
            return String.format("%02d-%02d-%04d", ld.getDayOfMonth(), ld.getMonthValue(), ld.getYear());
        }
        String s = raw.toString();
        if (s.isBlank()) return "";
        try {
            String datePart = s.contains("T") ? s.substring(0, s.indexOf('T')) : s.trim();
            String[] parts = datePart.split("-");
            if (parts.length == 3) return parts[2] + "-" + parts[1] + "-" + parts[0];
        } catch (Exception ignored) {}
        return s;
    }

    private String nestedStr(Document doc, String... keys) {
        Object cur = doc;
        for (String k : keys) {
            if (!(cur instanceof Document d)) return "";
            cur = d.get(k);
            if (cur == null) return "";
        }
        return cur.toString();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> castList(Object o) {
        return o instanceof List ? (List<T>) o : null;
    }

    // ---- helpers ----

    private String countryCodeFromUri(String uri) {
        if (uri == null) return null;
        String cleaned = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int idx = cleaned.lastIndexOf('/');
        if (idx < 0) return null;
        String seg = cleaned.substring(idx + 1);
        return seg.length() == 2 ? seg.toUpperCase() : null;
    }

    private String extractPersonCountryCode(Document p) {
        String code = countryCodeFromUri(getDocPath(p, "nationality", "uri"));
        if (code != null) return code;
        code = countryCodeFromUri(getDocPath(p, "nationalityType", "uri"));
        if (code != null) return code;
        Object nt = p.get("nationalityTypes");
        if (nt instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Document d) {
            code = countryCodeFromUri(d.getString("uri"));
        }
        return code;
    }

    private String getDocPath(Document doc, String... keys) {
        Object cur = doc;
        for (String key : keys) {
            if (!(cur instanceof Document d)) return null;
            cur = d.get(key);
        }
        return cur instanceof String s ? s : null;
    }

    @GetMapping("/stats/scholarships")
    public Map<String, Object> getScholarshipStats(
            @RequestParam(required = false) String nature,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean excludeResigned) {

        List<String> availableTypes = mongoTemplate.getCollection("Awards")
                .distinct("type.term.ca_ES", new Document("workflow.step", "validated"), String.class)
                .into(new ArrayList<>())
                .stream()
                .filter(t -> t != null && (t.contains("Beques") || t.contains("Becas") || t.contains("Fellowship")))
                .collect(java.util.stream.Collectors.toList());

        if (availableTypes.isEmpty()) {
            availableTypes = Arrays.asList("Beques", "Beques Internacionals");
        }

        List<String> availableNatures = mongoTemplate.getCollection("Awards")
                .distinct("natureTypes.term.ca_ES", new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", new Document("$in", availableTypes)), String.class)
                .into(new ArrayList<>())
                .stream()
                .filter(java.util.Objects::nonNull)
                .sorted()
                .collect(java.util.stream.Collectors.toList());

        List<Document> pipeline = new ArrayList<>();

        Document matchStage = new Document("workflow.step", "validated")
                .append("type.term.ca_ES", new Document("$in", availableTypes));

        if (nature != null && !nature.isBlank() && !"all".equalsIgnoreCase(nature)) {
            matchStage.append("natureTypes.term.ca_ES", nature);
        }
        pipeline.add(new Document("$match", matchStage));

        pipeline.add(new Document("$addFields", new Document("awardDateReal",
                new Document("$convert", new Document()
                        .append("input", "$awardDate")
                        .append("to", "date")
                        .append("onError", null)
                        .append("onNull", null)))));

        pipeline.add(new Document("$addFields", new Document("startDateReal",
                new Document("$convert", new Document()
                        .append("input", "$actualPeriod.startDate")
                        .append("to", "date")
                        .append("onError", null)
                        .append("onNull", null)))));

        pipeline.add(new Document("$addFields", new Document("anyo",
                new Document("$cond", Arrays.asList(
                        new Document("$ne", Arrays.asList("$awardDateReal", null)),
                        new Document("$year", "$awardDateReal"),
                        new Document("$cond", Arrays.asList(
                                new Document("$ne", Arrays.asList("$startDateReal", null)),
                                new Document("$year", "$startDateReal"),
                                null
                        ))
                )))));

        pipeline.add(new Document("$unwind", "$awardHolders"));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Persons")
                .append("localField", "awardHolders.person.uuid")
                .append("foreignField", "uuid")
                .append("as", "holderPerson")));

        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$holderPerson")
                .append("preserveNullAndEmptyArrays", true)));

        pipeline.add(new Document("$project", new Document()
                .append("_id", 0)
                .append("uuid", "$uuid")
                .append("anyo", "$anyo")
                .append("personUuid", "$holderPerson.uuid")
                .append("gender", "$holderPerson.gender")
                .append("sex", "$holderPerson.sex")
                .append("roleUri", "$awardHolders.role.uri")
                .append("roleCa", "$awardHolders.role.term.ca_ES")
                .append("roleEs", "$awardHolders.role.term.es_ES")
                .append("statusDiscriminator", "$status.typeDiscriminator")));

        List<Document> docs = mongoTemplate.getCollection("Awards")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        Map<Integer, Set<String>> evolutionMap = new TreeMap<>();
        Map<String, Set<String>> genderMap = new LinkedHashMap<>();
        genderMap.put("Femení", new HashSet<>());
        genderMap.put("Masculí", new HashSet<>());

        Map<Integer, Map<String, Set<String>>> genderEvolutionMap = new TreeMap<>();

        for (Document d : docs) {
            Integer anyo = d.getInteger("anyo");
            if (anyo == null || anyo < 2000 || anyo > 2100) {
                continue;
            }

            String awardUuid = d.getString("uuid");
            if (awardUuid == null || awardUuid.isBlank()) {
                continue;
            }

            // Filtrem les renúncies si està habilitat (només descartem DeclinedAwardStatus)
            String status = d.getString("statusDiscriminator");
            if (excludeResigned && "DeclinedAwardStatus".equals(status)) {
                continue;
            }

            // El gràfic d'evolució compta els awards únics de la natura corresponent,
            // independentment de quin rol tinguin els titulars associats.
            evolutionMap.computeIfAbsent(anyo, k -> new HashSet<>()).add(awardUuid);

            String personUuid = d.getString("personUuid");
            if (personUuid == null || personUuid.isBlank()) {
                continue;
            }

            String roleUri = d.getString("roleUri");
            String roleCa = d.getString("roleCa");
            String roleEs = d.getString("roleEs");

            boolean matchesRole = false;
            if (role == null || role.isBlank()) {
                matchesRole = true;
            } else if ("beneficiari".equalsIgnoreCase(role)) {
                matchesRole = isBeneficiaryRole(roleUri, roleCa, roleEs);
            } else if ("investigador".equalsIgnoreCase(role)) {
                matchesRole = isInvestigatorRole(roleUri, roleCa, roleEs);
            }

            if (!matchesRole) {
                continue;
            }

            String rawGender = extractGender(d);
            String classified = classifyGender(rawGender);
            if (!"female".equals(classified) && !"male".equals(classified)) {
                continue;
            }

            String displayGender = "female".equals(classified) ? "Femení" : "Masculí";

            genderMap.computeIfAbsent(displayGender, k -> new HashSet<>()).add(awardUuid);

            Map<String, Set<String>> yearlyGender = genderEvolutionMap.computeIfAbsent(anyo, k -> {
                Map<String, Set<String>> m = new LinkedHashMap<>();
                m.put("Femení", new HashSet<>());
                m.put("Masculí", new HashSet<>());
                return m;
            });
            yearlyGender.computeIfAbsent(displayGender, k -> new HashSet<>()).add(awardUuid);
        }

        List<Map<String, Object>> evolutionList = new ArrayList<>();
        evolutionMap.forEach((year, pSet) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("year", year);
            point.put("count", (long) pSet.size());
            evolutionList.add(point);
        });

        List<Map<String, Object>> genderList = new ArrayList<>();
        genderMap.forEach((g, pSet) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("gender", g);
            point.put("count", (long) pSet.size());
            genderList.add(point);
        });

        List<Map<String, Object>> genderEvolutionList = new ArrayList<>();
        genderEvolutionMap.forEach((year, gMap) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("year", year);
            point.put("female", (long) gMap.get("Femení").size());
            point.put("male", (long) gMap.get("Masculí").size());
            genderEvolutionList.add(point);
        });

        // 1. Find all calls (FundingOpportunities) on which the selected scholarship awards depend
        List<Document> convocatoriasPipeline = new ArrayList<>();
        convocatoriasPipeline.add(new Document("$match", matchStage));
        convocatoriasPipeline.add(new Document("$lookup", new Document()
                .append("from", "Applications")
                .append("localField", "applications.uuid")
                .append("foreignField", "uuid")
                .append("as", "appDocs")));
        convocatoriasPipeline.add(new Document("$unwind", "$appDocs"));
        convocatoriasPipeline.add(new Document("$group", new Document("_id", "$appDocs.fundingOpportunity.uuid")));

        List<String> fundingOppUuids = new ArrayList<>();
        try {
            List<Document> fOppDocs = mongoTemplate.getCollection("Awards")
                    .aggregate(convocatoriasPipeline)
                    .into(new ArrayList<>());
            for (Document doc : fOppDocs) {
                String fUuid = doc.getString("_id");
                if (fUuid != null && !fUuid.isBlank()) {
                    fundingOppUuids.add(fUuid);
                }
            }
        } catch (Exception e) {
            // Silently catch
        }

        // 2. Query rejected applications from Applications collection filtering by those calls
        List<Document> appDocs = new ArrayList<>();
        if (!fundingOppUuids.isEmpty()) {
            List<Document> appPipeline = new ArrayList<>();
            appPipeline.add(new Document("$match", new Document("fundingOpportunity.uuid", new Document("$in", fundingOppUuids))));
            
            appPipeline.add(new Document("$addFields", new Document("appDateReal",
                    new Document("$convert", new Document()
                            .append("input", "$applicationDate")
                            .append("to", "date")
                            .append("onError", null)
                            .append("onNull", null)))));
                            
            appPipeline.add(new Document("$addFields", new Document("anyo",
                    new Document("$cond", Arrays.asList(
                            new Document("$ne", Arrays.asList("$appDateReal", null)),
                            new Document("$year", "$appDateReal"),
                            null
                    )))));

            appPipeline.add(new Document("$project", new Document("anyo", 1)
                    .append("replyText", new Document("$toLower", new Document("$ifNull", Arrays.asList(
                        "$funderReply.key",
                        new Document("$ifNull", Arrays.asList(
                            "$funderReply.description.en_GB",
                            new Document("$ifNull", Arrays.asList(
                                "$funderReply.description.es_ES",
                                new Document("$ifNull", Arrays.asList(
                                    "$funderReply.description.ca_ES",
                                    new Document("$ifNull", Arrays.asList(
                                        "$funderReply.en_GB",
                                        new Document("$ifNull", Arrays.asList(
                                            "$funderReply.es_ES",
                                            new Document("$ifNull", Arrays.asList(
                                                "$funderReply.ca_ES",
                                                new Document("$ifNull", Arrays.asList("$funderReply", ""))
                                            ))
                                        ))
                                    ))
                                ))
                            ))
                        ))
                    ))
                ))));

            appPipeline.add(new Document("$project", new Document("anyo", 1)
                    .append("rejected", new Document("$cond", Arrays.asList(
                        new Document("$regexMatch", new Document("input", "$replyText")
                            .append("regex", "reject|deneg|declin|desestim|rebutj|unfavorable|refused|not funded|no funded")),
                        1,
                        0
                    )))));

            appPipeline.add(new Document("$group", new Document("_id", "$anyo")
                    .append("count", new Document("$sum", "$rejected"))));

            try {
                String appColl = "Applications";
                List<String> candidates = Arrays.asList("Applications", "applications", "Application", "application");
                for (String name : candidates) {
                    if (mongoTemplate.collectionExists(name)) {
                        appColl = name;
                        break;
                    }
                }
                appDocs = mongoTemplate.getCollection(appColl)
                        .aggregate(appPipeline)
                        .into(new ArrayList<>());
            } catch (Exception e) {
                // Silently catch
            }
        }

        List<Map<String, Object>> rejectedEvolutionList = new ArrayList<>();
        for (Document doc : appDocs) {
            Integer year = doc.getInteger("_id");
            if (year != null && year >= 2000 && year <= 2100) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("year", year);
                point.put("count", ((Number) doc.get("count")).longValue());
                rejectedEvolutionList.add(point);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("natures", availableNatures);
        response.put("evolution", evolutionList);
        response.put("gender", genderList);
        response.put("genderEvolution", genderEvolutionList);
        response.put("rejectedEvolution", rejectedEvolutionList);

        return response;
    }

    @GetMapping("/stats/fellowship-call-types")
    public List<String> getFellowshipCallTypes() {
        List<String> appUuids = mongoTemplate.getCollection("Awards")
                .distinct("applications.uuid", new Document("type.term.ca_ES", new Document("$in", Arrays.asList("Beques", "Beques Internacionals")))
                        .append("workflow.step", "validated"), String.class)
                .into(new ArrayList<>());

        if (appUuids.isEmpty()) {
            return List.of();
        }

        List<String> fundingOppUuids = mongoTemplate.getCollection("Applications")
                .distinct("fundingOpportunity.uuid", new Document("uuid", new Document("$in", appUuids)), String.class)
                .into(new ArrayList<>());

        if (fundingOppUuids.isEmpty()) {
            return List.of();
        }

        List<Document> fOpps = mongoTemplate.getCollection("FundingOpportunities")
                .find(new Document("uuid", new Document("$in", fundingOppUuids)))
                .projection(new Document("type", 1))
                .into(new ArrayList<>());

        Set<String> callTypes = new java.util.TreeSet<>();
        for (Document doc : fOpps) {
            Object typeObj = doc.get("type");
            if (typeObj instanceof Document tDoc) {
                Object termObj = tDoc.get("term");
                if (termObj instanceof Document termDoc) {
                    String caVal = termDoc.getString("ca_ES");
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
                } else if (termObj instanceof List<?> list) {
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
        return new ArrayList<>(callTypes);
    }

    @GetMapping("/stats/international-call-types")
    public List<String> getInternationalCallTypes() {
        List<String> appUuids = mongoTemplate.getCollection("Awards")
                .distinct("applications.uuid", new Document("type.term.ca_ES", "Projectes d'investigació Internacionals")
                        .append("workflow.step", "validated"), String.class)
                .into(new ArrayList<>());

        if (appUuids.isEmpty()) {
            return List.of();
        }

        List<String> fundingOppUuids = mongoTemplate.getCollection("Applications")
                .distinct("fundingOpportunity.uuid", new Document("uuid", new Document("$in", appUuids)), String.class)
                .into(new ArrayList<>());

        if (fundingOppUuids.isEmpty()) {
            return List.of();
        }

        List<Document> fOpps = mongoTemplate.getCollection("FundingOpportunities")
                .find(new Document("uuid", new Document("$in", fundingOppUuids)))
                .projection(new Document("type", 1))
                .into(new ArrayList<>());

        Set<String> callTypes = new java.util.TreeSet<>();
        for (Document doc : fOpps) {
            Object typeObj = doc.get("type");
            if (typeObj instanceof Document tDoc) {
                Object termObj = tDoc.get("term");
                if (termObj instanceof Document termDoc) {
                    String caVal = termDoc.getString("ca_ES");
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
                } else if (termObj instanceof List<?> list) {
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
        return new ArrayList<>(callTypes);
    }

    @GetMapping("/stats/incorporacio-call-types")
    public List<String> getIncorporacioCallTypes() {
        List<String> appUuids = mongoTemplate.getCollection("Awards")
                .distinct("applications.uuid", new Document("type.term.ca_ES", "Incorporació de Personal")
                        .append("workflow.step", "validated"), String.class)
                .into(new ArrayList<>());

        if (appUuids.isEmpty()) {
            return List.of();
        }

        List<String> fundingOppUuids = mongoTemplate.getCollection("Applications")
                .distinct("fundingOpportunity.uuid", new Document("uuid", new Document("$in", appUuids)), String.class)
                .into(new ArrayList<>());

        if (fundingOppUuids.isEmpty()) {
            return List.of();
        }

        List<Document> fOpps = mongoTemplate.getCollection("FundingOpportunities")
                .find(new Document("uuid", new Document("$in", fundingOppUuids)))
                .projection(new Document("type", 1))
                .into(new ArrayList<>());

        Set<String> callTypes = new java.util.TreeSet<>();
        for (Document doc : fOpps) {
            Object typeObj = doc.get("type");
            if (typeObj instanceof Document tDoc) {
                Object termObj = tDoc.get("term");
                if (termObj instanceof Document termDoc) {
                    String caVal = termDoc.getString("ca_ES");
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
                } else if (termObj instanceof List<?> list) {
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
        return new ArrayList<>(callTypes);
    }

    @GetMapping("/stats/research-project-programs")
    public List<String> getResearchProjectPrograms() {
        // Step 1: get application UUIDs from Projectes i Ajuts a la Recerca awards
        List<String> appUuids = mongoTemplate.getCollection("Awards")
                .distinct("applications.uuid",
                        new Document("type.term.ca_ES", "Projectes i Ajuts a la Recerca")
                                .append("workflow.step", "validated"),
                        String.class)
                .into(new ArrayList<>());

        if (appUuids.isEmpty()) return List.of();

        // Step 2: get FundingOpportunity UUIDs from Applications
        List<String> foppUuids = mongoTemplate.getCollection("Applications")
                .distinct("fundingOpportunity.uuid",
                        new Document("uuid", new Document("$in", appUuids)),
                        String.class)
                .into(new ArrayList<>());

        if (foppUuids.isEmpty()) return List.of();

        // Step 3: get programme names from FundingOpportunities keywordGroups
        List<Document> fOpps = mongoTemplate.getCollection("FundingOpportunities")
                .find(new Document("uuid", new Document("$in", foppUuids)))
                .projection(new Document("keywordGroups", 1))
                .into(new ArrayList<>());

        Set<String> programs = new java.util.TreeSet<>();
        for (Document fopp : fOpps) {
            Object kgObj = fopp.get("keywordGroups");
            if (!(kgObj instanceof List<?> kgList)) continue;
            for (Object kgItem : kgList) {
                if (!(kgItem instanceof Document kg)) continue;
                if (!"/uab/fundingopportunities/programes".equals(kg.getString("logicalName"))) continue;
                Object kcObj = kg.get("keywordContainers");
                if (!(kcObj instanceof List<?> kcList) || kcList.isEmpty()) continue;
                for (Object kcItem : kcList) {
                    if (!(kcItem instanceof Document kc)) continue;
                    Object skObj = kc.get("structuredKeyword");
                    if (!(skObj instanceof Document sk)) continue;
                    Object termObj = sk.get("term");
                    if (!(termObj instanceof Document termDoc)) continue;
                    Object textObj = termDoc.get("text");
                    if (textObj instanceof List<?> textList) {
                        for (Object tItem : textList) {
                            if (tItem instanceof Document td && "ca_ES".equals(td.getString("locale"))) {
                                String val = td.getString("value");
                                if (val != null && !val.isBlank()) {
                                    programs.add(val);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(programs);
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