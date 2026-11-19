package com.example.demo.plannacional.parser;

import com.example.demo.plannacional.model.ExtractedMember;
import com.example.demo.plannacional.model.ExtractedProposal;
import com.example.demo.plannacional.model.ExtractionMethod;
import com.example.demo.plannacional.model.MemberRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;

/**
 * Fallback opcional amb servidor local vLLM quan el parser determinista AEI no troba membres.
 */
@Service
public class VllmProposalFallbackParser {

    private static final Logger log = LoggerFactory.getLogger(VllmProposalFallbackParser.class);
    private static final int MAX_TEXT_TO_LLM = 14000;
    private static final int RAW_PREVIEW_LIMIT = 4000;

    private final boolean enabled;
    private final String vllmUrl;
    private final String modelName;
    private final String apiKey;
    private final int timeoutMs;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public VllmProposalFallbackParser(
            @Value("${app.plannacional.vllm-fallback.enabled:true}") boolean enabled,
            @Value("${app.vllm.url:http://localhost:8000/v1/chat/completions}") String vllmUrl,
            @Value("${app.vllm.model:}") String modelName,
            @Value("${app.vllm.api-key:}") String apiKey,
            @Value("${app.vllm.timeout-ms:25000}") int timeoutMs) {
        this.enabled = enabled;
        this.vllmUrl = vllmUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.objectMapper = new ObjectMapper();
        this.httpClient = buildHttpClient(timeoutMs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<ExtractedProposal> parseFallback(String fileName, String rawText) {
        if (!enabled || rawText == null || rawText.isBlank() || vllmUrl == null || vllmUrl.isBlank()) {
            return Optional.empty();
        }

        try {
            String snippet = rawText.length() > MAX_TEXT_TO_LLM ? rawText.substring(0, MAX_TEXT_TO_LLM) : rawText;
            Map<String, Object> systemMsg = Map.of(
                    "role", "system",
                    "content", "Extract research team members from this Spanish AEI grant application text as strict JSON: "
                            + "{\"projectCode\":string|null,\"acronym\":string|null,\"title\":string|null,\"durationYears\":number|null,"
                            + "\"applicantEntity\":string|null,\"members\":[{\"role\":\"IP|EQUIPO_INVESTIGACION|EQUIPO_TRABAJO\","
                            + "\"fullName\":string,\"firstName\":string|null,\"lastName\":string|null,\"nif\":string|null,\"entity\":string|null}]}. "
                            + "Return ONLY JSON."
            );
            Map<String, Object> userMsg = Map.of("role", "user", "content", snippet);

            Map<String, Object> payload = new LinkedHashMap<>();
            if (modelName != null && !modelName.isBlank()) {
                payload.put("model", modelName);
            }
            payload.put("messages", List.of(systemMsg, userMsg));
            payload.put("temperature", 0.0);
            payload.put("max_tokens", 2000);

            String endpoint = resolveChatEndpoint(vllmUrl);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                return parseVllmJson(fileName, rawText, response.body());
            }
            log.info("[vLLM-PlanNacional] HTTP {} des de {}", response.statusCode(), endpoint);
        } catch (Exception e) {
            log.info("[vLLM-PlanNacional] No disponible a {} ({}).", vllmUrl, e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<ExtractedProposal> parseVllmJson(String fileName, String rawText, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) {
                return Optional.empty();
            }
            String jsonBlock = stripCodeFences(content);
            JsonNode data = objectMapper.readTree(jsonBlock);
            JsonNode membersNode = data.path("members");
            if (!membersNode.isArray() || membersNode.isEmpty()) {
                return Optional.empty();
            }

            List<ExtractedMember> members = new ArrayList<>();
            for (JsonNode node : membersNode) {
                String fullName = textOrNull(node, "fullName");
                if (fullName == null) {
                    continue;
                }
                MemberRole role = parseRole(textOrNull(node, "role"));
                members.add(new ExtractedMember(
                        role,
                        fullName,
                        textOrNull(node, "firstName"),
                        textOrNull(node, "lastName"),
                        textOrNull(node, "nif"),
                        textOrNull(node, "entity"),
                        textOrNull(node, "declaredLinkage"),
                        fullName
                ));
            }
            if (members.isEmpty()) {
                return Optional.empty();
            }

            Integer durationYears = data.hasNonNull("durationYears") && data.get("durationYears").canConvertToInt()
                    ? data.get("durationYears").asInt()
                    : null;
            String preview = rawText != null && rawText.length() > RAW_PREVIEW_LIMIT
                    ? rawText.substring(0, RAW_PREVIEW_LIMIT)
                    : (rawText != null ? rawText : "");

            return Optional.of(new ExtractedProposal(
                    fileName,
                    textOrNull(data, "projectCode"),
                    textOrNull(data, "acronym"),
                    textOrNull(data, "title"),
                    durationYears,
                    null,
                    null,
                    textOrNull(data, "applicantEntity"),
                    ExtractionMethod.VLLM_FALLBACK,
                    List.of("Extret amb IA (format no estàndard) — reviseu el llistat"),
                    preview,
                    members
            ));
        } catch (Exception e) {
            log.info("[vLLM-PlanNacional] Resposta JSON no vàlida: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private MemberRole parseRole(String raw) {
        if (raw == null) {
            return MemberRole.EQUIPO_INVESTIGACION;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "IP" -> MemberRole.IP;
            case "EQUIPO_TRABAJO" -> MemberRole.EQUIPO_TRABAJO;
            default -> MemberRole.EQUIPO_INVESTIGACION;
        };
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String text = child.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private String stripCodeFences(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    private String resolveChatEndpoint(String url) {
        String clean = url.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/chat/completions")) {
            return clean;
        }
        return clean.endsWith("/v1") ? clean + "/chat/completions" : clean + "/v1/chat/completions";
    }

    private HttpClient buildHttpClient(int timeout) {
        int connectMs = Math.min(3000, Math.max(500, timeout));
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                        public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                    }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(ssl)
                    .connectTimeout(Duration.ofMillis(connectMs))
                    .build();
        } catch (Exception e) {
            return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs)).build();
        }
    }
}
