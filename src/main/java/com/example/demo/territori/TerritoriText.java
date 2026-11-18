package com.example.demo.territori;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utilitats de text i de lectura de documents Mongo (Pure) amb la mateixa semàntica que
 * l'script original {@code export_mapa_interaccions_territori.py}.
 */
final class TerritoriText {

    private static final Pattern WS_UNICODE = Pattern.compile("(?U)\\s+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern WS_ASCII = Pattern.compile("\\s+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private static final List<String> DEFAULT_LANGS = List.of("ca_ES", "es_ES", "en_GB");

    private static final Set<String> IGNORED_DESC = Set.of(
            "summary not available", "resum no disponible", "resumen no disponible",
            "no description", "sense descripcio");

    private TerritoriText() {}

    // ---- accés segur a documents ------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** Equivalent a {@code (a.get(k1) or {}).get(k2) ...}; retorna null si algun nivell falta. */
    static Object path(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            Map<String, Object> m = map(cur);
            if (m == null) return null;
            cur = m.get(k);
        }
        return cur;
    }

    /** Equivalent a {@code str(x or "")} per a valors escalars. */
    static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Boolean b && !b) return "";
        if (o instanceof Number n) {
            if (n.doubleValue() == 0) return "";
            return numberToString(n);
        }
        return o.toString();
    }

    static String numberToString(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return n.toString();
    }

    static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof CharSequence s) return s.length() > 0;
        if (o instanceof Map<?, ?> m) return !m.isEmpty();
        if (o instanceof List<?> l) return !l.isEmpty();
        return true;
    }

    // ---- text ----------------------------------------------------------------------

    static String normalize(Object value) {
        String s = Normalizer.normalize(str(value), Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = WS_ASCII.matcher(s).replaceAll(" ");
        return s.strip();
    }

    /** Comprova que needle apareix com a seqüència de paraules completes dins haystack. */
    static boolean containsWord(String haystackNorm, String needleNorm) {
        if (haystackNorm == null || haystackNorm.isEmpty() || needleNorm == null || needleNorm.isEmpty()) return false;
        return (" " + haystackNorm + " ").contains(" " + needleNorm + " ");
    }

    static String collapse(String s) {
        return WS_UNICODE.matcher(s).replaceAll(" ").strip();
    }

    static String pickLang(Object obj) {
        return pickLang(obj, DEFAULT_LANGS);
    }

    static String pickLang(Object obj, List<String> langs) {
        if (obj == null) return "";
        if (obj instanceof String s) return collapse(s);
        Map<String, Object> m = map(obj);
        if (m == null) return "";
        if (m.get("term") instanceof Map<?, ?>) return pickLang(m.get("term"), langs);
        for (String lang : langs) {
            if (m.get(lang) instanceof String v && !v.isBlank()) return collapse(v);
        }
        if (m.get("text") instanceof List<?> texts) {
            for (String lang : langs) {
                for (Object e : texts) {
                    Map<String, Object> em = map(e);
                    if (em != null && lang.equals(em.get("locale")) && !str(em.get("value")).isBlank()) {
                        return collapse(str(em.get("value")));
                    }
                }
            }
            for (Object e : texts) {
                Map<String, Object> em = map(e);
                if (em != null && !str(em.get("value")).isBlank()) return collapse(str(em.get("value")));
            }
        }
        for (Object v : m.values()) {
            if (v instanceof String s && !s.isBlank()) return collapse(s);
        }
        return "";
    }

    static double parseFloat(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        String s = val.toString().strip().replace(',', '.');
        if (!FLOAT.matcher(s).matches()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Date de Mongo → data. S'usa UTC, igual que pymongo (datetime naïf en UTC). */
    static LocalDate parseDate(Object val) {
        if (val == null) return null;
        if (val instanceof Date d) return Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        if (val instanceof String str) {
            String s = str.strip();
            if (s.length() > 10) s = s.substring(0, 10);
            try {
                return LocalDate.parse(s);
            } catch (Exception e) {
                return null;
            }
        }
        Map<String, Object> m = map(val);
        if (m != null && truthy(m.get("year"))) {
            try {
                int y = ((Number) m.get("year")).intValue();
                int mo = truthy(m.get("month")) ? ((Number) m.get("month")).intValue() : 1;
                int d = truthy(m.get("day")) ? ((Number) m.get("day")).intValue() : 1;
                return LocalDate.of(y, mo, d);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** round(x, scale) de Python: arrodoniment half-even sobre el valor binari exacte. */
    static double round(double x, int scale) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        return new BigDecimal(x).setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
    }

    static String cleanDescription(Object descriptions) {
        if (!(descriptions instanceof List<?> l)) return "";
        for (Object d : l) {
            Map<String, Object> dm = map(d);
            if (dm == null) continue;
            String val = pickLang(dm.get("value"));
            if (!val.isEmpty() && !IGNORED_DESC.contains(normalize(val))) {
                return val.length() > 800 ? val.substring(0, 797) + "..." : val;
            }
        }
        return "";
    }

    /** "; ".join(dict.fromkeys(items)). */
    static String joinDistinct(List<String> items) {
        return String.join("; ", new LinkedHashSet<>(items));
    }
}
