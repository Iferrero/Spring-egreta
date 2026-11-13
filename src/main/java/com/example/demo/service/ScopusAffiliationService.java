package com.example.demo.service;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Service to query Elsevier Scopus API for affiliations and publication metrics,
 * caching results in MongoDB.
 */
@Service
public class ScopusAffiliationService {

    private static final Logger log = LoggerFactory.getLogger(ScopusAffiliationService.class);
    private static final String CACHE_COLLECTION = "scopus_affiliations_cache";

    private final MongoTemplate mongoTemplate;

    @Value("${app.scopus.api-key:}")
    private String scopusApiKey;

    private final HttpClient httpClient;
    private final Map<String, ScopusAffiliationInfo> inMemoryCache = new ConcurrentHashMap<>();

    public record ScopusAffiliationInfo(
            String afid,
            String affiliationName,
            String city,
            String country,
            long documentCount,
            String scopusUrl
    ) {}

    public ScopusAffiliationService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    public void clearCache() {
        inMemoryCache.clear();
    }

    /**
     * Search an affiliation in Scopus by organization name, with cache-first lookup.
     */
    public ScopusAffiliationInfo findAffiliation(String orgName) {
        if (orgName == null || orgName.trim().isBlank()) {
            return null;
        }

        String cleanName = sanitizeQuery(orgName);
        if (cleanName.isBlank()) {
            return null;
        }

        String cacheKey = cleanName.toLowerCase();

        // 1. Check in-memory fast cache
        if (inMemoryCache.containsKey(cacheKey)) {
            ScopusAffiliationInfo cached = inMemoryCache.get(cacheKey);
            return (cached != null && cached.affiliationName() != null) ? cached : null;
        }

        // 2. Check MongoDB persistent cache
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(CACHE_COLLECTION);
            Document cachedDoc = collection.find(new Document("query", cacheKey)).first();
            if (cachedDoc != null) {
                if (cachedDoc.getBoolean("notFound", false)) {
                    inMemoryCache.put(cacheKey, new ScopusAffiliationInfo(null, null, null, null, 0, null));
                    return null;
                }
                ScopusAffiliationInfo fromMongo = new ScopusAffiliationInfo(
                        cachedDoc.getString("afid"),
                        cachedDoc.getString("affiliationName"),
                        cachedDoc.getString("city"),
                        cachedDoc.getString("country"),
                        cachedDoc.getLong("documentCount") != null ? cachedDoc.getLong("documentCount") : 0L,
                        cachedDoc.getString("scopusUrl")
                );
                inMemoryCache.put(cacheKey, fromMongo);
                return fromMongo;
            }
        } catch (Exception e) {
            log.warn("Error checking MongoDB scopus_affiliations_cache for '{}': {}", cleanName, e.getMessage());
        }

        // 3. Query Scopus API if key is present
        if (scopusApiKey == null || scopusApiKey.isBlank()) {
            log.debug("Scopus API key not configured. Skipping affiliation search for '{}'", cleanName);
            return null;
        }

        try {
            String queryParam = "AFFILORG(\"" + cleanName.replace("\"", "") + "\")";
            String url = "https://api.elsevier.com/content/search/scopus?query="
                    + URLEncoder.encode(queryParam, StandardCharsets.UTF_8)
                    + "&count=5";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ELS-APIKey", scopusApiKey.trim())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Document doc = Document.parse(response.body());
                Document searchResults = (Document) doc.get("search-results");
                if (searchResults != null) {
                    long totalResults = 0;
                    Object totalObj = searchResults.get("opensearch:totalResults");
                    if (totalObj instanceof Number num) {
                        totalResults = num.longValue();
                    } else if (totalObj instanceof String str) {
                        try {
                            totalResults = Long.parseLong(str.trim());
                        } catch (NumberFormatException ignored) {}
                    }

                    if (totalResults > 0) {
                        ScopusAffiliationInfo matched = extractBestAffiliation(searchResults, cleanName, totalResults);
                        if (matched != null) {
                            saveToMongoCache(cacheKey, matched, false);
                            inMemoryCache.put(cacheKey, matched);
                            return matched;
                        }
                    }
                }
                // No entries found or totalResults == 0
                saveToMongoCache(cacheKey, null, true);
                inMemoryCache.put(cacheKey, new ScopusAffiliationInfo(null, null, null, null, 0, null));
                return null;
            } else if (response.statusCode() == 404) {
                saveToMongoCache(cacheKey, null, true);
                inMemoryCache.put(cacheKey, new ScopusAffiliationInfo(null, null, null, null, 0, null));
                return null;
            } else {
                log.warn("Scopus API returned status {} for query '{}'", response.statusCode(), cleanName);
            }
        } catch (Exception e) {
            log.warn("Exception querying Scopus API for '{}': {}", cleanName, e.getMessage());
        }

        return null;
    }

    /**
     * Extracts the best matching affiliation from the search results entries.
     */
    private ScopusAffiliationInfo extractBestAffiliation(Document searchResults, String cleanName, long totalResults) {
        List<?> rawEntries = searchResults.get("entry", List.class);
        if (rawEntries == null || rawEntries.isEmpty()) {
            return null;
        }

        String targetNorm = normalizeText(cleanName);
        Document bestAffil = null;
        int bestScore = -1;

        for (Object entryObj : rawEntries) {
            if (!(entryObj instanceof Document entryDoc)) continue;
            Object affilObj = entryDoc.get("affiliation");
            List<Document> affilList = new ArrayList<>();
            if (affilObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Document d) affilList.add(d);
                }
            } else if (affilObj instanceof Document d) {
                affilList.add(d);
            }

            for (Document affil : affilList) {
                String affilName = affil.getString("affilname");
                if (affilName == null) affilName = affil.getString("affiliation-name");
                if (affilName == null) continue;

                String affilNorm = normalizeText(affilName);
                int score = 0;
                if (affilNorm.equals(targetNorm)) {
                    score = 100;
                } else if (affilNorm.contains(targetNorm) || targetNorm.contains(affilNorm)) {
                    score = 80;
                } else {
                    String[] targetTokens = targetNorm.split("\\s+");
                    int matches = 0;
                    for (String token : targetTokens) {
                        if (token.length() > 3 && affilNorm.contains(token)) {
                            matches++;
                        }
                    }
                    if (matches > 0) {
                        score = 20 * matches;
                    }
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestAffil = affil;
                    if (score == 100) break;
                }
            }
            if (bestScore == 100) break;
        }

        // If no high-confidence affiliation match within entries, use the first entry's first affiliation as fallback
        if (bestAffil == null && !rawEntries.isEmpty()) {
            Object firstEntryObj = rawEntries.get(0);
            if (firstEntryObj instanceof Document firstEntry) {
                Object affilObj = firstEntry.get("affiliation");
                if (affilObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Document d) {
                    bestAffil = d;
                } else if (affilObj instanceof Document d) {
                    bestAffil = d;
                }
            }
        }

        if (bestAffil != null) {
            return parseAffiliationEntry(bestAffil, totalResults, cleanName);
        }

        return null;
    }

    public ScopusAffiliationInfo parseAffiliationEntry(Document entry) {
        return parseAffiliationEntry(entry, 0, null);
    }

    public ScopusAffiliationInfo parseAffiliationEntry(Document entry, long defaultDocCount, String queryName) {
        if (entry == null) return null;

        String rawId = entry.getString("dc:identifier");
        if (rawId == null) rawId = entry.getString("afid");
        if (rawId == null) rawId = entry.getString("affiliation-id");

        String afid = null;
        if (rawId != null) {
            afid = rawId.replace("AFFILIATION_ID:", "").replace("SCOPUS_ID:", "").trim();
        }

        String name = entry.getString("affiliation-name");
        if (name == null) name = entry.getString("affilname");

        String city = entry.getString("city");
        if (city == null) city = entry.getString("affiliation-city");

        String country = entry.getString("country");
        if (country == null) country = entry.getString("affiliation-country");

        long docCount = defaultDocCount;
        Object countObj = entry.get("document-count");
        if (countObj instanceof Number num) {
            docCount = num.longValue();
        } else if (countObj instanceof String str) {
            try {
                docCount = Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {}
        }

        String searchName = (name != null && !name.isBlank()) ? name : (queryName != null ? queryName : "");
        String scopusUrl;
        if (afid != null && !afid.isBlank()) {
            scopusUrl = "https://www.scopus.com/affil/profile.uri?afid=" + afid;
        } else {
            scopusUrl = "https://www.scopus.com/results/results.uri?src=s&s=AFFILORG%28%22"
                    + URLEncoder.encode(searchName, StandardCharsets.UTF_8) + "%22%29";
            // Create a deterministic identifier from the name if no explicit afid was provided
            afid = "SCOPUS-" + Math.abs(searchName.toLowerCase().hashCode());
        }

        return new ScopusAffiliationInfo(afid, name != null ? name : searchName, city, country, docCount, scopusUrl);
    }

    private void saveToMongoCache(String query, ScopusAffiliationInfo info, boolean notFound) {
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(CACHE_COLLECTION);
            Document doc = new Document("query", query)
                    .append("notFound", notFound)
                    .append("updatedAt", Instant.now().toString());

            if (info != null && !notFound) {
                doc.append("afid", info.afid())
                   .append("affiliationName", info.affiliationName())
                   .append("city", info.city())
                   .append("country", info.country())
                   .append("documentCount", info.documentCount())
                   .append("scopusUrl", info.scopusUrl());
            }

            collection.replaceOne(new Document("query", query), doc, new com.mongodb.client.model.ReplaceOptions().upsert(true));
        } catch (Exception e) {
            log.warn("Failed to persist Scopus cache in MongoDB for query '{}': {}", query, e.getMessage());
        }
    }

    private String sanitizeQuery(String orgName) {
        return orgName.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[\"\\(\\)\\{\\}\\[\\]]", "")
                .trim();
    }

    private String normalizeText(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return pattern.matcher(normalized).replaceAll("").toLowerCase().trim();
    }
}
