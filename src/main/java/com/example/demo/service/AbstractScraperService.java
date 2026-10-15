package com.example.demo.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.*;

/**
 * Servei per a la obtenció de resums (abstracts) de fonts externes:
 * - DDD (Repositori de la UAB) via scraping de meta tags
 * - Dialnet via scraping de <ul id="resumen"> i meta tags amb reintent i timeouts
 * - Semantic Scholar via API REST
 */
@Service
public class AbstractScraperService {

    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // ─── Crossref ─────────────────────────────────────────────────────────────

    public String fetchAbstractFromCrossref(String doi) {
        if (doi == null || doi.isBlank()) return null;
        try {
            String cleanDoi = doi.trim();
            if (cleanDoi.toLowerCase().startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("http://dx.doi.org/")) cleanDoi = cleanDoi.substring("http://dx.doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("https://dx.doi.org/")) cleanDoi = cleanDoi.substring("https://dx.doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("doi.org/")) cleanDoi = cleanDoi.substring("doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("doi:")) cleanDoi = cleanDoi.substring("doi:".length()).trim();

            String encodedDoi = java.net.URLEncoder.encode(cleanDoi, java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://api.crossref.org/works/" + encodedDoi;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Spring-Egreta/1.0 (mailto:recerca@uab.cat)")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<?, ?> root = mapper.readValue(response.body(), Map.class);
                Map<?, ?> message = (Map<?, ?>) root.get("message");
                if (message != null && message.containsKey("abstract")) {
                    String rawAbstract = (String) message.get("abstract");
                    if (rawAbstract != null && !rawAbstract.isBlank()) {
                        String clean = cleanHtmlText(rawAbstract);
                        if (clean.toLowerCase().startsWith("abstract")) {
                            clean = clean.substring(8).trim();
                        }
                        if (!clean.isBlank()) {
                            return clean;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching abstract from Crossref (" + doi + "): " + e.getMessage());
        }
        return null;
    }

    // ─── DDD ─────────────────────────────────────────────────────────────────

    public Map<String, String> fetchAbstractFromDdd(String urlStr) {
        Map<String, String> result = new LinkedHashMap<>();
        if (urlStr == null || urlStr.isBlank()) return result;

        String targetUrl = urlStr.trim();
        if (targetUrl.toLowerCase().startsWith("http://")) {
            targetUrl = "https://" + targetUrl.substring(7);
        }

        String html = fetchAcademicHtml(targetUrl);
        if (html == null || html.isBlank()) {
            return result;
        }

        try {
            Pattern metaPattern = Pattern.compile("<meta\\s+([^>]+)>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = metaPattern.matcher(html);
            while (matcher.find()) {
                String attributes = matcher.group(1);
                String name = extractAttribute(attributes, "name");
                String content = extractAttribute(attributes, "content");
                String lang = extractAttribute(attributes, "xml:lang");
                if (lang == null || lang.isBlank()) lang = extractAttribute(attributes, "lang");

                if (name != null && content != null && !content.isBlank()) {
                    String nameLower = name.toLowerCase();
                    if ("citation_abstract".equals(nameLower) || "dc.description".equals(nameLower) || "description".equals(nameLower)) {
                        result.put(mapLocaleToEgretaLang(lang), cleanHtmlText(content));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing abstract from DDD (" + targetUrl + "): " + e.getMessage());
        }
        return result;
    }

    // ─── Dialnet ─────────────────────────────────────────────────────────────

    public Map<String, String> fetchAbstractFromDialnet(String urlStr) {
        Map<String, String> result = new LinkedHashMap<>();
        if (urlStr == null || urlStr.isBlank()) return result;

        String targetUrl = urlStr.trim();
        if (targetUrl.toLowerCase().startsWith("http://")) {
            targetUrl = "https://" + targetUrl.substring(7);
        }

        String html = fetchAcademicHtml(targetUrl);
        if (html == null || html.isBlank()) {
            return result;
        }

        try {
            // 1. Cercar bloc <ul id="resumen"> o <div id="resumen">
            Pattern resumenPattern = Pattern.compile("<(?:ul|div)\\s+[^>]*id=['\"]resumen['\"][^>]*>(.*?)</(?:ul|div)>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = resumenPattern.matcher(html);
            if (matcher.find()) {
                String resumenBody = matcher.group(1);
                Pattern liPattern = Pattern.compile("<li(?:\\s+xml:lang=['\"]([^'\"]*)['\"])?\\s*>(.*?)</li>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher liMatcher = liPattern.matcher(resumenBody);
                while (liMatcher.find()) {
                    String lang = liMatcher.group(1);
                    String textBody = liMatcher.group(2);
                    String text = cleanHtmlText(textBody);
                    if (text != null && !text.isBlank()) {
                        result.put(mapLocaleToEgretaLang(lang), text);
                    }
                }

                if (result.isEmpty()) {
                    String text = cleanHtmlText(resumenBody);
                    if (text != null && !text.isBlank()) {
                        result.put("es_ES", text);
                    }
                }
            }

            // 2. Fallback a meta tags de Dialnet si no s'ha trobat resum a la llista
            if (result.isEmpty()) {
                Pattern metaPattern = Pattern.compile("<meta\\s+([^>]+)>", Pattern.CASE_INSENSITIVE);
                Matcher metaMatcher = metaPattern.matcher(html);
                while (metaMatcher.find()) {
                    String attributes = metaMatcher.group(1);
                    String name = extractAttribute(attributes, "name");
                    String content = extractAttribute(attributes, "content");
                    String lang = extractAttribute(attributes, "xml:lang");
                    if (lang == null || lang.isBlank()) lang = extractAttribute(attributes, "lang");

                    if (name != null && content != null && !content.isBlank()) {
                        String nameLower = name.toLowerCase();
                        if ("citation_abstract".equals(nameLower) || "dc.description".equals(nameLower)) {
                            result.put(mapLocaleToEgretaLang(lang), cleanHtmlText(content));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing abstract from Dialnet (" + targetUrl + "): " + e.getMessage());
        }

        return result;
    }

    private String fetchAcademicHtml(String urlStr) {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            java.net.URL url = new java.net.URI(urlStr).toURL();
            java.net.URLConnection rawConn = url.openConnection();
            if (rawConn instanceof javax.net.ssl.HttpsURLConnection httpsConn) {
                httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                httpsConn.setHostnameVerifier((h, s) -> true);
            }
            rawConn.setConnectTimeout(15000);
            rawConn.setReadTimeout(15000);
            rawConn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            rawConn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            rawConn.setRequestProperty("Accept-Language", "ca-ES,ca;q=0.9,es-ES;q=0.8,es;q=0.7,en;q=0.6");

            if (rawConn instanceof java.net.HttpURLConnection httpConn) {
                int status = httpConn.getResponseCode();
                if (status < 200 || status >= 300) {
                    return null;
                }
            }

            try (java.io.InputStream is = rawConn.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                String utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (utf8.contains("\uFFFD")) {
                    return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                return utf8;
            }
        } catch (Exception e) {
            System.err.println("Error fetching academic HTML (" + urlStr + "): " + e.getMessage());
            return null;
        }
    }

    // ─── Semantic Scholar ─────────────────────────────────────────────────────

    public String fetchAbstractFromApis(String doi, String cleanTitle) {
        if (doi != null && !doi.isBlank()) {
            try {
                String url = "https://api.semanticscholar.org/graph/v1/paper/DOI:" + doi.trim() + "?fields=abstract";
                org.springframework.web.client.RestTemplate restTemplate = createUnsafeRestTemplate();
                Map<?, ?> resp = restTemplate.getForObject(url, Map.class);
                if (resp != null && resp.containsKey("abstract")) {
                    String ab = (String) resp.get("abstract");
                    if (ab != null && !ab.isBlank()) return ab.trim();
                }
            } catch (Exception e) {
                System.err.println("Error fetching abstract from Semantic Scholar: " + e.getMessage());
            }
        }
        return null;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    public String extractAttribute(String attributes, String attrName) {
        Pattern pattern = Pattern.compile(attrName + "=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    public String mapLocaleToEgretaLang(String locale) {
        if (locale == null || locale.isBlank()) return "en_GB";
        String l = locale.toLowerCase().trim();
        if (l.startsWith("ca") || l.startsWith("cat")) return "ca_ES";
        if (l.startsWith("es") || l.startsWith("spa") || l.startsWith("cas")) return "es_ES";
        if (l.startsWith("en") || l.startsWith("eng")) return "en_GB";
        return "en_GB";
    }

    public String cleanHtmlText(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                .replaceAll("&quot;", "\"").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&apos;", "'")
                .replaceAll("&#39;", "'").replaceAll("&#039;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    // ─── SSL (per a DDD) ──────────────────────────────────────────────────────

    public org.springframework.web.client.RestTemplate createUnsafeRestTemplate() {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                        if (connection instanceof javax.net.ssl.HttpsURLConnection httpsConnection) {
                            httpsConnection.setSSLSocketFactory(sc.getSocketFactory());
                            httpsConnection.setHostnameVerifier((hostname, session) -> true);
                        }
                        super.prepareConnection(connection, httpMethod);
                    }
                };
            requestFactory.setConnectTimeout(6000);
            requestFactory.setReadTimeout(10000);
            return new org.springframework.web.client.RestTemplate(requestFactory);
        } catch (Exception e) {
            return new org.springframework.web.client.RestTemplate();
        }
    }
}