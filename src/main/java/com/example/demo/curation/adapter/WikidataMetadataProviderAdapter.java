package com.example.demo.curation.adapter;

import com.example.demo.curation.model.CountryAggregate;
import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeAggregate;
import com.example.demo.curation.model.TypeInferenceResult;
import com.example.demo.curation.port.MetadataProviderPort;
import com.example.demo.curation.util.CountryCodeResolver;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.example.demo.curation.util.EgretaTypeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetadataProviderPort adapter for Wikidata.
 *
 * <p>Looks the organization up by name (wbsearchentities), reads its claims (wbgetentities) and derives:
 * <ul>
 *   <li><b>type</b>: from "instance of" (P31), walking "subclass of" (P279) up a few levels, then mapped onto
 *       Egreta's own taxonomy through {@link EgretaTypeResolver};</li>
 *   <li><b>country</b>: from "country" (P17), falling back to "headquarters location" (P159) and the
 *       "located in" chain (P131), resolved to an ISO code (P297);</li>
 *   <li><b>funding</b>: only when the class is unambiguous (public/private university, government agency, company).</li>
 * </ul>
 *
 * <p>It runs after ROR and OpenAlex (priority 25). Confidences are capped below what a registry such as ROR can
 * reach, because Wikidata is community edited and has many homonymous or duplicated items; its main value is to
 * confirm another source (the engine raises the confidence when two providers agree on the country) or to
 * cover organizations that ROR does not know, such as small foundations.
 *
 * <p>Only the regular Wikibase API is used: the SPARQL endpoint is aggressively rate limited.
 */
@Component
public class WikidataMetadataProviderAdapter implements MetadataProviderPort {

    private static final Logger log = LoggerFactory.getLogger(WikidataMetadataProviderAdapter.class);

    static final double MIN_NAME_SCORE = 0.88;
    static final double COUNTRY_CAP = 0.88;
    static final double TYPE_CAP = 0.88;
    static final double FUNDING_CAP = 0.85;
    private static final int MAX_CLASS_DEPTH = 4;
    private static final int MAX_LOCATION_DEPTH = 3;

    /** Wikidata class -> coarse type understood by {@link EgretaTypeResolver} ("" = organization marker only). */
    private static final Map<String, String> CLASS_TO_BROAD_TYPE = Map.ofEntries(
            Map.entry("Q3918", "education"),     // university
            Map.entry("Q38723", "education"),    // higher education institution
            Map.entry("Q875538", "education"),   // public university
            Map.entry("Q902104", "education"),   // private university
            Map.entry("Q16917", "healthcare"),   // hospital
            Map.entry("Q4830453", "company"),    // business
            Map.entry("Q783794", "company"),     // company
            Map.entry("Q891723", "company"),     // public company
            Map.entry("Q327333", "government"),  // government agency
            Map.entry("Q163740", "nonprofit"),   // nonprofit organization
            Map.entry("Q157031", "nonprofit"),   // foundation
            Map.entry("Q79913", "nonprofit"),    // non-governmental organization
            Map.entry("Q15911314", "nonprofit"), // association
            Map.entry("Q31855", "facility"),     // research institute
            Map.entry("Q43229", "")              // organization (generic)
    );

    @Value("${app.wikidata.suggest.enabled:true}")
    private boolean enabled = true;

    @Value("${app.wikidata.suggest.url:https://www.wikidata.org/w/api.php}")
    private String apiUrl = "https://www.wikidata.org/w/api.php";

    @Value("${app.wikidata.suggest.timeout-ms:3500}")
    private int timeoutMs = 3500;

    /** Overall budget for one organization: the engine calls providers sequentially, so it must stay bounded. */
    @Value("${app.wikidata.suggest.deadline-ms:7000}")
    private long deadlineMs = 7000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** QID -> parent QIDs (P279), shared across requests: the class hierarchy rarely changes. */
    private final Map<String, List<String>> parentCache = new ConcurrentHashMap<>();
    /** QID -> ISO alpha-2 code ("" when it cannot be resolved). */
    private final Map<String, String> countryCache = new ConcurrentHashMap<>();

    @Autowired
    public WikidataMetadataProviderAdapter(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String providerName() {
        return "wikidata";
    }

    @Override
    public int priority() {
        return 25;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
        if (!enabled || organizationName == null || organizationName.isBlank()) {
            return Optional.empty();
        }
        long deadline = System.nanoTime() + deadlineMs * 1_000_000L;
        try {
            List<CountryAggregate> countryCatalog = context != null ? context.countryCatalog() : List.of();
            List<TypeAggregate> typeCatalog = context != null ? context.typeCatalog() : List.of();
            String queryNorm = CurationTextNormalizer.normalizeText(organizationName);
            if (queryNorm.isBlank()) {
                return Optional.empty();
            }

            for (String language : List.of("ca", "en", "es")) {
                if (System.nanoTime() > deadline) break;
                List<String> ids = search(organizationName.trim(), language);
                if (ids.isEmpty()) continue;

                Optional<MetadataInferenceResult> res = evaluate(organizationName, queryNorm, ids, countryCatalog, typeCatalog, deadline);
                if (res.isPresent()) {
                    return res;
                }
            }
        } catch (Exception e) {
            log.debug("Wikidata inference failed for '{}': {}", organizationName, e.getMessage());
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------------------

    private Optional<MetadataInferenceResult> evaluate(
            String organizationName,
            String queryNorm,
            List<String> candidateIds,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog,
            long deadline) throws Exception {

        Map<String, JsonNode> entities = getEntities(candidateIds, "claims|labels|aliases");

        record Scored(String id, JsonNode entity, double score, int claimCount) {}
        List<Scored> scored = new ArrayList<>();
        for (String id : candidateIds) {
            JsonNode entity = entities.get(id);
            if (entity == null) continue;
            double score = bestNameScore(queryNorm, entity);
            if (score >= MIN_NAME_SCORE) {
                scored.add(new Scored(id, entity, score, entity.path("claims").size()));
            }
        }
        if (scored.isEmpty()) {
            return Optional.empty();
        }
        // Best name match first; among equals the richest item (the most claims) is the one people maintain.
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : Integer.compare(b.claimCount(), a.claimCount());
        });

        // Only organizations: a periodical, a person or a place with the same name must not count.
        for (Scored cand : scored) {
            if (System.nanoTime() > deadline) break;
            Set<String> classes = resolveClasses(cand.entity(), deadline);
            if (!classes.contains("Q43229") && classes.stream().noneMatch(c -> !CLASS_TO_BROAD_TYPE.getOrDefault(c, "").isEmpty())) {
                continue; // not an organization
            }

            String iso = resolveCountryIso(cand.entity(), deadline);

            // Two near-identical candidates in different countries: we cannot tell which one is meant.
            boolean ambiguous = false;
            for (Scored other : scored) {
                if (other == cand || Math.abs(other.score() - cand.score()) > 0.02) continue;
                String otherIso = resolveCountryIso(other.entity(), deadline);
                if (!otherIso.isEmpty() && !iso.isEmpty() && !otherIso.equals(iso) && cand.claimCount() < 2 * other.claimCount()) {
                    ambiguous = true;
                    break;
                }
            }

            return Optional.of(buildResult(organizationName, cand.id(), cand.entity(), cand.score(), classes, iso, ambiguous, countryCatalog, typeCatalog));
        }
        return Optional.empty();
    }

    private MetadataInferenceResult buildResult(
            String organizationName,
            String qid,
            JsonNode entity,
            double nameScore,
            Set<String> classes,
            String iso,
            boolean ambiguous,
            List<CountryAggregate> countryCatalog,
            List<TypeAggregate> typeCatalog) {

        String reasonRef = "wikidata:" + qid + (ambiguous ? "-ambiguous" : "");
        double ambiguityFactor = ambiguous ? 0.65 : 1.0;

        // ---- country
        InferenceResult country = InferenceResult.noSignal();
        if (!iso.isEmpty()) {
            String label = CountryCodeResolver.resolve(iso, countryCatalog);
            if (!label.isBlank()) {
                double conf = Math.min(COUNTRY_CAP, nameScore * COUNTRY_CAP) * ambiguityFactor;
                String reason = "wikidata-country:" + qid + (ambiguous ? "-ambiguous" : "");
                // The name itself names another country than the item's: do not trust it.
                Set<String> named = CountryCodeResolver.countryCodesNamedIn(organizationName);
                if (!named.isEmpty() && !named.contains(iso)) {
                    conf = Math.min(conf, 0.55);
                    reason = reason + ":name-country-conflict";
                }
                country = new InferenceResult(label, conf, reason);
            }
        }

        // ---- type
        String broadType = pickBroadType(classes);
        TypeInferenceResult type = TypeInferenceResult.noSignal();
        String canonicalName = labelOf(entity);
        TypeInferenceResult resolved = EgretaTypeResolver.resolve(
                organizationName, canonicalName, broadType, typeCatalog, nameScore, "wikidata-type:" + qid);
        if (!resolved.typeLabel().isBlank()) {
            double conf = Math.min(resolved.confidence(), Math.min(TYPE_CAP, nameScore * TYPE_CAP)) * ambiguityFactor;
            // A coarse class alone (nonprofit, institute, agency) maps to Associació / Centre R+D / Agència estatal
            // far too often wrongly: only the name can justify a confident type for those.
            if (List.of("nonprofit", "facility", "government").contains(broadType)) {
                TypeInferenceResult nameOnly = EgretaTypeResolver.resolve(
                        organizationName, canonicalName, "", typeCatalog, nameScore, "wikidata-type:" + qid);
                if (!resolved.typeLabel().equalsIgnoreCase(nameOnly.typeLabel())) {
                    conf = Math.min(conf, 0.75);
                }
            }
            type = new TypeInferenceResult(resolved.typeLabel(), conf, resolved.reason());
        }

        // ---- funding: only for unambiguous classes
        FundingInferenceResult funding = FundingInferenceResult.noSignal();
        double fundingConf = Math.min(FUNDING_CAP, nameScore * FUNDING_CAP) * ambiguityFactor;
        if (classes.contains("Q875538")) {
            funding = new FundingInferenceResult("Publica", fundingConf, "wikidata-public-university:" + qid);
        } else if (classes.contains("Q902104")) {
            funding = new FundingInferenceResult("Privada", fundingConf, "wikidata-private-university:" + qid);
        } else if (classes.contains("Q327333")) {
            funding = new FundingInferenceResult("Publica", fundingConf, "wikidata-government:" + qid);
        } else if (classes.contains("Q4830453") || classes.contains("Q783794")) {
            funding = new FundingInferenceResult("Privada", fundingConf, "wikidata-company:" + qid);
        }

        return new MetadataInferenceResult(country, type, funding);
    }

    /** The most specific broad type among the matched classes (a hospital that is also an "organization" is a hospital). */
    private static String pickBroadType(Set<String> classes) {
        for (String preferred : List.of("education", "healthcare", "company", "government", "nonprofit", "facility")) {
            for (String c : classes) {
                if (preferred.equals(CLASS_TO_BROAD_TYPE.get(c))) {
                    return preferred;
                }
            }
        }
        return "";
    }

    // ---- name matching -----------------------------------------------------------------------------------------

    private static String labelOf(JsonNode entity) {
        for (String lang : List.of("ca", "es", "en")) {
            String v = entity.path("labels").path(lang).path("value").asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static double bestNameScore(String queryNorm, JsonNode entity) {
        double best = 0.0;
        JsonNode labels = entity.path("labels");
        for (JsonNode l : labels) {
            best = Math.max(best, CurationTextNormalizer.calculateNameMatchScore(
                    queryNorm, CurationTextNormalizer.normalizeText(l.path("value").asText("")), false));
        }
        JsonNode aliases = entity.path("aliases");
        for (JsonNode perLang : aliases) {
            for (JsonNode a : perLang) {
                best = Math.max(best, CurationTextNormalizer.calculateNameMatchScore(
                        queryNorm, CurationTextNormalizer.normalizeText(a.path("value").asText("")), false));
            }
        }
        return best;
    }

    // ---- class / country resolution ----------------------------------------------------------------------------

    private static List<String> claimIds(JsonNode entity, String property) {
        List<String> out = new ArrayList<>();
        for (JsonNode claim : entity.path("claims").path(property)) {
            if ("deprecated".equals(claim.path("rank").asText())) continue;
            String id = claim.path("mainsnak").path("datavalue").path("value").path("id").asText("");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** All known classes of the item: its own P31 values plus their P279 ancestors (bounded depth). */
    private Set<String> resolveClasses(JsonNode entity, long deadline) throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>(claimIds(entity, "P31"));
        for (int depth = 0; depth <= MAX_CLASS_DEPTH && !frontier.isEmpty(); depth++) {
            seen.addAll(frontier);
            if (depth == MAX_CLASS_DEPTH || System.nanoTime() > deadline) break;

            List<String> toFetch = frontier.stream().filter(q -> !parentCache.containsKey(q)).distinct().toList();
            if (!toFetch.isEmpty()) {
                Map<String, JsonNode> fetched = getEntities(toFetch, "claims");
                for (String q : toFetch) {
                    JsonNode e = fetched.get(q);
                    parentCache.put(q, e == null ? List.of() : claimIds(e, "P279"));
                }
            }
            List<String> next = new ArrayList<>();
            for (String q : frontier) {
                for (String parent : parentCache.getOrDefault(q, List.of())) {
                    if (seen.add(parent)) next.add(parent);
                }
            }
            frontier = next;
        }
        return seen;
    }

    /** ISO alpha-2 code of the item's country, or "" when it cannot be determined reliably. */
    private String resolveCountryIso(JsonNode entity, long deadline) throws Exception {
        List<String> countries = claimIds(entity, "P17");
        if (countries.isEmpty()) {
            // Headquarters, then the administrative "located in" chain (a foundation in a town of Barcelona).
            List<String> places = new ArrayList<>(claimIds(entity, "P159"));
            places.addAll(claimIds(entity, "P131"));
            for (int depth = 0; depth < MAX_LOCATION_DEPTH && countries.isEmpty() && !places.isEmpty(); depth++) {
                if (System.nanoTime() > deadline) return "";
                Map<String, JsonNode> fetched = getEntities(places.stream().distinct().toList(), "claims");
                List<String> next = new ArrayList<>();
                for (String place : places) {
                    JsonNode p = fetched.get(place);
                    if (p == null) continue;
                    countries.addAll(claimIds(p, "P17"));
                    next.addAll(claimIds(p, "P131"));
                }
                places = next;
            }
        }
        if (countries.isEmpty()) return "";

        // Several countries (e.g. historical ones): only trust it when they agree.
        Set<String> isos = new LinkedHashSet<>();
        for (String country : countries.stream().distinct().toList()) {
            String iso = countryCache.get(country);
            if (iso == null) {
                if (System.nanoTime() > deadline) return "";
                JsonNode c = getEntities(List.of(country), "claims").get(country);
                iso = "";
                if (c != null) {
                    for (JsonNode claim : c.path("claims").path("P297")) {
                        String v = claim.path("mainsnak").path("datavalue").path("value").asText("");
                        if (v.length() == 2) { iso = v.toUpperCase(Locale.ROOT); break; }
                    }
                }
                countryCache.put(country, iso);
            }
            if (!iso.isEmpty()) isos.add(iso);
        }
        return isos.size() == 1 ? isos.iterator().next() : "";
    }

    // ---- HTTP --------------------------------------------------------------------------------------------------

    private List<String> search(String name, String language) throws Exception {
        String url = apiUrl + "?action=wbsearchentities&format=json&type=item&limit=5"
                + "&language=" + language + "&uselang=" + language
                + "&search=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : fetchJson(url).path("search")) {
            String id = hit.path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private Map<String, JsonNode> getEntities(List<String> ids, String props) throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + 50));
            String url = apiUrl + "?action=wbgetentities&format=json&props=" + URLEncoder.encode(props, StandardCharsets.UTF_8)
                    + "&languages=ca%7Ces%7Cen&ids=" + URLEncoder.encode(String.join("|", batch), StandardCharsets.UTF_8);
            JsonNode entities = fetchJson(url).path("entities");
            entities.fields().forEachRemaining(e -> {
                if (!e.getValue().has("missing")) out.put(e.getKey(), e.getValue());
            });
        }
        return out;
    }

    /** Overridable so tests can feed canned responses without network access. */
    protected JsonNode fetchJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", "Spring-egreta/1.0 (research metadata enrichment)")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
            throw new IllegalStateException("Wikidata HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }
}
