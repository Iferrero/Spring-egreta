package com.example.demo.service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
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
    private final Map<String, CacheEntry> discrepanciesStatsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60 * 60 * 1000; // 60 minutes
    public static final String STATS_CACHE_COLLECTION = "AbstractsLinguisticStatsCache";

    public record CacheEntry(Object data, long timestamp) {}

    // Language detector using Lingua with optimal languages and low-accuracy mode (low memory footprint)
    private static final LanguageDetector LINGUA_DETECTOR = LanguageDetectorBuilder
            .fromLanguages(
                Language.CATALAN,
                Language.SPANISH,
                Language.ENGLISH,
                Language.FRENCH,
                Language.ITALIAN,
                Language.PORTUGUESE,
                Language.GERMAN,
                Language.BASQUE,
                Language.RUSSIAN
            )
            .withLowAccuracyMode()
            .build();

    public static final String HTML_EMPTY_REGEX = "^([\\s\u00a0\ufeff\u200b]|<[^>]*>|&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?)*$";
    public static final Pattern HTML_EMPTY_PATTERN = Pattern.compile(HTML_EMPTY_REGEX, Pattern.CASE_INSENSITIVE);

    public static boolean isHtmlEmpty(String text) {
        if (text == null || text.isBlank()) return true;
        return HTML_EMPTY_PATTERN.matcher(text).matches();
    }

    public static String cleanHtmlToText(String text) {
        if (text == null || isHtmlEmpty(text)) return "";
        return text.replaceAll("<[^>]+>", " ")
                   .replaceAll("&(?:apos|#39);?", "'")
                   .replaceAll("&(?:quot|#34);?", "\"")
                   .replaceAll("&(?:amp|#38);?", "&")
                   .replaceAll("&(?:lt|#60);?", "<")
                   .replaceAll("&(?:gt|#62);?", ">")
                   .replaceAll("&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?", " ")
                   .replace('’', '\'')
                   .replace('‘', '\'')
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    public static int countWords(String text) {
        String clean = cleanHtmlToText(text);
        if (clean.isBlank()) return 0;
        return clean.split("\\s+").length;
    }

    public static final List<String> EMPTY_HTML_STRINGS = List.of(
        "", "<p></p>", "<p><br></p>", "<p><br/></p>", "<p>&nbsp;</p>", "<p> </p>", "<br>", "<br/>"
    );

    public static Document buildHasTextExpr(String langField) {
        Document trimmed = new Document("$trim", new Document("input", new Document("$ifNull", List.of("$abstract." + langField, ""))));
        return new Document("$and", List.of(
            new Document("$gt", List.of(new Document("$strLenCP", trimmed), 0)),
            new Document("$not", List.of(new Document("$in", List.of(trimmed, EMPTY_HTML_STRINGS))))
        ));
    }

    public static Document buildHasTextCond(String langField) {
        return new Document("$cond", List.of(
            buildHasTextExpr(langField),
            1,
            0
        ));
    }

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

    private static final Set<String> FAST_EN_STOPWORDS = Set.of(
        "the", "and", "of", "in", "to", "a", "is", "that", "for", "with", "as", "was", "on", "are", 
        "by", "this", "from", "at", "which", "an", "be", "has", "were", "we", "study", "results",
        "between", "these", "their", "paper", "used", "using", "can", "also", "into", "all",
        "script", "report", "review", "analysis"
    );

    private static final Set<String> FAST_ES_STOPWORDS = Set.of(
        "de", "la", "que", "el", "en", "y", "los", "se", "del", "las", "por", "un", "para", "con", 
        "no", "una", "su", "al", "lo", "como", "mas", "más", "pero", "sus", "este", "entre", "esta", 
        "sobre", "todo", "tambien", "también", "estudio", "resultados", "investigacion", "investigación",
        "guia", "guía", "guion", "guión", "video"
    );

    private static final Set<String> FAST_CA_STOPWORDS = Set.of(
        "de", "la", "que", "el", "en", "i", "els", "es", "del", "les", "per", "un", "amb", 
        "no", "una", "dels", "aquest", "aquesta", "aquests", "aquestes", 
        "com", "son", "són", "mes", "més", "també", "sobre", "entre", "ja", "estudi", "resultats", 
        "recerca", "objectiu", "desenvolupament", "mitjançant", "havent", "nostre", "nostra",
        "guio", "guió", "vídeo"
    );

    private static final Pattern CA_CIO_PATTERN = Pattern.compile("(?iu)[\\p{L}]+(?:ció|sió)(?![\\p{L}])");
    private static final Pattern ES_CION_PATTERN = Pattern.compile("(?iu)[\\p{L}]+(?:ción|sión)(?![\\p{L}])");

    public static String detectFastLanguage(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase().trim();
        if (lower.length() < 3) return null;

        // Distinctive Catalan markers (grave accents, contractions, geminated l, words ending in -ció/-sió)
        boolean hasCaApostrophe = lower.contains("l'") || lower.contains("d'") || lower.contains("s'") 
                || lower.contains("m'") || lower.contains("n'") || lower.contains("t'") || lower.contains("l·l") 
                || lower.contains("ŀ");

        boolean hasCaGraveAccent = lower.contains("è") || lower.contains("ò");
        boolean hasCaCio = CA_CIO_PATTERN.matcher(lower).find();

        boolean hasCaMarker = lower.contains(" per a ") || lower.startsWith("per a ") || lower.contains(" per a")
                || lower.contains(" amb ") || lower.startsWith("amb ")
                || lower.contains(" dels ") || lower.startsWith("dels ")
                || lower.contains(" aquest ") || lower.startsWith("aquest ")
                || lower.contains(" aquesta ") || lower.startsWith("aquesta ")
                || lower.contains(" aquests ") || lower.startsWith("aquests ")
                || lower.contains(" aquestes ") || lower.startsWith("aquestes ")
                || lower.contains("guió") || lower.contains("vídeo") || lower.contains("recerca")
                || lower.contains("catalunya");

        // Distinctive Spanish markers (ñ, words ending in -ción/-sión)
        boolean hasEsEnie = lower.contains("ñ");
        boolean hasEsCion = ES_CION_PATTERN.matcher(lower).find();

        boolean hasEsMarker = lower.contains(" para ") || lower.startsWith("para ")
                || lower.contains(" con ") || lower.startsWith("con ")
                || lower.contains(" los ") || lower.startsWith("los ")
                || lower.contains(" las ") || lower.startsWith("las ")
                || lower.contains(" este ") || lower.startsWith("este ")
                || lower.contains(" esta ") || lower.startsWith("esta ")
                || lower.contains("guía") || lower.contains("guion") || lower.contains("guión")
                || lower.contains("investigación");

        // Distinctive English markers
        boolean hasEnMarker = lower.contains(" the ") || lower.startsWith("the ")
                || lower.contains(" and ") || lower.startsWith("and ")
                || lower.contains(" with ") || lower.startsWith("with ")
                || lower.contains(" this ") || lower.startsWith("this ")
                || lower.contains("script");

        boolean isCa = hasCaApostrophe || hasCaGraveAccent || hasCaCio || hasCaMarker;
        boolean isEs = hasEsEnie || hasEsCion || hasEsMarker;
        boolean isEn = hasEnMarker;

        if (isCa && !isEs && !isEn) return "ca_ES";
        if (isEs && !isCa && !isEn) return "es_ES";
        if (isEn && !isCa && !isEs) return "en_GB";

        // Multi-word stopword frequency scoring
        String[] words = lower.split("[^\\p{L}]+");
        int enCount = 0;
        int esCount = 0;
        int caCount = 0;

        for (String w : words) {
            if (w.isEmpty()) continue;
            if (FAST_EN_STOPWORDS.contains(w)) enCount++;
            if (FAST_ES_STOPWORDS.contains(w)) esCount++;
            if (FAST_CA_STOPWORDS.contains(w)) caCount++;
        }

        if (isCa) caCount += 3;
        if (isEs) esCount += 3;
        if (isEn) enCount += 3;

        int strictlyEn = 0;
        if (lower.contains(" the ") || lower.startsWith("the ")) strictlyEn += 3;
        if (lower.contains(" and ")) strictlyEn += 2;
        if (lower.contains(" with ")) strictlyEn += 2;
        if (lower.contains(" this ")) strictlyEn += 2;

        int strictlyCa = 0;
        if (lower.contains(" amb ")) strictlyCa += 4;
        if (lower.contains(" i ")) strictlyCa += 2;
        if (lower.contains(" dels ")) strictlyCa += 3;
        if (lower.contains(" per a ")) strictlyCa += 3;
        if (lower.contains(" aquest ")) strictlyCa += 3;

        int strictlyEs = 0;
        if (lower.contains(" con ")) strictlyEs += 4;
        if (lower.contains(" y ")) strictlyEs += 2;
        if (lower.contains(" los ")) strictlyEs += 3;
        if (lower.contains(" para ")) strictlyEs += 3;
        if (lower.contains(" este ")) strictlyEs += 2;

        int totalEn = enCount + strictlyEn;
        int totalEs = esCount + strictlyEs;
        int totalCa = caCount + strictlyCa;

        // High confidence English
        if (totalEn > totalEs * 1.5 && totalEn > totalCa * 1.5 && totalEn >= 3) {
            return "en_GB";
        }
        // High confidence Spanish
        if (totalEs > totalEn * 1.3 && totalEs > totalCa && strictlyEs > strictlyCa && totalEs >= 3) {
            return "es_ES";
        }
        // High confidence Catalan
        if (totalCa > totalEn * 1.3 && totalCa >= totalEs && totalCa >= 3) {
            return "ca_ES";
        }

        // Return null to fallback to Lingua
        return null;
    }

    /**
     * Language detector based on Lingua (n-gram statistical model optimized for JVM)
     * augmented with HTML entity unescaping, bibliographic metadata stripping,
     * and ultra-fast heuristic path for Catalan, Spanish, and English.
     */
    public static String detectLanguage(String text) {
        if (text == null || isHtmlEmpty(text)) return "unknown";
        String sample = text.length() > 1500 ? text.substring(0, 1500) : text;
        String raw = sample.replaceAll("<[^>]+>", " ")
                           .replaceAll("&(?:apos|#39);?", "'")
                           .replaceAll("&(?:quot|#34);?", "\"")
                           .replaceAll("&(?:amp|#38);?", "&")
                           .replaceAll("&(?:lt|#60);?", "<")
                           .replaceAll("&(?:gt|#62);?", ">")
                           .replaceAll("&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?", " ")
                           .replace('’', '\'')
                           .replace('‘', '\'')
                           .replaceAll("\\s+", " ")
                           .trim();
        if (raw.length() < 3) return "unknown";

        // Remove trailing bibliographic metadata: coordinator/editor lists, ISBNs, publisher copyright
        String cleaned = raw.replaceAll("(?i)©?\\s*\\b\\d{4}\\b[^.]*all rights reserved.*", " ")
                            .replaceAll("(?i)/\\s*(?:coord(?:inator[s]?)?|dir(?:ector[s]?)?|edit(?:or[s]?)?|ed|coords?)\\.?\\s*(?:by|per|por|de)\\b.*", " ")
                            .replaceAll("(?i)\\bISBN(?:-1[03])?:?\\s*[0-9\\-Xx]+.*", " ")
                            .trim();
        String toCheck = cleaned.length() >= 15 ? cleaned : raw;

        // Fast heuristic check for English, Spanish and Catalan (>98% coverage in sub-millisecond)
        String fastLang = detectFastLanguage(toCheck);
        if (fastLang != null) {
            return fastLang;
        }

        if (toCheck.length() < 10) return "unknown";

        Language lang = LINGUA_DETECTOR.detectLanguageOf(toCheck);
        if (lang == null || lang == Language.UNKNOWN) {
            return "unknown";
        }

        return switch (lang) {
            case CATALAN -> "ca_ES";
            case SPANISH -> "es_ES";
            case ENGLISH -> "en_GB";
            case FRENCH -> "fr_FR";
            case ITALIAN -> "it_IT";
            case PORTUGUESE -> "pt_PT";
            case GERMAN -> "de_DE";
            case BASQUE -> "eu_ES";
            case RUSSIAN -> "ru_RU";
            default -> "unknown";
        };
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
            .append("has_ca", buildHasTextCond("ca_ES"))
            .append("has_es", buildHasTextCond("es_ES"))
            .append("has_en", buildHasTextCond("en_GB"))
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
        String persistentKey = "ling_" + desde + "_" + hasta + "_" + (type == null ? "" : type) + "_" + (language == null ? "" : language) + "_" + yearType;
        try {
            Document cachedDoc = mongoTemplate.getCollection(STATS_CACHE_COLLECTION).find(new Document("_id", persistentKey)).first();
            if (cachedDoc != null) {
                cachedDoc.remove("_id");
                cachedDoc.remove("updatedAt");
                return new LinkedHashMap<>(cachedDoc);
            }
        } catch (Exception ignored) {}

        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);
        Document query = new Document();
        List<Document> andList = new ArrayList<>();

        andList.add(new Document("$or", List.of(
            new Document("abstract.ca_ES", new Document("$exists", true).append("$type", "string")),
            new Document("abstract.es_ES", new Document("$exists", true).append("$type", "string")),
            new Document("abstract.en_GB", new Document("$exists", true).append("$type", "string"))
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

        java.util.concurrent.atomic.LongAdder wrongLanguageCount = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder genuine3Langs = new java.util.concurrent.atomic.LongAdder();

        java.util.concurrent.atomic.LongAdder caTotal = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder caCorrect = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder caWrongEn = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder caWrongEs = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder caWrongOther = new java.util.concurrent.atomic.LongAdder();

        java.util.concurrent.atomic.LongAdder esTotal = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder esCorrect = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder esWrongEn = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder esWrongCa = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder esWrongOther = new java.util.concurrent.atomic.LongAdder();

        java.util.concurrent.atomic.LongAdder enTotal = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder enCorrect = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder enWrongEs = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder enWrongCa = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder enWrongOther = new java.util.concurrent.atomic.LongAdder();

        try (var cursor = mongoTemplate.getCollection("Researchoutputs").find(query).projection(projection).batchSize(5000).iterator()) {
            List<Document> batch = new ArrayList<>(5000);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 5000) {
                    processBatch(batch, wrongLanguageCount, genuine3Langs, caTotal, caCorrect, caWrongEn, caWrongEs, caWrongOther,
                            esTotal, esCorrect, esWrongEn, esWrongCa, esWrongOther, enTotal, enCorrect, enWrongEs, enWrongCa, enWrongOther);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                processBatch(batch, wrongLanguageCount, genuine3Langs, caTotal, caCorrect, caWrongEn, caWrongEs, caWrongOther,
                        esTotal, esCorrect, esWrongEn, esWrongCa, esWrongOther, enTotal, enCorrect, enWrongEs, enWrongCa, enWrongOther);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        long totalWrong = wrongLanguageCount.sum();
        long totalGenuine = genuine3Langs.sum();
        result.put("wrongLanguageCount", totalWrong);
        result.put("pctWrongLanguageOfWithAbstract", withAbstract > 0 ? (double) Math.round((totalWrong * 1000.0) / withAbstract) / 10.0 : 0.0);
        result.put("genuine3Langs", totalGenuine);
        result.put("pctGenuine3Langs", in3Langs > 0 ? (double) Math.round((totalGenuine * 1000.0) / in3Langs) / 10.0 : 0.0);

        Map<String, Object> caStats = new LinkedHashMap<>();
        long caTot = caTotal.sum();
        long caCorr = caCorrect.sum();
        long caWEn = caWrongEn.sum();
        long caWEs = caWrongEs.sum();
        long caWOther = caWrongOther.sum();
        caStats.put("total", caTot);
        caStats.put("correct", caCorr);
        caStats.put("wrongEn", caWEn);
        caStats.put("wrongEs", caWEs);
        caStats.put("wrongOther", caWOther);
        caStats.put("pctWrong", caTot > 0 ? (double) Math.round(((caTot - caCorr) * 1000.0) / caTot) / 10.0 : 0.0);
        caStats.put("pctCorrect", caTot > 0 ? (double) Math.round((caCorr * 1000.0) / caTot) / 10.0 : 0.0);
        caStats.put("pctWrongEn", caTot > 0 ? (double) Math.round((caWEn * 1000.0) / caTot) / 10.0 : 0.0);
        caStats.put("pctWrongEs", caTot > 0 ? (double) Math.round((caWEs * 1000.0) / caTot) / 10.0 : 0.0);

        Map<String, Object> esStats = new LinkedHashMap<>();
        long esTot = esTotal.sum();
        long esCorr = esCorrect.sum();
        long esWEn = esWrongEn.sum();
        long esWCa = esWrongCa.sum();
        long esWOther = esWrongOther.sum();
        esStats.put("total", esTot);
        esStats.put("correct", esCorr);
        esStats.put("wrongEn", esWEn);
        esStats.put("wrongCa", esWCa);
        esStats.put("wrongOther", esWOther);
        esStats.put("pctWrong", esTot > 0 ? (double) Math.round(((esTot - esCorr) * 1000.0) / esTot) / 10.0 : 0.0);
        esStats.put("pctCorrect", esTot > 0 ? (double) Math.round((esCorr * 1000.0) / esTot) / 10.0 : 0.0);
        esStats.put("pctWrongEn", esTot > 0 ? (double) Math.round((esWEn * 1000.0) / esTot) / 10.0 : 0.0);
        esStats.put("pctWrongCa", esTot > 0 ? (double) Math.round((esWCa * 1000.0) / esTot) / 10.0 : 0.0);

        Map<String, Object> enStats = new LinkedHashMap<>();
        long enTot = enTotal.sum();
        long enCorr = enCorrect.sum();
        long enWEs = enWrongEs.sum();
        long enWCa = enWrongCa.sum();
        long enWOther = enWrongOther.sum();
        enStats.put("total", enTot);
        enStats.put("correct", enCorr);
        enStats.put("wrongEs", enWEs);
        enStats.put("wrongCa", enWCa);
        enStats.put("wrongOther", enWOther);
        enStats.put("pctWrong", enTot > 0 ? (double) Math.round(((enTot - enCorr) * 1000.0) / enTot) / 10.0 : 0.0);
        enStats.put("pctCorrect", enTot > 0 ? (double) Math.round((enCorr * 1000.0) / enTot) / 10.0 : 0.0);
        enStats.put("pctWrongEs", enTot > 0 ? (double) Math.round((enWEs * 1000.0) / enTot) / 10.0 : 0.0);
        enStats.put("pctWrongCa", enTot > 0 ? (double) Math.round((enWCa * 1000.0) / enTot) / 10.0 : 0.0);

        result.put("caFieldStats", caStats);
        result.put("esFieldStats", esStats);
        result.put("enFieldStats", enStats);

        try {
            Document toSave = new Document(result).append("_id", persistentKey).append("updatedAt", new Date());
            mongoTemplate.getCollection(STATS_CACHE_COLLECTION).replaceOne(
                new Document("_id", persistentKey),
                toSave,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
            );
        } catch (Exception ignored) {}

        return result;
    }

    private void processBatch(
            List<Document> batch,
            java.util.concurrent.atomic.LongAdder wrongLanguageCount,
            java.util.concurrent.atomic.LongAdder genuine3Langs,
            java.util.concurrent.atomic.LongAdder caTotal,
            java.util.concurrent.atomic.LongAdder caCorrect,
            java.util.concurrent.atomic.LongAdder caWrongEn,
            java.util.concurrent.atomic.LongAdder caWrongEs,
            java.util.concurrent.atomic.LongAdder caWrongOther,
            java.util.concurrent.atomic.LongAdder esTotal,
            java.util.concurrent.atomic.LongAdder esCorrect,
            java.util.concurrent.atomic.LongAdder esWrongEn,
            java.util.concurrent.atomic.LongAdder esWrongCa,
            java.util.concurrent.atomic.LongAdder esWrongOther,
            java.util.concurrent.atomic.LongAdder enTotal,
            java.util.concurrent.atomic.LongAdder enCorrect,
            java.util.concurrent.atomic.LongAdder enWrongEs,
            java.util.concurrent.atomic.LongAdder enWrongCa,
            java.util.concurrent.atomic.LongAdder enWrongOther) {

        batch.parallelStream().forEach(doc -> {
            Document abs = (Document) doc.get("abstract");
            if (abs == null) return;

            String ca = abs.getString("ca_ES");
            String es = abs.getString("es_ES");
            String en = abs.getString("en_GB");

            boolean hasCa = !isHtmlEmpty(ca);
            boolean hasEs = !isHtmlEmpty(es);
            boolean hasEn = !isHtmlEmpty(en);

            String detCa = hasCa ? detectLanguage(ca) : "none";
            String detEs = hasEs ? detectLanguage(es) : "none";
            String detEn = hasEn ? detectLanguage(en) : "none";

            boolean isWrongCa = hasCa && ("en_GB".equals(detCa) || "es_ES".equals(detCa));
            boolean isWrongEs = hasEs && ("en_GB".equals(detEs) || "ca_ES".equals(detEs));
            boolean isWrongEn = hasEn && ("ca_ES".equals(detEn) || "es_ES".equals(detEn));

            if (hasCa) {
                caTotal.increment();
                if (!isWrongCa) {
                    caCorrect.increment();
                } else {
                    if ("en_GB".equals(detCa)) caWrongEn.increment();
                    else if ("es_ES".equals(detCa)) caWrongEs.increment();
                    else caWrongOther.increment();
                }
            }

            if (hasEs) {
                esTotal.increment();
                if (!isWrongEs) {
                    esCorrect.increment();
                } else {
                    if ("en_GB".equals(detEs)) esWrongEn.increment();
                    else if ("ca_ES".equals(detEs)) esWrongCa.increment();
                    else esWrongOther.increment();
                }
            }

            if (hasEn) {
                enTotal.increment();
                if (!isWrongEn) {
                    enCorrect.increment();
                } else {
                    if ("es_ES".equals(detEn)) enWrongEs.increment();
                    else if ("ca_ES".equals(detEn)) enWrongCa.increment();
                    else enWrongOther.increment();
                }
            }

            if (isWrongCa || isWrongEs || isWrongEn) {
                wrongLanguageCount.increment();
            }

            if (hasCa && hasEs && hasEn && !isWrongCa && !isWrongEs && !isWrongEn) {
                genuine3Langs.increment();
            }
        });
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
            .append("has_ca", buildHasTextCond("ca_ES"))
            .append("has_es", buildHasTextCond("es_ES"))
            .append("has_en", buildHasTextCond("en_GB"))
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
        // Only publications that have an abstract with actual text (not empty HTML)
        initialMatches.add(new Document("$or", List.of(
            new Document("$expr", buildHasTextExpr("ca_ES")),
            new Document("$expr", buildHasTextExpr("es_ES")),
            new Document("$expr", buildHasTextExpr("en_GB"))
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
            .append("has_ca", buildHasTextCond("ca_ES"))
            .append("has_es", buildHasTextCond("es_ES"))
            .append("has_en", buildHasTextCond("en_GB"))
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
            .append("has_ca", buildHasTextCond("ca_ES"))
            .append("has_es", buildHasTextCond("es_ES"))
            .append("has_en", buildHasTextCond("en_GB"))
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
            String f = filter.toLowerCase();
            int fromIndex = Math.max(0, page) * Math.max(1, size);
            int toIndex = fromIndex + Math.max(1, size);
            long matchCount = 0;

            try (var cursor = mongoTemplate.getCollection("Researchoutputs")
                    .aggregate(pipeline)
                    .allowDiskUse(true)
                    .batchSize(500)
                    .iterator()) {
                while (cursor.hasNext()) {
                    Document doc = cursor.next();
                    if (matchesLinguisticFilter(f, doc)) {
                        if (matchCount >= fromIndex && matchCount < toIndex) {
                            items.add(formatPublicationItem(doc));
                        }
                        matchCount++;
                    }
                }
            }

            totalElements = matchCount;
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

    private boolean matchesLinguisticFilter(String filter, Document doc) {
        String caText = doc.getString("ca");
        String esText = doc.getString("es");
        String enText = doc.getString("en");

        boolean hasCa = !isHtmlEmpty(caText);
        boolean hasEs = !isHtmlEmpty(esText);
        boolean hasEn = !isHtmlEmpty(enText);
        if (!hasCa && !hasEs && !hasEn) return false;

        // Fast-path skips if specific language field is missing
        if ("wrong_ca".equals(filter) && !hasCa) return false;
        if ("wrong_es".equals(filter) && !hasEs) return false;
        if ("wrong_en".equals(filter) && !hasEn) return false;
        if ("genuine_3_langs".equals(filter) && (!hasCa || !hasEs || !hasEn)) return false;

        boolean isIdentical3 = hasCa && hasEs && hasEn && ((Number) doc.getOrDefault("is_identical_3", 0)).intValue() == 1;
        if ("genuine_3_langs".equals(filter) && isIdentical3) return false;

        String detectedCa = hasCa ? detectLanguage(caText) : "none";
        boolean isWrongCa = hasCa && ("en_GB".equals(detectedCa) || "es_ES".equals(detectedCa));
        if ("wrong_ca".equals(filter)) return isWrongCa;

        String detectedEs = hasEs ? detectLanguage(esText) : "none";
        boolean isWrongEs = hasEs && ("en_GB".equals(detectedEs) || "ca_ES".equals(detectedEs));
        if ("wrong_es".equals(filter)) return isWrongEs;

        String detectedEn = hasEn ? detectLanguage(enText) : "none";
        boolean isWrongEn = hasEn && ("ca_ES".equals(detectedEn) || "es_ES".equals(detectedEn));
        if ("wrong_en".equals(filter)) return isWrongEn;

        if ("wrong_language".equals(filter)) {
            return isWrongCa || isWrongEs || isWrongEn;
        }

        if ("genuine_3_langs".equals(filter)) {
            return hasCa && hasEs && hasEn && !isWrongCa && !isWrongEs && !isWrongEn && !isIdentical3;
        }

        return false;
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

        String caText = doc.getString("ca");
        String esText = doc.getString("es");
        String enText = doc.getString("en");

        boolean hasCa = !isHtmlEmpty(caText);
        boolean hasEs = !isHtmlEmpty(esText);
        boolean hasEn = !isHtmlEmpty(enText);
        int langCount = (hasCa ? 1 : 0) + (hasEs ? 1 : 0) + (hasEn ? 1 : 0);
        boolean isIdentical3 = hasCa && hasEs && hasEn && ((Number) doc.getOrDefault("is_identical_3", 0)).intValue() == 1;

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

        item.put("abstractCa", hasCa ? caText : "");
        item.put("abstractEs", hasEs ? esText : "");
        item.put("abstractEn", hasEn ? enText : "");

        item.put("abstractPreviewCa", hasCa ? truncate(caText, 160) : "");
        item.put("abstractPreviewEs", hasEs ? truncate(esText, 160) : "");
        item.put("abstractPreviewEn", hasEn ? truncate(enText, 160) : "");

        return item;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || isHtmlEmpty(text)) return "";
        String cleaned = text.replaceAll("<[^>]+>", " ")
                             .replaceAll("&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?", " ")
                             .replaceAll("\\s+", " ")
                             .trim();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    /**
     * Retorna el detall complet d'abstracts d'una publicació concreta amb diagnòstic lingüístic
     */
    public Optional<Map<String, Object>> getAbstractDetail(String uuid) {
        Document doc = mongoTemplate.getCollection("Researchoutputs").find(new Document("uuid", uuid)).first();
        if (doc == null) {
            doc = mongoTemplate.getCollection("ResearchoutputsAiAbstracts").find(new Document("uuid", uuid)).first();
            if (doc == null) return Optional.empty();
        }

        Document abstractDoc = (Document) doc.get("abstract");
        String ca = extractAbstractString(abstractDoc, "ca_ES");
        String es = extractAbstractString(abstractDoc, "es_ES");
        String en = extractAbstractString(abstractDoc, "en_GB");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        Document titleDoc = (Document) doc.get("title");
        String titleStr = titleDoc != null ? titleDoc.getString("value") : (doc.getString("title") != null ? doc.getString("title") : "Sense títol");
        result.put("title", titleStr);
        result.put("publicationYear", extractPublicationYear(doc));
        result.put("submissionYear", doc.get("submissionYear"));

        Document typeDoc = (Document) doc.get("type");
        Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
        String typeName = termDoc != null ? termDoc.getString("ca_ES") : (doc.getString("type") != null ? doc.getString("type") : "Sense tipus");
        result.put("type", typeName);
        result.put("typeName", typeName);

        List<Document> contributors = doc.getList("contributors", Document.class);
        String authors = "Sense autors";
        if (contributors != null && !contributors.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Document cnt : contributors) {
                Document n = (Document) cnt.get("name");
                if (n != null) {
                    String ln = n.getString("lastName");
                    String fn = n.getString("firstName");
                    if (ln != null && fn != null) names.add(ln + ", " + fn);
                    else if (ln != null) names.add(ln);
                    else if (n.getString("text") != null) names.add(n.getString("text"));
                }
            }
            if (!names.isEmpty()) authors = String.join("; ", names);
        } else if (doc.get("authors") instanceof String a && !a.isBlank()) {
            authors = a;
        }
        result.put("authors", authors);

        boolean hasCa = !isHtmlEmpty(ca);
        boolean hasEs = !isHtmlEmpty(es);
        boolean hasEn = !isHtmlEmpty(en);

        result.put("abstractCa", hasCa ? ca : "");
        result.put("abstractEs", hasEs ? es : "");
        result.put("abstractEn", hasEn ? en : "");
        result.put("ca_ES", hasCa ? ca : "");
        result.put("es_ES", hasEs ? es : "");
        result.put("en_GB", hasEn ? en : "");

        result.put("hasCa", hasCa);
        result.put("hasEs", hasEs);
        result.put("hasEn", hasEn);
        result.put("has_ca", hasCa);
        result.put("has_es", hasEs);
        result.put("has_en", hasEn);

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

        int langCount = (hasCa ? 1 : 0) + (hasEs ? 1 : 0) + (hasEn ? 1 : 0);
        boolean isGenuine3Langs = hasCa && hasEs && hasEn && !isWrongCa && !isWrongEs && !isWrongEn && !allThreeMatch;
        result.put("languagesCount", langCount);
        result.put("isThreeLanguages", langCount == 3);
        result.put("isGenuine3Langs", isGenuine3Langs);

        result.put("lengthCa", hasCa ? ca.length() : 0);
        result.put("lengthEs", hasEs ? es.length() : 0);
        result.put("lengthEn", hasEn ? en.length() : 0);
        result.put("charsCa", hasCa ? ca.length() : 0);
        result.put("charsEs", hasEs ? es.length() : 0);
        result.put("charsEn", hasEn ? en.length() : 0);

        int wordsCa = hasCa ? countWords(ca) : 0;
        int wordsEs = hasEs ? countWords(es) : 0;
        int wordsEn = hasEn ? countWords(en) : 0;
        result.put("wordsCa", wordsCa);
        result.put("wordsEs", wordsEs);
        result.put("wordsEn", wordsEn);

        List<Integer> activeCounts = new ArrayList<>();
        if (hasCa) activeCounts.add(wordsCa);
        if (hasEs) activeCounts.add(wordsEs);
        if (hasEn) activeCounts.add(wordsEn);

        if (activeCounts.size() >= 2) {
            int maxW = Collections.max(activeCounts);
            int minW = Collections.min(activeCounts);
            result.put("diffWords", maxW - minW);
            result.put("ratioWords", minW > 0 ? (double) Math.round(((double) maxW / minW) * 10.0) / 10.0 : (maxW > 0 ? 99.0 : 1.0));
            result.put("pctDiscrepancy", maxW > 0 ? Math.round(((double) (maxW - minW) / maxW) * 1000.0) / 10.0 : 0.0);
        } else {
            result.put("diffWords", 0);
            result.put("ratioWords", 1.0);
            result.put("pctDiscrepancy", 0.0);
        }

        return Optional.of(result);
    }

    private String cleanForCompare(String text) {
        if (text == null || isHtmlEmpty(text)) return "";
        return text.replaceAll("<[^>]+>", " ")
                   .replaceAll("&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?", " ")
                   .replaceAll("\\s+", " ")
                   .trim()
                   .toLowerCase();
    }

    public static String extractAbstractString(Document absDoc, String langKey) {
        if (absDoc == null) return null;
        Object val = absDoc.get(langKey);
        if (val instanceof String s) return s;
        if (val instanceof Document d) {
            String v = d.getString("value");
            if (v != null) return v;
            v = d.getString("text");
            if (v != null) return v;
            return d.toJson();
        }
        return val != null ? val.toString() : null;
    }

    private static class DiscrepancyCandidate {
        final Document doc;
        final int wordsCa;
        final int wordsEs;
        final int wordsEn;
        final int diffWords;
        final double ratioWords;
        final double pctDiscrepancy;
        final String shortestLang;
        final String longestLang;
        final String severity;
        final int pubYear;
        final int subYear;

        DiscrepancyCandidate(Document doc, int wordsCa, int wordsEs, int wordsEn, int diffWords, double ratioWords, double pctDiscrepancy, String shortestLang, String longestLang, String severity, int pubYear, int subYear) {
            this.doc = doc;
            this.wordsCa = wordsCa;
            this.wordsEs = wordsEs;
            this.wordsEn = wordsEn;
            this.diffWords = diffWords;
            this.ratioWords = ratioWords;
            this.pctDiscrepancy = pctDiscrepancy;
            this.shortestLang = shortestLang;
            this.longestLang = longestLang;
            this.severity = severity;
            this.pubYear = pubYear;
            this.subYear = subYear;
        }
    }

    public Map<String, Object> getDiscrepanciesStats(Integer desde, Integer hasta, String type, String yearType, Integer minDiff, Double minRatio, Integer minLangs) {
        int effectiveMinDiff = minDiff != null ? minDiff : 50;
        double effectiveMinRatio = minRatio != null ? minRatio : 1.5;
        int effectiveMinLangs = minLangs != null && minLangs == 3 ? 3 : 2;
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);

        String cacheKey = "disc_stats_" + desde + "_" + hasta + "_" + type + "_" + yearType + "_" + effectiveMinDiff + "_" + effectiveMinRatio + "_" + effectiveMinLangs;
        CacheEntry cached = discrepanciesStatsCache.get(cacheKey);
        if (isCacheValid(cached)) {
            return (Map<String, Object>) cached.data();
        }

        List<Document> pipeline = buildDiscrepanciesBasePipeline(desde, hasta, type, isSubmissionYear, effectiveMinLangs);

        long totalMulti = 0;
        long totalDiscrepant = 0;
        long criticalCount = 0;
        long highCount = 0;
        long moderateCount = 0;

        long caShortestCount = 0;
        long esShortestCount = 0;
        long enShortestCount = 0;

        long caLongestCount = 0;
        long esLongestCount = 0;
        long enLongestCount = 0;

        long totalWordsCa = 0, countCa = 0;
        long totalWordsEs = 0, countEs = 0;
        long totalWordsEn = 0, countEn = 0;
        long sumDiffWords = 0;

        try (var cursor = mongoTemplate.getCollection("Researchoutputs")
                .aggregate(pipeline)
                .batchSize(100)
                .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String ca = doc.getString("ca");
                String es = doc.getString("es");
                String en = doc.getString("en");

                boolean hasCa = !isHtmlEmpty(ca);
                boolean hasEs = !isHtmlEmpty(es);
                boolean hasEn = !isHtmlEmpty(en);

                int wCa = hasCa ? countWords(ca) : 0;
                int wEs = hasEs ? countWords(es) : 0;
                int wEn = hasEn ? countWords(en) : 0;

                int activeLangs = (hasCa ? 1 : 0) + (hasEs ? 1 : 0) + (hasEn ? 1 : 0);
                if (activeLangs < effectiveMinLangs) continue;

                totalMulti++;
                if (hasCa) { totalWordsCa += wCa; countCa++; }
                if (hasEs) { totalWordsEs += wEs; countEs++; }
                if (hasEn) { totalWordsEn += wEn; countEn++; }

                List<Integer> activeCounts = new ArrayList<>();
                if (hasCa) activeCounts.add(wCa);
                if (hasEs) activeCounts.add(wEs);
                if (hasEn) activeCounts.add(wEn);

                int maxW = Collections.max(activeCounts);
                int minW = Collections.min(activeCounts);
                int diffW = maxW - minW;
                double ratioW = minW > 0 ? (double) maxW / minW : (maxW > 0 ? 99.0 : 1.0);

                if (diffW >= effectiveMinDiff || ratioW >= effectiveMinRatio) {
                    totalDiscrepant++;
                    sumDiffWords += diffW;

                    if (ratioW >= 4.0) {
                        criticalCount++;
                    } else if (ratioW >= 3.0 || (diffW >= 100 && ratioW >= 2.0)) {
                        highCount++;
                    } else {
                        moderateCount++;
                    }

                    if (hasCa && wCa == minW) caShortestCount++;
                    else if (hasEs && wEs == minW) esShortestCount++;
                    else if (hasEn && wEn == minW) enShortestCount++;

                    if (hasEn && wEn == maxW) enLongestCount++;
                    else if (hasEs && wEs == maxW) esLongestCount++;
                    else if (hasCa && wCa == maxW) caLongestCount++;
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalMultiLangs", totalMulti);
        resp.put("totalDiscrepant", totalDiscrepant);
        resp.put("pctDiscrepant", totalMulti > 0 ? Math.round(((double) totalDiscrepant / totalMulti) * 1000.0) / 10.0 : 0.0);
        resp.put("criticalCount", criticalCount);
        resp.put("highCount", highCount);
        resp.put("moderateCount", moderateCount);

        resp.put("caShortestCount", caShortestCount);
        resp.put("esShortestCount", esShortestCount);
        resp.put("enShortestCount", enShortestCount);

        resp.put("caLongestCount", caLongestCount);
        resp.put("esLongestCount", esLongestCount);
        resp.put("enLongestCount", enLongestCount);

        resp.put("avgWordsCa", countCa > 0 ? Math.round((double) totalWordsCa / countCa) : 0);
        resp.put("avgWordsEs", countEs > 0 ? Math.round((double) totalWordsEs / countEs) : 0);
        resp.put("avgWordsEn", countEn > 0 ? Math.round((double) totalWordsEn / countEn) : 0);
        resp.put("avgDiffWords", totalDiscrepant > 0 ? Math.round((double) sumDiffWords / totalDiscrepant) : 0);

        discrepanciesStatsCache.put(cacheKey, new CacheEntry(resp, System.currentTimeMillis()));
        return resp;
    }

    public Map<String, Object> getDiscrepanciesList(
            Integer desde, Integer hasta, String type, String yearType,
            Integer minDiff, Double minRatio, Integer minLangs,
            String shortestLang, String longestLang, String severity,
            String search, int page, int size, String sortField, String sortDir) {

        int effectiveMinDiff = minDiff != null ? minDiff : 50;
        double effectiveMinRatio = minRatio != null ? minRatio : 1.5;
        int effectiveMinLangs = minLangs != null && minLangs == 3 ? 3 : 2;
        boolean isSubmissionYear = "submissionYear".equalsIgnoreCase(yearType);

        List<Document> pipeline = buildDiscrepanciesBasePipeline(desde, hasta, type, isSubmissionYear, effectiveMinLangs);

        String searchLower = (search != null && !search.isBlank()) ? search.trim().toLowerCase() : null;

        List<DiscrepancyCandidate> candidates = new ArrayList<>();

        try (var cursor = mongoTemplate.getCollection("Researchoutputs")
                .aggregate(pipeline)
                .batchSize(100)
                .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String ca = doc.getString("ca");
                String es = doc.getString("es");
                String en = doc.getString("en");

                boolean hasCa = !isHtmlEmpty(ca);
                boolean hasEs = !isHtmlEmpty(es);
                boolean hasEn = !isHtmlEmpty(en);

                int activeLangs = (hasCa ? 1 : 0) + (hasEs ? 1 : 0) + (hasEn ? 1 : 0);
                if (activeLangs < effectiveMinLangs) continue;

                int wCa = hasCa ? countWords(ca) : 0;
                int wEs = hasEs ? countWords(es) : 0;
                int wEn = hasEn ? countWords(en) : 0;

                List<Integer> activeCounts = new ArrayList<>();
                if (hasCa) activeCounts.add(wCa);
                if (hasEs) activeCounts.add(wEs);
                if (hasEn) activeCounts.add(wEn);

                int maxW = Collections.max(activeCounts);
                int minW = Collections.min(activeCounts);
                int diffW = maxW - minW;
                double ratioW = minW > 0 ? (double) maxW / minW : (maxW > 0 ? 99.0 : 1.0);

                if (diffW < effectiveMinDiff && ratioW < effectiveMinRatio) continue;

                String sLang = (hasCa && wCa == minW) ? "ca" : ((hasEs && wEs == minW) ? "es" : "en");
                String lLang = (hasEn && wEn == maxW) ? "en" : ((hasEs && wEs == maxW) ? "es" : "ca");

                if (shortestLang != null && !"all".equalsIgnoreCase(shortestLang) && !sLang.equalsIgnoreCase(shortestLang)) continue;
                if (longestLang != null && !"all".equalsIgnoreCase(longestLang) && !lLang.equalsIgnoreCase(longestLang)) continue;

                String sev;
                if (ratioW >= 4.0) {
                    sev = "critical";
                } else if (ratioW >= 3.0 || (diffW >= 100 && ratioW >= 2.0)) {
                    sev = "high";
                } else {
                    sev = "moderate";
                }

                if (severity != null && !"all".equalsIgnoreCase(severity) && !sev.equalsIgnoreCase(severity)) continue;

                if (searchLower != null) {
                    Document titleDoc = (Document) doc.get("title");
                    String titleVal = titleDoc != null ? titleDoc.getString("value") : "";
                    boolean matchTitle = titleVal != null && titleVal.toLowerCase().contains(searchLower);
                    boolean matchUuid = doc.getString("uuid") != null && doc.getString("uuid").toLowerCase().contains(searchLower);
                    boolean matchCa = ca != null && ca.toLowerCase().contains(searchLower);
                    boolean matchEs = es != null && es.toLowerCase().contains(searchLower);
                    boolean matchEn = en != null && en.toLowerCase().contains(searchLower);
                    if (!matchTitle && !matchUuid && !matchCa && !matchEs && !matchEn) continue;
                }

                double pctDiscrepancy = maxW > 0 ? Math.round(((double)(maxW - minW) / maxW) * 1000.0) / 10.0 : 0.0;
                int pYear = ((Number) doc.getOrDefault("publicationYear", 0)).intValue();
                int sYear = ((Number) doc.getOrDefault("submissionYear", 0)).intValue();

                candidates.add(new DiscrepancyCandidate(doc, wCa, wEs, wEn, diffW, ratioW, pctDiscrepancy, sLang, lLang, sev, pYear, sYear));
            }
        }

        Comparator<DiscrepancyCandidate> comparator;
        switch (sortField != null ? sortField : "diffWords") {
            case "ratioWords" -> comparator = Comparator.comparingDouble(c -> c.ratioWords);
            case "wordsCa" -> comparator = Comparator.comparingInt(c -> c.wordsCa);
            case "wordsEs" -> comparator = Comparator.comparingInt(c -> c.wordsEs);
            case "wordsEn" -> comparator = Comparator.comparingInt(c -> c.wordsEn);
            case "submissionYear" -> comparator = Comparator.comparingInt(c -> c.subYear);
            case "publicationYear" -> comparator = Comparator.comparingInt(c -> c.pubYear);
            default -> comparator = Comparator.comparingInt(c -> c.diffWords);
        }
        if (!"asc".equalsIgnoreCase(sortDir)) {
            comparator = comparator.reversed();
        }
        candidates.sort(comparator);

        int totalElements = candidates.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        int fromIndex = Math.min(Math.max(0, page) * Math.max(1, size), totalElements);
        int toIndex = Math.min(fromIndex + Math.max(1, size), totalElements);

        List<DiscrepancyCandidate> paged = candidates.subList(fromIndex, toIndex);
        List<Map<String, Object>> items = new ArrayList<>();

        for (DiscrepancyCandidate c : paged) {
            Document doc = c.doc;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", doc.getString("uuid"));
            Document titleDoc = (Document) doc.get("title");
            item.put("title", titleDoc != null ? titleDoc.getString("value") : "Sense títol");
            item.put("publicationYear", c.pubYear);
            item.put("submissionYear", c.subYear);

            Document typeDoc = (Document) doc.get("type");
            Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
            item.put("typeName", termDoc != null ? termDoc.getString("ca_ES") : "Sense tipus");

            List<Document> contributors = doc.getList("contributors", Document.class);
            String authors = "Sense autors";
            if (contributors != null && !contributors.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Document cnt : contributors) {
                    Document n = (Document) cnt.get("name");
                    if (n != null) {
                        String ln = n.getString("lastName");
                        String fn = n.getString("firstName");
                        if (ln != null && fn != null) names.add(ln + ", " + fn);
                        else if (ln != null) names.add(ln);
                    }
                }
                if (!names.isEmpty()) authors = String.join("; ", names);
            }
            item.put("authors", authors);

            String ca = doc.getString("ca");
            String es = doc.getString("es");
            String en = doc.getString("en");

            boolean hasCa = !isHtmlEmpty(ca);
            boolean hasEs = !isHtmlEmpty(es);
            boolean hasEn = !isHtmlEmpty(en);

            item.put("hasCa", hasCa);
            item.put("hasEs", hasEs);
            item.put("hasEn", hasEn);

            item.put("wordsCa", c.wordsCa);
            item.put("wordsEs", c.wordsEs);
            item.put("wordsEn", c.wordsEn);

            item.put("charsCa", ca != null ? ca.length() : 0);
            item.put("charsEs", es != null ? es.length() : 0);
            item.put("charsEn", en != null ? en.length() : 0);

            item.put("diffWords", c.diffWords);
            item.put("ratioWords", Math.round(c.ratioWords * 10.0) / 10.0);
            item.put("pctDiscrepancy", c.pctDiscrepancy);

            item.put("shortestLang", c.shortestLang);
            item.put("longestLang", c.longestLang);
            item.put("severity", c.severity);

            item.put("detectedCa", hasCa ? detectLanguage(ca) : "none");
            item.put("detectedEs", hasEs ? detectLanguage(es) : "none");
            item.put("detectedEn", hasEn ? detectLanguage(en) : "none");

            item.put("abstractPreviewCa", hasCa ? truncate(ca, 200) : "");
            item.put("abstractPreviewEs", hasEs ? truncate(es, 200) : "");
            item.put("abstractPreviewEn", hasEn ? truncate(en, 200) : "");

            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("items", items);
        return response;
    }

    private List<Document> buildDiscrepanciesBasePipeline(Integer desde, Integer hasta, String type, boolean isSubmissionYear, int minLangs) {
        List<Document> pipeline = new ArrayList<>();

        List<Document> initialMatches = new ArrayList<>();
        initialMatches.add(new Document("abstract", new Document("$exists", true).append("$ne", null)));
        if (type != null && !type.isBlank()) {
            initialMatches.add(new Document("$or", List.of(
                new Document("type.term.ca_ES", type),
                new Document("type.term.es_ES", type),
                new Document("type.term.en_GB", type)
            )));
        }
        pipeline.add(new Document("$match", new Document("$and", initialMatches)));

        pipeline.add(new Document("$project", new Document()
            .append("uuid", 1)
            .append("title.value", 1)
            .append("publicationYear", new Document("$cond", List.of(
                new Document("$gte", List.of(
                    new Document("$ifNull", List.of(buildPublicationYearExpr(), 0)),
                    1900
                )),
                buildPublicationYearExpr(),
                0
            )))
            .append("submissionYear", new Document("$ifNull", List.of("$submissionYear", 0)))
            .append("type.term.ca_ES", 1)
            .append("contributors.name", 1)
            .append("has_ca", buildHasTextCond("ca_ES"))
            .append("has_es", buildHasTextCond("es_ES"))
            .append("has_en", buildHasTextCond("en_GB"))
            .append("ca", new Document("$ifNull", List.of("$abstract.ca_ES", "")))
            .append("es", new Document("$ifNull", List.of("$abstract.es_ES", "")))
            .append("en", new Document("$ifNull", List.of("$abstract.en_GB", "")))
        ));

        pipeline.add(new Document("$project", new Document()
            .append("uuid", 1)
            .append("title", 1)
            .append("publicationYear", 1)
            .append("submissionYear", 1)
            .append("type", 1)
            .append("contributors", 1)
            .append("ca", 1)
            .append("es", 1)
            .append("en", 1)
            .append("lang_count", new Document("$add", List.of("$has_ca", "$has_es", "$has_en")))
        ));

        List<Document> matchConditions = new ArrayList<>();
        matchConditions.add(new Document("lang_count", new Document("$gte", minLangs)));

        String checkYearField = isSubmissionYear ? "submissionYear" : "publicationYear";
        if (desde != null) {
            if (desde == 0) {
                matchConditions.add(new Document("$or", List.of(
                    new Document(checkYearField, null),
                    new Document(checkYearField, 0),
                    new Document(checkYearField, new Document("$exists", false))
                )));
            } else {
                matchConditions.add(new Document(checkYearField, new Document("$gte", desde)));
            }
        }
        if (hasta != null && hasta > 0) {
            matchConditions.add(new Document(checkYearField, new Document("$lte", hasta)));
        }

        pipeline.add(new Document("$match", new Document("$and", matchConditions)));

        return pipeline;
    }

    public void clearCache() {
        statsCache.clear();
        yearsCache.clear();
        yearsLanguagesCache.clear();
        typesCache.clear();
        discrepanciesStatsCache.clear();
        try {
            mongoTemplate.getCollection(STATS_CACHE_COLLECTION).drop();
        } catch (Exception ignored) {}
    }
}

