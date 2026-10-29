package com.example.demo.service;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class ScopusJournalsService {

    private static final Logger log = LoggerFactory.getLogger(ScopusJournalsService.class);

    private final MongoTemplate mongoTemplate;
    private final EgretaApiService egretaApiService;

    @Value("${app.scopus.api-key:}")
    private String scopusApiKey;

    // Caches holding the latest stats per environment (prod -> kraken, test -> kraken_test)
    private final Map<String, Map<String, Object>> cachedStatsByEnv = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Instant> lastCalculatedByEnv = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    public String resolveDatabaseName(String env) {
        if ("test".equalsIgnoreCase(env)) {
            return "kraken_test";
        }
        return "kraken";
    }

    public MongoCollection<Document> getCollection(String collectionName, String env) {
        String dbName = resolveDatabaseName(env);
        return mongoTemplate.getMongoDatabaseFactory().getMongoDatabase(dbName).getCollection(collectionName);
    }

    public void invalidateCache(String env) {
        String cleanEnv = (env != null && !env.isBlank()) ? env.trim().toLowerCase() : "prod";
        cachedStatsByEnv.remove(cleanEnv);
        lastCalculatedByEnv.remove(cleanEnv);
    }

    public void invalidateAllCaches() {
        cachedStatsByEnv.clear();
        lastCalculatedByEnv.clear();
    }

    // Async batch execution for automatic Scopus ID assignment
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor();
    private volatile BatchJobProgress activeJob = null;

    public static class BatchJobProgress {
        public String jobId;
        public Instant startedAt;
        public Instant finishedAt;
        public volatile boolean running;
        public volatile boolean cancelled;
        public int total;
        public int processed;
        public int foundCount;
        public int assignedCount;
        public int notFoundCount;
        public int errorCount;
        public boolean dryRun;
        public String targetEnv;
        public String journalUuid;
        public volatile String currentTitle;
        public List<Map<String, Object>> logs = new CopyOnWriteArrayList<>();
    }

    public ScopusJournalsService(MongoTemplate mongoTemplate, EgretaApiService egretaApiService) {
        this.mongoTemplate = mongoTemplate;
        this.egretaApiService = egretaApiService;
    }

    public Map<String, Object> getJournalsStats(boolean forceRefresh) {
        return getJournalsStats(forceRefresh, "prod");
    }

    public synchronized Map<String, Object> getJournalsStats(boolean forceRefresh, String targetEnv) {
        String env = (targetEnv != null && !targetEnv.isBlank()) ? targetEnv.trim().toLowerCase() : "prod";
        String dbName = resolveDatabaseName(env);

        Instant last = lastCalculatedByEnv.get(env);
        Map<String, Object> cached = cachedStatsByEnv.get(env);
        if (!forceRefresh && cached != null && last != null) {
            long age = Instant.now().getEpochSecond() - last.getEpochSecond();
            if (age < CACHE_TTL_SECONDS) {
                log.debug("Returning cached Scopus journals stats for env '{}' (db: {}, age: {}s)", env, dbName, age);
                return cached;
            }
        }

        log.info("Calculating Scopus journals and articles statistics from MongoDB database '{}' (env: {})...", dbName, env);
        long startTime = System.currentTimeMillis();

        MongoCollection<Document> roCollection = getCollection("Researchoutputs", env);
        MongoCollection<Document> jCollection = getCollection("Journals", env);

        // 1. Aggregation on Researchoutputs: count articles, with ScopusID and without ScopusID grouped by journal UUID
        List<Document> pipeline = List.of(
            new Document("$match", new Document("journalAssociation.journal.uuid", new Document("$exists", true).append("$ne", null))),
            new Document("$project", new Document("journalUuid", "$journalAssociation.journal.uuid")
                .append("hasScopus", new Document("$in", List.of("Scopus", new Document("$ifNull", List.of("$identifiers.idSource", List.of())))))
            ),
            new Document("$group", new Document("_id", "$journalUuid")
                .append("totalArticles", new Document("$sum", 1))
                .append("withScopusId", new Document("$sum", new Document("$cond", List.of("$hasScopus", 1, 0))))
                .append("withoutScopusId", new Document("$sum", new Document("$cond", List.of("$hasScopus", 0, 1))))
            )
        );

        Map<String, Document> countsByJournal = new HashMap<>();
        for (Document doc : roCollection.aggregate(pipeline).allowDiskUse(true)) {
            String uuid = doc.getString("_id");
            if (uuid != null) {
                countsByJournal.put(uuid, doc);
            }
        }

        // 2. Fetch all journals in Journals collection that have a Scopus identifier
        Document journalFilter = new Document("identifiers.idSource", "Scopus");
        Document journalProjection = new Document("uuid", 1)
                .append("titles.title", 1)
                .append("issns.issn", 1)
                .append("additionalSearchableIssns.issn", 1)
                .append("identifiers", 1);

        List<Map<String, Object>> journalsList = new ArrayList<>();
        long totalArticlesAll = 0;
        long totalWithScopusAll = 0;
        long totalWithoutScopusAll = 0;

        for (Document jDoc : jCollection.find(journalFilter).projection(journalProjection)) {
            String uuid = jDoc.getString("uuid");
            if (uuid == null) {
                continue;
            }

            Document countDoc = countsByJournal.get(uuid);
            int totalArticles = countDoc != null ? getIntVal(countDoc.get("totalArticles")) : 0;
            int withScopusId = countDoc != null ? getIntVal(countDoc.get("withScopusId")) : 0;
            int withoutScopusId = countDoc != null ? getIntVal(countDoc.get("withoutScopusId")) : 0;

            // Only journals with at least 1 article
            if (totalArticles == 0) {
                continue;
            }

            totalArticlesAll += totalArticles;
            totalWithScopusAll += withScopusId;
            totalWithoutScopusAll += withoutScopusId;

            // Extract main title
            String title = "Sense títol";
            List<?> titlesList = jDoc.get("titles", List.class);
            if (titlesList != null && !titlesList.isEmpty() && titlesList.get(0) instanceof Document td) {
                String t = td.getString("title");
                if (t != null && !t.isBlank()) {
                    title = t.trim();
                }
            }

            // Extract ISSNs
            Set<String> issns = new LinkedHashSet<>();
            List<?> issnsList = jDoc.get("issns", List.class);
            if (issnsList != null) {
                for (Object item : issnsList) {
                    if (item instanceof Document d && d.getString("issn") != null) {
                        issns.add(d.getString("issn").trim());
                    }
                }
            }
            List<?> addIssnsList = jDoc.get("additionalSearchableIssns", List.class);
            if (addIssnsList != null) {
                for (Object item : addIssnsList) {
                    if (item instanceof Document d && d.getString("issn") != null) {
                        issns.add(d.getString("issn").trim());
                    }
                }
            }

            // Extract Scopus Source ID
            String scopusSourceId = null;
            List<?> identsList = jDoc.get("identifiers", List.class);
            if (identsList != null) {
                for (Object item : identsList) {
                    if (item instanceof Document idDoc && "Scopus".equalsIgnoreCase(idDoc.getString("idSource"))) {
                        String v = idDoc.getString("value");
                        if (v == null) {
                            v = idDoc.getString("id");
                        }
                        if (v != null && !v.isBlank()) {
                            scopusSourceId = v.trim();
                            break;
                        }
                    }
                }
            }

            double coverage = roundPercent(totalArticles > 0 ? (double) withScopusId / totalArticles * 100 : 0.0);

            Map<String, Object> jEntry = new LinkedHashMap<>();
            jEntry.put("uuid", uuid);
            jEntry.put("title", title);
            jEntry.put("issns", new ArrayList<>(issns));
            jEntry.put("scopusSourceId", scopusSourceId);
            jEntry.put("totalArticles", totalArticles);
            jEntry.put("withScopusId", withScopusId);
            jEntry.put("withoutScopusId", withoutScopusId);
            jEntry.put("coveragePercent", coverage);

            journalsList.add(jEntry);
        }

        // Sort descending by totalArticles
        journalsList.sort((a, b) -> Integer.compare((int) b.get("totalArticles"), (int) a.get("totalArticles")));

        double globalCoverage = roundPercent(totalArticlesAll > 0 ? (double) totalWithScopusAll / totalArticlesAll * 100 : 0.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScopusJournals", journalsList.size());
        result.put("totalArticles", totalArticlesAll);
        result.put("totalWithScopusId", totalWithScopusAll);
        result.put("totalWithoutScopusId", totalWithoutScopusAll);
        result.put("globalCoveragePercent", globalCoverage);
        result.put("journals", journalsList);
        result.put("calculatedAt", Instant.now().toString());
        result.put("durationMs", System.currentTimeMillis() - startTime);

        result.put("env", env);
        result.put("database", dbName);

        cachedStatsByEnv.put(env, result);
        lastCalculatedByEnv.put(env, Instant.now());

        log.info("Finished Scopus journals stats calculation in {}ms for env '{}' (db: {}). Found {} journals with production ({} articles).",
                result.get("durationMs"), env, dbName, journalsList.size(), totalArticlesAll);

        return result;
    }

    public Map<String, Object> getArticlesByJournal(String journalUuid, int limit, int offset, Boolean hasScopusId, String search) {
        return getArticlesByJournal(journalUuid, limit, offset, hasScopusId, search, "prod");
    }

    public Map<String, Object> getArticlesByJournal(String journalUuid, int limit, int offset, Boolean hasScopusId, String search, String targetEnv) {
        String env = (targetEnv != null && !targetEnv.isBlank()) ? targetEnv.trim().toLowerCase() : "prod";
        String dbName = resolveDatabaseName(env);

        MongoCollection<Document> roCollection = getCollection("Researchoutputs", env);
        MongoCollection<Document> jCollection = getCollection("Journals", env);

        // 1. Get Journal info
        Document jDoc = jCollection.find(new Document("uuid", journalUuid)).first();
        Map<String, Object> journalInfo = new LinkedHashMap<>();
        journalInfo.put("uuid", journalUuid);
        if (jDoc != null) {
            String title = "Sense títol";
            List<?> titlesList = jDoc.get("titles", List.class);
            if (titlesList != null && !titlesList.isEmpty() && titlesList.get(0) instanceof Document td) {
                String t = td.getString("title");
                if (t != null && !t.isBlank()) title = t.trim();
            }
            journalInfo.put("title", title);

            Set<String> issns = new LinkedHashSet<>();
            List<?> issnsList = jDoc.get("issns", List.class);
            if (issnsList != null) {
                for (Object item : issnsList) {
                    if (item instanceof Document d && d.getString("issn") != null) {
                        issns.add(d.getString("issn").trim());
                    }
                }
            }
            journalInfo.put("issns", new ArrayList<>(issns));

            String scopusSourceId = null;
            List<?> identsList = jDoc.get("identifiers", List.class);
            if (identsList != null) {
                for (Object item : identsList) {
                    if (item instanceof Document idDoc && "Scopus".equalsIgnoreCase(idDoc.getString("idSource"))) {
                        String v = idDoc.getString("value");
                        if (v == null) v = idDoc.getString("id");
                        if (v != null && !v.isBlank()) {
                            scopusSourceId = v.trim();
                            break;
                        }
                    }
                }
            }
            journalInfo.put("scopusSourceId", scopusSourceId);
        } else {
            journalInfo.put("title", "Revista desconeguda");
            journalInfo.put("issns", List.of());
            journalInfo.put("scopusSourceId", null);
        }

        // 2. Build filters for articles
        Document filter = new Document("journalAssociation.journal.uuid", journalUuid);

        if (hasScopusId != null) {
            if (hasScopusId) {
                filter.append("identifiers.idSource", "Scopus");
            } else {
                filter.append("identifiers", new Document("$not", new Document("$elemMatch", new Document("idSource", "Scopus"))));
            }
        }

        if (search != null && !search.isBlank()) {
            Pattern searchRegex = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            filter.append("$or", List.of(
                new Document("title.value", searchRegex),
                new Document("contributors.name.lastName", searchRegex),
                new Document("electronicVersions.doi", searchRegex)
            ));
        }

        long totalMatching = roCollection.countDocuments(filter);

        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);

        Document projection = new Document("uuid", 1)
                .append("title.value", 1)
                .append("submissionYear", 1)
                .append("electronicVersions.doi", 1)
                .append("identifiers", 1)
                .append("contributors", 1)
                .append("type.term", 1);

        List<Map<String, Object>> articles = new ArrayList<>();
        for (Document roDoc : roCollection.find(filter)
                .projection(projection)
                .sort(new Document("submissionYear", -1).append("title.value", 1))
                .skip(safeOffset)
                .limit(safeLimit)) {

            String uuid = roDoc.getString("uuid");

            // Extract Title
            String title = "Sense títol";
            Document titleDoc = (Document) roDoc.get("title");
            if (titleDoc != null && titleDoc.getString("value") != null) {
                title = titleDoc.getString("value");
            }

            Integer year = roDoc.getInteger("submissionYear");

            // Extract DOI
            String doi = null;
            List<?> evList = roDoc.get("electronicVersions", List.class);
            if (evList != null) {
                for (Object evObj : evList) {
                    if (evObj instanceof Document ev && ev.getString("doi") != null && !ev.getString("doi").isBlank()) {
                        doi = ev.getString("doi").trim();
                        break;
                    }
                }
            }

            // Extract Scopus ID
            String scopusId = null;
            List<?> idList = roDoc.get("identifiers", List.class);
            if (idList != null) {
                for (Object idObj : idList) {
                    if (idObj instanceof Document idDoc && "Scopus".equalsIgnoreCase(idDoc.getString("idSource"))) {
                        String val = idDoc.getString("value");
                        if (val != null && !val.isBlank()) {
                            scopusId = val.trim();
                            break;
                        }
                    }
                }
            }

            // Extract Authors
            List<?> contribList = roDoc.get("contributors", List.class);
            List<String> authorNames = new ArrayList<>();
            if (contribList != null) {
                int count = 0;
                for (Object cObj : contribList) {
                    if (cObj instanceof Document cDoc) {
                        Document nameDoc = (Document) cDoc.get("name");
                        if (nameDoc != null) {
                            String ln = nameDoc.getString("lastName");
                            String fn = nameDoc.getString("firstName");
                            String full = ((ln != null ? ln : "") + (fn != null ? ", " + fn : "")).trim();
                            if (full.startsWith(",")) full = full.substring(1).trim();
                            if (!full.isEmpty()) {
                                if (count < 3) {
                                    authorNames.add(full);
                                }
                                count++;
                            }
                        }
                    }
                }
                if (count > 3) {
                    authorNames.add("+" + (count - 3) + " més");
                }
            }

            // Extract Type
            String typeName = "Article";
            Document typeDoc = (Document) roDoc.get("type");
            if (typeDoc != null) {
                Document termDoc = (Document) typeDoc.get("term");
                if (termDoc != null) {
                    if (termDoc.getString("ca_ES") != null) typeName = termDoc.getString("ca_ES");
                    else if (termDoc.getString("es_ES") != null) typeName = termDoc.getString("es_ES");
                    else if (termDoc.getString("en_GB") != null) typeName = termDoc.getString("en_GB");
                }
            }

            Map<String, Object> articleMap = new LinkedHashMap<>();
            articleMap.put("uuid", uuid);
            articleMap.put("title", title);
            articleMap.put("submissionYear", year);
            articleMap.put("authors", String.join("; ", authorNames));
            articleMap.put("doi", doi);
            articleMap.put("hasScopusId", scopusId != null);
            articleMap.put("scopusId", scopusId);
            articleMap.put("typeName", typeName);

            articles.add(articleMap);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("journal", journalInfo);
        response.put("total", totalMatching);
        response.put("limit", safeLimit);
        response.put("offset", safeOffset);
        response.put("env", env);
        response.put("database", dbName);
        response.put("articles", articles);

        return response;
    }

    public Map<String, Object> lookupScopusId(String articleUuid, String userApiKey) {
        return lookupScopusId(articleUuid, userApiKey, "prod");
    }

    public Map<String, Object> lookupScopusId(String articleUuid, String userApiKey, String targetEnv) {
        String env = (targetEnv != null && !targetEnv.isBlank()) ? targetEnv.trim().toLowerCase() : "prod";
        String dbName = resolveDatabaseName(env);

        MongoCollection<Document> roCollection = getCollection("Researchoutputs", env);
        Document article = roCollection.find(new Document("uuid", articleUuid)).first();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("uuid", articleUuid);
        response.put("env", env);
        response.put("database", dbName);

        if (article == null) {
            response.put("error", "Article no trobat");
            return response;
        }

        // 1. Check if it already has a Scopus ID in MongoDB
        List<?> identifiers = article.get("identifiers", List.class);
        if (identifiers != null) {
            for (Object idObj : identifiers) {
                if (idObj instanceof Document idDoc && "Scopus".equalsIgnoreCase(idDoc.getString("idSource"))) {
                    String val = idDoc.getString("value");
                    if (val != null && !val.isBlank()) {
                        response.put("found", true);
                        response.put("scopusId", val.trim());
                        response.put("source", "database");
                        response.put("directUrl", "https://www.scopus.com/record/display.uri?eid=2-s2.0-" + val.trim() + "&origin=resultslist");
                        return response;
                    }
                }
            }
        }

        // 2. Extract DOI, Title, Year and Authors
        String doi = null;
        List<?> evList = article.get("electronicVersions", List.class);
        if (evList != null) {
            for (Object evObj : evList) {
                if (evObj instanceof Document ev && ev.getString("doi") != null && !ev.getString("doi").isBlank()) {
                    doi = ev.getString("doi").trim();
                    break;
                }
            }
        }

        String title = null;
        Document titleDoc = (Document) article.get("title");
        if (titleDoc != null && titleDoc.getString("value") != null) {
            title = titleDoc.getString("value").trim();
        }

        Integer year = article.getInteger("submissionYear");
        String volume = article.getString("volume");
        String pages = article.getString("pages");

        List<String> authorLastNames = new ArrayList<>();
        List<?> contribList = article.get("contributors", List.class);
        if (contribList != null) {
            for (Object cObj : contribList) {
                if (cObj instanceof Document cDoc) {
                    Document nameDoc = (Document) cDoc.get("name");
                    if (nameDoc != null) {
                        String ln = nameDoc.getString("lastName");
                        if (ln != null && !ln.isBlank()) {
                            authorLastNames.add(ln.trim());
                        }
                    }
                }
            }
        }
        if (authorLastNames.isEmpty()) {
            List<?> paList = article.get("personAssociations", List.class);
            if (paList != null) {
                for (Object paObj : paList) {
                    if (paObj instanceof Document paDoc) {
                        Document nameDoc = (Document) paDoc.get("name");
                        if (nameDoc == null && paDoc.get("person") instanceof Document pDoc) {
                            nameDoc = (Document) pDoc.get("name");
                        }
                        if (nameDoc != null) {
                            String ln = nameDoc.getString("lastName");
                            if (ln != null && !ln.isBlank()) {
                                authorLastNames.add(ln.trim());
                            }
                        }
                    }
                }
            }
        }

        response.put("doi", doi);
        response.put("title", title);
        response.put("submissionYear", year);
        response.put("volume", volume);
        response.put("pages", pages);
        response.put("authors", authorLastNames);

        // 3. Build Direct Scopus Search Web URL (includes author for better web accuracy)
        String directSearchUrl;
        if (doi != null && !doi.isBlank()) {
            directSearchUrl = "https://www.scopus.com/results/results.uri?src=s&s=DOI(%22" + URLEncoder.encode(doi, StandardCharsets.UTF_8) + "%22)";
        } else if (title != null && !title.isBlank()) {
            String scopusWebQuery = "TITLE(\"" + title.replace("\"", "") + "\")";
            if (!authorLastNames.isEmpty()) {
                scopusWebQuery += " AND AUTH(\"" + authorLastNames.get(0).replace("\"", "") + "\")";
            }
            directSearchUrl = "https://www.scopus.com/results/results.uri?src=s&s=" + URLEncoder.encode(scopusWebQuery, StandardCharsets.UTF_8);
        } else {
            directSearchUrl = "https://www.scopus.com/search/form.uri?display=basic";
        }
        response.put("searchUrl", directSearchUrl);

        // 4. Determine effective API Key
        String effectiveKey = (userApiKey != null && !userApiKey.isBlank()) ? userApiKey.trim() : (scopusApiKey != null ? scopusApiKey.trim() : "");

        if (effectiveKey.isBlank()) {
            response.put("found", false);
            response.put("requiresApiKey", true);
            response.put("message", "Clau d'API de Scopus no configurada. Pots fer servir l'enllaç de cerca directa o introduir la teva clau.");
            return response;
        }

        // 5. Query Elsevier Scopus API: Priority 1 - DOI (if present)
        if (doi != null && !doi.isBlank()) {
            Map<String, Object> doiResult = queryScopusApiByDoi(doi, effectiveKey);
            if (Boolean.TRUE.equals(doiResult.get("found"))) {
                response.put("found", true);
                response.put("scopusId", doiResult.get("scopusId"));
                response.put("eid", doiResult.get("eid"));
                response.put("scopusTitle", doiResult.get("scopusTitle"));
                response.put("source", "api_doi");
                response.put("directUrl", doiResult.get("directUrl"));
                return response;
            }
        }

        // 6. Query Elsevier Scopus API: Priority 2 - Title + Verified Authors match
        if (title != null && !title.isBlank()) {
            Map<String, Object> titleResult = queryScopusApiByTitleAndAuthors(title, authorLastNames, year, volume, pages, effectiveKey);
            if (titleResult.containsKey("scopusDoi") && response.get("doi") == null) {
                response.put("scopusDoi", titleResult.get("scopusDoi"));
            }
            if (Boolean.TRUE.equals(titleResult.get("found"))) {
                response.put("found", true);
                response.put("scopusId", titleResult.get("scopusId"));
                response.put("eid", titleResult.get("eid"));
                response.put("scopusTitle", titleResult.get("scopusTitle"));
                response.put("scopusCreator", titleResult.get("scopusCreator"));
                response.put("matchScore", titleResult.get("matchScore"));
                response.put("source", "api_title_author_verified");
                response.put("directUrl", titleResult.get("directUrl"));
                return response;
            } else {
                response.put("found", false);
                response.put("message", titleResult.getOrDefault("message", "No s'ha trobat cap resultat a Scopus que coincideixi amb els autors i títol."));
                response.put("candidatesFound", titleResult.get("candidatesFound"));
                return response;
            }
        }

        response.put("found", false);
        response.put("message", "L'article no té DOI ni títol per cercar a l'API de Scopus.");
        return response;
    }

    public Map<String, Object> assignScopusId(String articleUuid, String scopusId, String targetEnv) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (scopusId == null || scopusId.isBlank()) {
            response.put("success", false);
            response.put("message", "El Scopus ID no pot estar buit.");
            return response;
        }

        String cleanScopusId = scopusId.trim();
        if (cleanScopusId.startsWith("SCOPUS_ID:")) {
            cleanScopusId = cleanScopusId.substring("SCOPUS_ID:".length()).trim();
        } else if (cleanScopusId.startsWith("2-s2.0-")) {
            cleanScopusId = cleanScopusId.substring("2-s2.0-".length()).trim();
        }

        String env = (targetEnv != null && !targetEnv.isBlank()) ? targetEnv.trim().toLowerCase() : "prod";
        boolean isTest = "test".equalsIgnoreCase(env);
        String dbName = resolveDatabaseName(env);
        String envName = isTest ? "Test (egretat.uab.cat | kraken_test)" : "Producció (egreta.uab.cat | kraken)";

        MongoCollection<Document> roCollection = getCollection("Researchoutputs", env);
        Document article = roCollection.find(new Document("uuid", articleUuid)).first();
        if (article == null) {
            response.put("success", false);
            response.put("message", "Article no trobat a la base de dades " + dbName + ".");
            return response;
        }

        boolean mongoUpdated = false;

        // Actualitzar MongoDB a la base de dades corresponent (kraken_test o kraken)
        List<?> identifiers = article.get("identifiers", List.class);
        boolean exists = false;
        if (identifiers != null) {
            for (Object idObj : identifiers) {
                if (idObj instanceof Document idDoc && "Scopus".equalsIgnoreCase(idDoc.getString("idSource"))) {
                    exists = true;
                    break;
                }
            }
        }

        if (!exists) {
            Document newIdent = new Document("typeDiscriminator", "Id")
                    .append("idSource", "Scopus")
                    .append("value", cleanScopusId);

            roCollection.updateOne(
                    new Document("uuid", articleUuid),
                    new Document("$push", new Document("identifiers", newIdent))
            );
        } else {
            // Update existing Scopus identifier
            roCollection.updateOne(
                    new Document("uuid", articleUuid).append("identifiers.idSource", "Scopus"),
                    new Document("$set", new Document("identifiers.$.value", cleanScopusId))
            );
        }

        // Invalidate stats cache for this specific environment so numbers reflect the change immediately
        invalidateCache(env);
        mongoUpdated = true;
        log.info("ScopusID '{}' desat correctament a MongoDB '{}' (env: {}) per a l'article {}",
                cleanScopusId, dbName, env, articleUuid);

        // Synchronize directly with Egreta (Pure) via EgretaApiService
        String egretaError = null;
        try {
            egretaError = egretaApiService.syncScopusIdToEgreta(articleUuid, cleanScopusId, env);
        } catch (Exception e) {
            log.error("Error syncing Scopus ID {} for article {} to Egreta ({})", cleanScopusId, articleUuid, env, e);
            egretaError = e.getMessage();
        }

        boolean egretaSynced = egretaError == null;
        boolean overallSuccess = isTest ? egretaSynced : true;

        String message;
        if (isTest) {
            if (egretaSynced) {
                message = "Sincronitzat amb èxit a Egreta Test (egretat.uab.cat). La base de dades MongoDB no s'ha modificat.";
            } else {
                message = "Error al sincronitzar a Egreta Test (egretat.uab.cat): " + egretaError;
            }
        } else {
            if (egretaSynced) {
                message = "Desat a MongoDB i sincronitzat amb èxit a Egreta Producció (egreta.uab.cat).";
            } else {
                message = "Desat a MongoDB, però Egreta Producció (egreta.uab.cat) ha retornat: " + egretaError;
            }
        }

        response.put("success", overallSuccess);
        response.put("uuid", articleUuid);
        response.put("scopusId", cleanScopusId);
        response.put("targetEnv", env);
        response.put("database", dbName);
        response.put("mongoUpdated", mongoUpdated);
        response.put("egretaSynced", egretaSynced);
        response.put("directUrl", "https://www.scopus.com/record/display.uri?eid=2-s2.0-" + cleanScopusId + "&origin=resultslist");
        response.put("egretaMessage", message);
        response.put("message", message);

        return response;
    }

    public Map<String, Object> queryScopusApiByDoi(String doi, String userApiKey) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (doi == null || doi.isBlank()) {
            response.put("found", false);
            response.put("message", "DOI buit.");
            return response;
        }

        String cleanDoi = doi.trim();
        if (cleanDoi.startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());
        else if (cleanDoi.startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
        else if (cleanDoi.startsWith("http://dx.doi.org/")) cleanDoi = cleanDoi.substring("http://dx.doi.org/".length());
        else if (cleanDoi.startsWith("https://dx.doi.org/")) cleanDoi = cleanDoi.substring("https://dx.doi.org/".length());

        String effectiveKey = (userApiKey != null && !userApiKey.isBlank()) ? userApiKey.trim() : (scopusApiKey != null ? scopusApiKey.trim() : "");
        if (effectiveKey.isBlank()) {
            response.put("found", false);
            response.put("requiresApiKey", true);
            response.put("message", "Clau d'API de Scopus no configurada.");
            return response;
        }

        try {
            String queryStr = "DOI(\"" + cleanDoi.replace("\"", "") + "\")";
            String url = "https://api.elsevier.com/content/search/scopus?query=" + URLEncoder.encode(queryStr, StandardCharsets.UTF_8);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ELS-APIKey", effectiveKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                Document doc = Document.parse(httpResponse.body());
                Document searchResults = (Document) doc.get("search-results");
                if (searchResults != null) {
                    List<?> entries = searchResults.get("entry", List.class);
                    if (entries != null && !entries.isEmpty() && entries.get(0) instanceof Document firstEntry) {
                        String dcId = firstEntry.getString("dc:identifier");
                        String eid = firstEntry.getString("eid");
                        String scopusId = null;

                        if (dcId != null && dcId.startsWith("SCOPUS_ID:")) {
                            scopusId = dcId.substring("SCOPUS_ID:".length()).trim();
                        } else if (eid != null && eid.startsWith("2-s2.0-")) {
                            scopusId = eid.substring("2-s2.0-".length()).trim();
                        } else if (dcId != null && !dcId.isBlank()) {
                            scopusId = dcId.trim();
                        }

                        if (scopusId != null && !scopusId.isBlank()) {
                            response.put("found", true);
                            response.put("scopusId", scopusId);
                            response.put("eid", eid != null ? eid : "2-s2.0-" + scopusId);
                            response.put("scopusTitle", firstEntry.getString("dc:title"));
                            response.put("directUrl", "https://www.scopus.com/record/display.uri?eid=2-s2.0-" + scopusId + "&origin=resultslist");
                            return response;
                        }
                    }
                }
                response.put("found", false);
                response.put("message", "No s'ha trobat cap resultat a Scopus per aquest DOI.");
            } else {
                response.put("found", false);
                response.put("apiError", "HTTP " + httpResponse.statusCode());
                response.put("message", "Error de l'API de Scopus (HTTP " + httpResponse.statusCode() + ").");
            }
        } catch (Exception e) {
            log.error("Error querying Scopus API for DOI {}", cleanDoi, e);
            response.put("found", false);
            response.put("error", e.getMessage());
        }

        return response;
    }

    public Map<String, Object> getBatchCandidatesStats(String journalUuid) {
        return getBatchCandidatesStats(journalUuid, "prod");
    }

    public Map<String, Object> getBatchCandidatesStats(String journalUuid, String targetEnv) {
        String env = (targetEnv != null && !targetEnv.isBlank()) ? targetEnv.trim().toLowerCase() : "prod";
        String dbName = resolveDatabaseName(env);

        MongoCollection<Document> roCollection = getCollection("Researchoutputs", env);
        MongoCollection<Document> jCollection = getCollection("Journals", env);

        Document filter = new Document();
        filter.append("identifiers", new Document("$not", new Document("$elemMatch", new Document("idSource", "Scopus"))));
        filter.append("title.value", new Document("$exists", true).append("$ne", null).append("$ne", ""));

        if (journalUuid != null && !journalUuid.isBlank()) {
            filter.append("journalAssociation.journal.uuid", journalUuid.trim());
        } else {
            // Restringit a revistes de Scopus
            List<String> scopusJournalUuids = jCollection.distinct("uuid", new Document("identifiers.idSource", "Scopus"), String.class).into(new ArrayList<>());
            filter.append("journalAssociation.journal.uuid", new Document("$in", scopusJournalUuids));
        }

        long count = roCollection.countDocuments(filter);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("candidatesCount", count);
        res.put("journalUuid", journalUuid);
        res.put("env", env);
        res.put("database", dbName);
        return res;
    }

    public synchronized Map<String, Object> startBatchJob(int limit, boolean dryRun, String targetEnv, String journalUuid, String apiKey) {
        if (activeJob != null && activeJob.running) {
            return getBatchJobStatus();
        }

        String env = (targetEnv != null && !targetEnv.isBlank()) ? targetEnv.trim().toLowerCase() : "prod";
        String dbName = resolveDatabaseName(env);
        String effectiveKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : (scopusApiKey != null ? scopusApiKey.trim() : "");

        MongoCollection<Document> roCollection = getCollection("Researchoutputs", env);
        MongoCollection<Document> jCollection = getCollection("Journals", env);

        Document filter = new Document();
        filter.append("identifiers", new Document("$not", new Document("$elemMatch", new Document("idSource", "Scopus"))));
        filter.append("title.value", new Document("$exists", true).append("$ne", null).append("$ne", ""));

        if (journalUuid != null && !journalUuid.isBlank()) {
            filter.append("journalAssociation.journal.uuid", journalUuid.trim());
        } else {
            List<String> scopusJournalUuids = jCollection.distinct("uuid", new Document("identifiers.idSource", "Scopus"), String.class).into(new ArrayList<>());
            filter.append("journalAssociation.journal.uuid", new Document("$in", scopusJournalUuids));
        }

        Document projection = new Document("uuid", 1)
                .append("title.value", 1)
                .append("electronicVersions.doi", 1)
                .append("submissionYear", 1);

        var cursor = roCollection.find(filter)
                .projection(projection)
                .sort(new Document("electronicVersions.doi", -1).append("submissionYear", -1));

        if (limit > 0) {
            cursor = cursor.limit(limit);
        }

        List<Document> candidates = cursor.into(new ArrayList<>());

        BatchJobProgress job = new BatchJobProgress();
        job.jobId = UUID.randomUUID().toString();
        job.startedAt = Instant.now();
        job.running = true;
        job.cancelled = false;
        job.total = candidates.size();
        job.dryRun = dryRun;
        job.targetEnv = env;
        job.journalUuid = journalUuid;
        activeJob = job;

        // Launch background execution
        batchExecutor.submit(() -> {
            log.info("Iniciant batch assignació ScopusID (total: {}, dryRun: {}, env: {})", job.total, job.dryRun, job.targetEnv);
            try {
                for (Document artDoc : candidates) {
                    if (job.cancelled) {
                        log.info("Batch assignació cancel·lat per l'usuari (jobId: {})", job.jobId);
                        break;
                    }

                    String uuid = artDoc.getString("uuid");
                    String title = "Sense títol";
                    Document titleDoc = (Document) artDoc.get("title");
                    if (titleDoc != null && titleDoc.getString("value") != null) {
                        title = titleDoc.getString("value");
                    }
                    job.currentTitle = title;

                    // Delay to respect Scopus API rate limits (150ms)
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    // Procés normal: comprova primer per DOI; si no el troba (o no en té), fa la cerca complexa per títol i autors
                    Map<String, Object> queryRes = lookupScopusId(uuid, effectiveKey);
                    boolean found = Boolean.TRUE.equals(queryRes.get("found"));
                    String scopusId = (String) queryRes.get("scopusId");
                    String foundSource = (String) queryRes.get("source");
                    String doi = (String) queryRes.get("doi");
                    if (doi == null && queryRes.get("scopusDoi") != null) {
                        doi = (String) queryRes.get("scopusDoi");
                    }

                    Map<String, Object> logEntry = new LinkedHashMap<>();
                    logEntry.put("uuid", uuid);
                    logEntry.put("title", title);
                    logEntry.put("doi", doi);
                    logEntry.put("scopusId", scopusId);
                    logEntry.put("source", foundSource);
                    logEntry.put("timestamp", Instant.now().toString());

                    if (found && scopusId != null) {
                        job.foundCount++;
                        String matchInfo = "api_doi".equals(foundSource)
                                ? "Trobat per DOI"
                                : ("Trobat per cerca complexa (" + queryRes.getOrDefault("matchScore", 100) + "%)");

                        if (job.dryRun) {
                            logEntry.put("status", "DRY_RUN_FOUND");
                            logEntry.put("message", matchInfo + ": Scopus ID " + scopusId + " (Simulació, no desat)");
                        } else {
                            Map<String, Object> assignRes = assignScopusId(uuid, scopusId, job.targetEnv);
                            boolean success = Boolean.TRUE.equals(assignRes.get("success"));
                            if (success) {
                                job.assignedCount++;
                                logEntry.put("status", "ASSIGNED");
                                logEntry.put("message", matchInfo + " | " + assignRes.get("egretaMessage"));
                            } else {
                                job.errorCount++;
                                logEntry.put("status", "ERROR");
                                logEntry.put("message", assignRes.get("message"));
                            }
                        }
                    } else {
                        job.notFoundCount++;
                        logEntry.put("status", "NOT_FOUND");
                        logEntry.put("message", queryRes.getOrDefault("message", "No trobat a Scopus"));
                    }

                    // Keep up to 250 most recent log entries
                    if (job.logs.size() >= 250) {
                        job.logs.remove(0);
                    }
                    job.logs.add(logEntry);
                    job.processed++;
                }
            } catch (Exception ex) {
                log.error("Error durant l'execució del batch Scopus", ex);
                job.errorCount++;
            } finally {
                job.running = false;
                job.finishedAt = Instant.now();
                job.currentTitle = null;
                log.info("Batch Scopus finalitzat. Total: {}, Processats: {}, Tro bats: {}, Assignats: {}",
                        job.total, job.processed, job.foundCount, job.assignedCount);
            }
        });

        return getBatchJobStatus();
    }

    public Map<String, Object> getBatchJobStatus() {
        Map<String, Object> res = new LinkedHashMap<>();
        BatchJobProgress job = activeJob;
        if (job == null) {
            res.put("hasActiveJob", false);
            return res;
        }

        res.put("hasActiveJob", true);
        res.put("jobId", job.jobId);
        res.put("running", job.running);
        res.put("cancelled", job.cancelled);
        res.put("startedAt", job.startedAt != null ? job.startedAt.toString() : null);
        res.put("finishedAt", job.finishedAt != null ? job.finishedAt.toString() : null);
        res.put("total", job.total);
        res.put("processed", job.processed);
        res.put("foundCount", job.foundCount);
        res.put("assignedCount", job.assignedCount);
        res.put("notFoundCount", job.notFoundCount);
        res.put("errorCount", job.errorCount);
        res.put("dryRun", job.dryRun);
        res.put("targetEnv", job.targetEnv);
        res.put("currentTitle", job.currentTitle);
        int percent = job.total > 0 ? (int) Math.round(((double) job.processed / job.total) * 100) : 0;
        res.put("progressPercent", percent);
        res.put("logs", new ArrayList<>(job.logs));
        return res;
    }

    public synchronized Map<String, Object> stopBatchJob() {
        BatchJobProgress job = activeJob;
        Map<String, Object> res = new LinkedHashMap<>();
        if (job != null && job.running) {
            job.cancelled = true;
            res.put("stopped", true);
            res.put("message", "S'ha enviat la petició d'aturada del procés.");
        } else {
            res.put("stopped", false);
            res.put("message", "No hi ha cap procés en execució.");
        }
        return res;
    }

    private static int getIntVal(Object val) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static double roundPercent(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public Map<String, Object> queryScopusApiByTitleAndAuthors(String title, List<String> authors, Integer year, String userApiKey) {
        return queryScopusApiByTitleAndAuthors(title, authors, year, null, null, userApiKey);
    }

    public Map<String, Object> queryScopusApiByTitleAndAuthors(String title, List<String> authors, Integer year, String volume, String pages, String userApiKey) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (title == null || title.isBlank()) {
            response.put("found", false);
            response.put("message", "Títol buit.");
            return response;
        }

        String effectiveKey = (userApiKey != null && !userApiKey.isBlank()) ? userApiKey.trim() : (scopusApiKey != null ? scopusApiKey.trim() : "");
        if (effectiveKey.isBlank()) {
            response.put("found", false);
            response.put("requiresApiKey", true);
            response.put("message", "Clau d'API de Scopus no configurada.");
            return response;
        }

        String cleanTitle = title.replaceAll("[\"\\:\\*\\?\\[\\]\\{\\}\\(\\)\\\\]", " ").replaceAll("\\s+", " ").trim();
        String firstAuthor = (authors != null && !authors.isEmpty())
                ? authors.get(0).replaceAll("[\"\\:\\*\\?\\[\\]\\{\\}\\(\\)\\\\]", " ").trim()
                : null;

        // Query 1: Exact title phrase + author
        String queryStr = "TITLE(\"" + cleanTitle + "\")";
        if (firstAuthor != null && !firstAuthor.isBlank()) {
            queryStr += " AND AUTH(\"" + firstAuthor + "\")";
        }

        List<Document> entries = fetchScopusEntries(queryStr, effectiveKey, 10);

        // Query 2 fallback: If no entries and we had firstAuthor, search title without author filter in query
        if (entries.isEmpty() && firstAuthor != null) {
            String fallbackQuery = "TITLE(\"" + cleanTitle + "\")";
            entries = fetchScopusEntries(fallbackQuery, effectiveKey, 10);
        }

        // Query 3 fallback: If still empty, search using significant words
        if (entries.isEmpty()) {
            List<String> words = new ArrayList<>();
            for (String w : cleanTitle.split("\\s+")) {
                if (w.length() > 3) words.add(w);
                if (words.size() >= 7) break;
            }
            if (words.size() >= 3) {
                String phraseQuery = "TITLE(" + String.join(" ", words) + ")";
                if (firstAuthor != null) {
                    phraseQuery += " AND AUTH(\"" + firstAuthor + "\")";
                }
                entries = fetchScopusEntries(phraseQuery, effectiveKey, 10);
            }
        }

        if (entries.isEmpty()) {
            response.put("found", false);
            response.put("candidatesFound", 0);
            response.put("message", "No s'ha trobat cap entrada a Scopus per aquest títol.");
            return response;
        }

        // Evaluate candidates strictly: Author MUST match, title overlap >= 70%, and year within +/-1
        Document bestCandidate = null;
        double bestScore = 0.0;
        int evaluatedCount = 0;

        for (Document entry : entries) {
            if (entry.containsKey("error")) continue;
            evaluatedCount++;

            String scopusTitle = entry.getString("dc:title");
            String scopusCreator = entry.getString("dc:creator");
            String coverDate = entry.getString("prism:coverDate");

            boolean authorOk = matchesAuthor(scopusCreator, authors);
            double titleScore = computeTitleOverlap(title, scopusTitle);
            boolean yearOk = matchesYear(coverDate, year);

            // Multilingual / Translated titles check:
            // Scopus search entry often translates non-English titles (Spanish, Catalan, etc.) into English.
            // If the title overlap is low but author and year match, fetch the abstract record
            // which contains both the English translation and the original non-English title!
            if (titleScore < 0.70 && authorOk && yearOk) {
                String candidateId = extractScopusIdFromEntry(entry);
                if (candidateId != null && !candidateId.isBlank()) {
                    String absTitle = fetchScopusAbstractTitle(candidateId, effectiveKey);
                    if (absTitle != null && !absTitle.isBlank()) {
                        double absScore = computeTitleOverlap(title, absTitle);
                        if (absScore >= 0.70) {
                            titleScore = absScore;
                            scopusTitle = absTitle;
                        }
                    }
                }
            }

            boolean accepted = false;
            if (authors != null && !authors.isEmpty()) {
                if (authorOk && titleScore >= 0.70 && yearOk) {
                    accepted = true;
                } else if (authorOk && yearOk && matchesBibliographic(entry.getString("prism:volume"), entry.getString("prism:pageRange"), volume, pages)) {
                    accepted = true;
                    if (titleScore < 0.70) titleScore = 0.90;
                }
            } else {
                if (titleScore >= 0.85 && yearOk) {
                    accepted = true;
                }
            }

            if (accepted && titleScore > bestScore) {
                bestScore = titleScore;
                bestCandidate = entry;
            }
        }

        if (bestCandidate != null) {
            String dcId = bestCandidate.getString("dc:identifier");
            String eid = bestCandidate.getString("eid");
            String scopusId = null;

            if (dcId != null && dcId.startsWith("SCOPUS_ID:")) {
                scopusId = dcId.substring("SCOPUS_ID:".length()).trim();
            } else if (eid != null && eid.startsWith("2-s2.0-")) {
                scopusId = eid.substring("2-s2.0-".length()).trim();
            } else if (dcId != null && !dcId.isBlank()) {
                scopusId = dcId.trim();
            }

            if (scopusId != null && !scopusId.isBlank()) {
                response.put("found", true);
                response.put("scopusId", scopusId);
                response.put("eid", eid != null ? eid : "2-s2.0-" + scopusId);
                response.put("scopusTitle", bestCandidate.getString("dc:title"));
                response.put("scopusCreator", bestCandidate.getString("dc:creator"));
                response.put("matchScore", Math.round(bestScore * 100));
                if (bestCandidate.getString("prism:doi") != null && !bestCandidate.getString("prism:doi").isBlank()) {
                    response.put("scopusDoi", bestCandidate.getString("prism:doi").trim());
                }
                response.put("directUrl", "https://www.scopus.com/record/display.uri?eid=2-s2.0-" + scopusId + "&origin=resultslist");
                return response;
            }
        }

        response.put("found", false);
        response.put("candidatesFound", evaluatedCount);
        response.put("message", evaluatedCount > 0
                ? "S'han trobat " + evaluatedCount + " resultats a Scopus, però s'han descartat perquè no coincideixen els autors i títol exactament."
                : "No s'ha trobat cap resultat a Scopus.");
        return response;
    }

    private List<Document> fetchScopusEntries(String queryStr, String apiKey, int count) {
        try {
            String url = "https://api.elsevier.com/content/search/scopus?query=" 
                    + URLEncoder.encode(queryStr, StandardCharsets.UTF_8) + "&count=" + count;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ELS-APIKey", apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() == 200) {
                Document doc = Document.parse(httpResponse.body());
                Document searchResults = (Document) doc.get("search-results");
                if (searchResults != null) {
                    List<?> rawList = searchResults.get("entry", List.class);
                    if (rawList != null) {
                        List<Document> list = new ArrayList<>();
                        for (Object o : rawList) {
                            if (o instanceof Document d) list.add(d);
                        }
                        return list;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error fetching Scopus entries for query '{}': {}", queryStr, e.getMessage());
        }
        return Collections.emptyList();
    }

    public static String normalizeText(String text) {
        if (text == null || text.isBlank()) return "";
        String nfd = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        String noAccents = nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String clean = noAccents.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        return clean.trim().replaceAll("\\s+", " ");
    }

    public static boolean matchesAuthor(String scopusCreator, List<String> articleAuthors) {
        if (scopusCreator == null || scopusCreator.isBlank() || articleAuthors == null || articleAuthors.isEmpty()) {
            return false;
        }
        String normCreator = normalizeText(scopusCreator);
        Set<String> creatorTokens = new HashSet<>(Arrays.asList(normCreator.split("\\s+")));

        for (String auth : articleAuthors) {
            String normAuth = normalizeText(auth);
            if (normAuth.isBlank()) continue;

            if (normCreator.contains(normAuth) || normAuth.contains(normCreator)) {
                return true;
            }

            String[] parts = normAuth.split("\\s+");
            for (String p : parts) {
                if (p.length() > 2 && creatorTokens.contains(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static double computeTitleOverlap(String title1, String title2) {
        if (title1 == null || title2 == null) return 0.0;
        String n1 = normalizeText(title1);
        String n2 = normalizeText(title2);
        if (n1.equals(n2)) return 1.0;

        Set<String> w1 = filterMeaningfulWords(n1);
        Set<String> w2 = filterMeaningfulWords(n2);
        if (w1.isEmpty() || w2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(w1);
        intersection.retainAll(w2);

        return (double) intersection.size() / Math.min(w1.size(), w2.size());
    }

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "sobre", "para", "como", "dels", "les", "los", "las", "del", "por", "que", "una", "uno", "unos", "unas"
    );

    private static Set<String> filterMeaningfulWords(String normalized) {
        Set<String> result = new HashSet<>();
        for (String w : normalized.split("\\s+")) {
            if (w.length() > 2 && !STOP_WORDS.contains(w)) {
                result.add(w);
            }
        }
        return result;
    }

    public static String extractScopusIdFromEntry(Document entry) {
        if (entry == null) return null;
        String dcId = entry.getString("dc:identifier");
        if (dcId != null && dcId.startsWith("SCOPUS_ID:")) {
            return dcId.substring("SCOPUS_ID:".length()).trim();
        }
        String eid = entry.getString("eid");
        if (eid != null && eid.startsWith("2-s2.0-")) {
            return eid.substring("2-s2.0-".length()).trim();
        }
        if (dcId != null && !dcId.isBlank()) {
            return dcId.trim();
        }
        return null;
    }

    private String fetchScopusAbstractTitle(String scopusId, String apiKey) {
        if (scopusId == null || scopusId.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            String url = "https://api.elsevier.com/content/abstract/scopus_id/" + scopusId.trim();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-ELS-APIKey", apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() == 200) {
                Document doc = Document.parse(httpResponse.body());
                Document resp = (Document) doc.get("abstracts-retrieval-response");
                if (resp != null) {
                    Document core = (Document) resp.get("coredata");
                    if (core != null && core.getString("dc:title") != null) {
                        return core.getString("dc:title");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error fetching Scopus abstract title for ID {}: {}", scopusId, e.getMessage());
        }
        return null;
    }

    public static boolean matchesBibliographic(String scopusVolume, String scopusPageRange, String articleVolume, String articlePages) {
        if (articlePages != null && !articlePages.isBlank() && scopusPageRange != null && !scopusPageRange.isBlank()) {
            String normArtPages = articlePages.replaceAll("\\s+", "").toLowerCase();
            String normScopusPages = scopusPageRange.replaceAll("\\s+", "").toLowerCase();
            if (normArtPages.equals(normScopusPages) || normArtPages.contains(normScopusPages) || normScopusPages.contains(normArtPages)) {
                return true;
            }
        }
        if (articleVolume != null && !articleVolume.isBlank() && scopusVolume != null && !scopusVolume.isBlank()) {
            if (articleVolume.trim().equalsIgnoreCase(scopusVolume.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesYear(String scopusCoverDate, Integer articleYear) {
        if (articleYear == null || scopusCoverDate == null || scopusCoverDate.isBlank()) {
            return true;
        }
        try {
            String yearStr = scopusCoverDate.trim().substring(0, 4);
            int y = Integer.parseInt(yearStr);
            return Math.abs(y - articleYear) <= 1;
        } catch (Exception e) {
            return true;
        }
    }
}
