package com.example.demo.service;

import com.example.demo.util.PublicacionDocumentUtils;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Servei per a la cerca i suggestio de DOIs via l'API de Crossref.
 * Implementa 6 estrategies de cerca i algoritmes de similitud
 * (exact, substring, Levenshtein >= 85%, Jaccard token overlap).
 *
 * Extret de PublicacionController per aplicar el principi de responsabilitat unica.
 */
@SuppressWarnings("unchecked")
@Service
public class CrossrefService {

    private static final String CROSSREF_USER_AGENT = "PortalRecercaUAB/1.0 (mailto:recerca@uab.cat)";

    public static final Set<String> DOI_SEARCH_STOP_WORDS = new HashSet<>(Arrays.asList(
        "the", "of", "and", "in", "on", "at", "to", "for", "with", "by", "a", "an", "as", "from",
        "de", "la", "el", "i", "els", "les", "per", "en", "un", "una", "y", "o", "del", "dels",
        "las", "los", "por", "para", "con", "al", "sobre", "entre", "que", "qui", "des", "du"
    ));

    // ─── Extraccio de dades de publicacio ─────────────────────────────────────

    public Integer getPublicationYear(Document pub) {
        Integer year = pub.getInteger("submissionYear");
        if (year == null) {
            Document pubDate = (Document) pub.get("publicationDate");
            if (pubDate != null) year = pubDate.getInteger("year");
        }
        if (year == null) {
            List<Document> statuses = PublicacionDocumentUtils.getListDocument(pub, "publicationStatuses");
            if (statuses != null && !statuses.isEmpty()) {
                Document firstStatus = statuses.get(0);
                Document statusDate = (Document) firstStatus.get("publicationDate");
                if (statusDate != null) year = statusDate.getInteger("year");
            }
        }
        return year;
    }

    public List<String> extractAllAuthorLastNames(Document pub) {
        List<Document> contrs = PublicacionDocumentUtils.getListDocument(pub, "contributors");
        if (contrs == null || contrs.isEmpty()) return new ArrayList<>();
        List<String> lastNames = new ArrayList<>();
        for (Document c : contrs) {
            Document name = (Document) c.get("name");
            if (name != null) {
                String ln = name.getString("lastName");
                if (ln != null && !ln.isBlank()) { lastNames.add(ln.trim()); continue; }
                String fn = name.getString("firstName");
                if (fn != null && !fn.isBlank()) { lastNames.add(fn.trim()); continue; }
            }
            Document person = (Document) c.get("person");
            if (person != null) {
                Document pName = (Document) person.get("name");
                if (pName != null) {
                    String ln = pName.getString("lastName");
                    if (ln != null && !ln.isBlank()) { lastNames.add(ln.trim()); continue; }
                }
            }
        }
        return lastNames;
    }

    public String extractAuthorSearchString(Document pub) {
        List<String> lastNames = extractAllAuthorLastNames(pub);
        if (lastNames.isEmpty()) return "";
        List<String> searchList = new ArrayList<>();
        for (int i = 0; i < Math.min(3, lastNames.size()); i++) searchList.add(lastNames.get(i));
        return String.join(" ", searchList);
    }

    public String extractJournalIssn(Document pub) {
        Document ja = (Document) pub.get("journalAssociation");
        if (ja != null) {
            String issn = PublicacionDocumentUtils.nestedString(ja, "issn", "value");
            if (issn != null && !issn.isBlank()) return issn.trim();
            String eIssn = PublicacionDocumentUtils.nestedString(ja, "electronicIssn", "value");
            if (eIssn != null && !eIssn.isBlank()) return eIssn.trim();
            Document journal = (Document) ja.get("journal");
            if (journal != null) {
                String jIssn = PublicacionDocumentUtils.nestedString(journal, "issn", "value");
                if (jIssn != null && !jIssn.isBlank()) return jIssn.trim();
                String jEIssn = PublicacionDocumentUtils.nestedString(journal, "electronicIssn", "value");
                if (jEIssn != null && !jEIssn.isBlank()) return jEIssn.trim();
            }
        }
        return null;
    }

    public String extractJournalTitle(Document pub) {
        Document ja = (Document) pub.get("journalAssociation");
        if (ja != null) {
            String title = PublicacionDocumentUtils.nestedString(ja, "journal", "title", "value");
            if (title != null && !title.isBlank()) return title.trim();
            title = PublicacionDocumentUtils.nestedString(ja, "title", "value");
            if (title != null && !title.isBlank()) return title.trim();
        }
        return null;
    }

    public String extractEnglishTitle(Document pub) {
        Object transTitleObj = pub.get("translatedTitle");
        if (transTitleObj == null) return null;
        if (transTitleObj instanceof Document transTitle) {
            String val = transTitle.getString("value");
            if (val != null && !val.isBlank()) return val.trim();
            for (String key : transTitle.keySet()) {
                if (key.toLowerCase().startsWith("en")) {
                    String v = transTitle.getString(key);
                    if (v != null && !v.isBlank()) return v.trim();
                }
            }
        } else if (transTitleObj instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    // ─── Consultes a Crossref ─────────────────────────────────────────────────

    public void queryCrossrefByFields(
            String titleQuery, String authorQuery, String journalTitle, String issnFilter,
            Map<String, Map<String, Object>> suggestionsMap, List<String> pubAuthors,
            String... titleVariantsToMatch) {
        if (titleQuery == null || titleQuery.isBlank()) return;
        try {
            List<String> params = new ArrayList<>();
            params.add("query.title=" + URLEncoder.encode(PublicacionDocumentUtils.cleanRawTitle(titleQuery), StandardCharsets.UTF_8));
            if (authorQuery != null && !authorQuery.isBlank())
                params.add("query.author=" + URLEncoder.encode(authorQuery.trim(), StandardCharsets.UTF_8));
            if (journalTitle != null && !journalTitle.isBlank())
                params.add("query.container-title=" + URLEncoder.encode(PublicacionDocumentUtils.cleanRawTitle(journalTitle), StandardCharsets.UTF_8));
            if (issnFilter != null && !issnFilter.isBlank())
                params.add("filter=issn:" + URLEncoder.encode(issnFilter.trim(), StandardCharsets.UTF_8));
            params.add("rows=10");
            String url = "https://api.crossref.org/works?" + String.join("&", params);

            String body = doGet(url);
            if (body != null) {
                Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
                Map<String, Object> message = (Map<String, Object>) data.get("message");
                List<Map<String, Object>> items = message != null ? (List<Map<String, Object>>) message.get("items") : null;
                if (items != null) processCrossrefItems(items, suggestionsMap, pubAuthors, titleVariantsToMatch);
            }
        } catch (Exception e) {
            System.err.println("Error querying Crossref by fields: " + e.getMessage());
        }
    }

    public void queryAndCollectCrossref(
            String titleQuery, String authorQuery, Integer queryYear,
            Map<String, Map<String, Object>> suggestionsMap, List<String> pubAuthors,
            String... titleVariantsToMatch) {
        if (titleQuery == null || titleQuery.isBlank()) return;
        try {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append(titleQuery.replaceAll("[^\\p{L}0-9\\s]", " ").replaceAll("\\s+", " ").trim());
            if (authorQuery != null && !authorQuery.isBlank()) queryBuilder.append(" ").append(authorQuery.trim());
            if (queryYear != null && queryYear > 0) queryBuilder.append(" ").append(queryYear);

            String url = "https://api.crossref.org/works?query.bibliographic="
                    + URLEncoder.encode(queryBuilder.toString(), StandardCharsets.UTF_8) + "&rows=15";

            String body = doGet(url);
            if (body != null) {
                Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
                Map<String, Object> message = (Map<String, Object>) data.get("message");
                List<Map<String, Object>> items = message != null ? (List<Map<String, Object>>) message.get("items") : null;
                if (items != null) processCrossrefItems(items, suggestionsMap, pubAuthors, titleVariantsToMatch);
            }
        } catch (Exception e) {
            System.err.println("Error querying Crossref (" + titleQuery + "): " + e.getMessage());
        }
    }

    public void processCrossrefItems(
            List<Map<String, Object>> items,
            Map<String, Map<String, Object>> suggestionsMap,
            List<String> pubAuthors,
            String... titleVariantsToMatch) {
        for (Map<String, Object> item : items) {
            String doi = (String) item.get("DOI");
            if (doi == null || doi.isBlank() || suggestionsMap.containsKey(doi.trim())) continue;

            String sTitle = "";
            List<String> titles = (List<String>) item.get("title");
            if (titles != null && !titles.isEmpty()) sTitle = titles.get(0);
            sTitle = PublicacionDocumentUtils.cleanRawTitle(sTitle);
            if (sTitle.isBlank()) continue;

            boolean similar = false;
            for (String variant : titleVariantsToMatch) {
                if (variant != null && !variant.isBlank() && isTitleSimilar(variant, sTitle)) { similar = true; break; }
            }
            if (!similar) continue;

            List<String> authorNames = new ArrayList<>();
            List<Map<String, Object>> authors = (List<Map<String, Object>>) item.get("author");
            if (authors != null) {
                for (Map<String, Object> aut : authors) {
                    String family = (String) aut.get("family");
                    if (family != null && !family.isBlank()) authorNames.add(family.trim());
                }
            }
            if (!isAuthorMatching(pubAuthors, authorNames)) continue;

            Integer sYear = extractCrossrefYear(item, "published-print");
            if (sYear == null) sYear = extractCrossrefYear(item, "published-online");
            if (sYear == null) sYear = extractCrossrefYear(item, "issued");

            List<String> sIssns = (List<String>) item.get("ISSN");
            List<String> sIsbns = (List<String>) item.get("ISBN");

            Map<String, Object> sug = new LinkedHashMap<>();
            sug.put("doi", doi.trim());
            sug.put("title", sTitle);
            sug.put("authors", !authorNames.isEmpty() ? String.join(", ", authorNames) : "Sense autors");
            sug.put("year", sYear != null ? sYear : "-");
            sug.put("issn", sIssns != null && !sIssns.isEmpty() ? sIssns.get(0) : "");
            sug.put("isbn", sIsbns != null && !sIsbns.isEmpty() ? sIsbns.get(0) : "");
            suggestionsMap.put(doi.trim(), sug);
            if (suggestionsMap.size() >= 5) break;
        }
    }

    // ─── Similitud de titols i autors ─────────────────────────────────────────

    public boolean isTitleSimilar(String pubTitle, String sugTitle) {
        if (pubTitle == null || sugTitle == null) return false;
        String t1 = PublicacionDocumentUtils.normalizeForComparison(pubTitle);
        String t2 = PublicacionDocumentUtils.normalizeForComparison(sugTitle);
        if (t1.isEmpty() || t2.isEmpty()) return false;
        if (t1.equals(t2)) return true;
        if (t1.length() >= 15 && t2.length() >= 15) {
            if (t1.startsWith(t2) || t2.startsWith(t1) || t1.contains(t2) || t2.contains(t1)) {
                double lenRatio = (double) Math.min(t1.length(), t2.length()) / Math.max(t1.length(), t2.length());
                if (lenRatio >= 0.70) return true;
            }
        }
        int maxLen = Math.max(t1.length(), t2.length());
        int dist = computeLevenshteinDistance(t1, t2);
        double levRatio = 1.0 - ((double) dist / maxLen);
        if (levRatio >= 0.85) return true;
        String[] w1 = t1.split(" ");
        String[] w2 = t2.split(" ");
        Set<String> s1 = new HashSet<>();
        for (String w : w1) if (w.length() >= 2 && !DOI_SEARCH_STOP_WORDS.contains(w)) s1.add(w);
        Set<String> s2 = new HashSet<>();
        for (String w : w2) if (w.length() >= 2 && !DOI_SEARCH_STOP_WORDS.contains(w)) s2.add(w);
        if (s1.isEmpty() || s2.isEmpty()) return levRatio >= 0.80;
        int intersection = 0;
        for (String word : s1) if (s2.contains(word)) intersection++;
        double overlap = (double) intersection / Math.min(s1.size(), s2.size());
        double jaccard = (double) intersection / (s1.size() + s2.size() - intersection);
        return (overlap >= 0.85 && jaccard >= 0.70);
    }

    public boolean isAuthorMatching(List<String> pubAuthors, List<String> sugAuthors) {
        if (pubAuthors == null || pubAuthors.isEmpty()) return true;
        if (sugAuthors == null || sugAuthors.isEmpty()) return false;
        for (String pAuth : pubAuthors) {
            String normP = PublicacionDocumentUtils.normalizeAuthorName(pAuth);
            if (normP.isEmpty() || normP.length() < 2) continue;
            String[] pTokens = normP.split("\\s+");
            for (String sAuth : sugAuthors) {
                String normS = PublicacionDocumentUtils.normalizeAuthorName(sAuth);
                if (normS.isEmpty() || normS.length() < 2) continue;
                String[] sTokens = normS.split("\\s+");
                if (normP.equals(normS)) return true;
                for (String pt : pTokens) {
                    if (pt.length() >= 3 && !DOI_SEARCH_STOP_WORDS.contains(pt)) {
                        for (String st : sTokens) {
                            if (st.length() >= 3 && !DOI_SEARCH_STOP_WORDS.contains(st) && pt.equals(st)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // ─── Utils interns ────────────────────────────────────────────────────────

    private static int computeLevenshteinDistance(String s1, String s2) {
        int len1 = s1.length(), len2 = s2.length();
        int[] prev = new int[len2 + 1], curr = new int[len2 + 1];
        for (int j = 0; j <= len2; j++) prev[j] = j;
        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, len2 + 1);
        }
        return prev[len2];
    }

    private Integer extractCrossrefYear(Map<String, Object> item, String field) {
        Map<String, Object> dateObj = (Map<String, Object>) item.get(field);
        if (dateObj == null) return null;
        List<List<Integer>> dateParts = (List<List<Integer>>) dateObj.get("date-parts");
        if (dateParts != null && !dateParts.isEmpty() && !dateParts.get(0).isEmpty()) {
            return dateParts.get(0).get(0);
        }
        return null;
    }

    public Map<String, String> fetchTitleAndSubtitleByDoi(String doi) {
        if (doi == null || doi.isBlank()) return null;
        try {
            String cleanDoi = doi.trim();
            if (cleanDoi.toLowerCase().startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("http://dx.doi.org/")) cleanDoi = cleanDoi.substring("http://dx.doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("https://dx.doi.org/")) cleanDoi = cleanDoi.substring("https://dx.doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("doi.org/")) cleanDoi = cleanDoi.substring("doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("doi:")) cleanDoi = cleanDoi.substring("doi:".length()).trim();

            String encodedDoi = URLEncoder.encode(cleanDoi, StandardCharsets.UTF_8);
            String url = "https://api.crossref.org/works/" + encodedDoi;
            String body = doGet(url);
            if (body != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> root = mapper.readValue(body, Map.class);
                Map<String, Object> message = (Map<String, Object>) root.get("message");
                if (message != null) {
                    Map<String, String> result = new LinkedHashMap<>();
                    if (message.containsKey("title")) {
                        Object tObj = message.get("title");
                        if (tObj instanceof List<?> tList && !tList.isEmpty()) {
                            result.put("title", tList.get(0).toString().trim());
                        } else if (tObj instanceof String s) {
                            result.put("title", s.trim());
                        }
                    }
                    if (message.containsKey("subtitle")) {
                        Object stObj = message.get("subtitle");
                        if (stObj instanceof List<?> stList && !stList.isEmpty()) {
                            result.put("subTitle", stList.get(0).toString().trim());
                        } else if (stObj instanceof String s) {
                            result.put("subTitle", s.trim());
                        }
                    }
                    if (!result.isEmpty()) return result;
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching title from Crossref (" + doi + "): " + e.getMessage());
        }
        return null;
    }

    private String doGet(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", CROSSREF_USER_AGENT)
                    .timeout(java.time.Duration.ofSeconds(7))
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            System.err.println("HTTP GET error: " + e.getMessage());
            return null;
        }
    }
}