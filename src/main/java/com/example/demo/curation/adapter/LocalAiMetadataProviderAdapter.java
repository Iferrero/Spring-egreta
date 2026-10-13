package com.example.demo.curation.adapter;

import com.example.demo.curation.model.CountryAggregate;
import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeAggregate;
import com.example.demo.curation.model.TypeInferenceResult;
import com.example.demo.curation.port.MetadataProviderPort;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * MetadataProviderPort adapter for local Ollama / vLLM inference models.
 * Used as a fallback when registry authorities (ROR, OpenAlex) fail to resolve metadata.
 */
@Component
public class LocalAiMetadataProviderAdapter implements MetadataProviderPort {

    private static final Logger log = LoggerFactory.getLogger(LocalAiMetadataProviderAdapter.class);

    @Value("${app.ai.country-suggest.enabled:false}")
    private boolean aiCountrySuggestEnabled;

    @Value("${app.ai.country-suggest.url:}")
    private String aiCountrySuggestUrl;

    @Value("${app.ai.country-suggest.model:}")
    private String aiCountrySuggestModel;

    @Value("${app.ai.country-suggest.api-key:}")
    private String aiCountrySuggestApiKey;

    @Value("${app.ai.country-suggest.timeout-ms:2000}")
    private int aiCountrySuggestTimeoutMs;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public LocalAiMetadataProviderAdapter(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    }

    @Override
    public String providerName() {
        return "local-ai";
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public boolean isEnabled() {
        return aiCountrySuggestEnabled && aiCountrySuggestUrl != null && !aiCountrySuggestUrl.isBlank();
    }

    @Override
    public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
        if (!isEnabled() || organizationName == null || organizationName.isBlank()) {
            return Optional.empty();
        }

        try {
            String prompt = "Respond exclusively with valid JSON: {\"country\": \"...\", \"type\": \"...\", \"funding\": \"Publica|Privada\", \"confidence\": 0.0-1.0} for organization: " + organizationName;

            Map<String, Object> reqPayload = Map.of(
                    "model", (aiCountrySuggestModel != null && !aiCountrySuggestModel.isBlank()) ? aiCountrySuggestModel : "default",
                    "prompt", prompt,
                    "stream", false
            );

            String bodyJson = objectMapper.writeValueAsString(reqPayload);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(aiCountrySuggestUrl))
                    .timeout(Duration.ofMillis(aiCountrySuggestTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson));

            if (aiCountrySuggestApiKey != null && !aiCountrySuggestApiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + aiCountrySuggestApiKey.trim());
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String textResponse = root.has("response") ? root.path("response").asText("") : root.path("content").asText("");

            if (textResponse.isBlank()) return Optional.empty();

            // Extract embedded json
            int startIdx = textResponse.indexOf("{");
            int endIdx = textResponse.lastIndexOf("}");
            if (startIdx >= 0 && endIdx > startIdx) {
                String parsedJson = textResponse.substring(startIdx, endIdx + 1);
                JsonNode parsed = objectMapper.readTree(parsedJson);

                String country = parsed.path("country").asText("");
                String type = parsed.path("type").asText("");
                String funding = parsed.path("funding").asText("");
                double conf = parsed.path("confidence").asDouble(0.75);

                InferenceResult cRes = !country.isBlank() ? new InferenceResult(country, conf, "local-ai") : InferenceResult.noSignal();
                TypeInferenceResult tRes = !type.isBlank() ? new TypeInferenceResult(type, conf, "local-ai") : TypeInferenceResult.noSignal();
                FundingInferenceResult fRes = !funding.isBlank() ? new FundingInferenceResult(funding, conf, "local-ai") : FundingInferenceResult.noSignal();

                return Optional.of(new MetadataInferenceResult(cRes, tRes, fRes));
            }
        } catch (Exception e) {
            log.debug("Local AI inference failed for '{}': {}", organizationName, e.getMessage());
        }

        return Optional.empty();
    }
}
