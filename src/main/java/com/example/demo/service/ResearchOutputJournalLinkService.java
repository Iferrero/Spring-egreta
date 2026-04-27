package com.example.demo.service;

import com.example.demo.model.Jcr;
import com.example.demo.model.Journal;
import com.example.demo.repository.JournalRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

@Service
public class ResearchOutputJournalLinkService {

    private static final String RESEARCHOUTPUTS_COLLECTION = "Researchoutputs";
    private static final String PERSONS_COLLECTION = "Persons";
    private static final long QUARTILES_CACHE_TTL_MS = 600_000;
    private static final long JOURNAL_UUID_CACHE_TTL_MS = 600_000;

    private final MongoTemplate mongoTemplate;
    private final JournalRepository journalRepository;
    private final JournalJcrService journalJcrService;
    private final Map<String, CacheEntry> quartilesDashboardCache = new ConcurrentHashMap<>();
    private final Map<String, JournalCacheEntry> journalUuidCache = new ConcurrentHashMap<>();

    private record CacheEntry(Map<String, Object> data, long expiresAtMs) {}
    private record JournalCacheEntry(Optional<Journal> journal, long expiresAtMs) {}

    public ResearchOutputJournalLinkService(
            MongoTemplate mongoTemplate,
            JournalRepository journalRepository,
            JournalJcrService journalJcrService) {
        this.mongoTemplate = mongoTemplate;
        this.journalRepository = journalRepository;
        this.journalJcrService = journalJcrService;
    }

    public Optional<Map<String, Object>> linkByPublicationUuid(String publicationUuid) {
        Query query = new Query(Criteria.where("uuid").is(publicationUuid));
        Document publication = mongoTemplate.findOne(query, Document.class, RESEARCHOUTPUTS_COLLECTION);
        if (publication == null) {
            return Optional.empty();
        }

        return Optional.of(buildLinkResponse(publication));
    }

    public Optional<Map<String, Object>> summarizeByPublicationUuid(String publicationUuid) {
        return linkByPublicationUuid(publicationUuid).map(this::toCompactSummary);
    }

    /**
     * Builds an APA citation string for a raw Researchoutputs document.
     * Used by AwardController to render articles in Word reports.
     */
    public String formatApaForDocument(Document publication) {
        String authors = extractAuthorsApa(publication);
        Integer year = extractYear(publication);
        Integer day = extractDatePart(publication, "day");
        Integer month = extractDatePart(publication, "month");
        String title = nestedString(publication, "title", "value");
        String journalTitle = extractJournalTitle(publication, Optional.empty());
        String volume = findFirstString(publication, List.of("journalAssociation", "volume"), List.of("volume"));
        String issue = findFirstString(publication, List.of("journalAssociation", "journalNumber"),
                List.of("journalAssociation", "issue"), List.of("issue"));
        String pages = findFirstString(publication, List.of("journalAssociation", "pages"), List.of("pages"));
        String articleNumber = findFirstString(publication, List.of("journalAssociation", "articleNumber"),
                List.of("articleNumber"));
        return buildCitationApa(authors, day, month, year, journalTitle, volume, issue, pages, articleNumber, title);
    }

    public Map<String, Object> quartilesDashboardByDepartment(String deptUuid, Integer desde, Integer hasta, String filtrePersonal, String personUuid) {
        long now = System.currentTimeMillis();
        String cacheKey = buildQuartilesCacheKey(deptUuid, desde, hasta, filtrePersonal, personUuid);
        CacheEntry cached = quartilesDashboardCache.get(cacheKey);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.data();
        }

        // Filter by year directly in the Mongo pipeline (indexed) instead of fetching all years.
        List<String> publicationUuids = publicationUuidsByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
        if (publicationUuids.isEmpty()) {
            Map<String, Object> emptyResult = Map.of(
                    "quartiles", List.of(),
                    "articles", List.of(),
                    "evolution", List.of(),
                    "openAccess", List.of()
            );
            quartilesDashboardCache.put(cacheKey, new CacheEntry(emptyResult, now + QUARTILES_CACHE_TTL_MS));
            return emptyResult;
        }

        Query publicationsQuery = new Query(Criteria.where("uuid").in(publicationUuids));
        publicationsQuery.fields()
                .include("uuid")
                .include("title.value")
                .include("publicationDate")
                .include("submissionYear")
                .include("contributors.name")
                .include("contributors.person.uuid")
                .include("contributors.externalPerson.uuid")
                .include("journalAssociation")
                .include("journal")
                .include("publicationChannel.journal.uuid")
                .include("issn")
                .include("eissn")
                .include("electronicVersions.accessType.term")
                .include("volume")
                .include("issue")
                .include("numberOfPages")
                .include("pages")
                .include("articleNumber")
                .include("journalName")
                .include("journalTitle");
        List<Document> publications = mongoTemplate.find(publicationsQuery, Document.class, RESEARCHOUTPUTS_COLLECTION);

        Map<String, Optional<Journal>> journalByUuid = new HashMap<>();
        Map<String, String> quartileByIssnAndYear = new HashMap<>();
        Map<String, List<Jcr>> jcrByIssn = new HashMap<>();

        // Pre-batch: resolve all journals and JCR data in 2 queries instead of N per-publication queries.
        prebatchJournalsAndJcr(publications, journalByUuid, jcrByIssn);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Q1", 0);
        counts.put("Q2", 0);
        counts.put("Q3", 0);
        counts.put("Q4", 0);
        counts.put("Sense Quartil", 0);

        int openAccessCount = 0;
        int notOpenAccessCount = 0;

        Map<Integer, Map<String, Object>> byYear = new TreeMap<>();
        List<Map<String, Object>> articles = new ArrayList<>();

        for (Document publication : publications) {
            String publicationUuid = asString(publication.get("uuid"));
            String title = nestedString(publication, "title", "value");
            Integer year = extractYear(publication);
            String authors = extractAuthorsApa(publication);

            boolean hasJournal = hasJournalAssociation(publication);
            String quartileResolved = resolveQuartileForPublication(
                    publication, year, journalByUuid, quartileByIssnAndYear, jcrByIssn
            );
            // quartile used for pie/evolution grouping (null means non-journal: excluded)
            String quartile = hasJournal
                    ? ((quartileResolved == null || quartileResolved.isBlank()) ? "Sense Quartil" : quartileResolved)
                    : null;
            if (quartile != null && !counts.containsKey(quartile)) {
                quartile = "Sense Quartil";
            }
            // display label for the articles table
            String quartileDisplay = quartile != null ? quartile : "-";

            String journalUuid = findFirstString(publication,
                    List.of("journalAssociation", "journal", "uuid"),
                    List.of("journal", "uuid"),
                    List.of("publicationChannel", "journal", "uuid")
            );
            Optional<Journal> journalOpt = Optional.empty();
            if (journalUuid != null && !journalUuid.isBlank()) {
                journalOpt = journalByUuid.computeIfAbsent(journalUuid, key -> findJournalByUuidCached(key));
            }

            Integer month = extractDatePart(publication, "month");
            Integer day = extractDatePart(publication, "day");
            String journalTitle = extractJournalTitle(publication, journalOpt);
            String volume = findFirstString(publication,
                    List.of("journalAssociation", "volume"),
                    List.of("journal", "volume"),
                    List.of("volume")
            );
            String issue = findFirstString(publication,
                    List.of("journalAssociation", "journalNumber"),
                    List.of("journalAssociation", "issue"),
                    List.of("journal", "issue"),
                    List.of("issue")
            );
            String pages = findFirstString(publication,
                    List.of("journalAssociation", "pages"),
                    List.of("journal", "pages"),
                    List.of("pages"),
                    List.of("numberOfPages")
            );
            String articleNumber = findFirstString(publication,
                    List.of("journalAssociation", "articleNumber"),
                    List.of("journal", "articleNumber"),
                    List.of("articleNumber")
            );

            boolean openAccess = isOpenAccess(publication);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("publicationUuid", publicationUuid);
            row.put("title", title);
            row.put("year", year);
            row.put("quartile", quartileDisplay);
            row.put("openAccess", openAccess);
            row.put("cita", buildCitationApa(authors, day, month, year, journalTitle, volume, issue, pages, articleNumber, title));

            if (quartile != null) {
                counts.put(quartile, counts.get(quartile) + 1);
                if (openAccess) {
                    openAccessCount++;
                } else {
                    notOpenAccessCount++;
                }
            }

            if (quartile != null && year != null) {
                Map<String, Object> rowByYear = byYear.computeIfAbsent(year, y -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("year", y);
                    item.put("Q1", 0);
                    item.put("Q2", 0);
                    item.put("Q3", 0);
                    item.put("Q4", 0);
                    item.put("Sense Quartil", 0);
                    return item;
                });
                int current = rowByYear.get(quartile) instanceof Number n ? n.intValue() : 0;
                rowByYear.put(quartile, current + 1);
            }

            articles.add(row);
        }

        articles.sort((a, b) -> {
            String qa = String.valueOf(a.getOrDefault("quartile", "Sense Quartil"));
            String qb = String.valueOf(b.getOrDefault("quartile", "Sense Quartil"));
            // "-" (non-journal) sorts after all quartile labels
            boolean aNoJ = "-".equals(qa);
            boolean bNoJ = "-".equals(qb);
            if (aNoJ && !bNoJ) return 1;
            if (!aNoJ && bNoJ) return -1;
            int cmpQ = qa.compareTo(qb);
            if (cmpQ != 0) {
                return cmpQ;
            }
            Integer ya = a.get("year") instanceof Integer i ? i : -1;
            Integer yb = b.get("year") instanceof Integer i ? i : -1;
            return Integer.compare(yb, ya);
        });

        List<Map<String, Object>> quartiles = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 0) {
                quartiles.add(Map.of(
                        "quartile", entry.getKey(),
                        "total", entry.getValue()
                ));
            }
        }

        List<Map<String, Object>> openAccessData = new ArrayList<>();
        if (openAccessCount > 0) openAccessData.add(Map.of("label", "Accés obert", "value", openAccessCount));
        if (notOpenAccessCount > 0) openAccessData.add(Map.of("label", "Accés tancat", "value", notOpenAccessCount));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quartiles", quartiles);
        result.put("articles", articles);
        result.put("evolution", new ArrayList<>(byYear.values()));
        result.put("openAccess", openAccessData);

        quartilesDashboardCache.put(cacheKey, new CacheEntry(result, now + QUARTILES_CACHE_TTL_MS));
        cleanupExpiredQuartilesCache(now);
        return result;
    }

    /**
     * Batch-fetches all Journal and JCR data needed for a list of publications using
     * at most 2 MongoDB queries (one to Journals for UUIDs, one to the JCR Journals for ISSNs),
     * populating the per-request lookup maps so that the main processing loop has only cache hits.
     */
    private void prebatchJournalsAndJcr(
            List<Document> publications,
            Map<String, Optional<Journal>> journalByUuid,
            Map<String, List<Jcr>> jcrByIssn) {

        // 1. Collect all journal UUIDs referenced by the publications.
        Set<String> uuids = new LinkedHashSet<>();
        for (Document pub : publications) {
            String uid = findFirstString(pub,
                    List.of("journalAssociation", "journal", "uuid"),
                    List.of("journal", "uuid"),
                    List.of("publicationChannel", "journal", "uuid")
            );
            if (uid != null && !uid.isBlank()) {
                uuids.add(uid);
            }
        }

        // 2. For UUIDs not in the service-level journal cache, batch-fetch from DB.
        long now = System.currentTimeMillis();
        Set<String> uncachedUuids = new LinkedHashSet<>();
        for (String uid : uuids) {
            JournalCacheEntry cached = journalUuidCache.get(uid);
            if (cached == null || cached.expiresAtMs() <= now) {
                uncachedUuids.add(uid);
            }
        }
        if (!uncachedUuids.isEmpty()) {
            List<Journal> fetched = journalRepository.findByUuidIn(uncachedUuids);
            Map<String, Journal> byUuidFetched = new LinkedHashMap<>();
            for (Journal j : fetched) {
                if (j.getUuid() != null) {
                    byUuidFetched.put(j.getUuid(), j);
                }
            }
            for (String uid : uncachedUuids) {
                Optional<Journal> opt = Optional.ofNullable(byUuidFetched.get(uid));
                journalUuidCache.put(uid, new JournalCacheEntry(opt, now + JOURNAL_UUID_CACHE_TTL_MS));
            }
        }

        // Populate the per-request map from the service-level cache.
        for (String uid : uuids) {
            JournalCacheEntry ce = journalUuidCache.get(uid);
            if (ce != null) {
                journalByUuid.put(uid, ce.journal());
            }
        }

        // 3. Collect all raw ISSNs from publications and from the resolved journals.
        Set<String> allIssns = new LinkedHashSet<>();
        for (Document pub : publications) {
            allIssns.addAll(extractPublicationIssns(pub));
            if (allIssns.isEmpty()) {
                allIssns.addAll(scanIssnValues(pub));
            }
        }
        for (Optional<Journal> jOpt : journalByUuid.values()) {
            jOpt.ifPresent(j -> allIssns.addAll(j.getAllIssnsForJoin()));
        }

        // 4. Batch-warm the JCR cache for all collected ISSNs (single DB query for uncached ones).
        journalJcrService.warmJcrCacheForIssns(allIssns);

        // 5. Populate the per-request jcrByIssn map from the service-level JCR cache so the loop
        //    can use it directly without going through journalJcrService again.
        // (The loop still calls findJcrByIssnCached which will be a cache hit after step 4.)
    }

    private String buildQuartilesCacheKey(String deptUuid, Integer desde, Integer hasta, String filtrePersonal, String personUuid) {
        String dep = (deptUuid == null || deptUuid.isBlank()) ? "*" : deptUuid.trim();
        String from = desde == null ? "*" : String.valueOf(desde);
        String to = hasta == null ? "*" : String.valueOf(hasta);
        String mode = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal.trim();
        String person = (personUuid == null || personUuid.isBlank()) ? "*" : personUuid.trim();
        return dep + "|" + from + "|" + to + "|" + mode + "|" + person;
    }

    private void cleanupExpiredQuartilesCache(long now) {
        if (quartilesDashboardCache.size() <= 128) {
            return;
        }
        quartilesDashboardCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    private Optional<Journal> findJournalByUuidCached(String uuid) {
        long now = System.currentTimeMillis();
        JournalCacheEntry cached = journalUuidCache.get(uuid);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.journal();
        }
        Optional<Journal> result = journalRepository.findByUuid(uuid);
        journalUuidCache.put(uuid, new JournalCacheEntry(result, now + JOURNAL_UUID_CACHE_TTL_MS));
        if (journalUuidCache.size() > 4096) {
            journalUuidCache.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= now);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> quartileDistributionByDepartment(String deptUuid, Integer desde, Integer hasta, String filtrePersonal, String personUuid) {
        return (List<Map<String, Object>>) quartilesDashboardByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid)
                .getOrDefault("quartiles", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> quartileArticlesByDepartment(String deptUuid, Integer desde, Integer hasta, String filtrePersonal, String personUuid) {
        return (List<Map<String, Object>>) quartilesDashboardByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid)
                .getOrDefault("articles", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> quartileEvolutionByDepartment(String deptUuid, Integer desde, Integer hasta, String filtrePersonal, String personUuid) {
        return (List<Map<String, Object>>) quartilesDashboardByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid)
                .getOrDefault("evolution", List.of());
    }

    /**
     * Returns true when the publication has at least one electronicVersion whose
     * accessType term is Open/Abierto/Obert (mirrors the Mongo $facet query).
     */
    private static boolean isOpenAccess(Document publication) {
        Object evObj = publication.get("electronicVersions");
        if (evObj instanceof List<?> evList) {
            for (Object item : evList) {
                if (item instanceof Document ev) {
                    Object atObj = ev.get("accessType");
                    if (atObj instanceof Document at) {
                        Object termObj = at.get("term");
                        if (termObj instanceof Document term) {
                            String en = term.getString("en_GB");
                            String es = term.getString("es_ES");
                            String ca = term.getString("ca_ES");
                            if ("Open".equalsIgnoreCase(en)
                                    || "Abierto".equalsIgnoreCase(es)
                                    || "Obert".equalsIgnoreCase(ca)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private String resolveQuartileForPublication(
            Document publication,
            Integer publicationYear,
            Map<String, Optional<Journal>> journalByUuid,
            Map<String, String> quartileByIssnAndYear,
            Map<String, List<Jcr>> jcrByIssn) {
        String journalUuid = findFirstString(publication,
                List.of("journalAssociation", "journal", "uuid"),
                List.of("journal", "uuid"),
                List.of("publicationChannel", "journal", "uuid")
        );

        Optional<Journal> journalOpt = Optional.empty();
        if (journalUuid != null && !journalUuid.isBlank()) {
            journalOpt = journalByUuid.computeIfAbsent(journalUuid, key -> findJournalByUuidCached(key));
        }

        Set<String> issns = extractPublicationIssns(publication);
        if (issns.isEmpty()) {
            issns.addAll(scanIssnValues(publication));
        }
        journalOpt.ifPresent(journal -> issns.addAll(journal.getAllIssnsForJoin()));

        for (String issn : issns) {
            String normalized = normalizeIssn(issn);
            if (normalized == null) {
                continue;
            }

            String cacheKey = normalized + "|" + (publicationYear == null ? "null" : publicationYear);
            String resolved = quartileByIssnAndYear.get(cacheKey);
            if (resolved == null && !quartileByIssnAndYear.containsKey(cacheKey)) {
                resolved = resolveQuartileByIssnAndYear(issn, publicationYear, jcrByIssn);
                quartileByIssnAndYear.put(cacheKey, resolved);
            }

            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }

        return null;
    }

    private static boolean hasJournalAssociation(Document publication) {
        String jUuid = findFirstString(publication,
                List.of("journalAssociation", "journal", "uuid"),
                List.of("journal", "uuid"),
                List.of("publicationChannel", "journal", "uuid")
        );
        if (jUuid != null && !jUuid.isBlank()) return true;
        String issn = findFirstString(publication,
                List.of("journalAssociation", "issn", "value"),
                List.of("journal", "issn", "value"),
                List.of("issn", "value"),
                List.of("journalAssociation", "electronicIssn", "value"),
                List.of("journal", "electronicIssn", "value")
        );
        return issn != null && !issn.isBlank();
    }

    private static Set<String> extractPublicationIssns(Document publication) {
        Set<String> issns = new LinkedHashSet<>();
        collectIssn(issns, findFirstString(publication,
                List.of("journalAssociation", "issn", "value"),
                List.of("journal", "issn", "value"),
                List.of("issn", "value")
        ));
        collectIssn(issns, findFirstString(publication,
                List.of("journalAssociation", "electronicIssn", "value"),
                List.of("journal", "electronicIssn", "value"),
                List.of("eissn", "value")
        ));
        return issns;
    }

    private static String extractAuthorsApa(Document publication) {
        Object contributorsObj = publication.get("contributors");
        if (!(contributorsObj instanceof List<?> contributors)) {
            return null;
        }

        List<String> names = new ArrayList<>();
        for (Object item : contributors) {
            if (!(item instanceof Document contributor)) {
                continue;
            }
            Object nameObj = contributor.get("name");
            if (!(nameObj instanceof Document nameDoc)) {
                continue;
            }
            String firstName = nameDoc.getString("firstName");
            String lastName = nameDoc.getString("lastName");
            String apaName = toApaAuthor(lastName, firstName);
            if (apaName != null) {
                names.add(apaName);
            }
        }

        if (names.isEmpty()) {
            return null;
        }

        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + " & " + names.get(1);
        }
        if (names.size() <= 20) {
            return String.join(", ", names.subList(0, names.size() - 1)) + ", & " + names.get(names.size() - 1);
        }
        return String.join(", ", names.subList(0, 19)) + ", ... " + names.get(names.size() - 1);
    }

    private static String buildCitationApa(
            String authors,
            Integer day,
            Integer month,
            Integer year,
            String journalTitle,
            String volume,
            String issue,
            String pages,
            String articleNumber,
            String titleFallback) {

        String safeAuthors = sanitizeHtmlText(authors);
        if (safeAuthors == null || safeAuthors.isBlank()) {
            safeAuthors = "Autor desconegut";
        }

        String safeYear = year == null ? "s. d." : String.valueOf(year);
        String safeTitle = sanitizeHtmlText(titleFallback);
        if (safeTitle == null || safeTitle.isBlank()) {
            safeTitle = "Titol desconegut";
        }

        String safeJournal = sanitizeHtmlText(journalTitle);
        if (safeJournal == null || safeJournal.isBlank()) {
            safeJournal = "Revista desconeguda";
        }

        String volumePart = volume == null ? "" : volume.trim();
        String issuePart = issue == null ? "" : issue.trim();
        String pagesPart = pages == null ? "" : pages.trim();
        String articlePart = articleNumber == null ? "" : articleNumber.trim();

        StringBuilder citation = new StringBuilder();
        citation
                .append(safeAuthors)
                .append(" (")
                .append(safeYear)
                .append("). ")
                .append(safeTitle)
                .append(". ")
                .append(safeJournal);

        if (!volumePart.isBlank()) {
            citation.append(", ").append(volumePart);
            if (!issuePart.isBlank()) {
                citation.append("(").append(issuePart).append(")");
            }
        }

        if (!pagesPart.isBlank()) {
            citation.append(", ").append(pagesPart);
        } else if (!articlePart.isBlank()) {
            citation.append(", ").append(articlePart);
        }

        citation.append('.');
        return citation.toString();
    }

    private static String sanitizeHtmlText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // Convert HTML entities and strip markup, then normalize spaces.
        String decoded = HtmlUtils.htmlUnescape(raw);
        String noTags = decoded.replaceAll("<[^>]+>", " ");
        String normalized = noTags.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static Integer extractDatePart(Document publication, String part) {
        Object v = getByPath(publication, List.of("publicationDate", part));
        if (v instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static String extractJournalTitle(Document publication, Optional<Journal> journalOpt) {
        String direct = findFirstString(publication,
                List.of("journalAssociation", "journal", "title", "value"),
                List.of("journalAssociation", "title", "value"),
                List.of("journal", "title", "value"),
                List.of("journal", "name"),
                List.of("journalName"),
                List.of("journalTitle")
        );
        if (direct != null && !direct.isBlank()) {
            return direct;
        }

        if (journalOpt.isPresent() && journalOpt.get().getMainTitle() != null && !journalOpt.get().getMainTitle().isBlank()) {
            return journalOpt.get().getMainTitle();
        }
        return null;
    }

    private static String toApaAuthor(String lastName, String firstName) {
        String ln = lastName == null ? "" : lastName.trim();
        String fn = firstName == null ? "" : firstName.trim();

        if (ln.isBlank() && fn.isBlank()) {
            return null;
        }

        if (ln.isBlank()) {
            return fn;
        }

        if (fn.isBlank()) {
            return ln;
        }

        String[] tokens = fn.split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (String token : tokens) {
            if (!token.isBlank()) {
                initials.append(Character.toUpperCase(token.charAt(0))).append('.');
                if (token.length() > 1) {
                    initials.append(' ');
                }
            }
        }
        return ln + ", " + initials.toString().trim();
    }

    private String resolveQuartileByIssnAndYear(String issn, Integer publicationYear, Map<String, List<Jcr>> jcrByIssn) {
        if (publicationYear == null) {
            return null;
        }

        List<Jcr> matches = findJcrByIssnCached(issn, jcrByIssn);
        if (matches.isEmpty()) {
            return null;
        }

        for (Jcr jcr : matches) {
            String quartile = findQuartileForYear(jcr, publicationYear);
            if (quartile != null && !quartile.isBlank()) {
                return quartile;
            }
        }

        return null;
    }

    private List<Jcr> findJcrByIssnCached(String issn, Map<String, List<Jcr>> jcrByIssn) {
        String normalized = normalizeIssn(issn);
        if (normalized == null) {
            return List.of();
        }

        return jcrByIssn.computeIfAbsent(normalized, ignored -> journalJcrService.findJcrByIssn(issn));
    }

    private static String normalizeIssn(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static Document buildVigentAssociationCriteria() {
        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();
        Date hoyDate = Date.from(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant());
        return new Document("$or", List.of(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 9)),
                        new Document("period.endDate", new Document("$gt", hoyDate))
                )),
                new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 2)),
                        new Document("period.endDate", new Document("$gt", hoyIso))
                ))
        ));
    }

    /**
     * Criteria for associations whose period overlaps [desde, hasta]:
     *   startDate <= Dec-31-hasta  AND  (endDate missing OR endDate >= Jan-1-desde)
     * Falls back to vigent criteria when desde/hasta are both null.
     */
    private static Document buildPeriodeAssociationCriteria(Integer desde, Integer hasta) {
        if (desde == null && hasta == null) {
            return buildVigentAssociationCriteria();
        }

        List<Document> conditions = new ArrayList<>();

        // endDate >= Jan 1 of desde  (or endDate missing → still active)
        if (desde != null) {
            String desdeIso = desde + "-01-01";
            Date desdeDate = Date.from(LocalDate.of(desde, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            conditions.add(new Document("$or", List.of(
                    new Document("period.endDate", null),
                    new Document("period.endDate", new Document("$exists", false)),
                    new Document("$and", List.of(
                            new Document("period.endDate", new Document("$type", 9)),
                            new Document("period.endDate", new Document("$gte", desdeDate))
                    )),
                    new Document("$and", List.of(
                            new Document("period.endDate", new Document("$type", 2)),
                            new Document("period.endDate", new Document("$gte", desdeIso))
                    ))
            )));
        }

        // startDate <= Dec 31 of hasta  (or startDate missing → joined before any tracking)
        if (hasta != null) {
            String hastaIso = hasta + "-12-31";
            Date hastaDate = Date.from(LocalDate.of(hasta, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant());
            conditions.add(new Document("$or", List.of(
                    new Document("period.startDate", null),
                    new Document("period.startDate", new Document("$exists", false)),
                    new Document("$and", List.of(
                            new Document("period.startDate", new Document("$type", 9)),
                            new Document("period.startDate", new Document("$lte", hastaDate))
                    )),
                    new Document("$and", List.of(
                            new Document("period.startDate", new Document("$type", 2)),
                            new Document("period.startDate", new Document("$lte", hastaIso))
                    ))
            )));
        }

        return conditions.size() == 1 ? conditions.get(0) : new Document("$and", conditions);
    }

    private static Document buildPublicationYearCriteria(Integer desde, Integer hasta) {
        if (desde == null && hasta == null) {
            return null;
        }

        Document range = new Document();
        if (desde != null) {
            range.append("$gte", desde);
        }
        if (hasta != null) {
            range.append("$lte", hasta);
        }

        // Match directly on source year fields so Mongo can use indexes before heavy stages.
        return new Document("$or", List.of(
                new Document("publicationDate.year", new Document(range)),
                new Document("$and", List.of(
                        new Document("$or", List.of(
                                new Document("publicationDate.year", null),
                                new Document("publicationDate.year", new Document("$exists", false))
                        )),
                        new Document("submissionYear", new Document(range))
                ))
        ));
    }

    private List<String> publicationUuidsByDepartment(String deptUuid, Integer desde, Integer hasta, String filtrePersonal, String personUuid) {

        // When a specific person is selected, bypass directly.
        if (personUuid != null && !personUuid.isBlank()) {
            return publicationUuidsByPerson(personUuid.trim(), desde, hasta);
        }

        // Step 1: resolve matching person UUIDs from the Persons collection (one fast query).
        // This avoids the former $unwind+$lookup-per-contributor chain that hit MongoDB
        // O(publications × contributors) times.
        List<String> personUuids = personUuidsByDepartment(deptUuid, desde, hasta, filtrePersonal);
        if (personUuids.isEmpty()) {
            return List.of();
        }

        // Step 2: find publications where any contributor matches via $in (index-friendly).
        List<Document> matchClauses = new ArrayList<>();
        matchClauses.add(new Document("workflow.step", "approved"));

        // Filtro para solo artículos
        matchClauses.add(new Document("type.term.ca_ES", "Article"));

        Document yearCriteria = buildPublicationYearCriteria(desde, hasta);
        if (yearCriteria != null) {
            matchClauses.add(yearCriteria);
        }

        matchClauses.add(new Document("$or", List.of(
            new Document("contributors.person.uuid", new Document("$in", personUuids)),
            new Document("contributors.externalPerson.uuid", new Document("$in", personUuids))
        )));

        Document filter = new Document("$and", matchClauses);
        Document projection = new Document("uuid", 1).append("_id", 0);

        List<Document> rows = mongoTemplate
            .getCollection(RESEARCHOUTPUTS_COLLECTION)
            .find(filter)
            .projection(projection)
            .into(new ArrayList<>());

        return rows.stream()
            .map(row -> row.getString("uuid"))
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    /**
     * Returns trimmed UUIDs of persons with an active (or period-matching) staff association
     * in the given department. One targeted query on the Persons collection.
     */
    private List<String> personUuidsByDepartment(String deptUuid, Integer desde, Integer hasta, String filtrePersonal) {
        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        Document assocCriteria = usePeriode
                ? buildPeriodeAssociationCriteria(desde, hasta)
                : buildVigentAssociationCriteria();

        Document elemMatch = (deptUuid != null && !deptUuid.isBlank())
                ? new Document("$and", List.of(new Document("organization.uuid", deptUuid), assocCriteria))
                : assocCriteria;

        Document filter = new Document("staffOrganizationAssociations", new Document("$elemMatch", elemMatch));
        Document projection = new Document("uuid", 1).append("_id", 0);

        List<Document> rows = mongoTemplate
                .getCollection(PERSONS_COLLECTION)
                .find(filter)
                .projection(projection)
                .into(new ArrayList<>());

        return rows.stream()
                .map(d -> d.getString("uuid"))
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * Direct lookup of all approved publications where the given personUuid appears as a
     * contributor (person or externalPerson). Avoids the $unwind-contributors stage so that
     * publications with an empty contributors array (books, edited volumes, etc.) are NOT dropped.
     * Uses $trim on each contributor UUID to match the original pipeline behaviour (some UUIDs
     * in the database have leading/trailing whitespace).
     */
    private List<String> publicationUuidsByPerson(String personUuid, Integer desde, Integer hasta) {
        List<Document> pipeline = new ArrayList<>();

        // Use $expr + $filter + $trim so that contributor UUIDs with surrounding whitespace
        // are still matched, consistent with the main publicationUuidsByDepartment pipeline.
        Document trimmedUuidMatch = new Document("$expr", new Document("$gt", List.of(
            new Document("$size", new Document("$filter", new Document()
                .append("input", new Document("$ifNull", List.of("$contributors", List.of())))
                .append("as", "c")
                .append("cond", new Document("$or", List.of(
                    new Document("$eq", List.of(
                        new Document("$trim", new Document("input",
                            new Document("$ifNull", List.of("$$c.person.uuid", "")))),
                        personUuid
                    )),
                    new Document("$eq", List.of(
                        new Document("$trim", new Document("input",
                            new Document("$ifNull", List.of("$$c.externalPerson.uuid", "")))),
                        personUuid
                    ))
                )))
            )),
            0
        )));

        pipeline.add(new Document("$match", new Document("$and", List.of(
                new Document("workflow.step", "approved"),
                trimmedUuidMatch
        ))));

        Document yearCriteria = buildPublicationYearCriteria(desde, hasta);
        if (yearCriteria != null) {
            pipeline.add(new Document("$match", yearCriteria));
        }

        pipeline.add(new Document("$project", new Document("_id", 0).append("publicationUuid", "$uuid")));

        List<Document> rows = mongoTemplate
                .getCollection(RESEARCHOUTPUTS_COLLECTION)
                .aggregate(pipeline)
                .into(new ArrayList<>());

        return rows.stream()
                .map(row -> row.getString("publicationUuid"))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private Map<String, Object> buildLinkResponse(Document publication) {
        String publicationUuid = asString(publication.get("uuid"));
        String publicationTitle = nestedString(publication, "title", "value");
        Integer publicationYear = extractYear(publication);

        String journalUuid = findFirstString(publication,
                List.of("journalAssociation", "journal", "uuid"),
                List.of("journal", "uuid"),
                List.of("publicationChannel", "journal", "uuid")
        );

        Set<String> issns = new LinkedHashSet<>();
        collectIssn(issns, findFirstString(publication,
                List.of("journalAssociation", "issn", "value"),
                List.of("journal", "issn", "value"),
                List.of("issn", "value")
        ));
        collectIssn(issns, findFirstString(publication,
                List.of("journalAssociation", "electronicIssn", "value"),
                List.of("journal", "electronicIssn", "value"),
                List.of("eissn", "value")
        ));

        Optional<Journal> journalFromUuid = Optional.empty();
        if (journalUuid != null && !journalUuid.isBlank()) {
            journalFromUuid = journalRepository.findByUuid(journalUuid);
            journalFromUuid.ifPresent(journal -> issns.addAll(journal.getAllIssnsForJoin()));
        }

        if (issns.isEmpty() && journalFromUuid.isEmpty()) {
            List<String> scanned = scanIssnValues(publication);
            issns.addAll(scanned);
        }

        List<Jcr> jcrMatches = new ArrayList<>();
        for (String issn : issns) {
            jcrMatches.addAll(journalJcrService.findJcrByIssn(issn));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("publicationUuid", publicationUuid);
        response.put("publicationTitle", publicationTitle);
        response.put("publicationYear", publicationYear);
        response.put("journalUuid", journalUuid);
        response.put("journalFound", journalFromUuid.isPresent());
        response.put("journal", journalFromUuid.orElse(null));
        response.put("issnsUsed", new ArrayList<>(issns));
        response.put("jcrCount", dedupeJcr(jcrMatches).size());
        response.put("jcrMatches", dedupeJcr(jcrMatches));
        return response;
    }

    private Map<String, Object> toCompactSummary(Map<String, Object> full) {
        @SuppressWarnings("unchecked")
        List<Jcr> jcrMatches = (List<Jcr>) full.getOrDefault("jcrMatches", List.of());

        Integer bestYear = null;
        String bestQuartile = null;
        if (!jcrMatches.isEmpty()) {
            bestYear = latestReportYear(jcrMatches.get(0));
            bestQuartile = findQuartileForYear(jcrMatches.get(0), bestYear);
        }

        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("publicationUuid", full.get("publicationUuid"));
        compact.put("publicationTitle", full.get("publicationTitle"));
        compact.put("publicationYear", full.get("publicationYear"));
        compact.put("journalUuid", full.get("journalUuid"));
        compact.put("journalFound", full.get("journalFound"));
        compact.put("issnsUsed", full.get("issnsUsed"));
        compact.put("jcrCount", full.get("jcrCount"));
        compact.put("jcrBestYear", bestYear);
        compact.put("jcrBestQuartile", bestQuartile);
        return compact;
    }

    private static Integer latestReportYear(Jcr jcr) {
        Integer best = null;
        if (jcr == null || jcr.getJournalCitationReports() == null) {
            return null;
        }

        for (Jcr.JournalCitationReport report : jcr.getJournalCitationReports()) {
            if (report == null || report.getYear() == null) {
                continue;
            }
            if (best == null || report.getYear() > best) {
                best = report.getYear();
            }
        }
        return best;
    }

    private static String findQuartileForYear(Jcr jcr, Integer year) {
        if (jcr == null || year == null || jcr.getJournalCitationReports() == null) {
            return null;
        }

        for (Jcr.JournalCitationReport report : jcr.getJournalCitationReports()) {
            if (report == null || !year.equals(report.getYear()) || report.getRanks() == null) {
                continue;
            }

            String quartile = firstQuartile(report.getRanks().getJif());
            if (quartile == null) {
                quartile = firstQuartile(report.getRanks().getJci());
            }
            if (quartile == null) {
                quartile = firstQuartile(report.getRanks().getArticleInfluence());
            }
            return quartile;
        }
        return null;
    }

    private static String firstQuartile(List<Jcr.RankItem> items) {
        if (items == null) {
            return null;
        }
        for (Jcr.RankItem item : items) {
            if (item != null && item.getQuartile() != null && !item.getQuartile().isBlank()) {
                return item.getQuartile().trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static List<Jcr> dedupeJcr(List<Jcr> input) {
        Map<String, Jcr> byKey = new LinkedHashMap<>();
        for (Jcr item : input) {
            if (item == null) {
                continue;
            }
            String key = item.getId() != null ? item.getId() : item.getBestIssnForJoin();
            if (key != null) {
                byKey.put(key, item);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static Integer extractYear(Document publication) {
        Object year = nestedValue(publication, "publicationDate", "year");
        if (year instanceof Number number) {
            return number.intValue();
        }

        Object submissionYear = publication.get("submissionYear");
        if (submissionYear instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    @SafeVarargs
    private static String findFirstString(Document root, List<String>... paths) {
        for (List<String> path : paths) {
            Object value = getByPath(root, path);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private static Object nestedValue(Document root, String... keys) {
        return getByPath(root, List.of(keys));
    }

    private static String nestedString(Document root, String... keys) {
        Object value = nestedValue(root, keys);
        return value instanceof String text ? text : null;
    }

    private static Object getByPath(Document root, List<String> path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Document doc)) {
                return null;
            }
            current = doc.get(key);
        }
        return current;
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static void collectIssn(Set<String> out, String candidate) {
        if (candidate == null) {
            return;
        }
        String cleaned = candidate.trim();
        if (!cleaned.isBlank()) {
            out.add(cleaned);
        }
    }

    private static List<String> scanIssnValues(Object node) {
        Set<String> out = new LinkedHashSet<>();
        scanIssnRecursive(node, out);
        return new ArrayList<>(out);
    }

    private static void scanIssnRecursive(Object node, Set<String> out) {
        if (node == null) {
            return;
        }

        if (node instanceof Document doc) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key != null && key.toLowerCase().contains("issn")) {
                    if (value instanceof String text && !text.isBlank()) {
                        out.add(text.trim());
                    }
                    if (value instanceof Document nestedValueDoc) {
                        Object v = nestedValueDoc.get("value");
                        if (v instanceof String text && !text.isBlank()) {
                            out.add(text.trim());
                        }
                    }
                }
                scanIssnRecursive(value, out);
            }
            return;
        }

        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                scanIssnRecursive(value, out);
            }
            return;
        }

        if (node instanceof List<?> list) {
            for (Object item : list) {
                scanIssnRecursive(item, out);
            }
        }
    }
}