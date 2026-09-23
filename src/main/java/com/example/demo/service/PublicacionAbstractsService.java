package com.example.demo.service;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PublicacionAbstractsService {

    private final MongoTemplate mongoTemplate;

    private final Map<String, CacheEntry> statsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> yearsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> yearsLanguagesCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> typesCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    public record CacheEntry(Object data, long timestamp) {}

    // NLP Stop words and distinctive markers for language classification
    private static final Set<String> CA_DISTINCT = Set.of(
        "amb", "dels", "pels", "aquest", "aquesta", "aquests", "aquestes", "perque", "perquè", "seva",
        "seus", "seves", "tambe", "també", "gairebe", "gairebé", "gaire", "quelcom", "adhuc", "àdhuc",
        "nomes", "només", "doncs", "mateix", "mateixa", "mateixos", "mateixes", "qualque", "llur", "llurs",
        "despres", "després", "mentre", "sovint", "tanmateix", "hom", "cadascu", "cadascú", "cadascun", "cadascuna",
        "aleshores", "aixi", "així", "sense", "fins", "segons", "mitjançant", "envers", "sigui", "siguin",
        "essent", "estat", "estats", "estada", "estades", "fet", "feta", "fets", "fetes", "millorar", "aconseguir",
        "coneixement", "coneixements", "desenvolupament", "recerca", "poblacio", "població", "qualitat", "activitat"
    );

    private static final Set<String> CA_COMMON = Set.of(
        "de", "la", "el", "a", "i", "en", "que", "del", "les", "per", "un", "els", "una", "uns", "unes",
        "al", "als", "pel", "no", "com", "es", "és", "se", "mes", "més", "tot", "tots", "tota", "totes",
        "ha", "han", "estudi", "objectiu", "resultats", "conclusions", "metodologia", "metode", "mètode",
        "investigacio", "investigació", "entre", "sobre", "on", "quan", "molt", "molta", "molts", "moltes",
        "poc", "pocs", "poca", "poques", "altre", "altres", "altra", "nostre", "nostra", "nostres", "durant",
        "hi", "ho", "li", "son", "són", "era", "eren", "va", "van", "ja", "fa", "fan"
    );

    private static final Set<String> ES_DISTINCT = Set.of(
        "los", "las", "por", "para", "con", "sus", "este", "esta", "estos", "estas", "esto", "ese", "esa", "esos", "esas",
        "aquel", "aquella", "aquellos", "aquellas", "tambien", "también", "hacia", "hasta", "desde", "despues", "después",
        "entonces", "ademas", "además", "mientras", "aunque", "donde", "cuando", "mismo", "misma", "mismos", "mismas",
        "cada", "nuestro", "nuestra", "nuestros", "nuestras", "suyo", "suya", "suyos", "suyas", "hecho", "hecha", "hechos", "hechas",
        "hace", "hacen", "hizo", "sido", "habia", "había", "hubo", "hay", "desarrollo", "conocimiento", "mediante",
        "acerca", "cuanto", "tanto", "poblacion", "población", "calidad", "actividad", "sociedad", "universidad"
    );

    private static final Set<String> ES_COMMON = Set.of(
        "de", "la", "el", "en", "y", "a", "que", "del", "se", "un", "una", "unos", "unas", "no", "al", "es", "su",
        "algunos", "como", "mas", "más", "pero", "le", "les", "ya", "o", "fue", "ha", "han", "si", "porque", "por",
        "son", "era", "eran", "entre", "estudio", "objetivo", "resultados", "conclusiones", "investigacion", "investigación",
        "analisis", "análisis", "durante", "sobre", "todo", "todos", "toda", "todas", "muy", "mucho", "muchos", "mucha",
        "muchas", "poco", "pocos", "poca", "pocas", "otro", "otros", "otra", "otras", "solo", "solamente"
    );

    private static final Set<String> EN_DISTINCT = Set.of(
        "the", "this", "that", "these", "those", "with", "from", "which", "were", "have", "been", "their", "our",
        "they", "them", "between", "through", "within", "should", "could", "would", "where", "when", "what",
        "who", "whose", "whom", "why", "how", "both", "each", "other", "others", "such", "also", "than", "then",
        "after", "before", "during", "while", "without", "about", "into", "onto", "under", "above", "more", "most",
        "many", "much", "some", "any", "few", "paper", "aims", "findings", "discuss", "discusses", "used", "using",
        "background", "approach", "based", "showed", "demonstrated", "provides", "significant", "however", "although", "furthermore"
    );

    private static final Set<String> EN_COMMON = Set.of(
        "and", "of", "to", "in", "is", "are", "was", "for", "on", "as", "at", "by", "an", "be", "it", "its",
        "we", "you", "your", "he", "she", "his", "her", "not", "but", "or", "if", "so", "can", "will", "has",
        "had", "do", "does", "did", "study", "results", "aim", "methods", "method", "research", "analysis", "data", "conclusion", "conclusions"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-zA-Zà-ÿÀ-ß'-]+\\b");

    @Autowired
    public PublicacionAbstractsService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private boolean isCacheValid(CacheEntry entry) {
        return entry != null && (System.currentTimeMillis() - entry.timestamp()) < CACHE_TTL_MS;
    }

    public static Document buildPublicationYearExpr() {
        return new Document("$ifNull", List.of(
            new Document("$arrayElemAt", List.of("$publicationStatuses.publicationDate.year", 0)),
            new Document("$ifNull", List.of("$publicationDate.year", "$submissionYear"))
        ));
    }

    public static Integer extractPublicationYear(Document doc) {
        if (doc == null) return null;
        Object pubStatusesObj = doc.get("publicationStatuses");
        if (pubStatusesObj instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Document itemDoc) {
                Object pDate = itemDoc.get("publicationDate");
                if (pDate instanceof Document dateDoc && dateDoc.get("year") instanceof Number n) {
                    return n.intValue();
                }
            }
        }
        Object pubDateObj = doc.get("publicationDate");
        if (pubDateObj instanceof Document dateDoc && dateDoc.get("year") instanceof Number n) {
            return n.intValue();
        }
        Object subYear = doc.get("submissionYear");
        if (subYear instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /**
     * Language detector based on vocabulary, distinctive stopwords, character discriminators and elision handling
     */
    public static String detectLanguage(String text) {
        if (text == null || text.trim().length() < 10) return "unknown";
        String raw = text.replaceAll("<[^>]+>", " ");

        int caSpecificBonus = 0;
        int esSpecificBonus = 0;

        // Character-level unique features
        if (raw.contains("ñ") || raw.contains("Ñ") || raw.contains("¿") || raw.contains("¡")) {
            esSpecificBonus += 6;
        }
        if (raw.contains("ç") || raw.contains("Ç") || raw.contains("·") || raw.contains("l·l") || raw.contains("L·L") || raw.contains("L.L") || raw.contains("l.l")) {
            caSpecificBonus += 6;
        }

        // Separate Catalan elisions: d', l', s', m', t', n', c', qu'
        String clean = raw.toLowerCase()
                .replaceAll("\\b([dlstmnc]|qu)['’]", "$1' ")
                .replaceAll("['-]", " ");

        Matcher matcher = WORD_PATTERN.matcher(clean);

        int totalWords = 0;
        int scoreEn = 0;
        int scoreEs = esSpecificBonus;
        int scoreCa = caSpecificBonus;

        while (matcher.find()) {
            String w = matcher.group();
            totalWords++;

            if (EN_DISTINCT.contains(w)) scoreEn += 3;
            else if (EN_COMMON.contains(w)) scoreEn += 1;

            if (ES_DISTINCT.contains(w)) scoreEs += 3;
            else if (ES_COMMON.contains(w)) scoreEs += 1;

            if (CA_DISTINCT.contains(w)) scoreCa += 3;
            else if (CA_COMMON.contains(w)) scoreCa += 1;

            if ("d'".equals(w) || "l'".equals(w) || "s'".equals(w) || "m'".equals(w) || "t'".equals(w) || "n'".equals(w)) {
                scoreCa += 3;
            }
            if ("i".equals(w)) scoreCa += 2;
            if ("y".equals(w)) scoreEs += 2;
        }

        if (totalWords == 0) return "unknown";

        int maxScore = Math.max(scoreEn, Math.max(scoreEs, scoreCa));
        if (maxScore < 4 && totalWords > 5) return "unknown";

        if (scoreEn > scoreEs && scoreEn > scoreCa && scoreEn >= (maxScore * 0.6)) return "en_GB";
        if (scoreCa > scoreEs && scoreCa > scoreEn) return "ca_ES";
        if (scoreEs > scoreCa && scoreEs > scoreEn) return "es_ES";

        return "unknown";
    }

    /**
     * Calcula KPIs globals d'abstracts amb filtres opcionals i diagnòstic lingüístic
     */
    public Map<String, Object> getAbstractStats(Integer desde, Integer hasta, String type) {
        return getAbstractStats(desde, hasta, type, null, "publicationYear");
    }

    public Map<String, Object> getAbstractStats(Integer desde, Integer hasta, String type, String language) {
        return getAbstractStats(desde, hasta, type, language, "publicationYear");
    }

    /**
     * Calcula KPIs globals d'abstracts amb filtres opcionals i diagnòstic lingüístic
     */
    public Map<String, Object> getAbstractStats(Integer desde, Integer hasta, String type, String language, String yearType) {
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        String cacheKey = "stats_" + desde + "_" + hasta + "_" + (type == null ? "" : type) + "_" + (language == null ? "" : language) + "_" + (isSubmissionYear ? "sub" : "pub");
        CacheEntry cached = statsCache.get(cacheKey);
        if (isCacheValid(cached)) {
            return (Map<String, Object>) cached.data();
        }

        List<Document> pipeline = new ArrayList<>();

        List<Document> initialMatches = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            initialMatches.add(new Document("$or", List.of(
                new Document("type.term.ca_ES", type),
                new Document("type.term.es_ES", type),
                new Document("type.term.en_GB", type)
            )));
        }
        if (language != null && !language.isBlank() && !"all".equalsIgnoreCase(language)) {
            String norm = normalizeRecognizedLanguage(language);
            if ("Anglès".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Angl").append("$options", "i")));
            } else if ("Espanyol".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Espanyol|Castell").append("$options", "i")));
            } else if ("Català".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Catal").append("$options", "i")));
            } else if ("Francès".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Franc").append("$options", "i")));
            } else if ("Italià".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Itali").append("$options", "i")));
            } else if ("Portuguès".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Portugu").append("$options", "i")));
            } else if ("Alemany".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Alem").append("$options", "i")));
            } else if ("Gallec".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Gallec").append("$options", "i")));
            } else if ("Rus".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Rus").append("$options", "i")));
            } else if ("Basc".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "Basc|usc").append("$options", "i")));
            } else if ("Múltiples idiomes".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("language.term.ca_ES", new Document("$regex", "ltiples").append("$options", "i")));
            } else if ("Altres / Desconegut".equalsIgnoreCase(norm)) {
                initialMatches.add(new Document("$or", List.of(
                    new Document("language", new Document("$exists", false)),
                    new Document("language", null),
                    new Document("language.term.ca_ES", new Document("$nin", List.of(
                        "Anglès", "Anglès nord-americà", "Espanyol", "Català", "Francès", "Italià", "Portuguès", "Portuguès (Brasil)", "Alemany", "Gallec", "Rus", "Basc", "Múltiples idiomes"
                    )))
                )));
            } else {
                initialMatches.add(new Document("language.term.ca_ES", language));
            }
        }
        if (!initialMatches.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", initialMatches)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("year", new Document("$cond", List.of(
                new Document("$gte", List.of(
                    new Document("$ifNull", List.of(isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(), 0)),
                    1900
                )),
                isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(),
                0
            )))
            .append("has_ca", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.ca_ES", ""))), 0)), 1, 0)))
            .append("has_es", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.es_ES", ""))), 0)), 1, 0)))
            .append("has_en", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.en_GB", ""))), 0)), 1, 0)))
            .append("ca", new Document("$ifNull", List.of("$abstract.ca_ES", "")))
            .append("es", new Document("$ifNull", List.of("$abstract.es_ES", "")))
            .append("en", new Document("$ifNull", List.of("$abstract.en_GB", "")))
        ));

        List<Document> yearFilterList = new ArrayList<>();
        if (desde != null) {
            if (desde == 0) {
                yearFilterList.add(new Document("year", 0));
            } else {
                yearFilterList.add(new Document("year", new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) yearFilterList.add(new Document("year", new Document("$lte", hasta)));
        if (!yearFilterList.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", yearFilterList)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("has_ca", 1)
            .append("has_es", 1)
            .append("has_en", 1)
            .append("lang_count", new Document("$add", List.of("$has_ca", "$has_es", "$has_en")))
            .append("is_identical_3", new Document("$cond", List.of(
                new Document("$and", List.of(
                    new Document("$eq", List.of("$has_ca", 1)),
                    new Document("$eq", List.of("$has_es", 1)),
                    new Document("$eq", List.of("$has_en", 1)),
                    new Document("$eq", List.of("$ca", "$es")),
                    new Document("$eq", List.of("$es", "$en"))
                )), 1, 0
            )))
            .append("is_identical_any_2", new Document("$cond", List.of(
                new Document("$or", List.of(
                    new Document("$and", List.of(new Document("$eq", List.of("$has_ca", 1)), new Document("$eq", List.of("$has_es", 1)), new Document("$eq", List.of("$ca", "$es")))),
                    new Document("$and", List.of(new Document("$eq", List.of("$has_ca", 1)), new Document("$eq", List.of("$has_en", 1)), new Document("$eq", List.of("$ca", "$en")))),
                    new Document("$and", List.of(new Document("$eq", List.of("$has_es", 1)), new Document("$eq", List.of("$has_en", 1)), new Document("$eq", List.of("$es", "$en"))))
                )), 1, 0
            )))
        ));

        pipeline.add(new Document("$group", new Document("_id", null)
            .append("total", new Document("$sum", 1))
            .append("has_ca", new Document("$sum", "$has_ca"))
            .append("has_es", new Document("$sum", "$has_es"))
            .append("has_en", new Document("$sum", "$has_en"))
            .append("with_abstract", new Document("$sum", new Document("$cond", List.of(new Document("$gt", List.of("$lang_count", 0)), 1, 0))))
            .append("no_abstract", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 0)), 1, 0))))
            .append("in_1_lang", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 1)), 1, 0))))
            .append("in_2_langs", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 2)), 1, 0))))
            .append("in_3_langs", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 3)), 1, 0))))
            .append("identical_in_3", new Document("$sum", "$is_identical_3"))
            .append("identical_in_any_2", new Document("$sum", "$is_identical_any_2"))
        ));

        List<Document> results = mongoTemplate.getCollection("Researchoutputs").aggregate(pipeline).into(new ArrayList<>());
        Map<String, Object> resp = new LinkedHashMap<>();

        if (!results.isEmpty()) {
            Document doc = results.get(0);
            long total = ((Number) doc.getOrDefault("total", 0)).longValue();
            long withAbstract = ((Number) doc.getOrDefault("with_abstract", 0)).longValue();
            long noAbstract = ((Number) doc.getOrDefault("no_abstract", 0)).longValue();
            long in1Lang = ((Number) doc.getOrDefault("in_1_lang", 0)).longValue();
            long in2Langs = ((Number) doc.getOrDefault("in_2_langs", 0)).longValue();
            long in3Langs = ((Number) doc.getOrDefault("in_3_langs", 0)).longValue();
            long identicalIn3 = ((Number) doc.getOrDefault("identical_in_3", 0)).longValue();
            long identicalAny2 = ((Number) doc.getOrDefault("identical_in_any_2", 0)).longValue();
            long differentIn3 = in3Langs - identicalIn3;

            long hasCa = ((Number) doc.getOrDefault("has_ca", 0)).longValue();
            long hasEs = ((Number) doc.getOrDefault("has_es", 0)).longValue();
            long hasEn = ((Number) doc.getOrDefault("has_en", 0)).longValue();

            resp.put("total", total);
            resp.put("withAbstract", withAbstract);
            resp.put("noAbstract", noAbstract);
            resp.put("pctWithAbstract", total > 0 ? (double) Math.round((withAbstract * 1000.0) / total) / 10.0 : 0.0);
            resp.put("pctNoAbstract", total > 0 ? (double) Math.round((noAbstract * 1000.0) / total) / 10.0 : 0.0);

            resp.put("in1Lang", in1Lang);
            resp.put("in2Langs", in2Langs);
            resp.put("in3Langs", in3Langs);
            resp.put("pctIn3LangsOfTotal", total > 0 ? (double) Math.round((in3Langs * 1000.0) / total) / 10.0 : 0.0);
            resp.put("pctIn3LangsOfWithAbstract", withAbstract > 0 ? (double) Math.round((in3Langs * 1000.0) / withAbstract) / 10.0 : 0.0);

            resp.put("identicalIn3", identicalIn3);
            resp.put("differentIn3", differentIn3);
            resp.put("pctIdenticalOf3Langs", in3Langs > 0 ? (double) Math.round((identicalIn3 * 1000.0) / in3Langs) / 10.0 : 0.0);
            resp.put("pctDifferentOf3Langs", in3Langs > 0 ? (double) Math.round((differentIn3 * 1000.0) / in3Langs) / 10.0 : 0.0);

            resp.put("identicalAny2", identicalAny2);
            resp.put("hasCa", hasCa);
            resp.put("hasEs", hasEs);
            resp.put("hasEn", hasEn);

            // Detailed Linguistic Mismatch breakdown computed dynamically
            Map<String, Object> dynStats = computeDynamicLinguisticStats(desde, hasta, type, language, yearType, withAbstract, in3Langs);
            resp.putAll(dynStats);

        } else {
            resp.put("total", 0);
            resp.put("withAbstract", 0);
            resp.put("noAbstract", 0);
            resp.put("pctWithAbstract", 0.0);
            resp.put("pctNoAbstract", 0.0);
            resp.put("in1Lang", 0);
            resp.put("in2Langs", 0);
            resp.put("in3Langs", 0);
            resp.put("identicalIn3", 0);
            resp.put("differentIn3", 0);
            resp.put("pctIdenticalOf3Langs", 0.0);
            resp.put("pctDifferentOf3Langs", 0.0);
            resp.put("hasCa", 0);
            resp.put("hasEs", 0);
            resp.put("hasEn", 0);
            resp.put("wrongLanguageCount", 0);
            resp.put("genuine3Langs", 0);
        }

        statsCache.put(cacheKey, new CacheEntry(resp, System.currentTimeMillis()));
        return resp;
    }

    private Map<String, Object> computeDynamicLinguisticStats(Integer desde, Integer hasta, String type, String language, long withAbstract, long in3Langs) {
        return computeDynamicLinguisticStats(desde, hasta, type, language, "publicationYear", withAbstract, in3Langs);
    }

    private Map<String, Object> computeDynamicLinguisticStats(Integer desde, Integer hasta, String type, String language, String yearType, long withAbstract, long in3Langs) {
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        Document query = new Document();
        List<Document> andList = new ArrayList<>();

        andList.add(new Document("$or", List.of(
            new Document("abstract.ca_ES", new Document("$exists", true).append("$ne", "")),
            new Document("abstract.es_ES", new Document("$exists", true).append("$ne", "")),
            new Document("abstract.en_GB", new Document("$exists", true).append("$ne", ""))
        )));

        if (isSubmissionYear) {
            if (desde != null) {
                if (desde == 0) {
                    andList.add(new Document("$or", List.of(
                        new Document("submissionYear", null),
                        new Document("submissionYear", 0),
                        new Document("submissionYear", new Document("$exists", false))
                    )));
                } else {
                    andList.add(new Document("submissionYear", new Document("$gte", desde)));
                }
            }
            if (hasta != null && hasta > 0) andList.add(new Document("submissionYear", new Document("$lte", hasta)));
        } else {
            if (desde != null) {
                if (desde == 0) {
                    andList.add(new Document("$and", List.of(
                        new Document("publicationStatuses.publicationDate.year", new Document("$exists", false)),
                        new Document("publicationDate.year", new Document("$exists", false)),
                        new Document("$or", List.of(
                            new Document("submissionYear", null),
                            new Document("submissionYear", 0),
                            new Document("submissionYear", new Document("$exists", false))
                        ))
                    )));
                } else {
                    andList.add(new Document("$or", List.of(
                        new Document("publicationStatuses.publicationDate.year", new Document("$gte", desde)),
                        new Document("$and", List.of(
                            new Document("publicationStatuses.publicationDate.year", new Document("$exists", false)),
                            new Document("publicationDate.year", new Document("$gte", desde))
                        )),
                        new Document("$and", List.of(
                            new Document("publicationStatuses.publicationDate.year", new Document("$exists", false)),
                            new Document("publicationDate.year", new Document("$exists", false)),
                            new Document("submissionYear", new Document("$gte", desde))
                        ))
                    )));
                }
            }
            if (hasta != null && hasta > 0) {
                andList.add(new Document("$or", List.of(
                    new Document("publicationStatuses.publicationDate.year", new Document("$lte", hasta)),
                    new Document("$and", List.of(
                        new Document("publicationStatuses.publicationDate.year", new Document("$exists", false)),
                        new Document("publicationDate.year", new Document("$lte", hasta))
                    )),
                    new Document("$and", List.of(
                        new Document("publicationStatuses.publicationDate.year", new Document("$exists", false)),
                        new Document("publicationDate.year", new Document("$exists", false)),
                        new Document("submissionYear", new Document("$lte", hasta))
                    ))
                )));
            }
        }

        if (type != null && !type.isBlank()) {
            andList.add(new Document("$or", List.of(
                new Document("type.term.ca_ES", type),
                new Document("type.term.es_ES", type),
                new Document("type.term.en_GB", type)
            )));
        }
        if (language != null && !language.isBlank() && !"all".equalsIgnoreCase(language)) {
            String norm = normalizeRecognizedLanguage(language);
            if ("Anglès".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Angl").append("$options", "i")));
            } else if ("Espanyol".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Espanyol|Castell").append("$options", "i")));
            } else if ("Català".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Catal").append("$options", "i")));
            } else if ("Francès".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Franc").append("$options", "i")));
            } else if ("Italià".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Itali").append("$options", "i")));
            } else if ("Portuguès".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Portugu").append("$options", "i")));
            } else if ("Alemany".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Alem").append("$options", "i")));
            } else if ("Gallec".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Gallec").append("$options", "i")));
            } else if ("Rus".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Rus").append("$options", "i")));
            } else if ("Basc".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "Basc|usc").append("$options", "i")));
            } else if ("Múltiples idiomes".equalsIgnoreCase(norm)) {
                andList.add(new Document("language.term.ca_ES", new Document("$regex", "ltiples").append("$options", "i")));
            } else if ("Altres / Desconegut".equalsIgnoreCase(norm)) {
                andList.add(new Document("$or", List.of(
                    new Document("language", new Document("$exists", false)),
                    new Document("language", null),
                    new Document("language.term.ca_ES", new Document("$nin", List.of(
                        "Anglès", "Anglès nord-americà", "Espanyol", "Català", "Francès", "Italià", "Portuguès", "Portuguès (Brasil)", "Alemany", "Gallec", "Rus", "Basc", "Múltiples idiomes"
                    )))
                )));
            } else {
                andList.add(new Document("language.term.ca_ES", language));
            }
        }

        query.put("$and", andList);

        Document projection = new Document()
            .append("abstract.ca_ES", 1)
            .append("abstract.es_ES", 1)
            .append("abstract.en_GB", 1);

        long wrongLanguageCount = 0;
        long genuine3Langs = 0;

        long caTotal = 0, caCorrect = 0, caWrongEn = 0, caWrongEs = 0, caWrongOther = 0;
        long esTotal = 0, esCorrect = 0, esWrongEn = 0, esWrongCa = 0, esWrongOther = 0;
        long enTotal = 0, enCorrect = 0, enWrongEs = 0, enWrongCa = 0, enWrongOther = 0;

        try (var cursor = mongoTemplate.getCollection("Researchoutputs").find(query).projection(projection).batchSize(5000).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                Document abs = (Document) doc.get("abstract");
                if (abs == null) continue;

                String ca = abs.getString("ca_ES");
                String es = abs.getString("es_ES");
                String en = abs.getString("en_GB");

                boolean hasCa = ca != null && !ca.isBlank();
                boolean hasEs = es != null && !es.isBlank();
                boolean hasEn = en != null && !en.isBlank();

                String detCa = hasCa ? detectLanguage(ca) : "none";
                String detEs = hasEs ? detectLanguage(es) : "none";
                String detEn = hasEn ? detectLanguage(en) : "none";

                boolean isWrongCa = hasCa && ("en_GB".equals(detCa) || "es_ES".equals(detCa));
                boolean isWrongEs = hasEs && ("en_GB".equals(detEs) || "ca_ES".equals(detEs));
                boolean isWrongEn = hasEn && ("ca_ES".equals(detEn) || "es_ES".equals(detEn));

                if (hasCa) {
                    caTotal++;
                    if (!isWrongCa) {
                        caCorrect++;
                    } else {
                        if ("en_GB".equals(detCa)) caWrongEn++;
                        else if ("es_ES".equals(detCa)) caWrongEs++;
                        else caWrongOther++;
                    }
                }

                if (hasEs) {
                    esTotal++;
                    if (!isWrongEs) {
                        esCorrect++;
                    } else {
                        if ("en_GB".equals(detEs)) esWrongEn++;
                        else if ("ca_ES".equals(detEs)) esWrongCa++;
                        else esWrongOther++;
                    }
                }

                if (hasEn) {
                    enTotal++;
                    if (!isWrongEn) {
                        enCorrect++;
                    } else {
                        if ("es_ES".equals(detEn)) enWrongEs++;
                        else if ("ca_ES".equals(detEn)) enWrongCa++;
                        else enWrongOther++;
                    }
                }

                if (isWrongCa || isWrongEs || isWrongEn) {
                    wrongLanguageCount++;
                }

                if (hasCa && hasEs && hasEn && !isWrongCa && !isWrongEs && !isWrongEn) {
                    genuine3Langs++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wrongLanguageCount", wrongLanguageCount);
        result.put("pctWrongLanguageOfWithAbstract", withAbstract > 0 ? (double) Math.round((wrongLanguageCount * 1000.0) / withAbstract) / 10.0 : 0.0);
        result.put("genuine3Langs", genuine3Langs);
        result.put("pctGenuine3Langs", in3Langs > 0 ? (double) Math.round((genuine3Langs * 1000.0) / in3Langs) / 10.0 : 0.0);

        Map<String, Object> caStats = new LinkedHashMap<>();
        caStats.put("total", caTotal);
        caStats.put("correct", caCorrect);
        caStats.put("wrongEn", caWrongEn);
        caStats.put("wrongEs", caWrongEs);
        caStats.put("wrongOther", caWrongOther);
        caStats.put("pctWrong", caTotal > 0 ? (double) Math.round(((caTotal - caCorrect) * 1000.0) / caTotal) / 10.0 : 0.0);
        caStats.put("pctCorrect", caTotal > 0 ? (double) Math.round((caCorrect * 1000.0) / caTotal) / 10.0 : 0.0);
        caStats.put("pctWrongEn", caTotal > 0 ? (double) Math.round((caWrongEn * 1000.0) / caTotal) / 10.0 : 0.0);
        caStats.put("pctWrongEs", caTotal > 0 ? (double) Math.round((caWrongEs * 1000.0) / caTotal) / 10.0 : 0.0);

        Map<String, Object> esStats = new LinkedHashMap<>();
        esStats.put("total", esTotal);
        esStats.put("correct", esCorrect);
        esStats.put("wrongEn", esWrongEn);
        esStats.put("wrongCa", esWrongCa);
        esStats.put("wrongOther", esWrongOther);
        esStats.put("pctWrong", esTotal > 0 ? (double) Math.round(((esTotal - esCorrect) * 1000.0) / esTotal) / 10.0 : 0.0);
        esStats.put("pctCorrect", esTotal > 0 ? (double) Math.round((esCorrect * 1000.0) / esTotal) / 10.0 : 0.0);
        esStats.put("pctWrongEn", esTotal > 0 ? (double) Math.round((esWrongEn * 1000.0) / esTotal) / 10.0 : 0.0);
        esStats.put("pctWrongCa", esTotal > 0 ? (double) Math.round((esWrongCa * 1000.0) / esTotal) / 10.0 : 0.0);

        Map<String, Object> enStats = new LinkedHashMap<>();
        enStats.put("total", enTotal);
        enStats.put("correct", enCorrect);
        enStats.put("wrongEs", enWrongEs);
        enStats.put("wrongCa", enWrongCa);
        enStats.put("wrongOther", enWrongOther);
        enStats.put("pctWrong", enTotal > 0 ? (double) Math.round(((enTotal - enCorrect) * 1000.0) / enTotal) / 10.0 : 0.0);
        enStats.put("pctCorrect", enTotal > 0 ? (double) Math.round((enCorrect * 1000.0) / enTotal) / 10.0 : 0.0);
        enStats.put("pctWrongEs", enTotal > 0 ? (double) Math.round((enWrongEs * 1000.0) / enTotal) / 10.0 : 0.0);
        enStats.put("pctWrongCa", enTotal > 0 ? (double) Math.round((enWrongCa * 1000.0) / enTotal) / 10.0 : 0.0);

        result.put("caFieldStats", caStats);
        result.put("esFieldStats", esStats);
        result.put("enFieldStats", enStats);

        return result;
    }

    /**
     * Distribució per any (Any de Publicació o Any de Submission)
     */
    public List<Map<String, Object>> getAbstractsByYear(Integer desde, Integer hasta) {
        return getAbstractsByYear(desde, hasta, "publicationYear");
    }

    public List<Map<String, Object>> getAbstractsByYear(Integer desde, Integer hasta, String yearType) {
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        String cacheKey = "years_" + (isSubmissionYear ? "sub" : "pub") + "_" + desde + "_" + hasta;
        CacheEntry cached = yearsCache.get(cacheKey);
        if (isCacheValid(cached)) {
            return (List<Map<String, Object>>) cached.data();
        }

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$project", new Document()
            .append("year", new Document("$cond", List.of(
                new Document("$gte", List.of(
                    new Document("$ifNull", List.of(isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(), 0)),
                    1900
                )),
                isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(),
                0
            )))
            .append("has_ca", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.ca_ES", ""))), 0)), 1, 0)))
            .append("has_es", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.es_ES", ""))), 0)), 1, 0)))
            .append("has_en", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.en_GB", ""))), 0)), 1, 0)))
            .append("ca", new Document("$ifNull", List.of("$abstract.ca_ES", "")))
            .append("es", new Document("$ifNull", List.of("$abstract.es_ES", "")))
            .append("en", new Document("$ifNull", List.of("$abstract.en_GB", "")))
        ));

        List<Document> yearFilterList = new ArrayList<>();
        if (desde != null) {
            if (desde == 0) {
                yearFilterList.add(new Document("year", 0));
            } else {
                yearFilterList.add(new Document("year", new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) yearFilterList.add(new Document("year", new Document("$lte", hasta)));
        if (!yearFilterList.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", yearFilterList)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("year", 1)
            .append("has_ca", 1)
            .append("has_es", 1)
            .append("has_en", 1)
            .append("lang_count", new Document("$add", List.of("$has_ca", "$has_es", "$has_en")))
            .append("is_identical_3", new Document("$cond", List.of(
                new Document("$and", List.of(
                    new Document("$eq", List.of("$has_ca", 1)),
                    new Document("$eq", List.of("$has_es", 1)),
                    new Document("$eq", List.of("$has_en", 1)),
                    new Document("$eq", List.of("$ca", "$es")),
                    new Document("$eq", List.of("$es", "$en"))
                )), 1, 0
            )))
        ));

        pipeline.add(new Document("$group", new Document("_id", "$year")
            .append("total", new Document("$sum", 1))
            .append("with_abstract", new Document("$sum", new Document("$cond", List.of(new Document("$gt", List.of("$lang_count", 0)), 1, 0))))
            .append("no_abstract", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 0)), 1, 0))))
            .append("in_1_lang", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 1)), 1, 0))))
            .append("in_2_langs", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 2)), 1, 0))))
            .append("in_3_langs", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 3)), 1, 0))))
            .append("identical_in_3", new Document("$sum", "$is_identical_3"))
        ));

        pipeline.add(new Document("$sort", new Document("_id", -1)));

        List<Document> rawResults = mongoTemplate.getCollection("Researchoutputs").aggregate(pipeline).into(new ArrayList<>());
        List<Map<String, Object>> response = new ArrayList<>();

        for (Document d : rawResults) {
            Object yr = d.get("_id");
            Map<String, Object> item = new LinkedHashMap<>();
            long total = ((Number) d.getOrDefault("total", 0)).longValue();
            long withAbs = ((Number) d.getOrDefault("with_abstract", 0)).longValue();
            long in3 = ((Number) d.getOrDefault("in_3_langs", 0)).longValue();
            long ident3 = ((Number) d.getOrDefault("identical_in_3", 0)).longValue();

            boolean isNoYear = (yr == null || (yr instanceof Number && ((Number) yr).intValue() == 0));
            item.put("year", isNoYear ? "Sense any" : yr);
            item.put("yearValue", isNoYear ? 0 : ((Number) yr).intValue());
            item.put("isNoYear", isNoYear);
            item.put("total", total);
            item.put("withAbstract", withAbs);
            item.put("noAbstract", ((Number) d.getOrDefault("no_abstract", 0)).longValue());
            item.put("in1Lang", ((Number) d.getOrDefault("in_1_lang", 0)).longValue());
            item.put("in2Langs", ((Number) d.getOrDefault("in_2_langs", 0)).longValue());
            item.put("in3Langs", in3);
            item.put("identicalIn3", ident3);
            item.put("differentIn3", in3 - ident3);
            item.put("pctWithAbstract", total > 0 ? (double) Math.round((withAbs * 1000.0) / total) / 10.0 : 0.0);
            item.put("pctIdenticalOf3Langs", in3 > 0 ? (double) Math.round((ident3 * 1000.0) / in3) / 10.0 : 0.0);
            response.add(item);
        }

        yearsCache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis()));
        return response;
    }

    private static final List<String> RECOGNIZED_LANGUAGES = List.of(
        "Anglès",
        "Espanyol",
        "Català",
        "Francès",
        "Italià",
        "Portuguès",
        "Alemany",
        "Gallec",
        "Rus",
        "Basc",
        "Múltiples idiomes",
        "Altres / Desconegut"
    );

    private static final Map<String, String> LANGUAGE_COLORS = Map.ofEntries(
        Map.entry("Anglès", "#6366f1"),
        Map.entry("Espanyol", "#f59e0b"),
        Map.entry("Català", "#10b981"),
        Map.entry("Francès", "#ec4899"),
        Map.entry("Italià", "#06b6d4"),
        Map.entry("Portuguès", "#8b5cf6"),
        Map.entry("Alemany", "#f97316"),
        Map.entry("Gallec", "#14b8a6"),
        Map.entry("Rus", "#ef4444"),
        Map.entry("Basc", "#84cc16"),
        Map.entry("Múltiples idiomes", "#3b82f6"),
        Map.entry("Altres / Desconegut", "#94a3b8")
    );

    public String normalizeRecognizedLanguage(String raw) {
        if (raw == null || raw.isBlank()) return "Altres / Desconegut";
        String lower = raw.trim().toLowerCase();
        if (lower.contains("no s'ha definit") || lower.contains("desconegut")) return "Altres / Desconegut";
        if (lower.contains("angl")) return "Anglès";
        if (lower.contains("espanyol") || lower.contains("castell")) return "Espanyol";
        if (lower.contains("catal")) return "Català";
        if (lower.contains("franc")) return "Francès";
        if (lower.contains("itali")) return "Italià";
        if (lower.contains("portugu")) return "Portuguès";
        if (lower.contains("alemany") || lower.contains("alem")) return "Alemany";
        if (lower.contains("gallec")) return "Gallec";
        if (lower.contains("rus")) return "Rus";
        if (lower.contains("basc") || lower.contains("èusc") || lower.contains("eusc")) return "Basc";
        if (lower.contains("múltiples") || lower.contains("multiples")) return "Múltiples idiomes";
        return "Altres / Desconegut";
    }

    /**
     * Distribució per any dels diferents idiomes reconeguts a les publicacions que tenen abstract
     */
    public Map<String, Object> getAbstractsLanguagesByYear(Integer desde, Integer hasta, String type) {
        return getAbstractsLanguagesByYear(desde, hasta, type, "publicationYear");
    }

    public Map<String, Object> getAbstractsLanguagesByYear(Integer desde, Integer hasta, String type, String yearType) {
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        String cacheKey = "years_languages_" + (isSubmissionYear ? "sub" : "pub") + "_" + desde + "_" + hasta + "_" + (type == null ? "" : type);
        CacheEntry cached = yearsLanguagesCache.get(cacheKey);
        if (isCacheValid(cached)) {
            return (Map<String, Object>) cached.data();
        }

        List<Document> pipeline = new ArrayList<>();

        List<Document> initialMatches = new ArrayList<>();
        // Only publications that have an abstract (non-empty ca, es, or en)
        initialMatches.add(new Document("$or", List.of(
            new Document("abstract.ca_ES", new Document("$exists", true).append("$ne", "")),
            new Document("abstract.es_ES", new Document("$exists", true).append("$ne", "")),
            new Document("abstract.en_GB", new Document("$exists", true).append("$ne", ""))
        )));

        if (type != null && !type.isBlank()) {
            initialMatches.add(new Document("$or", List.of(
                new Document("type.term.ca_ES", type),
                new Document("type.term.es_ES", type),
                new Document("type.term.en_GB", type)
            )));
        }

        pipeline.add(new Document("$match", new Document("$and", initialMatches)));

        pipeline.add(new Document("$project", new Document()
            .append("year", new Document("$cond", List.of(
                new Document("$gte", List.of(
                    new Document("$ifNull", List.of(isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(), 0)),
                    1900
                )),
                isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(),
                0
            )))
            .append("rawLang", new Document("$ifNull", List.of("$language.term.ca_ES", "Desconegut")))
        ));

        List<Document> yearFilterList = new ArrayList<>();
        if (desde != null) {
            if (desde == 0) {
                yearFilterList.add(new Document("year", 0));
            } else {
                yearFilterList.add(new Document("year", new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) yearFilterList.add(new Document("year", new Document("$lte", hasta)));
        if (!yearFilterList.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", yearFilterList)));
        }

        pipeline.add(new Document("$group", new Document("_id", new Document("year", "$year").append("lang", "$rawLang"))
            .append("count", new Document("$sum", 1))
        ));

        pipeline.add(new Document("$sort", new Document("_id.year", 1)));

        List<Document> rawResults = mongoTemplate.getCollection("Researchoutputs").aggregate(pipeline).into(new ArrayList<>());

        // Map of year -> Map<language, count>
        Map<Integer, Map<String, Long>> yearLangCounts = new HashMap<>();
        Map<String, Long> totalPerLanguage = new HashMap<>();

        for (Document doc : rawResults) {
            Document idDoc = (Document) doc.get("_id");
            if (idDoc == null) continue;
            Object yrObj = idDoc.get("year");
            if (yrObj == null || !(yrObj instanceof Number)) continue;
            int year = ((Number) yrObj).intValue();
            String rawLang = idDoc.getString("lang");
            String normLang = normalizeRecognizedLanguage(rawLang);
            long count = ((Number) doc.getOrDefault("count", 0)).longValue();

            yearLangCounts.computeIfAbsent(year, y -> new HashMap<>())
                .merge(normLang, count, Long::sum);

            totalPerLanguage.merge(normLang, count, Long::sum);
        }

        List<Integer> numericYears = new ArrayList<>();
        boolean hasNoYear = false;
        for (Integer yr : yearLangCounts.keySet()) {
            if (yr != null && yr > 0) {
                numericYears.add(yr);
            } else if (yr != null && yr == 0) {
                hasNoYear = true;
            }
        }
        Collections.sort(numericYears);
        List<Integer> years = new ArrayList<>(numericYears);
        if (hasNoYear) {
            years.add(0);
        }

        // Sort languages so that languages with publications appear first, followed by others, preserving standard order
        List<String> activeLanguages = new ArrayList<>();
        for (String lang : RECOGNIZED_LANGUAGES) {
            if (totalPerLanguage.getOrDefault(lang, 0L) > 0) {
                activeLanguages.add(lang);
            }
        }
        if (activeLanguages.isEmpty()) {
            activeLanguages.addAll(RECOGNIZED_LANGUAGES);
        }

        // Build series for Chart.js
        List<Map<String, Object>> series = new ArrayList<>();
        for (String lang : activeLanguages) {
            List<Long> data = new ArrayList<>();
            for (Integer yr : years) {
                long c = yearLangCounts.getOrDefault(yr, Collections.emptyMap()).getOrDefault(lang, 0L);
                data.add(c);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("language", lang);
            s.put("label", lang);
            s.put("color", LANGUAGE_COLORS.getOrDefault(lang, "#94a3b8"));
            s.put("total", totalPerLanguage.getOrDefault(lang, 0L));
            s.put("data", data);
            series.add(s);
        }

        // Totals per year
        List<Long> totalsByYear = new ArrayList<>();
        for (Integer yr : years) {
            long totalYear = yearLangCounts.getOrDefault(yr, Collections.emptyMap()).values().stream().mapToLong(Long::longValue).sum();
            totalsByYear.add(totalYear);
        }

        // Detailed Year Breakdown
        List<Map<String, Object>> yearBreakdown = new ArrayList<>();
        for (int i = 0; i < years.size(); i++) {
            int yr = years.get(i);
            long total = totalsByYear.get(i);
            Map<String, Object> yb = new LinkedHashMap<>();
            yb.put("year", yr);
            yb.put("total", total);
            yb.put("languages", yearLangCounts.getOrDefault(yr, Collections.emptyMap()));
            yearBreakdown.add(yb);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("years", years);
        response.put("languages", activeLanguages);
        response.put("series", series);
        response.put("totalsByYear", totalsByYear);
        response.put("yearBreakdown", yearBreakdown);
        response.put("totalLanguagesCount", totalPerLanguage);

        yearsLanguagesCache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis()));
        return response;
    }

    /**
     * Distribució per tipus de document
     */
    public List<Map<String, Object>> getAbstractsByType(Integer desde, Integer hasta) {
        return getAbstractsByType(desde, hasta, "publicationYear");
    }

    public List<Map<String, Object>> getAbstractsByType(Integer desde, Integer hasta, String yearType) {
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        String cacheKey = "types_" + (isSubmissionYear ? "sub" : "pub") + "_" + desde + "_" + hasta;
        CacheEntry cached = typesCache.get(cacheKey);
        if (isCacheValid(cached)) {
            return (List<Map<String, Object>>) cached.data();
        }

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$project", new Document()
            .append("year", new Document("$cond", List.of(
                new Document("$gte", List.of(
                    new Document("$ifNull", List.of(isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(), 0)),
                    1900
                )),
                isSubmissionYear ? "$submissionYear" : buildPublicationYearExpr(),
                0
            )))
            .append("type", new Document("$ifNull", List.of("$type.term.ca_ES", "Sense tipus")))
            .append("has_ca", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.ca_ES", ""))), 0)), 1, 0)))
            .append("has_es", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.es_ES", ""))), 0)), 1, 0)))
            .append("has_en", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.en_GB", ""))), 0)), 1, 0)))
            .append("ca", new Document("$ifNull", List.of("$abstract.ca_ES", "")))
            .append("es", new Document("$ifNull", List.of("$abstract.es_ES", "")))
            .append("en", new Document("$ifNull", List.of("$abstract.en_GB", "")))
        ));

        List<Document> yearFilterList = new ArrayList<>();
        if (desde != null) {
            if (desde == 0) {
                yearFilterList.add(new Document("year", 0));
            } else {
                yearFilterList.add(new Document("year", new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) yearFilterList.add(new Document("year", new Document("$lte", hasta)));
        if (!yearFilterList.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", yearFilterList)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("type", 1)
            .append("lang_count", new Document("$add", List.of("$has_ca", "$has_es", "$has_en")))
            .append("is_identical_3", new Document("$cond", List.of(
                new Document("$and", List.of(
                    new Document("$eq", List.of("$has_ca", 1)),
                    new Document("$eq", List.of("$has_es", 1)),
                    new Document("$eq", List.of("$has_en", 1)),
                    new Document("$eq", List.of("$ca", "$es")),
                    new Document("$eq", List.of("$es", "$en"))
                )), 1, 0
            )))
        ));

        pipeline.add(new Document("$group", new Document("_id", "$type")
            .append("total", new Document("$sum", 1))
            .append("with_abstract", new Document("$sum", new Document("$cond", List.of(new Document("$gt", List.of("$lang_count", 0)), 1, 0))))
            .append("no_abstract", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 0)), 1, 0))))
            .append("in_3_langs", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$lang_count", 3)), 1, 0))))
            .append("identical_in_3", new Document("$sum", "$is_identical_3"))
        ));

        pipeline.add(new Document("$sort", new Document("total", -1)));
        pipeline.add(new Document("$limit", 20));

        List<Document> rawResults = mongoTemplate.getCollection("Researchoutputs").aggregate(pipeline).into(new ArrayList<>());
        List<Map<String, Object>> response = new ArrayList<>();

        for (Document d : rawResults) {
            String typeName = d.getString("_id");
            if (typeName == null || typeName.isBlank()) typeName = "Sense tipus";
            Map<String, Object> item = new LinkedHashMap<>();
            long total = ((Number) d.getOrDefault("total", 0)).longValue();
            long withAbs = ((Number) d.getOrDefault("with_abstract", 0)).longValue();
            long in3 = ((Number) d.getOrDefault("in_3_langs", 0)).longValue();
            long ident3 = ((Number) d.getOrDefault("identical_in_3", 0)).longValue();

            item.put("type", typeName);
            item.put("total", total);
            item.put("withAbstract", withAbs);
            item.put("noAbstract", ((Number) d.getOrDefault("no_abstract", 0)).longValue());
            item.put("in3Langs", in3);
            item.put("identicalIn3", ident3);
            item.put("differentIn3", in3 - ident3);
            item.put("pctWithAbstract", total > 0 ? (double) Math.round((withAbs * 1000.0) / total) / 10.0 : 0.0);
            item.put("pctIdenticalOf3Langs", in3 > 0 ? (double) Math.round((ident3 * 1000.0) / in3) / 10.0 : 0.0);
            response.add(item);
        }

        typesCache.put(cacheKey, new CacheEntry(response, System.currentTimeMillis()));
        return response;
    }

    /**
     * Llistat paginat de publicacions amb filtrat per estat d'abstract i idioma
     */
    public Map<String, Object> getAbstractsList(
            String filter,
            String search,
            Integer desde,
            Integer hasta,
            String type,
            String language,
            int page,
            int size,
            String sortField,
            String sortDir) {
        return getAbstractsList(filter, search, desde, hasta, type, language, "publicationYear", page, size, sortField, sortDir);
    }

    public Map<String, Object> getAbstractsList(
            String filter,
            String search,
            Integer desde,
            Integer hasta,
            String type,
            String language,
            String yearType,
            int page,
            int size,
            String sortField,
            String sortDir) {

        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        List<Document> pipeline = new ArrayList<>();

        List<Document> initialMatches = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            initialMatches.add(new Document("$or", List.of(
                new Document("type.term.ca_ES", type),
                new Document("type.term.es_ES", type),
                new Document("type.term.en_GB", type)
            )));
        }
        if (!initialMatches.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", initialMatches)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("uuid", 1)
            .append("title", 1)
            .append("publicationYear", buildPublicationYearExpr())
            .append("submissionYear", 1)
            .append("type", 1)
            .append("language", 1)
            .append("contributors", 1)
            .append("abstract", 1)
            .append("has_ca", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.ca_ES", ""))), 0)), 1, 0)))
            .append("has_es", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.es_ES", ""))), 0)), 1, 0)))
            .append("has_en", new Document("$cond", List.of(new Document("$gt", List.of(new Document("$strLenCP", new Document("$ifNull", List.of("$abstract.en_GB", ""))), 0)), 1, 0)))
            .append("ca", new Document("$ifNull", List.of("$abstract.ca_ES", "")))
            .append("es", new Document("$ifNull", List.of("$abstract.es_ES", "")))
            .append("en", new Document("$ifNull", List.of("$abstract.en_GB", "")))
        ));

        List<Document> yearFilterList = new ArrayList<>();
        String checkYearField = isSubmissionYear ? "submissionYear" : "publicationYear";
        if (desde != null) {
            if (desde == 0) {
                yearFilterList.add(new Document("$or", List.of(
                    new Document(checkYearField, null),
                    new Document(checkYearField, 0),
                    new Document(checkYearField, new Document("$exists", false))
                )));
            } else {
                yearFilterList.add(new Document(checkYearField, new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) {
            yearFilterList.add(new Document(checkYearField, new Document("$lte", hasta)));
        }
        if (!yearFilterList.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", yearFilterList)));
        }

        pipeline.add(new Document("$project", new Document()
            .append("uuid", 1)
            .append("title", 1)
            .append("publicationYear", 1)
            .append("submissionYear", 1)
            .append("type", 1)
            .append("language", 1)
            .append("contributors", 1)
            .append("abstract", 1)
            .append("has_ca", 1)
            .append("has_es", 1)
            .append("has_en", 1)
            .append("ca", 1)
            .append("es", 1)
            .append("en", 1)
            .append("lang_count", new Document("$add", List.of("$has_ca", "$has_es", "$has_en")))
            .append("is_identical_3", new Document("$cond", List.of(
                new Document("$and", List.of(
                    new Document("$eq", List.of("$has_ca", 1)),
                    new Document("$eq", List.of("$has_es", 1)),
                    new Document("$eq", List.of("$has_en", 1)),
                    new Document("$eq", List.of("$ca", "$es")),
                    new Document("$eq", List.of("$es", "$en"))
                )), 1, 0
            )))
        ));

        List<Document> matchConditions = new ArrayList<>();

        if (filter != null && !filter.isBlank()) {
            switch (filter.toLowerCase()) {
                case "with_abstract" -> matchConditions.add(new Document("lang_count", new Document("$gt", 0)));
                case "without_abstract" -> matchConditions.add(new Document("lang_count", 0));
                case "three_langs" -> matchConditions.add(new Document("lang_count", 3));
                case "one_or_two_langs" -> matchConditions.add(new Document("lang_count", new Document("$in", List.of(1, 2))));
                case "identical_three_langs" -> matchConditions.add(new Document("is_identical_3", 1));
                case "different_three_langs" -> {
                    matchConditions.add(new Document("lang_count", 3));
                    matchConditions.add(new Document("is_identical_3", 0));
                }
                case "wrong_language", "wrong_ca", "wrong_es", "wrong_en", "genuine_3_langs" -> {
                    // Filter where abstract exists
                    matchConditions.add(new Document("lang_count", new Document("$gt", 0)));
                }
            }
        }

        if (language != null && !language.isBlank() && !"all".equalsIgnoreCase(language)) {
            String norm = normalizeRecognizedLanguage(language);
            if ("Anglès".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Angl").append("$options", "i")));
            } else if ("Espanyol".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Espanyol|Castell").append("$options", "i")));
            } else if ("Català".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Catal").append("$options", "i")));
            } else if ("Francès".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Franc").append("$options", "i")));
            } else if ("Italià".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Itali").append("$options", "i")));
            } else if ("Portuguès".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Portugu").append("$options", "i")));
            } else if ("Alemany".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Alem").append("$options", "i")));
            } else if ("Gallec".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Gallec").append("$options", "i")));
            } else if ("Rus".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Rus").append("$options", "i")));
            } else if ("Basc".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "Basc|usc").append("$options", "i")));
            } else if ("Múltiples idiomes".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("language.term.ca_ES", new Document("$regex", "ltiples").append("$options", "i")));
            } else if ("Altres / Desconegut".equalsIgnoreCase(norm)) {
                matchConditions.add(new Document("$or", List.of(
                    new Document("language", new Document("$exists", false)),
                    new Document("language", null),
                    new Document("language.term.ca_ES", new Document("$nin", List.of(
                        "Anglès", "Anglès nord-americà", "Espanyol", "Català", "Francès", "Italià", "Portuguès", "Portuguès (Brasil)", "Alemany", "Gallec", "Rus", "Basc", "Múltiples idiomes"
                    )))
                )));
            } else {
                matchConditions.add(new Document("language.term.ca_ES", language));
            }
        }

        if (search != null && !search.isBlank()) {
            String s = Pattern.quote(search.trim());
            matchConditions.add(new Document("$or", List.of(
                new Document("title.value", new Document("$regex", s).append("$options", "i")),
                new Document("uuid", new Document("$regex", s).append("$options", "i")),
                new Document("ca", new Document("$regex", s).append("$options", "i")),
                new Document("es", new Document("$regex", s).append("$options", "i")),
                new Document("en", new Document("$regex", s).append("$options", "i")),
                new Document("contributors.name.lastName", new Document("$regex", s).append("$options", "i"))
            )));
        }

        if (!matchConditions.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", matchConditions)));
        }

        boolean isLinguisticFilter = filter != null && Set.of("wrong_language", "wrong_ca", "wrong_es", "wrong_en", "genuine_3_langs")
                .contains(filter.toLowerCase());

        String sortProperty = (sortField != null && !sortField.isBlank()) ? sortField : "submissionYear";
        int sortOrder = "asc".equalsIgnoreCase(sortDir) ? 1 : -1;

        long totalElements = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        if (isLinguisticFilter) {
            pipeline.add(new Document("$sort", new Document(sortProperty, sortOrder).append("uuid", 1)));
            List<Document> allDocs = mongoTemplate.getCollection("Researchoutputs").aggregate(pipeline).into(new ArrayList<>());
            List<Map<String, Object>> matchedItems = new ArrayList<>();
            String f = filter.toLowerCase();

            for (Document doc : allDocs) {
                Map<String, Object> item = formatPublicationItem(doc);
                boolean matches = false;
                switch (f) {
                    case "wrong_language" -> matches = Boolean.TRUE.equals(item.get("hasAnyWrongLanguage"));
                    case "genuine_3_langs" -> matches = Boolean.TRUE.equals(item.get("isGenuine3Langs"));
                    case "wrong_ca" -> matches = Boolean.TRUE.equals(item.get("isWrongCa"));
                    case "wrong_es" -> matches = Boolean.TRUE.equals(item.get("isWrongEs"));
                    case "wrong_en" -> matches = Boolean.TRUE.equals(item.get("isWrongEn"));
                }
                if (matches) {
                    matchedItems.add(item);
                }
            }

            totalElements = matchedItems.size();
            int fromIndex = Math.min(Math.max(0, page) * Math.max(1, size), matchedItems.size());
            int toIndex = Math.min(fromIndex + Math.max(1, size), matchedItems.size());
            items = matchedItems.subList(fromIndex, toIndex);
        } else {
            List<Document> dataPipeline = new ArrayList<>();
            dataPipeline.add(new Document("$sort", new Document(sortProperty, sortOrder).append("uuid", 1)));
            dataPipeline.add(new Document("$skip", Math.max(0, page) * Math.max(1, size)));
            dataPipeline.add(new Document("$limit", Math.max(1, size)));

            pipeline.add(new Document("$facet", new Document()
                .append("totalCount", List.of(new Document("$count", "count")))
                .append("data", dataPipeline)
            ));

            List<Document> facetResult = mongoTemplate.getCollection("Researchoutputs").aggregate(pipeline).into(new ArrayList<>());

            if (!facetResult.isEmpty()) {
                Document first = facetResult.get(0);
                List<Document> countList = first.getList("totalCount", Document.class);
                if (countList != null && !countList.isEmpty()) {
                    totalElements = ((Number) countList.get(0).getOrDefault("count", 0)).longValue();
                }

                List<Document> dataList = first.getList("data", Document.class);
                if (dataList != null) {
                    for (Document doc : dataList) {
                        Map<String, Object> item = formatPublicationItem(doc);
                        items.add(item);
                    }
                }
            }
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("items", items);
        return response;
    }

    private Map<String, Object> formatPublicationItem(Document doc) {
        Map<String, Object> item = new LinkedHashMap<>();
        String uuid = doc.getString("uuid");
        item.put("uuid", uuid);

        Document titleDoc = (Document) doc.get("title");
        item.put("title", titleDoc != null ? titleDoc.getString("value") : "Sense títol");
        item.put("publicationYear", doc.get("publicationYear") != null ? doc.get("publicationYear") : extractPublicationYear(doc));
        item.put("submissionYear", doc.get("submissionYear"));

        Document typeDoc = (Document) doc.get("type");
        Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
        String typeName = termDoc != null ? termDoc.getString("ca_ES") : "Sense tipus";
        item.put("typeName", typeName != null ? typeName : "Sense tipus");

        Document langDoc = (Document) doc.get("language");
        Document langTermDoc = langDoc != null ? (Document) langDoc.get("term") : null;
        String pubLang = langTermDoc != null ? langTermDoc.getString("ca_ES") : null;
        item.put("publicationLanguage", pubLang != null && !pubLang.isBlank() ? pubLang : "Sense idioma");

        List<Document> contributors = doc.getList("contributors", Document.class);
        String authors = "Sense autors";
        if (contributors != null && !contributors.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Document c : contributors) {
                Document n = (Document) c.get("name");
                if (n != null) {
                    String ln = n.getString("lastName");
                    String fn = n.getString("firstName");
                    if (ln != null && fn != null) names.add(ln + ", " + fn);
                    else if (ln != null) names.add(ln);
                    else if (fn != null) names.add(fn);
                }
            }
            if (!names.isEmpty()) authors = String.join(" | ", names);
        }
        item.put("authors", authors);

        boolean hasCa = ((Number) doc.getOrDefault("has_ca", 0)).intValue() == 1;
        boolean hasEs = ((Number) doc.getOrDefault("has_es", 0)).intValue() == 1;
        boolean hasEn = ((Number) doc.getOrDefault("has_en", 0)).intValue() == 1;
        int langCount = ((Number) doc.getOrDefault("lang_count", 0)).intValue();
        boolean isIdentical3 = ((Number) doc.getOrDefault("is_identical_3", 0)).intValue() == 1;

        String caText = doc.getString("ca");
        String esText = doc.getString("es");
        String enText = doc.getString("en");

        // Real Language Detection
        String detectedCa = hasCa ? detectLanguage(caText) : "none";
        String detectedEs = hasEs ? detectLanguage(esText) : "none";
        String detectedEn = hasEn ? detectLanguage(enText) : "none";

        boolean isWrongCa = hasCa && ("en_GB".equals(detectedCa) || "es_ES".equals(detectedCa));
        boolean isWrongEs = hasEs && ("en_GB".equals(detectedEs) || "ca_ES".equals(detectedEs));
        boolean isWrongEn = hasEn && ("ca_ES".equals(detectedEn) || "es_ES".equals(detectedEn));
        boolean hasAnyWrongLanguage = isWrongCa || isWrongEs || isWrongEn;
        boolean isGenuine3Langs = hasCa && hasEs && hasEn && !isWrongCa && !isWrongEs && !isWrongEn && !isIdentical3;

        List<String> langsPresent = new ArrayList<>();
        if (hasCa) langsPresent.add("ca_ES");
        if (hasEs) langsPresent.add("es_ES");
        if (hasEn) langsPresent.add("en_GB");

        item.put("hasCa", hasCa);
        item.put("hasEs", hasEs);
        item.put("hasEn", hasEn);
        item.put("languagesCount", langCount);
        item.put("languagesPresent", langsPresent);
        item.put("isThreeLanguages", langCount == 3);
        item.put("isIdenticalThreeLanguages", isIdentical3);

        item.put("detectedCa", detectedCa);
        item.put("detectedEs", detectedEs);
        item.put("detectedEn", detectedEn);
        item.put("isWrongCa", isWrongCa);
        item.put("isWrongEs", isWrongEs);
        item.put("isWrongEn", isWrongEn);
        item.put("hasAnyWrongLanguage", hasAnyWrongLanguage);
        item.put("isGenuine3Langs", isGenuine3Langs);

        String statusLabel;
        String statusClass;
        if (langCount == 0) {
            statusLabel = "Sense abstract";
            statusClass = "bg-rose-100 text-rose-800 border-rose-300";
        } else if (isGenuine3Langs) {
            statusLabel = "🎯 3 Idiomes Genuïns";
            statusClass = "bg-emerald-100 text-emerald-800 border-emerald-400";
        } else if (hasAnyWrongLanguage) {
            statusLabel = "⚠️ Idioma Erroni / Clonat";
            statusClass = "bg-amber-100 text-amber-800 border-amber-300";
        } else if (langCount == 3 && isIdentical3) {
            statusLabel = "3 Idiomes (Idèntics ⚠️)";
            statusClass = "bg-amber-100 text-amber-800 border-amber-300";
        } else if (langCount == 3) {
            statusLabel = "3 Idiomes (Traduïts ✓)";
            statusClass = "bg-emerald-100 text-emerald-800 border-emerald-300";
        } else if (langCount == 2) {
            statusLabel = "2 Idiomes";
            statusClass = "bg-blue-100 text-blue-800 border-blue-300";
        } else {
            statusLabel = "1 Idioma";
            statusClass = "bg-slate-100 text-slate-800 border-slate-300";
        }
        item.put("statusLabel", statusLabel);
        item.put("statusClass", statusClass);

        item.put("abstractCa", caText);
        item.put("abstractEs", esText);
        item.put("abstractEn", enText);

        item.put("abstractPreviewCa", truncate(caText, 160));
        item.put("abstractPreviewEs", truncate(esText, 160));
        item.put("abstractPreviewEn", truncate(enText, 160));

        return item;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    /**
     * Retorna el detall complet d'abstracts d'una publicació concreta amb diagnòstic lingüístic
     */
    public Optional<Map<String, Object>> getAbstractDetail(String uuid) {
        Document doc = mongoTemplate.getCollection("Researchoutputs").find(new Document("uuid", uuid)).first();
        if (doc == null) return Optional.empty();

        Document abstractDoc = (Document) doc.get("abstract");
        String ca = abstractDoc != null ? abstractDoc.getString("ca_ES") : null;
        String es = abstractDoc != null ? abstractDoc.getString("es_ES") : null;
        String en = abstractDoc != null ? abstractDoc.getString("en_GB") : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        Document titleDoc = (Document) doc.get("title");
        result.put("title", titleDoc != null ? titleDoc.getString("value") : "Sense títol");
        result.put("publicationYear", extractPublicationYear(doc));
        result.put("submissionYear", doc.get("submissionYear"));

        Document typeDoc = (Document) doc.get("type");
        Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
        result.put("type", termDoc != null ? termDoc.getString("ca_ES") : "Sense tipus");

        result.put("abstractCa", ca != null ? ca : "");
        result.put("abstractEs", es != null ? es : "");
        result.put("abstractEn", en != null ? en : "");

        boolean hasCa = ca != null && !ca.isBlank();
        boolean hasEs = es != null && !es.isBlank();
        boolean hasEn = en != null && !en.isBlank();

        result.put("hasCa", hasCa);
        result.put("hasEs", hasEs);
        result.put("hasEn", hasEn);

        // Language detection
        String detectedCa = hasCa ? detectLanguage(ca) : "none";
        String detectedEs = hasEs ? detectLanguage(es) : "none";
        String detectedEn = hasEn ? detectLanguage(en) : "none";

        result.put("detectedCa", detectedCa);
        result.put("detectedEs", detectedEs);
        result.put("detectedEn", detectedEn);

        boolean isWrongCa = hasCa && ("en_GB".equals(detectedCa) || "es_ES".equals(detectedCa));
        boolean isWrongEs = hasEs && ("en_GB".equals(detectedEs) || "ca_ES".equals(detectedEs));
        boolean isWrongEn = hasEn && ("ca_ES".equals(detectedEn) || "es_ES".equals(detectedEn));
        boolean hasAnyWrongLanguage = isWrongCa || isWrongEs || isWrongEn;

        result.put("isWrongCa", isWrongCa);
        result.put("isWrongEs", isWrongEs);
        result.put("isWrongEn", isWrongEn);
        result.put("hasAnyWrongLanguage", hasAnyWrongLanguage);

        boolean caEsMatch = hasCa && hasEs && cleanForCompare(ca).equals(cleanForCompare(es));
        boolean caEnMatch = hasCa && hasEn && cleanForCompare(ca).equals(cleanForCompare(en));
        boolean esEnMatch = hasEs && hasEn && cleanForCompare(es).equals(cleanForCompare(en));
        boolean allThreeMatch = hasCa && hasEs && hasEn && caEsMatch && esEnMatch;

        result.put("caEsMatch", caEsMatch);
        result.put("caEnMatch", caEnMatch);
        result.put("esEnMatch", esEnMatch);
        result.put("allThreeMatch", allThreeMatch);

        result.put("lengthCa", hasCa ? ca.length() : 0);
        result.put("lengthEs", hasEs ? es.length() : 0);
        result.put("lengthEn", hasEn ? en.length() : 0);

        return Optional.of(result);
    }

    private String cleanForCompare(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim().toLowerCase();
    }

    public void clearCache() {
        statsCache.clear();
        yearsCache.clear();
        yearsLanguagesCache.clear();
        typesCache.clear();
    }
}
