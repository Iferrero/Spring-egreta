package com.example.demo.curation.adapter;

import com.example.demo.curation.model.CountryAggregate;
import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeAggregate;
import com.example.demo.curation.model.TypeInferenceResult;
import com.example.demo.curation.port.MetadataProviderPort;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MetadataProviderPort adapter for the Research Organization Registry (ROR).
 * Queries local MongoDB cache if available, falling back to the official ROR REST API.
 */
@Component
public class RorMetadataProviderAdapter implements MetadataProviderPort {

    private static final Logger log = LoggerFactory.getLogger(RorMetadataProviderAdapter.class);

    private static final Set<String> ROR_GENERIC_WORDS = Set.of(
            "universitat", "universidad", "university", "universite", "universitaet", "universitad",
            "institut", "instituto", "institute", "institution", "fondation", "fundacion", "fundacio", "foundation",
            "centre", "centro", "center", "hospital", "facultat", "facultad", "faculty",
            "departament", "departamento", "department", "de", "del", "la", "el", "les", "los", "las",
            "d", "l", "i", "y", "and", "of", "the", "for", "in", "a", "at", "on", "to", "und", "et", "du", "des",
            "fur", "der", "die", "das", "sa", "sl", "gmbh", "inc", "llc", "corp", "ltd"
    );

    @Value("${app.ror.suggest.enabled:true}")
    private boolean rorSuggestEnabled = true;

    public void setEnabled(boolean enabled) {
        this.rorSuggestEnabled = enabled;
    }

    @Value("${app.ror.suggest.url:https://api.ror.org/v2/organizations}")
    private String rorSuggestUrl;

    @Value("${app.ror.suggest.timeout-ms:3500}")
    private int rorSuggestTimeoutMs;

    @Value("${app.ror.mongodb.collection:ror_data}")
    private String rorCollectionName;

    private final MongoTemplate mongoTemplate;
    private final MongoTemplate rorMongoTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public RorMetadataProviderAdapter(
            MongoTemplate mongoTemplate,
            @Autowired(required = false) @Qualifier("rorMongoTemplate") MongoTemplate rorMongoTemplate,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.rorMongoTemplate = rorMongoTemplate != null ? rorMongoTemplate : mongoTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    @Override
    public String providerName() {
        return "ror";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean isEnabled() {
        return rorSuggestEnabled;
    }

    @Override
    public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
        if (!rorSuggestEnabled || organizationName == null || organizationName.isBlank()) {
            return Optional.empty();
        }

        String rawJson = fetchRorRawJson(organizationName);
        if (rawJson == null || rawJson.isBlank()) {
            return Optional.empty();
        }

        List<CountryAggregate> countryCatalog = context != null ? context.countryCatalog() : List.of();
        List<TypeAggregate> typeCatalog = context != null ? context.typeCatalog() : List.of();

        MetadataInferenceResult result = parseRorResponse(organizationName, rawJson, countryCatalog, typeCatalog);
        return Optional.ofNullable(result);
    }

    private String fetchRorRawJson(String orgName) {
        String result = queryRorFromMongo(orgName);
        if ((result == null || !hasRorItems(result)) && rorSuggestUrl != null && !rorSuggestUrl.isBlank() && rorSuggestUrl.startsWith("http")) {
            String chosen = executeRorAffiliation(orgName.trim());
            if (chosen != null) {
                return chosen;
            }
            String cleaned = cleanQueryForRor(orgName);
            if (!cleaned.isBlank()) {
                result = executeRorQuery(cleaned);
            }
            if (result == null || !hasRorItems(result)) {
                result = executeRorQuery(orgName.trim());
            }
        }
        return result;
    }

    private boolean hasRorItems(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.path("items");
            return items.isArray() && !items.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String executeRorQuery(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = rorSuggestUrl + "?query=" + encoded;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(rorSuggestTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception e) {
            log.debug("ROR HTTP query failed for '{}': {}", query, e.getMessage());
        }
        return null;
    }

    /**
     * ROR's affiliation matcher is designed for noisy organization strings. Unlike the free-text search it says
     * when it is confident ("chosen"), and we only accept those records, so a nearby but wrong institution is
     * never returned just because it ranked first.
     */
    private String executeRorAffiliation(String rawAffiliation) {
        try {
            String url = rorSuggestUrl + "?affiliation=" + URLEncoder.encode(rawAffiliation, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(rorSuggestTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode items = objectMapper.readTree(resp.body()).path("items");
            if (!items.isArray()) {
                return null;
            }
            for (JsonNode it : items) {
                if (it.path("chosen").asBoolean(false) && it.path("organization").isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode org = ((com.fasterxml.jackson.databind.node.ObjectNode) it.path("organization")).deepCopy();
                    org.put("_chosen", true);
                    com.fasterxml.jackson.databind.node.ObjectNode wrapper = objectMapper.createObjectNode();
                    wrapper.put("number_of_results", 1);
                    wrapper.putArray("items").add(org);
                    return objectMapper.writeValueAsString(wrapper);
                }
            }
        } catch (Exception e) {
            log.debug("ROR affiliation query failed for '{}': {}", rawAffiliation, e.getMessage());
        }
        return null;
    }

    private String cleanQueryForRor(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s*\\([^)]*\\)\\s*", " ")
                .replaceAll("\\s*-\\s*.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String queryRorFromMongo(String orgName) {
        if (orgName == null || orgName.isBlank()) return null;
        MongoTemplate template = rorMongoTemplate != null ? rorMongoTemplate : mongoTemplate;
        if (template == null) return null;

        String colName = (rorCollectionName == null || rorCollectionName.isBlank()) ? "ror_data" : rorCollectionName.trim();
        com.mongodb.client.MongoCollection<Document> collection;
        try {
            collection = template.getCollection(colName);
        } catch (Exception e) {
            log.debug("Error accessing ROR collection '{}': {}", colName, e.getMessage());
            return null;
        }

        Map<String, Document> candidates = new LinkedHashMap<>();
        String cleanedRaw = cleanQueryForRor(orgName);
        if (cleanedRaw.isBlank()) cleanedRaw = orgName.trim();

        try {
            Document query = new Document("names.value", new Document("$regex", "^" + Pattern.quote(cleanedRaw) + "$").append("$options", "i"));
            for (Document doc : collection.find(query).limit(10)) {
                String id = doc.getString("id");
                if (id != null && !candidates.containsKey(id)) {
                    candidates.put(id, doc);
                }
            }
        } catch (Exception e) {
            log.debug("ROR mongo exact match query error: {}", e.getMessage());
        }

        if (candidates.isEmpty()) return null;

        List<Document> list = new ArrayList<>(candidates.values());
        Document wrapper = new Document("number_of_results", list.size()).append("items", list);
        return wrapper.toJson();
    }

    private MetadataInferenceResult parseRorResponse(
            String queryOrgName,
            String jsonBody,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {
        if (jsonBody == null || jsonBody.isBlank()) return null;

        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return null;

            String queryNorm = CurationTextNormalizer.normalizeText(queryOrgName);
            if (queryNorm.isBlank()) return null;

            JsonNode bestItem = null;
            double bestScore = 0.0;
            double secondScore = 0.0; // best score of a *different* organization

            int maxInspect = Math.min(items.size(), 5);
            for (int i = 0; i < maxInspect; i++) {
                JsonNode item = items.get(i);
                double itemScore = 0.0;
                JsonNode namesNode = item.path("names");
                if (namesNode.isArray()) {
                    for (JsonNode nameObj : namesNode) {
                        String val = nameObj.path("value").asText("");
                        if (val.isBlank()) continue;
                        boolean isAcronym = false;
                        JsonNode typesNode = nameObj.path("types");
                        if (typesNode.isArray()) {
                            for (JsonNode t : typesNode) {
                                if ("acronym".equalsIgnoreCase(t.asText())) {
                                    isAcronym = true;
                                    break;
                                }
                            }
                        }
                        double score = CurationTextNormalizer.calculateNameMatchScore(queryNorm, CurationTextNormalizer.normalizeText(val), isAcronym);
                        double strScore = CurationTextNormalizer.calculateStringSimilarity(queryNorm, CurationTextNormalizer.normalizeText(val));
                        score = Math.max(score, strScore);
                        itemScore = Math.max(itemScore, score);
                    }
                }
                if (itemScore > bestScore) {
                    secondScore = bestScore;
                    bestScore = itemScore;
                    bestItem = item;
                } else if (itemScore > secondScore) {
                    secondScore = itemScore;
                }
            }

            // ROR's own affiliation matcher flagged this record as the confident match.
            if (bestItem != null && bestItem.path("_chosen").asBoolean(false)) {
                bestScore = Math.max(bestScore, 0.82);
            }

            if (bestItem == null || bestScore < 0.78) {
                return null;
            }

            // Two different organizations match the name almost equally well (e.g. several campuses or
            // homonymous institutions): the pick is a coin toss, so lower the confidence of everything derived from it.
            boolean ambiguous = secondScore >= 0.78 && (bestScore - secondScore) < 0.04;
            if (ambiguous) {
                bestScore = bestScore * 0.90;
            }

            String rorId = bestItem.path("id").asText("");
            if (ambiguous) {
                rorId = rorId + "-ambiguous";
            }

            InferenceResult countryResult = resolveCountryFromRorItem(bestItem, countryCatalog, bestScore, rorId, queryOrgName);
            TypeInferenceResult typeResult = resolveTypeFromRorItem(bestItem, queryOrgName, typeCatalog, bestScore, rorId);
            FundingInferenceResult fundingResult = resolveFundingFromRorItem(bestItem, queryOrgName, bestScore, rorId);

            return new MetadataInferenceResult(countryResult, typeResult, fundingResult);
        } catch (Exception e) {
            log.warn("Error parsing ROR response for '{}': {}", queryOrgName, e.getMessage());
            return null;
        }
    }

    private InferenceResult resolveCountryFromRorItem(
            JsonNode item,
            List<CountryAggregate> countryCatalog,
            double matchScore,
            String rorId,
            String queryOrgName) {
        JsonNode locations = item.path("locations");
        if (locations.isArray() && !locations.isEmpty()) {
            JsonNode loc = locations.get(0).path("geonames_details");
            String countryName = loc.path("country_name").asText("").trim();
            String countryCode = loc.path("country_code").asText("").trim();

            String resolved = "";
            if (!countryName.isBlank()) {
                String target = CurationTextNormalizer.normalizeText(countryName);
                resolved = countryCatalog.stream()
                        .filter(c -> c.normalizedLabel().equals(target))
                        .map(CountryAggregate::label)
                        .findFirst()
                        .orElse(countryName);
            } else if (!countryCode.isBlank()) {
                resolved = com.example.demo.curation.util.CountryCodeResolver.resolve(countryCode, countryCatalog);
            }

            if (!resolved.isBlank()) {
                double confidence = Math.min(0.99, matchScore * 0.98);
                String reason = "ror-match:" + (rorId.isBlank() ? "found" : rorId);

                // Coherence: the name itself names a country other than the one ROR returned
                // ("National Land Survey of Sweden" matched to the Finnish agency). Do not trust it.
                java.util.Set<String> namedCountries = com.example.demo.curation.util.CountryCodeResolver.countryCodesNamedIn(queryOrgName);
                if (!namedCountries.isEmpty() && !countryCode.isBlank() && !namedCountries.contains(countryCode.toUpperCase())) {
                    confidence = Math.min(confidence, 0.55);
                    reason = reason + ":name-country-conflict";
                } else if (item.path("types").toString().contains("company")) {
                    // ROR describes the group's headquarters; Egreta records the specific entity/branch,
                    // which is often in another country (BASF SE, Hewlett Packard Enterprise...). Needs review.
                    confidence = Math.min(confidence, 0.80);
                    reason = reason + ":company-hq";
                }
                return new InferenceResult(resolved, confidence, reason);
            }
        }
        return InferenceResult.noSignal();
    }

    private TypeInferenceResult resolveTypeFromRorItem(
            JsonNode item,
            String queryOrgName,
            List<TypeAggregate> typeCatalog,
            double matchScore,
            String rorId) {
        JsonNode typesNode = item.path("types");
        List<String> rorTypes = new ArrayList<>();
        if (typesNode.isArray()) {
            for (JsonNode t : typesNode) {
                rorTypes.add(t.asText().toLowerCase().trim());
            }
        }

        // ROR only gives a coarse type; the shared resolver maps it (plus the name) onto Egreta's own taxonomy.
        String broadType = "";
        for (String candidate : List.of("education", "healthcare", "company", "government", "nonprofit", "facility", "archive")) {
            if (rorTypes.contains(candidate)) {
                broadType = candidate;
                break;
            }
        }

        String canonicalName = "";
        JsonNode namesNode = item.path("names");
        if (namesNode.isArray()) {
            for (JsonNode n : namesNode) {
                JsonNode nt = n.path("types");
                if (nt.isArray()) {
                    for (JsonNode x : nt) {
                        if ("ror_display".equals(x.asText())) {
                            canonicalName = n.path("value").asText("");
                        }
                    }
                }
            }
        }

        String reasonRef = "ror-type:" + (rorId.isBlank() ? "found" : rorId);
        TypeInferenceResult res = com.example.demo.curation.util.EgretaTypeResolver.resolve(
                queryOrgName, canonicalName, broadType, typeCatalog, matchScore, reasonRef);
        if (res.typeLabel().isBlank()) {
            return TypeInferenceResult.noSignal();
        }
        // The type can never be more certain than the name match that identified the ROR record.
        double capped = Math.min(res.confidence(), Math.min(0.98, matchScore * 0.98));
        // If the label only exists because of ROR's coarse type (the name alone gives something else), the
        // evidence is weak for nonprofit/facility/archive/government: they map to Associació, Centre R+D or
        // Agència estatal far too often wrongly. Keep those below the auto-apply threshold.
        if (!broadType.isBlank() && List.of("nonprofit", "facility", "archive", "government").contains(broadType)) {
            TypeInferenceResult nameOnly = com.example.demo.curation.util.EgretaTypeResolver.resolve(
                    queryOrgName, canonicalName, "", typeCatalog, matchScore, reasonRef);
            if (!res.typeLabel().equalsIgnoreCase(nameOnly.typeLabel())) {
                capped = Math.min(capped, 0.80);
            }
        }
        return new TypeInferenceResult(res.typeLabel(), capped, res.reason());
    }

    private FundingInferenceResult resolveFundingFromRorItem(
            JsonNode item,
            String orgName,
            double matchScore,
            String rorId) {
        JsonNode typesNode = item.path("types");
        List<String> rorTypes = new ArrayList<>();
        if (typesNode.isArray()) {
            for (JsonNode t : typesNode) {
                rorTypes.add(t.asText().toLowerCase().trim());
            }
        }

        String reasonSuffix = rorId.isBlank() ? "" : ":" + rorId;
        String norm = CurationTextNormalizer.normalizeText(orgName);
        // Funding can never be more certain than the name match that identified the ROR record.
        double cap = Math.min(0.98, matchScore * 0.98);

        if (rorTypes.contains("government")) {
            return new FundingInferenceResult("Publica", Math.min(cap, 0.96), "ror-government" + reasonSuffix);
        }
        if (rorTypes.contains("company")) {
            return new FundingInferenceResult("Privada", Math.min(cap, 0.96), "ror-company" + reasonSuffix);
        }
        if (rorTypes.contains("nonprofit")) {
            // Foundations/associations may belong to the public sector: keep it below the auto-apply threshold.
            return new FundingInferenceResult("Privada", Math.min(cap, 0.85), "ror-nonprofit" + reasonSuffix);
        }
        if (rorTypes.contains("healthcare")) {
            if (hasAnyWord(norm, "privat", "privada", "privado", "private", "clinica", "quiron", "dexeus", "mutua")) {
                return new FundingInferenceResult("Privada", Math.min(cap, 0.92), "ror-healthcare-private" + reasonSuffix);
            }
            return new FundingInferenceResult("Publica", Math.min(cap, 0.92), "ror-healthcare-public" + reasonSuffix);
        }
        if (rorTypes.contains("facility") || rorTypes.contains("archive")) {
            return new FundingInferenceResult("Publica", Math.min(cap, 0.92), "ror-facility" + reasonSuffix);
        }
        if (rorTypes.contains("education")) {
            if (hasAnyWord(norm, "privada", "private", "catholic", "catolica", "esade", "ie university", "uoc", "ramon llull")) {
                return new FundingInferenceResult("Privada", Math.min(cap, 0.94), "ror-education-private" + reasonSuffix);
            }
            if (hasAnyWord(norm, "publica", "public", "nacional", "national", "autonoma", "estatal", "state", "politecnica", "polytechnic")) {
                return new FundingInferenceResult("Publica", Math.min(cap, 0.95), "ror-education-public" + reasonSuffix);
            }
            // No cue either way: most universities are public, but that is only a prior, so it needs review.
            return new FundingInferenceResult("Publica", Math.min(cap, 0.85), "ror-education-default" + reasonSuffix);
        }

        return FundingInferenceResult.noSignal();
    }

    private static boolean hasAnyWord(String normalizedText, String... terms) {
        for (String term : terms) {
            if (CurationTextNormalizer.containsWord(normalizedText, term)) {
                return true;
            }
        }
        return false;
    }
}
