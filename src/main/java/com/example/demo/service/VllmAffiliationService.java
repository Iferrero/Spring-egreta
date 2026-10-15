package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Servei per a la integracio amb un servidor local vLLM (API compatible amb OpenAI)
 * per netejar cadenes d'afiliacio en brut eliminant adreces, ciutats, codis postals i paisos.
 */
@Service
public class VllmAffiliationService {

    private static final Logger log = LoggerFactory.getLogger(VllmAffiliationService.class);

    @Value("${app.vllm.enabled:true}")
    private boolean enabled;

    @Value("${app.vllm.url:http://localhost:8000/v1/chat/completions}")
    private String vllmUrl;

    @Value("${app.vllm.model:}")
    private String modelName;

    @Value("${app.vllm.api-key:}")
    private String apiKey;

    @Value("${app.vllm.timeout-ms:4000}")
    private int timeoutMs;

    @Value("${app.vllm.max-concurrency:1}")
    private int maxConcurrency;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> memoryCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Semaphore rateLimiterSemaphore = new Semaphore(1);
    private volatile String discoveredModel = null;

    public VllmAffiliationService() {
        this.objectMapper = new ObjectMapper();
        HttpClient client;
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                }
            };
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofMillis(3000))
                    .build();
        } catch (Exception e) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(3000))
                    .build();
        }
        this.httpClient = client;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getVllmUrl() {
        return vllmUrl;
    }

    /**
     * Envia una cadena d'afiliacio en brut a vLLM per extreure exclusivament
     * el nom net d'institucio i departament/servei, sense adreces ni paisos.
     *
     * @param rawAffiliation cadena d'afiliacio en brut
     * @return nom netejat d'organitzacio o null si vLLM no esta disponible o falla
     */
    public String cleanOrganizationName(String rawAffiliation) {
        if (!enabled || rawAffiliation == null || rawAffiliation.isBlank()) {
            return null;
        }

        String trimmed = rawAffiliation.trim();
        if (memoryCache.containsKey(trimmed)) {
            String cached = memoryCache.get(trimmed);
            return cached.isEmpty() ? null : cached;
        }

        boolean acquired = false;
        try {
            acquired = rateLimiterSemaphore.tryAcquire(8, TimeUnit.SECONDS);
            if (!acquired) {
                log.info("[vLLM] Servidor ocupat (semàfor ple), usant netejador de respatller per: '{}'", trimmed);
                return null;
            }

            String endpoint = resolveChatEndpoint();
            String effectiveModel = resolveModelName();

            Map<String, Object> systemMsg = Map.of(
                "role", "system",
                "content", "You are an expert in academic bibliometrics and entity normalization. "
                    + "Given a raw affiliation string from a scientific paper, extract and return ONLY the clean organization, "
                    + "institution, faculty, department, and laboratory names. "
                    + "Omit any street addresses, building numbers, postal codes, cities, provinces, regions, and countries. "
                    + "Return ONLY the plain text name, without quotes, explanations, or extra commentary."
            );

            Map<String, Object> userMsg = Map.of(
                "role", "user",
                "content", trimmed
            );

            Map<String, Object> payload = new LinkedHashMap<>();
            if (effectiveModel != null && !effectiveModel.isBlank()) {
                payload.put("model", effectiveModel);
            }
            payload.put("messages", List.of(systemMsg, userMsg));
            payload.put("temperature", 0.0);
            payload.put("max_tokens", 800);

            String reqJson = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson, StandardCharsets.UTF_8));

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            
            // Si el servidor retorna 429 (Too Many Requests), esperem 500ms i fem un reintent
            if (resp.statusCode() == 429) {
                log.warn("[vLLM] HTTP 429 (Rate limit) a {}, reintentant en 500ms...", endpoint);
                Thread.sleep(500);
                resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            }

            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode msgNode = choices.get(0).path("message");
                    String content = msgNode.path("content").asText("").trim();
                    if (content.isBlank() || "null".equalsIgnoreCase(content)) {
                        content = "";
                    }
                    content = sanitizeLlmOutput(content);
                    if (!content.isBlank() && content.length() >= 3) {
                        log.info("[vLLM] Cleaned affiliation: '{}' -> '{}'", trimmed, content);
                        memoryCache.put(trimmed, content);
                        return content;
                    }
                }
            } else {
                log.info("[vLLM] Returned HTTP {} from {}", resp.statusCode(), endpoint);
            }
        } catch (Exception e) {
            log.info("[vLLM] No disponible a {} ({}). Usant netejador de respatller.", vllmUrl, e.getMessage());
        } finally {
            if (acquired) {
                rateLimiterSemaphore.release();
            }
        }

        // Cache empty string to avoid repeating failed external calls for the same raw string
        memoryCache.put(trimmed, "");
        return null;
    }

    private String resolveChatEndpoint() {
        String url = (vllmUrl == null || vllmUrl.isBlank()) ? "http://localhost:8000/v1/chat/completions" : vllmUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/chat/completions")) {
            if (url.endsWith("/v1")) {
                url += "/chat/completions";
            } else {
                url += "/v1/chat/completions";
            }
        }
        return url;
    }

    private String resolveModelName() {
        if (modelName != null && !modelName.isBlank()) {
            return modelName.trim();
        }
        if (discoveredModel != null) {
            return discoveredModel;
        }

        try {
            String baseUrl = vllmUrl != null ? vllmUrl.trim() : "http://localhost:8000";
            int idx = baseUrl.indexOf("/chat/completions");
            if (idx > 0) baseUrl = baseUrl.substring(0, idx);
            if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl + "/models";
            } else {
                baseUrl = baseUrl.replaceAll("/+$", "") + "/v1/models";
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(1500))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode data = root.path("data");
                if (data.isArray() && !data.isEmpty()) {
                    String id = data.get(0).path("id").asText("");
                    if (!id.isBlank()) {
                        discoveredModel = id;
                        log.info("[vLLM] Model detectat automaticament: {}", id);
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "default";
    }

    private String sanitizeLlmOutput(String text) {
        if (text == null) return "";
        String clean = text.trim();
        // Detect and reject proxy/backend error messages
        if (clean.matches("(?i).*(?:API call failed|Connection error|failed to connect|Server Error|Bad Gateway|Service Unavailable|Gateway Timeout|timed out|rate limit|quota exceeded|I cannot fulfill|I am an AI|As an AI).*")) {
            log.warn("[vLLM] Resposta descartada per contenir missatge d'error de proxy/backend: '{}'", clean);
            return "";
        }
        // Remove markdown formatting or code fences if present
        clean = clean.replaceAll("```[a-zA-Z]*", "").replaceAll("```", "").trim();
        // Remove surrounding quotes
        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        // Remove prefixes like "Cleaned Name:", "Affiliation:"
        clean = clean.replaceAll("(?i)^(?:Cleaned (?:Name|Affiliation)|Output|Result|Organization Name|Name):\\s*", "").trim();
        return clean;
    }
}
