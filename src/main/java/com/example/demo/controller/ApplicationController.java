package com.example.demo.controller;

import com.example.demo.model.Application;
import com.example.demo.repository.ApplicationRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/applications", "/applications", "/otr/api/applications"})
@CrossOrigin(origins = "*")
public class ApplicationController {

    private final ApplicationRepository repository;
    private final MongoTemplate mongoTemplate;

    public ApplicationController(ApplicationRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public Page<Application> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uuid"));

        String collectionName = resolveApplicationCollection();
        if (collectionName == null) {
            if (buscar == null || buscar.isBlank()) {
                return repository.findAll(pageable);
            }
            return repository.findByTitleContainingIgnoreCase(buscar, pageable);
        }

        Query query = new Query();
        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            query.addCriteria(new Criteria().orOperator(
                Criteria.where("title.value").regex(escaped, "i"),
                Criteria.where("title.ca_ES").regex(escaped, "i"),
                Criteria.where("title.es_ES").regex(escaped, "i"),
                Criteria.where("title.en_GB").regex(escaped, "i"),
                Criteria.where("title.text.value").regex(escaped, "i")
            ));
        }

        long total = mongoTemplate.count(query, collectionName);
        query.with(pageable);
        List<Application> items = mongoTemplate.find(query, Application.class, collectionName);
        return new PageImpl<>(items, pageable, total);
    }

    @GetMapping("/describe")
    public Map<String, Object> describeCollection(@RequestParam(defaultValue = "20") int sampleSize) {
        String collectionName = resolveApplicationCollection();
        if (collectionName == null) {
            return Map.of(
                "collection", "not-found",
                "totalDocuments", 0,
                "sampleSize", 0,
                "fieldPaths", List.of(),
                "samples", List.of()
            );
        }

        int safeSampleSize = Math.max(1, Math.min(sampleSize, 200));
        List<Document> docs = mongoTemplate
            .getCollection(collectionName)
            .find()
            .limit(safeSampleSize)
            .into(new ArrayList<>());

        Set<String> paths = new LinkedHashSet<>();
        List<Map<String, Object>> samples = new ArrayList<>();

        for (Document doc : docs) {
            collectFieldPaths(doc, "", paths);

            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("uuid", doc.get("uuid"));
            sample.put("title", doc.get("title"));
            sample.put("type", doc.get("type"));
            sample.put("workflow", doc.get("workflow"));
            samples.add(sample);
        }

        long totalDocuments = mongoTemplate.getCollection(collectionName).countDocuments();
        return Map.of(
            "collection", collectionName,
            "totalDocuments", totalDocuments,
            "sampleSize", docs.size(),
            "fieldPaths", new ArrayList<>(paths),
            "samples", samples
        );
    }

    @GetMapping("/stats/by-funding-opportunity")
    public List<Map<String, Object>> statsByFundingOpportunity() {
        String applicationCollection = resolveApplicationCollection();
        if (applicationCollection == null) {
            return List.of();
        }

        List<Document> pipeline = List.of(
            new Document("$match", new Document("fundingOpportunity.uuid", new Document("$ne", null))),
            new Document("$project", new Document("fundingUuid", "$fundingOpportunity.uuid")
                .append("sent", 1)
                .append("applicationDateRaw", "$applicationDate")
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
                ))))),
            new Document("$project", new Document("fundingUuid", 1)
                .append("sent", 1)
                .append("applicationDateRaw", 1)
                .append("rejected", new Document("$cond", Arrays.asList(
                    new Document("$regexMatch", new Document("input", "$replyText")
                        .append("regex", "reject|deneg|declin|desestim|rebutj|unfavorable|refused|not funded|no funded")),
                    1,
                    0
                )))
                .append("accepted", new Document("$cond", Arrays.asList(
                    new Document("$and", Arrays.asList(
                        new Document("$not", Arrays.asList(
                            new Document("$regexMatch", new Document("input", "$replyText")
                                .append("regex", "reject|deneg|declin|desestim|rebutj|unfavorable|refused|not funded|no funded"))
                        )),
                        new Document("$regexMatch", new Document("input", "$replyText")
                            .append("regex", "accept|approved|award|granted|conced|aprobad|admis|seleccion|favorable"))
                    )),
                    1,
                    0
                )))),
            new Document("$group", new Document("_id", "$fundingUuid")
                .append("sent", new Document("$sum", 1))
                .append("accepted", new Document("$sum", "$accepted"))
                .append("rejected", new Document("$sum", "$rejected"))
                .append("applicationDateRaw", new Document("$push", "$applicationDateRaw"))),
            new Document("$project", new Document("_id", 0)
                .append("fundingUuid", "$_id")
                .append("sent", 1)
                .append("accepted", 1)
                .append("rejected", 1)
                .append("applicationDateRaw", 1))
        );

        List<Document> stats = mongoTemplate
            .getCollection(applicationCollection)
            .aggregate(pipeline)
            .into(new ArrayList<>());

        if (stats.isEmpty()) {
            return List.of();
        }

        String fundingCollection = resolveFundingOpportunityCollection();
        Map<String, Document> fundingByUuid = new LinkedHashMap<>();

        if (fundingCollection != null) {
            List<String> uuids = stats.stream()
                .map(d -> d.getString("fundingUuid"))
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();

            if (!uuids.isEmpty()) {
                List<Document> fundingDocs = mongoTemplate
                    .getCollection(fundingCollection)
                    .find(new Document("uuid", new Document("$in", uuids)))
                    .into(new ArrayList<>());

                for (Document doc : fundingDocs) {
                    String uuid = doc.getString("uuid");
                    if (uuid != null && !uuid.isBlank()) {
                        fundingByUuid.put(uuid, doc);
                    }
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document row : stats) {
            String fundingUuid = row.getString("fundingUuid");
            int sent = toInt(row.get("sent"));
            int accepted = toInt(row.get("accepted"));
            int rejected = toInt(row.get("rejected"));
            int pending = Math.max(sent - accepted - rejected, 0);

            Document fundingDoc = fundingByUuid.get(fundingUuid);
            String fundingTitle = fundingDoc != null ? extractLocalizedValue(fundingDoc.get("title")) : null;
            String fundingType = fundingDoc != null ? extractFundingType(fundingDoc.get("type")) : null;
            Integer fundingOpeningYear = fundingDoc != null ? extractFundingYear(fundingDoc) : null;
            Integer applicationYear = extractFirstYearFromCollection(row.get("applicationDateRaw"));
            Integer fundingYear = fundingOpeningYear != null ? fundingOpeningYear : applicationYear;
            if (fundingYear == null) {
                fundingYear = extractYearFromTitle(fundingTitle);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fundingUuid", fundingUuid);
            item.put("fundingTitle", fundingTitle != null ? fundingTitle : "Sin título");
            item.put("fundingType", fundingType != null ? fundingType : "Desconocido");
            item.put("fundingYear", fundingYear);
            item.put("sent", sent);
            item.put("accepted", accepted);
            item.put("rejected", rejected);
            item.put("pending", pending);
            result.add(item);
        }

        result.sort((a, b) -> Integer.compare((int) b.get("sent"), (int) a.get("sent")));
        return result;
    }

    private String resolveApplicationCollection() {
        List<String> candidates = List.of(
            "Applications",
            "applications",
            "Application",
            "application"
        );

        for (String name : candidates) {
            if (!mongoTemplate.collectionExists(name)) {
                continue;
            }
            if (mongoTemplate.getCollection(name).countDocuments() > 0) {
                return name;
            }
        }

        for (String name : candidates) {
            if (mongoTemplate.collectionExists(name)) {
                return name;
            }
        }

        return null;
    }

    private String resolveFundingOpportunityCollection() {
        List<String> candidates = List.of(
            "FundingOpportunities",
            "Fundingopportunities",
            "fundingOpportunities",
            "fundingopportunities",
            "FundingOpportunity",
            "fundingopportunity"
        );

        for (String name : candidates) {
            if (!mongoTemplate.collectionExists(name)) {
                continue;
            }
            if (mongoTemplate.getCollection(name).countDocuments() > 0) {
                return name;
            }
        }

        for (String name : candidates) {
            if (mongoTemplate.collectionExists(name)) {
                return name;
            }
        }

        return null;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private String extractFundingType(Object rawType) {
        if (!(rawType instanceof Map<?, ?> typeMap)) {
            return extractLocalizedValue(rawType);
        }
        return extractLocalizedValue(typeMap.get("term"));
    }

    private Integer extractFundingYear(Document fundingDoc) {
        String[] directYearKeys = {"year", "anio", "anyo", "callYear", "convocatoriaYear", "submissionYear", "awardYear"};
        for (String key : directYearKeys) {
            Integer year = toInteger(fundingDoc.get(key));
            if (year != null) {
                return year;
            }
        }

        String[] nestedYearPaths = {
            "openingDate.year",
            "awardDate.year",
            "publicationDate.year",
            "periodStartDate.year",
            "periodStartDateReal.year",
            "periodEndDate.year",
            "periodEndDateReal.year",
            "startDate.year",
            "openDate.year",
            "callDate.year"
        };
        for (String path : nestedYearPaths) {
            Integer year = toInteger(getValueByPath(fundingDoc, path));
            if (year != null) {
                return year;
            }
        }

        String[] structuredDatePaths = {
            "openingDate",
            "awardDate",
            "publicationDate",
            "periodStartDate",
            "periodStartDateReal",
            "periodEndDate",
            "periodEndDateReal",
            "startDate",
            "openDate",
            "callDate"
        };
        for (String path : structuredDatePaths) {
            Integer year = extractYearFromDateValue(getValueByPath(fundingDoc, path));
            if (year != null) {
                return year;
            }
        }

        return null;
    }

    private Integer extractYearFromDateValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            long raw = number.longValue();
            if (raw > 10000000000L) {
                return java.time.Instant.ofEpochMilli(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .getYear();
            }
            if (raw > 1000000000L) {
                return java.time.Instant.ofEpochSecond(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .getYear();
            }
            return toInteger(value);
        }

        if (value instanceof Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).getYear();
        }

        if (value instanceof Map<?, ?> map) {
            Integer fromYear = toInteger(map.get("year"));
            if (fromYear != null) {
                return fromYear;
            }

            String[] nestedKeys = {"$date", "value", "date", "es_ES", "ca_ES", "en_GB", "term"};
            for (String key : nestedKeys) {
                Integer nested = extractYearFromDateValue(map.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                Integer year = extractYearFromDateValue(item);
                if (year != null) {
                    return year;
                }
            }
            return null;
        }

        if (value instanceof String str) {
            java.util.regex.Matcher matcher = Pattern.compile("(19|20)\\d{2}").matcher(str);
            if (matcher.find()) {
                return toInteger(matcher.group());
            }
        }

        return toInteger(value);
    }

    private Integer extractFirstYearFromCollection(Object values) {
        if (values instanceof List<?> list) {
            for (Object value : list) {
                Integer year = extractYearFromDateValue(value);
                if (year != null) {
                    return year;
                }
            }
            return null;
        }

        return extractYearFromDateValue(values);
    }

    private Integer extractYearFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        java.util.regex.Matcher matcher = Pattern.compile("(19|20)\\d{2}").matcher(title);
        Integer candidate = null;
        while (matcher.find()) {
            Integer year = toInteger(matcher.group());
            if (year != null) {
                candidate = year;
                break;
            }
        }

        return candidate;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            int candidate = number.intValue();
            if (candidate >= 1900 && candidate <= 3000) {
                return candidate;
            }
            return null;
        }

        if (value instanceof String str) {
            String clean = str.trim();
            if (clean.matches("^(19|20)\\d{2}$")) {
                return Integer.parseInt(clean);
            }
        }

        return null;
    }

    private Object getValueByPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        String[] segments = path.split("\\\\.");
        Object current = root;

        for (String segment : segments) {
            if (current instanceof Document doc) {
                current = doc.get(segment);
                continue;
            }

            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                continue;
            }

            return null;
        }

        return current;
    }

    private String extractLocalizedValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String str) {
            String clean = str.trim();
            return clean.isBlank() ? null : clean;
        }

        if (value instanceof Map<?, ?> map) {
            Object text = map.get("text");
            String fromTextArray = extractFromTextArray(text);
            if (fromTextArray != null) {
                return fromTextArray;
            }

            String[] keys = {"ca_ES", "es_ES", "en_GB", "value", "term", "text"};
            for (String key : keys) {
                String nested = extractLocalizedValue(map.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nested = extractLocalizedValue(item);
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String extractFromTextArray(Object text) {
        if (!(text instanceof List<?> list)) {
            return null;
        }

        String[] locales = {"ca_ES", "es_ES", "en_GB"};
        for (String locale : locales) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    continue;
                }
                Object localeValue = itemMap.get("locale");
                if (!(localeValue instanceof String localeStr) || !locale.equals(localeStr)) {
                    continue;
                }
                String resolved = extractLocalizedValue(itemMap.get("value"));
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }
            String resolved = extractLocalizedValue(itemMap.get("value"));
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    private void collectFieldPaths(Object value, String prefix, Set<String> paths) {
        if (value == null) {
            return;
        }

        if (value instanceof Document doc) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String path = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
                paths.add(path);
                collectFieldPaths(entry.getValue(), path, paths);
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                String path = prefix.isBlank() ? key : prefix + "." + key;
                paths.add(path);
                collectFieldPaths(entry.getValue(), path, paths);
            }
            return;
        }

        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return;
            }
            String listPath = prefix + "[]";
            paths.add(listPath);
            collectFieldPaths(list.get(0), listPath, paths);
        }
    }
}
