package com.example.demo.controller;

import com.example.demo.model.Award;
import com.example.demo.repository.AwardRepository;
import com.example.demo.service.AwardService;
import com.example.demo.service.ResearchOutputJournalLinkService;
import com.example.demo.award.port.AwardReportPort;
import com.example.demo.award.model.AwardCountryReportCriteria;
import com.example.demo.award.engine.AwardClassificationEngine;
import com.example.demo.award.engine.AwardFinancialEngine;

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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.text.Normalizer;

@RestController
@RequestMapping("/api/awards")
@CrossOrigin(origins = "*")
public class AwardController {
private final AwardRepository repository;
    private final AwardService service;
    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputService;
    private final com.example.demo.award.engine.AwardFinancialEngine financialEngine;
    private final AwardReportPort awardReportPort;
    private final AwardClassificationEngine classificationEngine;

    @Autowired
    public AwardController(AwardRepository repository,
                           AwardService service,
                           MongoTemplate mongoTemplate,
                           ResearchOutputJournalLinkService researchOutputService,
                           com.example.demo.award.engine.AwardFinancialEngine financialEngine,
                           AwardReportPort awardReportPort,
                           AwardClassificationEngine classificationEngine) {

        this.repository = repository;
        this.service = service;
        this.mongoTemplate = mongoTemplate;
        this.researchOutputService = researchOutputService;
        this.financialEngine = financialEngine;
        this.awardReportPort = awardReportPort;
        this.classificationEngine = classificationEngine;
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
            @RequestParam(required = false) String hasNature,
            @RequestParam(required = false) String fundingType,
            @RequestParam(required = false) String typeMatch) {

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
            List<Document> searchOr = new ArrayList<>();
            searchOr.add(new Document("title.ca_ES", new Document("$regex", cleanSearch).append("$options", "i")));
            searchOr.add(new Document("title.es_ES", new Document("$regex", cleanSearch).append("$options", "i")));
            searchOr.add(new Document("title.en_GB", new Document("$regex", cleanSearch).append("$options", "i")));
            searchOr.add(new Document("uuid", new Document("$regex", cleanSearch).append("$options", "i")));
            try {
                long pureIdNum = Long.parseLong(cleanSearch);
                searchOr.add(new Document("pureId", pureIdNum));
            } catch (NumberFormatException ignored) {}
            searchOr.add(new Document("pureId", new Document("$regex", cleanSearch).append("$options", "i")));
            searchOr.add(new Document("identifiers.value", new Document("$regex", cleanSearch).append("$options", "i")));
            searchOr.add(new Document("identifiers.id", new Document("$regex", cleanSearch).append("$options", "i")));
            andConditions.add(new Document("$or", searchOr));
        }

        if (hasNature != null && !hasNature.isBlank() && !"all".equalsIgnoreCase(hasNature)) {
            if ("yes".equalsIgnoreCase(hasNature)) {
                andConditions.add(new Document("natureTypes.0", new Document("$exists", true)));
            } else if ("no".equalsIgnoreCase(hasNature)) {
                andConditions.add(new Document("natureTypes.0", new Document("$exists", false)));
            } else {
                andConditions.add(new Document("$or", Arrays.asList(
                    new Document("natureTypes.term.ca_ES", hasNature),
                    new Document("natureTypes.term.text.value", hasNature),
                    new Document("natureTypes.term", hasNature)
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

        if (fundingType != null && !fundingType.isBlank() && !"all".equalsIgnoreCase(fundingType)) {
            basePipeline.add(new Document("$match", new Document("$or", Arrays.asList(
                new Document("fOpp.type.term.ca_ES", fundingType),
                new Document("fOpp.type.term.text.value", fundingType)
            ))));
        }

        if (typeMatch != null && !typeMatch.isBlank() && !"all".equalsIgnoreCase(typeMatch)) {
            basePipeline.add(new Document("$match", new Document("fOpp.uuid", new Document("$ne", null))));
            
            basePipeline.add(new Document("$addFields", new Document()
                .append("awardUri", new Document("$ifNull", Arrays.asList("$type.uri", "")))
                .append("foppUri", new Document("$ifNull", Arrays.asList("$fOpp.type.uri", "")))));

            basePipeline.add(new Document("$addFields", new Document()
                .append("awardCat", new Document("$switch", new Document("branches", Arrays.asList(
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "research_projects|international_award|international_educational|rtc_award"))).append("then", "PRO"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "fellowship|international_fellowships"))).append("then", "BEC"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "mobility"))).append("then", "ESF"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "staff_to_incorporate"))).append("then", "ESA"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "infraestructure"))).append("then", "INF"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "research_group|other_grants|other_international_grants"))).append("then", "GAC"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "avr_award"))).append("then", "AVR"),
                    new Document("case", new Document("$regexMatch", new Document("input", "$awardUri").append("regex", "fin_award"))).append("then", "FIN")
                )).append("default", "OTHER")))
                .append("foppCat", new Document("$let", new Document()
                    .append("vars", new Document("parts", new Document("$split", Arrays.asList("$foppUri", "/"))))
                    .append("in", new Document("$toUpper", new Document("$arrayElemAt", Arrays.asList(
                        "$$parts",
                        new Document("$subtract", Arrays.asList(new Document("$size", "$$parts"), 2))
                    ))))))));

            basePipeline.add(new Document("$addFields", new Document("isTypeMatch", new Document("$eq", Arrays.asList("$awardCat", "$foppCat")))));

            boolean targetMatch = "match".equalsIgnoreCase(typeMatch) || "yes".equalsIgnoreCase(typeMatch);
            basePipeline.add(new Document("$match", new Document("isTypeMatch", targetMatch)));
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
        if (size > 0) {
            dataPipeline.add(new Document("$skip", page * size));
            dataPipeline.add(new Document("$limit", size));
        }

        Document projection = new Document()
            .append("uuid", 1)
            .append("pureId", 1)
            .append("title", 1)
            .append("awardDate", 1)
            .append("type", 1)
            .append("natureTypes", 1)
            .append("identifiers", 1)
            .append("awardHolders", 1)
            .append("managingOrganization", 1)
            .append("fOppUuid", "$fOpp.uuid")
            .append("fOppTitle", "$fOpp.title")
            .append("fOppType", "$fOpp.type");
        dataPipeline.add(new Document("$project", projection));

        List<Document> data = new ArrayList<>();
        mongoTemplate.getCollection("Awards")
                .aggregate(dataPipeline)
                .into(data);

        // Enrich awards with parsed identifiers, IP name, org name, and suggestions
        enrichAwardDocuments(data);

        Map<String, Object> response = new HashMap<>();
        response.put("content", data);
        response.put("totalElements", totalElements);
        response.put("page", page);
        response.put("size", size);
        response.put("totalPages", size > 0 ? (int) Math.ceil((double) totalElements / size) : 1);

        return response;
    }

    /**
     * Helper to enrich Award documents with parsed identifiers, IP names, org units, and suggestions.
     */
    private void enrichAwardDocuments(List<Document> awards) {
        Map<String, Document> suggestionsMap = classificationEngine.getSuggestedNatureMap();
        for (Document award : awards) {
            // 1. Parsed Identifiers
            Map<String, Object> idMap = extractParsedIdentifiers(award);
            award.put("parsedIdentifiers", idMap);
            award.put("primaryId", idMap.get("primaryId"));
            award.put("idFenix", idMap.get("id_fenix"));
            award.put("referenceCode", idMap.get("referenceCode"));
            award.put("agaurCode", idMap.get("agaurCode"));
            award.put("agaurCode2", idMap.get("agaurCode2"));

            // 2. IP Name
            Object holdersObj = award.get("awardHolders");
            if (holdersObj instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Document hDoc) {
                    Object nameObj = hDoc.get("name");
                    if (nameObj instanceof Document nDoc) {
                        String firstN = nDoc.getString("firstName");
                        String lastN = nDoc.getString("lastName");
                        String full = ((firstN != null ? firstN : "") + " " + (lastN != null ? lastN : "")).trim();
                        if (!full.isBlank()) award.put("ipName", full);
                    }
                }
            }

            // 3. Managing Organization Name
            Object orgObj = award.get("managingOrganization");
            if (orgObj instanceof Document oDoc) {
                Object nameObj = oDoc.get("name");
                String orgName = classificationEngine.extractCaEsFromType(nameObj);
                if (orgName == null && nameObj instanceof String s) orgName = s;
                if (orgName != null && !orgName.isBlank()) award.put("managingOrgName", orgName);
            }

            // 4. Year extraction
            Object dateObj = award.get("awardDate");
            if (dateObj instanceof Date d) {
                LocalDate ld = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                award.put("awardYear", ld.getYear());
            } else if (dateObj instanceof Document dDoc) {
                Integer yr = dDoc.getInteger("year");
                if (yr != null) award.put("awardYear", yr);
            }

            // 5. Suggestions for awards without nature
            Object natureObj = award.get("natureTypes");
            boolean hasAnyNature = false;
            if (natureObj instanceof List<?> list && !list.isEmpty()) {
                hasAnyNature = true;
            }
            if (!hasAnyNature) {
                Document suggested = null;
                String fOppType = classificationEngine.extractCaEsFromType(award.get("fOppType"));
                if (fOppType != null) {
                    suggested = suggestionsMap.get(fOppType);
                }
                
                if (suggested == null && award.get("fOppType") instanceof Document fOppTypeDoc) {
                    String callTypeUri = fOppTypeDoc.getString("uri");
                    suggested = classificationEngine.findNatureByUriSimilarity(callTypeUri);
                }
                
                if (suggested != null) {
                    award.put("suggestedNature", suggested);
                }
            }
        }
    }

    private static Map<String, Object> extractParsedIdentifiers(Document award) {
        Map<String, Object> res = new LinkedHashMap<>();
        Object idListObj = award.get("identifiers");
        if (idListObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Document idDoc) {
                    String disc = idDoc.getString("typeDiscriminator");
                    String val = idDoc.getString("value");
                    if (val == null) {
                        Object vObj = idDoc.get("value");
                        if (vObj != null) val = String.valueOf(vObj);
                    }
                    String idStr = idDoc.getString("id");
                    if (idStr == null) {
                        Object iObj = idDoc.get("id");
                        if (iObj != null) idStr = String.valueOf(iObj);
                    }
                    
                    if ("PrimaryId".equalsIgnoreCase(disc)) {
                        res.put("primaryId", val != null ? val : idStr);
                    }
                    
                    Object typeObj = idDoc.get("type");
                    String uri = "";
                    if (typeObj instanceof Document typeDoc) {
                        uri = typeDoc.getString("uri");
                    }
                    if (uri != null) {
                        String lowerUri = uri.toLowerCase();
                        if (lowerUri.contains("id_fenix")) {
                            res.put("id_fenix", idStr != null ? idStr : val);
                        } else if (lowerUri.contains("reference") || lowerUri.contains("referencia") || lowerUri.contains("ref_code")) {
                            res.put("referenceCode", idStr != null ? idStr : val);
                        } else if (lowerUri.contains("agaur_code_2")) {
                            res.put("agaurCode2", idStr != null ? idStr : val);
                        } else if (lowerUri.contains("agaur_code")) {
                            res.put("agaurCode", idStr != null ? idStr : val);
                        }
                    }
                    if (disc != null && disc.toLowerCase().contains("reference") && !res.containsKey("referenceCode")) {
                        res.put("referenceCode", idStr != null ? idStr : val);
                    }
                }
            }
        }
        return res;
    }

    /**
     * Endpoint to fetch all unassigned nature awards (without pagination limit) for instant Excel-like filtering.
     */
    @GetMapping("/all-unassigned-nature")
    public Map<String, Object> getAllUnassignedNatureAwards() {
        return getDetailedList(0, 0, null, "all", "no", null, "all");
    }

    @PostMapping("/{uuid}/nature")
    public Map<String, Object> updateAwardNature(
            @PathVariable String uuid,
            @RequestParam(required = false, defaultValue = "test") String env,
            @RequestBody Document natureDoc) {
        return classificationEngine.updateAwardNature(uuid, env, natureDoc);
    }

    @PostMapping("/batch-update-nature")
    public Map<String, Object> batchUpdateNature(
            @RequestParam(required = false) String fundingType,
            @RequestParam(required = false, defaultValue = "test") String env,
            @RequestBody Document natureDoc) {
        return classificationEngine.batchUpdateNature(fundingType, env, natureDoc);
    }

    @PostMapping("/batch-update-nature-by-uuids")
    public Map<String, Object> batchUpdateNatureByUuids(
            @RequestParam(required = false, defaultValue = "test") String env,
            @RequestBody Map<String, Object> payload) {
        return classificationEngine.batchUpdateNatureByUuids(env, payload);
    }

    @PostMapping("/batch-apply-suggested-by-uuids")
    public Map<String, Object> batchApplySuggestedByUuids(
            @RequestParam(required = false, defaultValue = "test") String env,
            @RequestBody List<String> uuids) {
        return classificationEngine.batchApplySuggestedByUuids(env, uuids);
    }

    @GetMapping("/proposed-relations")
    public List<Map<String, Object>> getProposedRelations() {
        return classificationEngine.getProposedRelations();
    }

    @PostMapping("/batch-update-all-proposals")
    public Map<String, Object> batchUpdateAllProposals(
            @RequestParam(defaultValue = "test") String env) {
        return classificationEngine.batchUpdateAllProposals(env);
    }

    @GetMapping("/natures")
    public List<Document> getAvailableNatures() {
        return classificationEngine.getAvailableNatures();
    }

    @GetMapping("/nature-hierarchy")
    public List<Map<String, Object>> getNatureHierarchy() {
        return classificationEngine.getNatureHierarchy();
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
        return financialEngine.getTipusPerCategoria();
    }

    @GetMapping("/stats/awards-by-amount")
    public List<Document> getAwardsByAmount() {
        return financialEngine.getAwardsByAmount();
    }

    @GetMapping("/stats/import-per-tipus-anio")
    public List<Document> getImportPerTipusAnio() {
        return financialEngine.getImportPerTipusAnio();
    }

    @GetMapping("/stats/total")
    public Map<String, Object> getTotalStats() {
        return financialEngine.getTotalStats();
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

        return financialEngine.getPowerTable(com.example.demo.award.model.PowerTableQueryCriteria.of(desde, hasta, modoAnio, collaboratorUuid));
    }

    @GetMapping("/stats/map-convenis")
    public List<Document> getMapConvenis(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String collaboratorUuid) {

        return financialEngine.getMapConvenis(desde, hasta, collaboratorUuid);
    }

    @GetMapping("/stats/xarxes-plataformes")
    public int getXarxesPlataformesCount(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String collaboratorUuid) {

        return financialEngine.getXarxesPlataformesCount(desde, hasta, collaboratorUuid);
    }


    @GetMapping("/stats/llista-ajuts-institut")
    public List<Document> getLlistaAjutsInstitut(
            @RequestParam String collaboratorUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {

        return financialEngine.getAwardsLlistaInstitut(collaboratorUuid, desde, hasta);
    }

    @GetMapping("/stats/ips-institut")
    public List<Document> getIpsInstitut(
            @RequestParam String collaboratorUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return financialEngine.getIpsInstitut(collaboratorUuid, desde, hasta);
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

        return financialEngine.getProyectosPorAnio(
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
        return financialEngine.getConcessionsByProgram();
    }

    @GetMapping("/stats/concessions-awards")
    public List<Document> getConcessionsAwards() {
        return financialEngine.getConcessionsAwards();
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
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.setHeader("Content-Disposition", "attachment; filename=\"informe-pais-" + code + ".docx\"");

        AwardCountryReportCriteria criteria = new AwardCountryReportCriteria(
                countryCode, startDate, endDate, projectes, convenis, beques, xarxes, tesis, articles, llibres, capitols
        );
        awardReportPort.generateCountryWordReport(criteria, response.getOutputStream());
    }

    private String countryCodeFromUri(String uri) {
        if (uri == null) return null;
        int idx = uri.lastIndexOf('/');
        return (idx >= 0 && idx < uri.length() - 1) ? uri.substring(idx + 1).toUpperCase() : null;
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

    private LocalDate parseAnyDate(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Date d) {
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                if (s.length() >= 10) {
                    return LocalDate.parse(s.substring(0, 10));
                }
            } catch (Exception e) {}
        }
        return null;
    }

    @GetMapping("/stats/scholarship-employment-nature")
    public Map<String, Object> getScholarshipEmploymentNatureStats(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String nature,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) String search,
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

        Document matchStage = new Document("workflow.step", "validated")
                .append("type.term.ca_ES", new Document("$in", availableTypes));

        if (nature != null && !nature.isBlank() && !"all".equalsIgnoreCase(nature)) {
            matchStage.append("natureTypes.term.ca_ES", nature);
        }

        List<Document> awards = mongoTemplate.getCollection("Awards")
                .find(matchStage)
                .projection(new Document("uuid", 1)
                        .append("natureTypes", 1)
                        .append("awardHolders", 1)
                        .append("status", 1)
                        .append("awardDate", 1)
                        .append("actualPeriod", 1)
                        .append("expectedPeriod", 1))
                .into(new ArrayList<>());

        Set<String> personUuids = new HashSet<>();
        for (Document award : awards) {
            if (excludeResigned) {
                Document statusDoc = (Document) award.get("status");
                if (statusDoc != null && "DeclinedAwardStatus".equals(statusDoc.getString("typeDiscriminator"))) {
                    continue;
                }
            }
            List<Document> holders = (List<Document>) award.get("awardHolders");
            if (holders != null) {
                for (Document h : holders) {
                    Document personRef = (Document) h.get("person");
                    if (personRef != null && personRef.getString("uuid") != null) {
                        personUuids.add(personRef.getString("uuid"));
                    }
                }
            }
        }

        Map<String, Document> personMap = new HashMap<>();
        if (!personUuids.isEmpty()) {
            List<Document> personDocs = mongoTemplate.getCollection("Persons")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(personUuids))))
                    .projection(new Document("uuid", 1)
                            .append("employmentType", 1)
                            .append("staffOrganizationAssociations", 1))
                    .into(new ArrayList<>());

            for (Document pDoc : personDocs) {
                String pUuid = pDoc.getString("uuid");
                if (pUuid != null) {
                    personMap.put(pUuid, pDoc);
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> matrixCounts = new HashMap<>();
        Set<String> distinctNatures = new java.util.TreeSet<>();
        Set<String> distinctEmpTypes = new java.util.TreeSet<>();
        Set<String> matchedAwardUuids = new HashSet<>();
        Set<String> matchedPersonUuids = new HashSet<>();

        int totalLinks = 0;

        for (Document award : awards) {
            if (excludeResigned) {
                Document statusDoc = (Document) award.get("status");
                if (statusDoc != null && "DeclinedAwardStatus".equals(statusDoc.getString("typeDiscriminator"))) {
                    continue;
                }
            }

            Document actPer = (Document) award.get("actualPeriod");
            Document expPer = (Document) award.get("expectedPeriod");
            LocalDate awardDate = parseAnyDate(award.get("awardDate"));

            LocalDate aStart = parseAnyDate(actPer != null ? actPer.get("startDate") : (expPer != null ? expPer.get("startDate") : null));
            if (aStart == null) aStart = awardDate;
            LocalDate aEnd = parseAnyDate(actPer != null ? actPer.get("endDate") : (expPer != null ? expPer.get("endDate") : null));

            List<String> natureNames = new ArrayList<>();
            List<Document> natureList = (List<Document>) award.get("natureTypes");
            if (natureList != null && !natureList.isEmpty()) {
                for (Document nDoc : natureList) {
                    Document termDoc = (Document) nDoc.get("term");
                    if (termDoc != null && termDoc.getString("ca_ES") != null) {
                        natureNames.add(termDoc.getString("ca_ES"));
                    }
                }
            }
            if (natureNames.isEmpty()) {
                natureNames.add("Sense Natura");
            }

            List<Document> holders = (List<Document>) award.get("awardHolders");
            if (holders == null || holders.isEmpty()) {
                holders = Collections.singletonList(new Document());
            }

            for (Document h : holders) {
                Document roleDoc = (Document) h.get("role");
                String roleUri = roleDoc != null ? roleDoc.getString("uri") : "";
                String roleCa = roleDoc != null && roleDoc.get("term") instanceof Document tDoc ? tDoc.getString("ca_ES") : "";
                String roleEs = roleDoc != null && roleDoc.get("term") instanceof Document tDoc ? tDoc.getString("es_ES") : "";

                boolean matchesRole = false;
                if (role == null || role.isBlank() || "all".equalsIgnoreCase(role)) {
                    matchesRole = true;
                } else if ("beneficiari".equalsIgnoreCase(role) || "strict_beneficiari".equalsIgnoreCase(role)) {
                    matchesRole = isStrictBeneficiaryRole(roleUri, roleCa, roleEs);
                } else if ("candidat".equalsIgnoreCase(role)) {
                    matchesRole = isCandidateRole(roleUri, roleCa, roleEs);
                } else if ("beneficiari_and_candidate".equalsIgnoreCase(role)) {
                    matchesRole = isBeneficiaryRole(roleUri, roleCa, roleEs);
                } else if ("pi".equalsIgnoreCase(role) || "investigador".equalsIgnoreCase(role)) {
                    matchesRole = isInvestigatorRole(roleUri, roleCa, roleEs);
                }

                if (!matchesRole) {
                    continue;
                }

                Document personRef = (Document) h.get("person");
                String pUuid = personRef != null ? personRef.getString("uuid") : null;
                List<String> holderEmpTypes = new ArrayList<>();

                Document pDoc = pUuid != null ? personMap.get(pUuid) : null;
                if (pDoc != null) {
                    List<Document> assocs = (List<Document>) pDoc.get("staffOrganizationAssociations");
                    if (assocs != null) {
                        for (Document assoc : assocs) {
                            Document empTypeDoc = (Document) assoc.get("employmentType");
                            if (empTypeDoc != null && empTypeDoc.get("term") instanceof Document termDoc) {
                                String termCa = termDoc.getString("ca_ES");
                                if (termCa != null && !termCa.isBlank()) {
                                    Document aper = (Document) assoc.get("period");
                                    LocalDate pStart = aper != null ? parseAnyDate(aper.get("startDate")) : null;
                                    LocalDate pEnd = aper != null ? parseAnyDate(aper.get("endDate")) : null;

                                    boolean overlaps = true;
                                    if (pStart != null && aEnd != null && pStart.isAfter(aEnd)) {
                                        overlaps = false;
                                    }
                                    if (pEnd != null && aStart != null && pEnd.isBefore(aStart)) {
                                        overlaps = false;
                                    }

                                    if (overlaps && !holderEmpTypes.contains(termCa)) {
                                        holderEmpTypes.add(termCa);
                                    }
                                }
                            }
                        }
                    }
                }

                if (holderEmpTypes.isEmpty()) {
                    holderEmpTypes.add("Sense Employment Type en el període");
                }

                String displayRole = roleCa != null && !roleCa.isBlank() ? roleCa : "Sense Rol";

                matchedAwardUuids.add(award.getString("uuid"));
                if (pUuid != null) {
                    matchedPersonUuids.add(pUuid);
                }

                for (String nName : natureNames) {
                    distinctNatures.add(nName);
                    for (String eName : holderEmpTypes) {
                        if (employmentType != null && !employmentType.isBlank() && !"all".equalsIgnoreCase(employmentType)) {
                            if (!eName.equalsIgnoreCase(employmentType)) {
                                continue;
                            }
                        }

                        if (search != null && !search.isBlank()) {
                            String cleanSearch = search.trim().toLowerCase();
                            boolean matchesSearch = nName.toLowerCase().contains(cleanSearch) ||
                                                    eName.toLowerCase().contains(cleanSearch) ||
                                                    displayRole.toLowerCase().contains(cleanSearch);
                            if (!matchesSearch) {
                                continue;
                            }
                        }

                        distinctEmpTypes.add(eName);

                        String matrixKey = nName + "|||" + eName + "|||" + displayRole;
                        matrixCounts.put(matrixKey, matrixCounts.getOrDefault(matrixKey, 0) + 1);
                        totalLinks++;
                    }
                }
            }
        }

        for (Map.Entry<String, Integer> entry : matrixCounts.entrySet()) {
            String[] parts = entry.getKey().split("\\|\\|\\|");
            String nName = parts[0];
            String eName = parts[1];
            String rName = parts.length > 2 ? parts[2] : "Sense Rol";
            int count = entry.getValue();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nature", nName);
            row.put("employmentType", eName);
            row.put("role", rName);
            row.put("count", count);
            row.put("percentage", totalLinks > 0 ? Math.round((double) count * 1000.0 / totalLinks) / 10.0 : 0.0);
            rows.add(row);
        }

        rows.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalAwards", matchedAwardUuids.size());
        result.put("totalPersons", matchedPersonUuids.size());
        result.put("totalLinks", totalLinks);
        result.put("natures", new ArrayList<>(distinctNatures));
        result.put("employmentTypes", new ArrayList<>(distinctEmpTypes));
        result.put("rows", rows);

        return result;
    }

    @GetMapping("/stats/scholarship-employment-nature-details")
    public List<Map<String, Object>> getScholarshipEmploymentNatureDetails(
            @RequestParam(required = false) String nature,
            @RequestParam(required = false) String employmentType,
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

        Document matchStage = new Document("workflow.step", "validated")
                .append("type.term.ca_ES", new Document("$in", availableTypes));

        if (nature != null && !nature.isBlank() && !"all".equalsIgnoreCase(nature)) {
            matchStage.append("natureTypes.term.ca_ES", nature);
        }

        List<Document> awards = mongoTemplate.getCollection("Awards")
                .find(matchStage)
                .projection(new Document("uuid", 1)
                        .append("pureId", 1)
                        .append("title", 1)
                        .append("natureTypes", 1)
                        .append("awardHolders", 1)
                        .append("status", 1)
                        .append("awardDate", 1)
                        .append("actualPeriod", 1)
                        .append("expectedPeriod", 1)
                        .append("portalUrl", 1))
                .into(new ArrayList<>());

        Set<String> personUuids = new HashSet<>();
        for (Document award : awards) {
            if (excludeResigned) {
                Document statusDoc = (Document) award.get("status");
                if (statusDoc != null && "DeclinedAwardStatus".equals(statusDoc.getString("typeDiscriminator"))) {
                    continue;
                }
            }
            List<Document> holders = (List<Document>) award.get("awardHolders");
            if (holders != null) {
                for (Document h : holders) {
                    Document personRef = (Document) h.get("person");
                    if (personRef != null && personRef.getString("uuid") != null) {
                        personUuids.add(personRef.getString("uuid"));
                    }
                }
            }
        }

        Map<String, Document> personMap = new HashMap<>();
        if (!personUuids.isEmpty()) {
            List<Document> personDocs = mongoTemplate.getCollection("Persons")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(personUuids))))
                    .projection(new Document("uuid", 1)
                            .append("pureId", 1)
                            .append("name", 1)
                            .append("employmentType", 1)
                            .append("staffOrganizationAssociations", 1))
                    .into(new ArrayList<>());

            for (Document pDoc : personDocs) {
                String pUuid = pDoc.getString("uuid");
                if (pUuid != null) {
                    personMap.put(pUuid, pDoc);
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (Document award : awards) {
            if (excludeResigned) {
                Document statusDoc = (Document) award.get("status");
                if (statusDoc != null && "DeclinedAwardStatus".equals(statusDoc.getString("typeDiscriminator"))) {
                    continue;
                }
            }

            Document actPer = (Document) award.get("actualPeriod");
            Document expPer = (Document) award.get("expectedPeriod");
            LocalDate awardDate = parseAnyDate(award.get("awardDate"));

            LocalDate aStart = parseAnyDate(actPer != null ? actPer.get("startDate") : (expPer != null ? expPer.get("startDate") : null));
            if (aStart == null) aStart = awardDate;
            LocalDate aEnd = parseAnyDate(actPer != null ? actPer.get("endDate") : (expPer != null ? expPer.get("endDate") : null));

            String titleText = "Sense Títol";
            Object titleObj = award.get("title");
            if (titleObj instanceof Document tDoc) {
                String ca = tDoc.getString("ca_ES");
                String es = tDoc.getString("es_ES");
                String en = tDoc.getString("en_GB");
                if (ca != null && !ca.isBlank()) titleText = ca;
                else if (es != null && !es.isBlank()) titleText = es;
                else if (en != null && !en.isBlank()) titleText = en;
            } else if (titleObj instanceof String s && !s.isBlank()) {
                titleText = s;
            }

            List<Document> holders = (List<Document>) award.get("awardHolders");
            if (holders == null || holders.isEmpty()) {
                holders = Collections.singletonList(new Document());
            }

            List<Map<String, Object>> matchedHoldersList = new ArrayList<>();

            for (Document h : holders) {
                Document roleDoc = (Document) h.get("role");
                String roleUri = roleDoc != null ? roleDoc.getString("uri") : "";
                String roleCa = roleDoc != null && roleDoc.get("term") instanceof Document tDoc ? tDoc.getString("ca_ES") : "";
                String roleEs = roleDoc != null && roleDoc.get("term") instanceof Document tDoc ? tDoc.getString("es_ES") : "";

                boolean matchesRole = false;
                if (role == null || role.isBlank() || "all".equalsIgnoreCase(role)) {
                    matchesRole = true;
                } else if ("beneficiari".equalsIgnoreCase(role) || "strict_beneficiari".equalsIgnoreCase(role)) {
                    matchesRole = isStrictBeneficiaryRole(roleUri, roleCa, roleEs);
                } else if ("candidat".equalsIgnoreCase(role)) {
                    matchesRole = isCandidateRole(roleUri, roleCa, roleEs);
                } else if ("pi".equalsIgnoreCase(role) || "investigador".equalsIgnoreCase(role)) {
                    matchesRole = isInvestigatorRole(roleUri, roleCa, roleEs);
                }

                if (!matchesRole) {
                    continue;
                }

                Document personRef = (Document) h.get("person");
                String pUuid = personRef != null ? personRef.getString("uuid") : null;
                List<String> holderEmpTypes = new ArrayList<>();
                List<Map<String, Object>> holderEmploymentsDetails = new ArrayList<>();
                String fullName = "Desconegut";

                Document pDoc = pUuid != null ? personMap.get(pUuid) : null;
                if (pDoc != null) {
                    Object nameObj = pDoc.get("name");
                    if (nameObj instanceof Document nDoc) {
                        String first = nDoc.getString("firstName");
                        String last = nDoc.getString("lastName");
                        if (last != null && first != null) fullName = last + ", " + first;
                        else if (last != null) fullName = last;
                        else if (first != null) fullName = first;
                    }

                    List<Document> assocs = (List<Document>) pDoc.get("staffOrganizationAssociations");
                    if (assocs != null) {
                        for (Document assoc : assocs) {
                            Document empTypeDoc = (Document) assoc.get("employmentType");
                            if (empTypeDoc != null && empTypeDoc.get("term") instanceof Document termDoc) {
                                String termCa = termDoc.getString("ca_ES");
                                if (termCa != null && !termCa.isBlank()) {
                                    Document aper = (Document) assoc.get("period");
                                    LocalDate pStart = aper != null ? parseAnyDate(aper.get("startDate")) : null;
                                    LocalDate pEnd = aper != null ? parseAnyDate(aper.get("endDate")) : null;

                                    boolean overlaps = true;
                                    if (pStart != null && aEnd != null && pStart.isAfter(aEnd)) {
                                        overlaps = false;
                                    }
                                    if (pEnd != null && aStart != null && pEnd.isBefore(aStart)) {
                                        overlaps = false;
                                    }

                                    if (overlaps) {
                                        if (!holderEmpTypes.contains(termCa)) {
                                            holderEmpTypes.add(termCa);
                                        }
                                        Map<String, Object> empDetail = new LinkedHashMap<>();
                                        empDetail.put("type", termCa);
                                        empDetail.put("startDate", pStart != null ? pStart.toString() : null);
                                        empDetail.put("endDate", pEnd != null ? pEnd.toString() : null);
                                        holderEmploymentsDetails.add(empDetail);
                                    }
                                }
                            }
                        }
                    }
                }

                if (holderEmpTypes.isEmpty()) {
                    holderEmpTypes.add("Sense Employment Type en el període");
                    Map<String, Object> empDetail = new LinkedHashMap<>();
                    empDetail.put("type", "Sense Employment Type en el període");
                    empDetail.put("startDate", null);
                    empDetail.put("endDate", null);
                    holderEmploymentsDetails.add(empDetail);
                }

                // Sort employments from oldest to newest (by startDate ascending)
                holderEmploymentsDetails.sort((e1, e2) -> {
                    String s1 = (String) e1.get("startDate");
                    String s2 = (String) e2.get("startDate");
                    if (s1 == null && s2 == null) return 0;
                    if (s1 == null) return -1;
                    if (s2 == null) return 1;
                    return s1.compareTo(s2);
                });

                boolean empTypeMatches = false;
                if (employmentType == null || employmentType.isBlank() || "all".equalsIgnoreCase(employmentType)) {
                    empTypeMatches = true;
                } else {
                    for (String et : holderEmpTypes) {
                        if (et.equalsIgnoreCase(employmentType)) {
                            empTypeMatches = true;
                            break;
                        }
                    }
                }

                if (empTypeMatches) {
                    Object pPureIdObj = pDoc != null ? pDoc.get("pureId") : null;
                    String pPureIdStr = pPureIdObj != null ? pPureIdObj.toString() : null;
                    String personEgretaUrl = pPureIdStr != null && !pPureIdStr.isBlank() ?
                            "https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=" + pPureIdStr : null;

                    Map<String, Object> holderMap = new LinkedHashMap<>();
                    holderMap.put("personUuid", pUuid);
                    holderMap.put("personPureId", pPureIdStr);
                    holderMap.put("personEgretaUrl", personEgretaUrl);
                    holderMap.put("name", fullName);
                    holderMap.put("role", roleCa != null && !roleCa.isBlank() ? roleCa : "Sense Rol");
                    holderMap.put("employmentTypes", holderEmpTypes);
                    holderMap.put("employments", holderEmploymentsDetails);
                    matchedHoldersList.add(holderMap);
                }
            }

            if (!matchedHoldersList.isEmpty()) {
                String uuid = award.getString("uuid");
                String portalUrl = award.getString("portalUrl");
                if (portalUrl == null || portalUrl.isBlank()) {
                    portalUrl = "https://portalrecerca.uab.cat/en/projects/" + uuid;
                }

                Object pIdObj = award.get("pureId");
                String pIdStr = pIdObj != null ? pIdObj.toString() : null;
                String egretaUrl = pIdStr != null && !pIdStr.isBlank() ?
                        "https://egreta.uab.cat/admin/editor/dk/atira/pure/modules/unifiedprojectmodel/external/model/award/editor/awardeditor.xhtml?scheme=&type=&id=" + pIdStr :
                        "https://egreta.uab.cat/ws/api/awards/" + uuid;

                Map<String, Object> awardDetail = new LinkedHashMap<>();
                awardDetail.put("uuid", uuid);
                awardDetail.put("pureId", award.get("pureId"));
                awardDetail.put("title", titleText);
                awardDetail.put("portalUrl", portalUrl);
                awardDetail.put("egretaUrl", egretaUrl);
                awardDetail.put("startDate", aStart != null ? aStart.toString() : null);
                awardDetail.put("endDate", aEnd != null ? aEnd.toString() : null);
                awardDetail.put("holders", matchedHoldersList);

                result.add(awardDetail);
            }
        }

        return result;
    }

    @GetMapping("/stats/fellowship-call-types")
    public List<String> getFellowshipCallTypes() {
        return financialEngine.getFellowshipCallTypes();
    }

    @GetMapping("/stats/international-call-types")
    public List<String> getInternationalCallTypes() {
        return financialEngine.getInternationalCallTypes();
    }

    @GetMapping("/stats/incorporacio-call-types")
    public List<String> getIncorporacioCallTypes() {
        return financialEngine.getIncorporacioCallTypes();
    }

    @GetMapping("/stats/research-project-programs")
    public List<String> getResearchProjectPrograms() {
        return financialEngine.getResearchProjectPrograms();
    }

    @GetMapping("/stats/nature-funding-relation")
    public List<Map<String, Object>> getNatureFundingRelation() {
        return classificationEngine.getNatureFundingRelation();
    }

    /**
     * Returns a list of { tipologia, costCode, awardCount, convocatories[] } grouped by the
     * first 2 characters of budgets[].costCode.
     * Each convocatoria entry includes: { fOppUuid, fOppTitle, fOppType, count }.
     */
    @GetMapping("/stats/cost-center-convocatorias")
    public List<Map<String, Object>> getCostCenterConvocatorias() {
        return financialEngine.getCostCenterConvocatorias();
    }

    @GetMapping("/stats/pk-awards")
    public List<Document> getPkAwardsStats() {
        return financialEngine.getPkAwardsStats();
    }

    @GetMapping("/stats/ea-awards")
    public List<Document> getEaAwardsStats() {
        return financialEngine.getEaAwardsStats();
    }

    @GetMapping("/stats/po-awards")
    public List<Document> getPoAwardsStats() {
        return financialEngine.getPoAwardsStats();
    }

    @GetMapping("/stats/prefixed-awards")
    public List<Document> getPrefixedAwardsStats() {
        return financialEngine.getAllPrefixedAwardsStats();
    }

    @GetMapping("/stats/resigned-awards")
    public Map<String, Object> getResignedAwards(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String tipus,
            @RequestParam(required = false) String reasonFilter,
            @RequestParam(required = false) String statusType,
            @RequestParam(required = false) String year,
            @RequestParam(defaultValue = "date") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "false") boolean exportAll) {

        com.example.demo.award.model.ResignedAwardsQueryCriteria criteria =
                com.example.demo.award.model.ResignedAwardsQueryCriteria.builder()
                        .page(page)
                        .size(size)
                        .search(search)
                        .categoria(categoria)
                        .tipus(tipus)
                        .reasonFilter(reasonFilter)
                        .statusType(statusType)
                        .year(year)
                        .sortBy(sortBy)
                        .sortOrder(sortOrder)
                        .exportAll(exportAll)
                        .build();

        return financialEngine.getResignedAwards(criteria);
    }

    @GetMapping("/stats/reason-comparison")
    public Map<String, Object> getReasonComparison(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String filterMode,
            @RequestParam(defaultValue = "false") boolean exportAll) {

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

        List<Document> rawDocs = mongoTemplate.getCollection("Awards")
                .find(matchQuery)
                .into(new ArrayList<>());

        List<Map<String, Object>> processedList = new ArrayList<>();

        int countHasReason = 0;
        int countDiscrepancies = 0;
        int countRenuncia = 0;

        for (Document doc : rawDocs) {
            Map<String, Object> item = new HashMap<>();
            item.put("uuid", doc.getString("uuid"));
            item.put("pureId", doc.get("pureId"));

            // Title
            Document titleDoc = (Document) doc.get("title");
            String title = "";
            if (titleDoc != null) {
                title = titleDoc.getString("ca_ES");
                if (title == null || title.isBlank()) title = titleDoc.getString("es_ES");
                if (title == null || title.isBlank()) title = titleDoc.getString("en_GB");
            }
            item.put("title", title != null ? title : "");

            // Status & Reason
            Document statusDoc = (Document) doc.get("status");
            String docStatusType = "";
            String statusDate = "";
            String reason = "";
            if (statusDoc != null) {
                docStatusType = statusDoc.getString("typeDiscriminator");
                statusDate = statusDoc.getString("date");
                reason = statusDoc.getString("reason");
            }
            item.put("statusType", docStatusType != null ? docStatusType : "");
            item.put("statusDate", statusDate != null ? statusDate : "");
            item.put("statusReason", reason != null ? reason : "");

            boolean hasReason = reason != null && !reason.isBlank();
            if (hasReason) countHasReason++;

            // Extract keywordGroups details (estat_ajut, incidencies)
            String estatAjut = "";
            String incidencies = "";

            List<?> kwGroups = (List<?>) doc.get("keywordGroups");
            if (kwGroups != null) {
                for (Object kwObj : kwGroups) {
                    if (kwObj instanceof Document kwDoc) {
                        String logName = kwDoc.getString("logicalName");
                        List<?> containers = (List<?>) kwDoc.get("keywordContainers");
                        if (containers != null) {
                            for (Object cObj : containers) {
                                if (cObj instanceof Document cDoc) {
                                    Document skDoc = (Document) cDoc.get("structuredKeyword");
                                    String skTerm = "";
                                    if (skDoc != null) {
                                        Document tDoc = (Document) skDoc.get("term");
                                        if (tDoc != null) {
                                            skTerm = tDoc.getString("ca_ES");
                                            if (skTerm == null) skTerm = tDoc.getString("es_ES");
                                        }
                                    }
                                    List<String> freeList = new ArrayList<>();
                                    List<?> fkList = (List<?>) cDoc.get("freeKeywords");
                                    if (fkList != null) {
                                        for (Object fkObj : fkList) {
                                            if (fkObj instanceof Document fkDoc) {
                                                List<?> fWords = (List<?>) fkDoc.get("freeKeywords");
                                                if (fWords != null) {
                                                    for (Object w : fWords) {
                                                        if (w != null) freeList.add(w.toString());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    String joinedFree = String.join(", ", freeList);

                                    if ("/uab/awards/estat_ajut".equals(logName)) {
                                        if (!skTerm.isBlank()) estatAjut = skTerm;
                                    } else if ("/uab/awards/incidencies".equals(logName)) {
                                        String entry = (skTerm.isBlank() ? "" : "[" + skTerm + "] ") + joinedFree;
                                        incidencies = incidencies.isBlank() ? entry : incidencies + " | " + entry;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item.put("estatAjut", estatAjut);
            item.put("incidencies", incidencies);

            boolean isRenuncia = (reason != null && reason.toLowerCase().matches(".*(renunc|renuncia|resign|declin).*"))
                    || "Renunciada".equalsIgnoreCase(estatAjut)
                    || "DeclinedAwardStatus".equalsIgnoreCase(docStatusType);
            if (isRenuncia) countRenuncia++;

            boolean isDiscrepancy = false;
            if ("DeclinedAwardStatus".equalsIgnoreCase(docStatusType) && "Vigent".equalsIgnoreCase(estatAjut)) {
                isDiscrepancy = true;
            }
            if (isDiscrepancy) countDiscrepancies++;

            boolean isTerminatedWithReason = hasReason && ("TerminatedAwardStatus".equalsIgnoreCase(docStatusType) || "CurtailedAwardStatus".equalsIgnoreCase(docStatusType));

            item.put("isRenuncia", isRenuncia);
            item.put("isDiscrepancy", isDiscrepancy);
            item.put("isTerminatedWithReason", isTerminatedWithReason);

            if ("terminated_with_reason".equalsIgnoreCase(filterMode) && !isTerminatedWithReason) continue;
            if ("discrepancy".equalsIgnoreCase(filterMode) && !isDiscrepancy) continue;
            if ("has_reason".equalsIgnoreCase(filterMode) && !hasReason) continue;
            if ("renuncia".equalsIgnoreCase(filterMode) && !isRenuncia) continue;

            processedList.add(item);
        }

        processedList.sort((a, b) -> {
            String aDate = (String) a.getOrDefault("statusDate", "");
            String bDate = (String) b.getOrDefault("statusDate", "");
            return bDate.compareTo(aDate);
        });

        int totalElements = processedList.size();
        List<Map<String, Object>> pagedContent;

        if (exportAll) {
            pagedContent = processedList;
        } else {
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            pagedContent = processedList.subList(fromIndex, toIndex);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", pagedContent);
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", size > 0 ? (int) Math.ceil((double) totalElements / size) : 1);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalAnalyzed", rawDocs.size());
        summary.put("countHasReason", countHasReason);
        summary.put("countDiscrepancies", countDiscrepancies);
        summary.put("countRenuncia", countRenuncia);

        response.put("summary", summary);

        return response;
    }

    @GetMapping("/stats/distinct-reasons")
    public Map<String, Object> getDistinctReasons(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String statusType,
            @RequestParam(defaultValue = "count") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {

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

        List<Document> rawGrouped = mongoTemplate.getCollection("Awards")
                .aggregate(pipeline)
                .into(new ArrayList<>());

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

            String sampleTitle = d.getString("sampleTitle");
            if (sampleTitle == null || sampleTitle.isBlank()) sampleTitle = d.getString("sampleTitleEs");
            if (sampleTitle == null || sampleTitle.isBlank()) sampleTitle = d.getString("sampleTitleEn");

            Map<String, Object> map = new HashMap<>();
            map.put("reason", reason != null ? reason : "");
            map.put("statusType", stType != null ? stType : "");
            map.put("count", cnt);
            map.put("sampleTitle", sampleTitle != null ? sampleTitle : "");
            map.put("sampleUuid", d.getString("sampleUuid"));
            map.put("samplePureId", d.get("samplePureId"));
            map.put("sampleType", d.getString("sampleType"));
            map.put("sampleCategory", d.getString("sampleCategory"));

            processed.add(map);
        }

        final boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        processed.sort((a, b) -> {
            int cmp = 0;
            if ("reason".equalsIgnoreCase(sortBy)) {
                String aR = (String) a.getOrDefault("reason", "");
                String bR = (String) b.getOrDefault("reason", "");
                cmp = aR.compareToIgnoreCase(bR);
            } else {
                Integer aC = (Integer) a.getOrDefault("count", 0);
                Integer bC = (Integer) b.getOrDefault("count", 0);
                cmp = Integer.compare(aC, bC);
            }
            return isAsc ? cmp : -cmp;
        });

        Map<String, Object> response = new HashMap<>();
        response.put("content", processed);
        response.put("totalDistinctReasons", processed.size());
        response.put("totalProjectsWithReason", totalProjectsWithReason);

        return response;
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