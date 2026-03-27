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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

@Service
public class ResearchOutputJournalLinkService {

    private static final String RESEARCHOUTPUTS_COLLECTION = "Researchoutputs";

    private final MongoTemplate mongoTemplate;
    private final JournalRepository journalRepository;
    private final JournalJcrService journalJcrService;

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

    public List<Map<String, Object>> quartileDistributionByDepartment(String deptUuid, Integer desde, Integer hasta) {
        List<String> publicationUuids = publicationUuidsByDepartment(deptUuid, desde, hasta);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Q1", 0);
        counts.put("Q2", 0);
        counts.put("Q3", 0);
        counts.put("Q4", 0);
        counts.put("Sense Quartil", 0);

        if (publicationUuids.isEmpty()) {
            return List.of();
        }

        Query publicationsQuery = new Query(Criteria.where("uuid").in(publicationUuids));
        List<Document> publications = mongoTemplate.find(publicationsQuery, Document.class, RESEARCHOUTPUTS_COLLECTION);

        Map<String, Optional<Journal>> journalByUuid = new HashMap<>();
        Map<String, String> quartileByIssnAndYear = new HashMap<>();
        Map<String, List<Jcr>> jcrByIssn = new HashMap<>();

        for (Document publication : publications) {
            Integer publicationYear = extractYear(publication);
            String quartile = resolveQuartileForPublication(
                    publication,
                    publicationYear,
                    journalByUuid,
                    quartileByIssnAndYear,
                    jcrByIssn
            );

            if (quartile == null || quartile.isBlank() || !counts.containsKey(quartile)) {
                counts.put("Sense Quartil", counts.get("Sense Quartil") + 1);
            } else {
                counts.put(quartile, counts.get(quartile) + 1);
            }
        }

        List<Map<String, Object>> output = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 0) {
                output.add(Map.of(
                        "quartile", entry.getKey(),
                        "total", entry.getValue()
                ));
            }
        }
        return output;
    }

    public List<Map<String, Object>> quartileArticlesByDepartment(String deptUuid, Integer desde, Integer hasta) {
        List<String> publicationUuids = publicationUuidsByDepartment(deptUuid, desde, hasta);
        if (publicationUuids.isEmpty()) {
            return List.of();
        }

        Query publicationsQuery = new Query(Criteria.where("uuid").in(publicationUuids));
        List<Document> publications = mongoTemplate.find(publicationsQuery, Document.class, RESEARCHOUTPUTS_COLLECTION);

        Map<String, Optional<Journal>> journalByUuid = new HashMap<>();
        Map<String, String> quartileByIssnAndYear = new HashMap<>();
        Map<String, List<Jcr>> jcrByIssn = new HashMap<>();

        List<Map<String, Object>> output = new ArrayList<>();
        for (Document publication : publications) {
            String publicationUuid = asString(publication.get("uuid"));
            String title = nestedString(publication, "title", "value");
            Integer year = extractYear(publication);
            String authors = extractAuthorsApa(publication);
            String quartile = resolveQuartileForPublication(
                    publication,
                    year,
                    journalByUuid,
                    quartileByIssnAndYear,
                    jcrByIssn
            );
            String journalUuid = findFirstString(publication,
                List.of("journalAssociation", "journal", "uuid"),
                List.of("journal", "uuid"),
                List.of("publicationChannel", "journal", "uuid")
            );

            Optional<Journal> journalOpt = Optional.empty();
            if (journalUuid != null && !journalUuid.isBlank()) {
            journalOpt = journalByUuid.computeIfAbsent(journalUuid, key -> journalRepository.findByUuid(key));
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

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("publicationUuid", publicationUuid);
            row.put("title", title);
            row.put("year", year);
            row.put("quartile", quartile == null ? "Sense Quartil" : quartile);
            row.put("cita", buildCitationApa(authors, day, month, year, journalTitle, volume, issue, pages, articleNumber, title));
            output.add(row);
        }

        output.sort((a, b) -> {
            String qa = String.valueOf(a.getOrDefault("quartile", "Sense Quartil"));
            String qb = String.valueOf(b.getOrDefault("quartile", "Sense Quartil"));
            int cmpQ = qa.compareTo(qb);
            if (cmpQ != 0) {
                return cmpQ;
            }
            Integer ya = a.get("year") instanceof Integer i ? i : -1;
            Integer yb = b.get("year") instanceof Integer i ? i : -1;
            return Integer.compare(yb, ya);
        });

        return output;
    }

    public List<Map<String, Object>> quartileEvolutionByDepartment(String deptUuid, Integer desde, Integer hasta) {
        List<String> publicationUuids = publicationUuidsByDepartment(deptUuid, desde, hasta);
        if (publicationUuids.isEmpty()) {
            return List.of();
        }

        Query publicationsQuery = new Query(Criteria.where("uuid").in(publicationUuids));
        List<Document> publications = mongoTemplate.find(publicationsQuery, Document.class, RESEARCHOUTPUTS_COLLECTION);

        Map<String, Optional<Journal>> journalByUuid = new HashMap<>();
        Map<String, String> quartileByIssnAndYear = new HashMap<>();
        Map<String, List<Jcr>> jcrByIssn = new HashMap<>();

        Map<Integer, Map<String, Object>> byYear = new TreeMap<>();

        for (Document publication : publications) {
            Integer publicationYear = extractYear(publication);
            if (publicationYear == null) {
                continue;
            }

            String quartile = resolveQuartileForPublication(
                    publication,
                    publicationYear,
                    journalByUuid,
                    quartileByIssnAndYear,
                    jcrByIssn
            );

            String bucket = (quartile == null || quartile.isBlank()) ? "Sense Quartil" : quartile;
            if (!List.of("Q1", "Q2", "Q3", "Q4", "Sense Quartil").contains(bucket)) {
                bucket = "Sense Quartil";
            }

            Map<String, Object> row = byYear.computeIfAbsent(publicationYear, year -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("year", year);
                item.put("Q1", 0);
                item.put("Q2", 0);
                item.put("Q3", 0);
                item.put("Q4", 0);
                item.put("Sense Quartil", 0);
                return item;
            });

            int current = row.get(bucket) instanceof Number n ? n.intValue() : 0;
            row.put(bucket, current + 1);
        }

        return new ArrayList<>(byYear.values());
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
            journalOpt = journalByUuid.computeIfAbsent(journalUuid, key -> journalRepository.findByUuid(key));
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

    private List<String> publicationUuidsByDepartment(String deptUuid, Integer desde, Integer hasta) {
        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("workflow.step", "approved")));
        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", List.of("$publicationDate.year", "$submissionYear")))
                .append("contributors", new Document("$ifNull", List.of("$contributors", List.of())))));

        List<Document> andFilters = new ArrayList<>();
        if (desde != null) {
            andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
        }
        if (hasta != null) {
            andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
        }
        if (!andFilters.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", andFilters)));
        }

        pipeline.add(new Document("$unwind", "$contributors"));
        Document personUuidExpr = new Document("$trim", new Document("input",
            new Document("$ifNull", List.of(
                "$contributors.person.uuid",
                new Document("$ifNull", List.of("$contributors.externalPerson.uuid", ""))
            ))));
        pipeline.add(new Document("$project", new Document()
            .append("publicationUuid", 1)
            .append("personUuid", personUuidExpr)));

        pipeline.add(new Document("$match", new Document("$expr",
                new Document("$gt", List.of(new Document("$strLenCP", "$personUuid"), 0)))));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Persons")
                .append("localField", "personUuid")
                .append("foreignField", "uuid")
                .append("as", "persona_info")));

        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$persona_info")
                .append("preserveNullAndEmptyArrays", false)));

        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();
        Date hoyDate = Date.from(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant());

        Document activeAssociationCriteria = new Document("$or", List.of(
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

        if (deptUuid != null && !deptUuid.isBlank()) {
            Document assocCriteria = new Document("$and", List.of(
                    new Document("organization.uuid", deptUuid),
                    activeAssociationCriteria
            ));
            pipeline.add(new Document("$match", new Document(
                    "persona_info.staffOrganizationAssociations",
                    new Document("$elemMatch", assocCriteria)
            )));
        } else {
            pipeline.add(new Document("$match", new Document(
                    "persona_info.staffOrganizationAssociations",
                    new Document("$elemMatch", activeAssociationCriteria)
            )));
        }

        pipeline.add(new Document("$group", new Document("_id", "$publicationUuid")));
        pipeline.add(new Document("$project", new Document("_id", 0).append("publicationUuid", "$_id")));

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