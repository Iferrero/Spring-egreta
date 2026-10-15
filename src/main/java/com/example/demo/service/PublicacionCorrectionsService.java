package com.example.demo.service;

import com.example.demo.model.PublicacionCorreccion;
import com.example.demo.repository.PublicacionCorreccionRepository;
import com.example.demo.util.PublicacionDocumentUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servei per avaluar els 13 controls de qualitat de les publicacions
 * i generar el dashboard de correccions.
 */
@Service
public class PublicacionCorrectionsService {

    private final MongoTemplate mongoTemplate;
    private final PublicacionCorreccionRepository publicacionCorreccionRepository;

    private final Map<String, List<String>> duplicatesCache = new ConcurrentHashMap<>();
    private final Map<String, Long> duplicatesCacheTimestamps = new ConcurrentHashMap<>();
    private static final long DUPLICATES_CACHE_TTL_MS = 5 * 60 * 1000;

    @Autowired
    public PublicacionCorrectionsService(
            MongoTemplate mongoTemplate,
            PublicacionCorreccionRepository publicacionCorreccionRepository) {
        this.mongoTemplate = mongoTemplate;
        this.publicacionCorreccionRepository = publicacionCorreccionRepository;
    }

    // ─── Cache de duplicats ───────────────────────────────────────────────────

    public void clearDuplicatesCache() {
        duplicatesCache.clear();
        duplicatesCacheTimestamps.clear();
    }

    public List<String> getCachedDuplicateField(String cacheKey, String fieldPath) {
        long now = System.currentTimeMillis();
        Long last = duplicatesCacheTimestamps.get(cacheKey);
        if (last != null && (now - last) < DUPLICATES_CACHE_TTL_MS) {
            return duplicatesCache.get(cacheKey);
        }
        List<String> dups;
        if ("titles".equals(cacheKey)) {
            dups = getDuplicateCombinedTitlesAndType();
        } else {
            dups = getDuplicateFields(fieldPath);
        }
        duplicatesCache.put(cacheKey, dups);
        duplicatesCacheTimestamps.put(cacheKey, now);
        return dups;
    }

    public List<String> getCachedDuplicateUrls() {
        long now = System.currentTimeMillis();
        Long last = duplicatesCacheTimestamps.get("urls");
        if (last != null && (now - last) < DUPLICATES_CACHE_TTL_MS) {
            return duplicatesCache.get("urls");
        }
        List<String> dups = getDuplicateUrls();
        duplicatesCache.put("urls", dups);
        duplicatesCacheTimestamps.put("urls", now);
        return dups;
    }

    public List<String> getCachedDuplicateIdentifiers() {
        long now = System.currentTimeMillis();
        Long last = duplicatesCacheTimestamps.get("identifiers");
        if (last != null && (now - last) < DUPLICATES_CACHE_TTL_MS) {
            return duplicatesCache.get("identifiers");
        }
        List<String> dups = getDuplicateIdentifiers();
        duplicatesCache.put("identifiers", dups);
        duplicatesCacheTimestamps.put("identifiers", now);
        return dups;
    }

    private List<String> getDuplicateFields(String fieldPath) {
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
            org.springframework.data.mongodb.core.aggregation.Aggregation.match(Criteria.where(fieldPath).exists(true).ne(null).ne("")),
            org.springframework.data.mongodb.core.aggregation.Aggregation.group(fieldPath).count().as("count"),
            org.springframework.data.mongodb.core.aggregation.Aggregation.match(Criteria.where("count").gt(1)),
            org.springframework.data.mongodb.core.aggregation.Aggregation.limit(5000)
        );
        org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> results = mongoTemplate.aggregate(agg, "Researchoutputs", Document.class);
        List<String> dups = new ArrayList<>();
        for (Document d : results.getMappedResults()) {
            Object idVal = d.get("_id");
            if (idVal != null) {
                dups.add(idVal.toString());
            }
        }
        return dups;
    }

    private List<String> getDuplicateUrls() {
        List<Document> pipeline = Arrays.asList(
            new Document("$project", new Document("urls", new Document("$concatArrays", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$electronicVersions.link", Arrays.asList())),
                new Document("$ifNull", Arrays.asList("$electronicVersions.file.url", Arrays.asList())),
                new Document("$ifNull", Arrays.asList("$links.url", Arrays.asList()))
            )))),
            new Document("$unwind", "$urls"),
            new Document("$match", new Document("urls", new Document("$ne", null).append("$ne", ""))),
            new Document("$group", new Document("_id", "$urls").append("count", new Document("$sum", 1))),
            new Document("$match", new Document("count", new Document("$gt", 1))),
            new Document("$limit", 5000)
        );
        List<String> dups = new ArrayList<>();
        mongoTemplate.getCollection("Researchoutputs")
            .aggregate(pipeline)
            .forEach(d -> {
                Object idVal = d.get("_id");
                if (idVal != null) {
                    dups.add(idVal.toString());
                }
            });
        return dups;
    }

    private List<String> getDuplicateIdentifiers() {
        List<Document> pipeline = Arrays.asList(
            new Document("$unwind", "$identifiers"),
            new Document("$match", new Document("identifiers.value", new Document("$ne", null).append("$ne", ""))),
            new Document("$group", new Document("_id", "$identifiers.value").append("count", new Document("$sum", 1))),
            new Document("$match", new Document("count", new Document("$gt", 1))),
            new Document("$limit", 5000)
        );
        List<String> dups = new ArrayList<>();
        mongoTemplate.getCollection("Researchoutputs")
            .aggregate(pipeline)
            .forEach(d -> {
                Object idVal = d.get("_id");
                if (idVal != null) {
                    dups.add(idVal.toString());
                }
            });
        return dups;
    }

    public List<String> getDuplicateCombinedTitlesAndType() {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("title.value", new Document("$exists", true).append("$ne", ""))));
        
        Document typeExpr = new Document("$ifNull", Arrays.asList("$type.uri", new Document("$ifNull", Arrays.asList("$typeDiscriminator", ""))));
        Document groupKey = new Document("title", "$title.value")
                .append("subTitle", "$subTitle.value")
                .append("typeUri", typeExpr);

        pipeline.add(new Document("$group", new Document("_id", groupKey)
                .append("count", new Document("$sum", 1))));
        pipeline.add(new Document("$match", new Document("count", new Document("$gt", 1))));
        pipeline.add(new Document("$limit", 5000));

        com.mongodb.client.MongoCollection<Document> collection = mongoTemplate.getCollection("Researchoutputs");
        com.mongodb.client.AggregateIterable<Document> results = collection.aggregate(pipeline);

        List<String> dups = new ArrayList<>();
        for (Document d : results) {
            Document idDoc = (Document) d.get("_id");
            if (idDoc != null) {
                String title = idDoc.getString("title");
                String subTitle = idDoc.getString("subTitle");
                String typeUri = idDoc.getString("typeUri");
                if (title != null && !title.isBlank()) {
                    String combined = title.trim() + (subTitle != null && !subTitle.isBlank() ? ": " + subTitle.trim() : "");
                    String typeKey = typeUri != null ? typeUri.trim().toLowerCase() : "";
                    dups.add(combined.toLowerCase() + "###" + typeKey);
                }
            }
        }
        return dups;
    }

    // ─── Helpers d'extraccio i validacio ──────────────────────────────────────

    public boolean langUriNullOrUnd(String lang) {
        return lang == null || lang.isBlank() || lang.endsWith("/und") || lang.equals("und");
    }

    public List<String> scanIssnValues(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof Document doc) {
            Document journalAssoc = (Document) doc.get("journalAssociation");
            if (journalAssoc != null) {
                Object issnObj = journalAssoc.get("issn");
                if (issnObj instanceof String issnStr && !issnStr.isBlank()) {
                    out.add(issnStr.trim());
                } else if (issnObj instanceof Document issnDoc) {
                    String issnVal = issnDoc.getString("issn");
                    if (issnVal == null) {
                        issnVal = issnDoc.getString("value");
                    }
                    if (issnVal != null && !issnVal.isBlank()) {
                        out.add(issnVal.trim());
                    }
                }
            }
        }
        return out;
    }

    public String extractAuthorsNames(Document doc) {
        List<Document> contrs = PublicacionDocumentUtils.getListDocument(doc, "contributors");
        if (contrs == null || contrs.isEmpty()) return "Sense autor";
        List<String> names = new ArrayList<>();
        for (Document c : contrs) {
            Document name = (Document) c.get("name");
            if (name != null) {
                String fn = name.getString("firstName");
                String ln = name.getString("lastName");
                if (fn != null && ln != null) {
                    names.add(ln + ", " + fn);
                } else if (ln != null) {
                    names.add(ln);
                } else if (fn != null) {
                    names.add(fn);
                }
            }
        }
        return String.join(" | ", names);
    }

    public String extractFirstAuthorLastName(Document doc) {
        List<Document> contrs = PublicacionDocumentUtils.getListDocument(doc, "contributors");
        if (contrs == null || contrs.isEmpty()) return null;
        Document first = contrs.get(0);
        Document name = (Document) first.get("name");
        if (name != null) {
            return name.getString("lastName");
        }
        return null;
    }

    public boolean isLocalOpenAccess(Document doc) {
        List<Document> evs = PublicacionDocumentUtils.getListDocument(doc, "electronicVersions");
        if (evs != null) {
            for (Document ev : evs) {
                Document accessType = (Document) ev.get("accessType");
                if (accessType != null) {
                    String uri = accessType.getString("uri");
                    if (uri != null && (uri.contains("/open") || uri.contains("/openaccess"))) {
                        return true;
                    }
                    Document term = (Document) accessType.get("term");
                    if (term != null) {
                        for (Object val : term.values()) {
                            if (val instanceof String s) {
                                String ls = s.toLowerCase();
                                if (ls.equals("open") || ls.equals("obert") || ls.equals("abierto")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // ─── Dashboard de correccions ────────────────────────────────────────────

    public Map<String, Object> getPublicationCorrections(Integer anio) {
        Map<String, Object> result = new LinkedHashMap<>();
        int defaultYear = LocalDate.now().getYear();

        List<Document> allPubs = new ArrayList<>();
        List<Integer> yearsToFetch = new ArrayList<>();
        if (anio != null && anio > 0) {
            yearsToFetch.add(anio);
        } else if (anio != null && anio == -1) {
            for (int y = defaultYear; y >= defaultYear - 10; y--) {
                yearsToFetch.add(y);
            }
        } else {
            yearsToFetch.add(defaultYear);
        }

        com.mongodb.client.MongoCollection<Document> coll = mongoTemplate.getCollection("Researchoutputs");
        org.bson.conversions.Bson projection = com.mongodb.client.model.Projections.fields(
            com.mongodb.client.model.Projections.include(
                "uuid", "pureId", "title", "subTitle", "translatedTitle", "abstract",
                "language", "electronicVersions", "links", "contributors.name",
                "identifiers", "submissionYear", "type", "typeDiscriminator",
                "journalAssociation", "printISBNs", "electronicISBNs"
            )
        );

        for (Integer y : yearsToFetch) {
            org.bson.conversions.Bson filter = com.mongodb.client.model.Filters.eq("submissionYear", y);
            try (com.mongodb.client.MongoCursor<Document> cursor = coll.find(filter).projection(projection).batchSize(200).iterator()) {
                while (cursor.hasNext()) {
                    try {
                        Document doc = cursor.next();
                        if (doc != null) {
                            allPubs.add(doc);
                        }
                    } catch (Exception docEx) {
                        System.err.println("Warning: Corrupted publication document skipped in year " + y + ": " + docEx.getMessage());
                    }
                }
            } catch (Exception yEx) {
                System.err.println("Warning: Error fetching year " + y + ": " + yEx.getMessage());
            }
        }

        // 1. Fetch review statuses
        List<PublicacionCorreccion> allCorrections = publicacionCorreccionRepository.findAll();
        Map<String, PublicacionCorreccion> correctionsMap = new LinkedHashMap<>();
        for (PublicacionCorreccion pc : allCorrections) {
            if (pc.getId() != null) {
                correctionsMap.put(pc.getId(), pc);
            }
        }

        // 2. Fetch duplicate sets database-wide
        List<String> dupTitles = getCachedDuplicateField("titles", "title.value");
        List<String> dupDois = getCachedDuplicateField("dois", "electronicVersions.doi");
        List<String> dupUrls = getCachedDuplicateUrls();
        List<String> dupIdentifiers = getCachedDuplicateIdentifiers();

        Set<String> dupTitlesSet = new HashSet<>(dupTitles);
        Set<String> dupDoisSet = new HashSet<>(dupDois);
        Set<String> dupUrlsSet = new HashSet<>(dupUrls);
        Set<String> dupIdentifiersSet = new HashSet<>(dupIdentifiers);

        // 3. Find potential duplicates
        Map<String, List<String>> potentialDupsGroup = new LinkedHashMap<>();
        for (Document p : allPubs) {
            String title = PublicacionDocumentUtils.nestedString(p, "title", "value");
            String author = extractFirstAuthorLastName(p);
            Integer year = p.getInteger("submissionYear");
            String uuid = p.getString("uuid");
            Document typeDoc = (Document) p.get("type");
            String typeUri = typeDoc != null ? typeDoc.getString("uri") : p.getString("typeDiscriminator");
            String normType = typeUri != null ? typeUri.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
            if (title != null && !title.isBlank() && uuid != null) {
                String normTitle = title.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
                String normAuthor = author != null ? author.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
                String key = normTitle + "#" + normAuthor + "#" + (year != null ? year : 0) + "#" + normType;
                potentialDupsGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(uuid);
            }
        }
        
        Set<String> potentialDupUuids = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : potentialDupsGroup.entrySet()) {
            if (entry.getValue().size() >= 2) {
                potentialDupUuids.addAll(entry.getValue());
            }
        }

        int missingAbstractCount = 0;
        int undefinedLanguageCount = 0;
        int missingDoiCount = 0;
        int missingUrlCount = 0;
        int duplicateTitleCount = 0;
        int duplicateDoiCount = 0;
        int duplicateUrlCount = 0;
        int duplicateIdentifierCount = 0;
        int potentialDuplicateCount = 0;
        int textQualityCount = 0;
        int linkQualityCount = 0;
        int isbnQualityCount = 0;
        int issnQualityCount = 0;

        List<Map<String, Object>> pubsNeedingCorrection = new ArrayList<>();

        for (Document p : allPubs) {
            String uuid = p.getString("uuid");
            if (uuid == null) continue;

            String mainTitle = PublicacionDocumentUtils.nestedString(p, "title", "value");
            String mainSubTitle = PublicacionDocumentUtils.nestedString(p, "subTitle", "value");
            String mainLang = PublicacionDocumentUtils.nestedString(p, "language", "uri");
            if (mainLang == null) mainLang = "";
            mainLang = mainLang.toLowerCase();

            // Check 2: Missing abstract
            boolean missingAbstract = true;
            Document ab = (Document) p.get("abstract");
            if (ab != null) {
                for (Map.Entry<String, Object> entry : ab.entrySet()) {
                    if (entry.getValue() instanceof String val && !val.isBlank()) {
                        missingAbstract = false;
                        break;
                    }
                }
            }

            // Check 3: Undefined language
            boolean undefinedLanguage = langUriNullOrUnd(mainLang);

            // Check 4: Missing DOI
            boolean missingDoi = true;
            List<Document> evs = PublicacionDocumentUtils.getListDocument(p, "electronicVersions");
            List<String> dois = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String doi = ev.getString("doi");
                    if (doi != null && !doi.isBlank()) {
                        missingDoi = false;
                        dois.add(doi);
                    }
                }
            }

            // Check 5: Missing URL
            boolean missingUrl = true;
            List<String> urls = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String link = ev.getString("link");
                    if (link != null && !link.isBlank()) {
                        missingUrl = false;
                        urls.add(link);
                    }
                    Document file = (Document) ev.get("file");
                    if (file != null) {
                        String fileUrl = file.getString("url");
                        if (fileUrl != null && !fileUrl.isBlank()) {
                            missingUrl = false;
                            urls.add(fileUrl);
                        }
                    }
                }
            }
            List<Document> lks = PublicacionDocumentUtils.getListDocument(p, "links");
            if (lks != null) {
                for (Document lk : lks) {
                    String url = lk.getString("url");
                    if (url != null && !url.isBlank()) {
                        missingUrl = false;
                        urls.add(url);
                    }
                }
            }

            // Check 6: Duplicates
            Document typeDoc = (Document) p.get("type");
            String typeUri = typeDoc != null ? typeDoc.getString("uri") : p.getString("typeDiscriminator");
            String typeKey = typeUri != null ? typeUri.trim().toLowerCase() : "";

            String combinedTitle = "";
            if (mainTitle != null && !mainTitle.isBlank()) {
                combinedTitle = mainTitle.trim() + (mainSubTitle != null && !mainSubTitle.isBlank() ? ": " + mainSubTitle.trim() : "");
            }
            String titleAndTypeKey = combinedTitle.toLowerCase() + "###" + typeKey;
            boolean duplicateTitle = !combinedTitle.isEmpty() && dupTitlesSet.contains(titleAndTypeKey);
            boolean duplicateDoi = false;
            for (String d : dois) {
                if (dupDoisSet.contains(d)) {
                    duplicateDoi = true;
                    break;
                }
            }
            boolean duplicateUrl = PublicacionDocumentUtils.hasDuplicateUrlOfType(p);
            boolean duplicateIdentifier = false;
            List<Document> idList = PublicacionDocumentUtils.getListDocument(p, "identifiers");
            for (Document idObj : idList) {
                String val = idObj.getString("value");
                if (val != null && dupIdentifiersSet.contains(val)) {
                    duplicateIdentifier = true;
                    break;
                }
            }
            boolean potentialDuplicate = potentialDupUuids.contains(uuid);

            // Check 7: Text quality
            boolean strangeChars = false;
            List<String> titlesToCheck = new ArrayList<>();
            titlesToCheck.add(mainTitle);
            if (mainSubTitle != null && !mainSubTitle.isBlank()) {
                titlesToCheck.add(mainSubTitle);
            }
            Document transTitle = (Document) p.get("translatedTitle");
            if (transTitle != null) {
                for (Object val : transTitle.values()) {
                    if (val instanceof String s) titlesToCheck.add(s);
                }
            }
            for (String t : titlesToCheck) {
                if (t != null && PublicacionDocumentUtils.containsStrangeCharacters(t)) {
                    strangeChars = true;
                    break;
                }
            }

            boolean allCaps = false;
            if (mainTitle != null && !mainTitle.isBlank() && PublicacionDocumentUtils.isAllUppercase(mainTitle)) {
                allCaps = true;
            }
            if (!allCaps && mainSubTitle != null && !mainSubTitle.isBlank() && PublicacionDocumentUtils.isAllUppercase(mainSubTitle)) {
                allCaps = true;
            }

            boolean whitespaceOnly = false;
            if (mainTitle != null && !mainTitle.isBlank() && mainTitle.trim().isEmpty()) {
                whitespaceOnly = true;
            }
            if (!whitespaceOnly) {
                List<Document> contrs = PublicacionDocumentUtils.getListDocument(p, "contributors");
                for (Document c : contrs) {
                    Document name = (Document) c.get("name");
                    if (name != null) {
                        String fn = name.getString("firstName");
                        String ln = name.getString("lastName");
                        if ((fn != null && !fn.isEmpty() && fn.trim().isEmpty()) ||
                            (ln != null && !ln.isEmpty() && ln.trim().isEmpty())) {
                            whitespaceOnly = true;
                            break;
                        }
                    }
                }
            }
            boolean textQualityIssue = strangeChars || allCaps || whitespaceOnly;

            // Check 8: Link quality
            boolean invalidUrl = false;
            for (String u : urls) {
                String lu = u.toLowerCase();
                if (!lu.startsWith("http://") && !lu.startsWith("https://")) {
                    invalidUrl = true;
                    break;
                }
                if (u.contains(" ")) {
                    invalidUrl = true;
                    break;
                }
                if (lu.contains("academia.edu") || lu.contains("researchgate.net")) {
                    invalidUrl = true;
                    break;
                }
            }

            // Check 9: ISBN
            boolean isBookOrChapter = false;
            if (typeUri != null) {
                String tu = typeUri.toLowerCase();
                if (tu.contains("/book") || tu.contains("/contributiontobook") || tu.contains("/chapter")) {
                    isBookOrChapter = true;
                }
            }
            boolean invalidIsbn = false;
            if (isBookOrChapter) {
                List<String> printIsbns = (List<String>) p.get("printISBNs");
                List<String> elecIsbns = (List<String>) p.get("electronicISBNs");
                boolean hasIsbn = false;
                List<String> allIsbns = new ArrayList<>();
                if (printIsbns != null) {
                    for (String isbn : printIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (elecIsbns != null) {
                    for (String isbn : elecIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (!hasIsbn) {
                    invalidIsbn = true;
                } else {
                    for (String isbn : allIsbns) {
                        if (!PublicacionDocumentUtils.isValidIsbn(isbn)) {
                            invalidIsbn = true;
                            break;
                        }
                    }
                }
            }

            // Check 10: ISSN
            boolean isArticle = false;
            if (typeUri != null) {
                String tu = typeUri.toLowerCase();
                if (tu.contains("/article") || tu.contains("/contributiontojournal")) {
                    isArticle = true;
                }
            }
            boolean invalidIssn = false;
            List<String> issnsSugeridosRevista = new ArrayList<>();
            if (isArticle) {
                List<String> issns = scanIssnValues(p);
                boolean hasIssn = false;
                for (String issn : issns) {
                    if (issn != null && !issn.isBlank()) {
                        hasIssn = true;
                    }
                }
                if (!hasIssn) {
                    invalidIssn = true;
                    Document journalAssoc = (Document) p.get("journalAssociation");
                    Document journalRef = journalAssoc != null ? (Document) journalAssoc.get("journal") : null;
                    if (journalRef != null) {
                        String journalUuid = journalRef.getString("uuid");
                        Integer journalPureId = journalRef.getInteger("pureId");
                        Query jQueries = null;
                        if (journalUuid != null) {
                            jQueries = new Query(Criteria.where("uuid").is(journalUuid));
                        } else if (journalPureId != null) {
                            jQueries = new Query(Criteria.where("pureId").is(journalPureId));
                        }
                        if (jQueries != null) {
                            jQueries.fields().include("issns").include("additionalSearchableIssns");
                            Document journalDoc = mongoTemplate.findOne(jQueries, Document.class, "Journals");
                            if (journalDoc != null) {
                                Set<String> seen = new LinkedHashSet<>();
                                List<Document> jIssns = PublicacionDocumentUtils.getListDocument(journalDoc, "issns");
                                if (jIssns != null) {
                                    for (Document jIssnObj : jIssns) {
                                        String jIssnVal = jIssnObj.getString("issn");
                                        if (jIssnVal != null && !jIssnVal.isBlank()) {
                                            seen.add(jIssnVal.trim());
                                        }
                                    }
                                }
                                List<Document> jAddIssns = PublicacionDocumentUtils.getListDocument(journalDoc, "additionalSearchableIssns");
                                if (jAddIssns != null) {
                                    for (Document jIssnObj : jAddIssns) {
                                        String jIssnVal = jIssnObj.getString("issn");
                                        if (jIssnVal != null && !jIssnVal.isBlank()) {
                                            seen.add(jIssnVal.trim());
                                        }
                                    }
                                }
                                issnsSugeridosRevista.addAll(seen);
                                if (!issnsSugeridosRevista.isEmpty()) {
                                    invalidIssn = false;
                                }
                            }
                        }
                    }
                } else {
                    for (String issn : issns) {
                        if (issn != null && !issn.isBlank() && !PublicacionDocumentUtils.isValidIssn(issn)) {
                            invalidIssn = true;
                            break;
                        }
                    }
                }
            }

            List<String> printIsbnsClean = new ArrayList<>();
            List<String> printIsbns = (List<String>) p.get("printISBNs");
            if (printIsbns != null) {
                for (String isbn : printIsbns) {
                    if (isbn != null && !isbn.isBlank()) printIsbnsClean.add(isbn.trim());
                }
            }
            List<String> elecIsbnsClean = new ArrayList<>();
            List<String> elecIsbns = (List<String>) p.get("electronicISBNs");
            if (elecIsbns != null) {
                for (String isbn : elecIsbns) {
                    if (isbn != null && !isbn.isBlank()) elecIsbnsClean.add(isbn.trim());
                }
            }
            List<String> isbns = new ArrayList<>();
            isbns.addAll(printIsbnsClean);
            isbns.addAll(elecIsbnsClean);
            List<String> issns = scanIssnValues(p);

            // Flags aggregator
            if (missingAbstract || undefinedLanguage || missingDoi || missingUrl ||
                duplicateTitle || duplicateDoi || duplicateUrl || duplicateIdentifier || potentialDuplicate ||
                textQualityIssue || invalidUrl || invalidIsbn || invalidIssn) {

                List<String> errors = new ArrayList<>();
                if (missingAbstract) { errors.add("MISSING_ABSTRACT"); missingAbstractCount++; }
                if (undefinedLanguage) { errors.add("UNDEFINED_LANGUAGE"); undefinedLanguageCount++; }
                if (missingDoi) { errors.add("MISSING_DOI"); missingDoiCount++; }
                if (missingUrl) { errors.add("MISSING_URL"); missingUrlCount++; }
                if (duplicateTitle) { errors.add("DUPLICATE_TITLE"); duplicateTitleCount++; }
                if (duplicateDoi) { errors.add("DUPLICATE_DOI"); duplicateDoiCount++; }
                if (duplicateUrl) { errors.add("DUPLICATE_URL"); duplicateUrlCount++; }
                if (duplicateIdentifier) { errors.add("DUPLICATE_IDENTIFIER"); duplicateIdentifierCount++; }
                if (potentialDuplicate) { errors.add("POTENTIAL_DUPLICATE"); potentialDuplicateCount++; }
                if (textQualityIssue) { errors.add("TEXT_QUALITY"); textQualityCount++; }
                if (invalidUrl) { errors.add("LINK_QUALITY"); linkQualityCount++; }
                if (invalidIsbn) { errors.add("INVALID_ISBN"); isbnQualityCount++; }
                if (invalidIssn) { errors.add("INVALID_ISSN"); issnQualityCount++; }

                // Fetch review info
                PublicacionCorreccion pc = correctionsMap.get(uuid);
                boolean reviewed = pc != null && pc.isReviewed();
                String observations = pc != null ? pc.getObservations() : "";

                Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
                String tipo = termDoc != null ? termDoc.getString("ca_ES") : "Sense tipus";

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("uuid", uuid);
                details.put("pureId", p.get("pureId"));
                details.put("typeDiscriminator", p.getString("typeDiscriminator"));
                mainSubTitle = PublicacionDocumentUtils.nestedString(p, "subTitle", "value");
                String fullTitle = mainTitle;
                if (mainTitle != null && mainSubTitle != null && !mainSubTitle.isBlank()) {
                    fullTitle = mainTitle + ": " + mainSubTitle;
                }
                details.put("titulo", fullTitle != null ? PublicacionDocumentUtils.decodeHtmlEntities(fullTitle) : "Sense títol");
                details.put("autor", extractAuthorsNames(p));
                details.put("anio", p.getInteger("submissionYear"));
                details.put("tipo", tipo);
                details.put("errors", errors);
                details.put("dois", dois);
                details.put("urls", urls);
                details.put("isbns", isbns);
                details.put("printISBNs", printIsbnsClean);
                details.put("electronicISBNs", elecIsbnsClean);
                details.put("issns", issns);
                details.put("issnsSugeridosRevista", issnsSugeridosRevista);
                details.put("isBookOrChapter", isBookOrChapter);
                details.put("isArticle", isArticle);
                details.put("localOa", isLocalOpenAccess(p));
                List<Map<String, String>> idClean = new ArrayList<>();
                if (idList != null) {
                    for (Document idDoc : idList) {
                        String val = idDoc.getString("value");
                        String src = idDoc.getString("idSource");
                        if (src == null) src = idDoc.getString("typeDiscriminator");
                        if (val != null && !val.isBlank()) {
                            Map<String, String> m = new HashMap<>();
                            m.put("source", src != null ? src : "ID");
                            m.put("value", val.trim());
                            idClean.add(m);
                        }
                    }
                }
                details.put("identifiers", idClean);
                details.put("reviewed", reviewed);
                details.put("observations", observations);

                pubsNeedingCorrection.add(details);
            }
        }

        // Sort by submissionYear descending (newest first)
        pubsNeedingCorrection.sort((a, b) -> {
            Integer yA = (Integer) a.get("anio");
            Integer yB = (Integer) b.get("anio");
            if (yA == null && yB == null) return 0;
            if (yA == null) return 1;
            if (yB == null) return -1;
            return yB.compareTo(yA);
        });

        Map<String, Integer> kpis = new LinkedHashMap<>();
        kpis.put("missingAbstract", missingAbstractCount);
        kpis.put("undefinedLanguage", undefinedLanguageCount);
        kpis.put("missingDoi", missingDoiCount);
        kpis.put("missingUrl", missingUrlCount);
        kpis.put("duplicateTitle", duplicateTitleCount);
        kpis.put("duplicateDoi", duplicateDoiCount);
        kpis.put("duplicateUrl", duplicateUrlCount);
        kpis.put("duplicateIdentifier", duplicateIdentifierCount);
        kpis.put("potentialDuplicate", potentialDuplicateCount);
        kpis.put("textQuality", textQualityCount);
        kpis.put("linkQuality", linkQualityCount);
        kpis.put("isbnQuality", isbnQualityCount);
        kpis.put("issnQuality", issnQualityCount);

        result.put("kpis", kpis);
        result.put("publications", pubsNeedingCorrection);

        return result;
    }

    public void autoMarkAsReviewedIfNoErrorsLeft(String uuid, Document p) {
        try {
            String mainTitle = PublicacionDocumentUtils.nestedString(p, "title", "value");
            String mainSubTitle = PublicacionDocumentUtils.nestedString(p, "subTitle", "value");
            String mainLang = PublicacionDocumentUtils.nestedString(p, "language", "uri");
            if (mainLang == null) mainLang = "";
            mainLang = mainLang.toLowerCase();

            // Check 2: Missing abstract
            boolean missingAbstract = true;
            Document ab = (Document) p.get("abstract");
            if (ab != null) {
                for (Map.Entry<String, Object> entry : ab.entrySet()) {
                    if (entry.getValue() instanceof String val && !val.isBlank()) {
                        missingAbstract = false;
                        break;
                    }
                }
            }

            // Check 3: Undefined language
            boolean undefinedLanguage = langUriNullOrUnd(mainLang);

            // Check 4: Missing DOI
            boolean missingDoi = true;
            List<Document> evs = PublicacionDocumentUtils.getListDocument(p, "electronicVersions");
            List<String> dois = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String doi = ev.getString("doi");
                    if (doi != null && !doi.isBlank()) {
                        missingDoi = false;
                        dois.add(doi);
                    }
                }
            }

            // Check 5: Missing URL
            boolean missingUrl = true;
            List<String> urls = new ArrayList<>();
            if (evs != null) {
                for (Document ev : evs) {
                    String link = ev.getString("link");
                    if (link != null && !link.isBlank()) {
                        missingUrl = false;
                        urls.add(link);
                    }
                    Document file = (Document) ev.get("file");
                    if (file != null) {
                        String fileUrl = file.getString("url");
                        if (fileUrl != null && !fileUrl.isBlank()) {
                            missingUrl = false;
                            urls.add(fileUrl);
                        }
                    }
                }
            }
            List<Document> lks = PublicacionDocumentUtils.getListDocument(p, "links");
            if (lks != null) {
                for (Document lk : lks) {
                    String url = lk.getString("url");
                    if (url != null && !url.isBlank()) {
                        missingUrl = false;
                        urls.add(url);
                    }
                }
            }

            // Check 6: Duplicates
            List<String> dupTitles = getCachedDuplicateField("titles", "title.value");
            List<String> dupDois = getCachedDuplicateField("dois", "electronicVersions.doi");
            List<String> dupUrls = getCachedDuplicateUrls();
            List<String> dupIdentifiers = getCachedDuplicateIdentifiers();

            Set<String> dupTitlesSet = new HashSet<>(dupTitles);
            Set<String> dupDoisSet = new HashSet<>(dupDois);
            Set<String> dupUrlsSet = new HashSet<>(dupUrls);
            Set<String> dupIdentifiersSet = new HashSet<>(dupIdentifiers);

            Document typeDoc = (Document) p.get("type");
            String typeUri = typeDoc != null ? typeDoc.getString("uri") : p.getString("typeDiscriminator");
            String typeKey = typeUri != null ? typeUri.trim().toLowerCase() : "";

            String combinedTitle = "";
            if (mainTitle != null && !mainTitle.isBlank()) {
                combinedTitle = mainTitle.trim() + (mainSubTitle != null && !mainSubTitle.isBlank() ? ": " + mainSubTitle.trim() : "");
            }
            String titleAndTypeKey = combinedTitle.toLowerCase() + "###" + typeKey;
            boolean duplicateTitle = !combinedTitle.isEmpty() && dupTitlesSet.contains(titleAndTypeKey);
            boolean duplicateDoi = false;
            for (String d : dois) {
                if (dupDoisSet.contains(d)) {
                    duplicateDoi = true;
                    break;
                }
            }
            boolean duplicateUrl = PublicacionDocumentUtils.hasDuplicateUrlOfType(p);
            boolean duplicateIdentifier = false;
            List<Document> idList = PublicacionDocumentUtils.getListDocument(p, "identifiers");
            for (Document idObj : idList) {
                String val = idObj.getString("value");
                if (val != null && dupIdentifiersSet.contains(val)) {
                    duplicateIdentifier = true;
                    break;
                }
            }
            
            boolean potentialDuplicate = false;
            String author = extractFirstAuthorLastName(p);
            Integer year = p.getInteger("submissionYear");
            if (mainTitle != null && !mainTitle.isBlank()) {
                String normTitle = mainTitle.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
                Query dupQuery = new Query(Criteria.where("uuid").ne(uuid).and("submissionYear").is(year));
                dupQuery.fields().include("uuid", "title", "contributors.name", "submissionYear", "type", "typeDiscriminator");
                List<Document> siblingPubs = mongoTemplate.find(dupQuery, Document.class, "Researchoutputs");
                for (Document sib : siblingPubs) {
                    String sibTitle = PublicacionDocumentUtils.nestedString(sib, "title", "value");
                    if (sibTitle != null) {
                        String sibNormTitle = sibTitle.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim();
                        if (sibNormTitle.equals(normTitle)) {
                            Document sibTypeDoc = (Document) sib.get("type");
                            String sibTypeUri = sibTypeDoc != null ? sibTypeDoc.getString("uri") : sib.getString("typeDiscriminator");
                            String sibTypeKey = sibTypeUri != null ? sibTypeUri.trim().toLowerCase() : "";
                            if (sibTypeKey.equals(typeKey)) {
                                String sibAuthor = extractFirstAuthorLastName(sib);
                                String sibNormAuthor = sibAuthor != null ? sibAuthor.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
                                String normAuthor = author != null ? author.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim() : "";
                                if (sibNormAuthor.equals(normAuthor)) {
                                    potentialDuplicate = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            // Check 7: Text quality
            boolean strangeChars = false;
            List<String> titlesToCheck = new ArrayList<>();
            titlesToCheck.add(mainTitle);
            if (mainSubTitle != null && !mainSubTitle.isBlank()) {
                titlesToCheck.add(mainSubTitle);
            }
            Document transTitle = (Document) p.get("translatedTitle");
            if (transTitle != null) {
                for (Object val : transTitle.values()) {
                    if (val instanceof String s) titlesToCheck.add(s);
                }
            }
            for (String t : titlesToCheck) {
                if (t != null && PublicacionDocumentUtils.containsStrangeCharacters(t)) {
                    strangeChars = true;
                    break;
                }
            }

            boolean allCaps = false;
            if (mainTitle != null && !mainTitle.isBlank() && PublicacionDocumentUtils.isAllUppercase(mainTitle)) {
                allCaps = true;
            }
            if (!allCaps && mainSubTitle != null && !mainSubTitle.isBlank() && PublicacionDocumentUtils.isAllUppercase(mainSubTitle)) {
                allCaps = true;
            }

            boolean whitespaceOnly = false;
            if (mainTitle != null && !mainTitle.isBlank() && mainTitle.trim().isEmpty()) {
                whitespaceOnly = true;
            }
            if (!whitespaceOnly) {
                List<Document> contrs = PublicacionDocumentUtils.getListDocument(p, "contributors");
                for (Document c : contrs) {
                    Document name = (Document) c.get("name");
                    if (name != null) {
                        String fn = name.getString("firstName");
                        String ln = name.getString("lastName");
                        if ((fn != null && !fn.isEmpty() && fn.trim().isEmpty()) ||
                            (ln != null && !ln.isEmpty() && ln.trim().isEmpty())) {
                            whitespaceOnly = true;
                            break;
                        }
                    }
                }
            }
            boolean textQualityIssue = strangeChars || allCaps || whitespaceOnly;

            // Check 8: Link quality
            boolean invalidUrl = false;
            for (String u : urls) {
                String lu = u.toLowerCase();
                if (!lu.startsWith("http://") && !lu.startsWith("https://")) {
                    invalidUrl = true;
                    break;
                }
                if (u.contains(" ")) {
                    invalidUrl = true;
                    break;
                }
                if (lu.contains("academia.edu") || lu.contains("researchgate.net")) {
                    invalidUrl = true;
                    break;
                }
            }

            // Check 9: ISBN/ISSN
            boolean invalidIsbn = false;
            boolean invalidIssn = false;
            String typeDiscriminator = p.getString("typeDiscriminator");
            boolean isBookOrChapter = "BookAnthology".equalsIgnoreCase(typeDiscriminator)
                || "ContributionToBookAnthology".equalsIgnoreCase(typeDiscriminator)
                || "Book".equalsIgnoreCase(typeDiscriminator)
                || "Chapter".equalsIgnoreCase(typeDiscriminator);

            if (isBookOrChapter) {
                List<String> printIsbns = (List<String>) p.get("printISBNs");
                List<String> elecIsbns = (List<String>) p.get("electronicISBNs");
                List<String> allIsbns = new ArrayList<>();
                boolean hasIsbn = false;
                if (printIsbns != null) {
                    for (String isbn : printIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (elecIsbns != null) {
                    for (String isbn : elecIsbns) {
                        if (isbn != null && !isbn.isBlank()) {
                            hasIsbn = true;
                            allIsbns.add(isbn);
                        }
                    }
                }
                if (!hasIsbn) {
                    invalidIsbn = true;
                } else {
                    for (String isbn : allIsbns) {
                        if (!PublicacionDocumentUtils.isValidIsbn(isbn)) {
                            invalidIsbn = true;
                            break;
                        }
                    }
                }
            } else {
                boolean isArticle = false;
                Document typeObj = (Document) p.get("type");
                String articleTypeUri = typeObj != null ? typeObj.getString("uri") : null;
                if (articleTypeUri != null) {
                    String tu = articleTypeUri.toLowerCase();
                    if (tu.contains("/article") || tu.contains("/contributiontojournal")) {
                        isArticle = true;
                    }
                }
                if (isArticle) {
                    List<String> issns = scanIssnValues(p);
                    boolean hasIssn = false;
                    for (String issn : issns) {
                        if (issn != null && !issn.isBlank()) {
                            hasIssn = true;
                        }
                    }
                    if (!hasIssn) {
                        invalidIssn = true;
                    } else {
                        for (String issn : issns) {
                            if (!PublicacionDocumentUtils.isValidIssn(issn)) {
                                invalidIssn = true;
                                break;
                            }
                        }
                    }
                }
            }

            boolean hasErrors = missingAbstract || undefinedLanguage
                || missingDoi || missingUrl || duplicateTitle || duplicateDoi || duplicateUrl
                || duplicateIdentifier || potentialDuplicate || textQualityIssue || invalidUrl
                || invalidIsbn || invalidIssn;

            if (!hasErrors) {
                PublicacionCorreccion pc = publicacionCorreccionRepository.findById(uuid).orElse(null);
                if (pc == null) {
                    pc = new PublicacionCorreccion();
                    pc.setId(uuid);
                }
                pc.setReviewed(true);
                pc.setModifiedDate(new Date());
                publicacionCorreccionRepository.save(pc);
            }
        } catch (Exception e) {
            System.err.println("Error auto-marking publication as reviewed: " + e.getMessage());
        }
    }
}
