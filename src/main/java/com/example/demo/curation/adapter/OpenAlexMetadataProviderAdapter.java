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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * MetadataProviderPort adapter for OpenAlex scholarly API.
 * Infers institution metadata based on author affiliations and works.
 */
@Component
public class OpenAlexMetadataProviderAdapter implements MetadataProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexMetadataProviderAdapter.class);

    @Value("${app.openalex.author-suggest.enabled:true}")
    private boolean openAlexAuthorSuggestEnabled = true;

    public void setEnabled(boolean enabled) {
        this.openAlexAuthorSuggestEnabled = enabled;
    }

    @Value("${app.openalex.author-suggest.url:https://api.openalex.org/authors}")
    private String openAlexAuthorSuggestUrl;

    @Value("${app.openalex.author-suggest.timeout-ms:3500}")
    private int openAlexAuthorSuggestTimeoutMs;

    @Value("${app.openalex.api-key:}")
    private String openAlexApiKey;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OpenAlexMetadataProviderAdapter(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    @Override
    public String providerName() {
        return "openalex";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean isEnabled() {
        return openAlexAuthorSuggestEnabled;
    }

    @Override
    public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
        if (!openAlexAuthorSuggestEnabled || organizationName == null || organizationName.isBlank()) {
            return Optional.empty();
        }

        String author = context != null ? context.associatedPerson() : null;
        if (author == null || author.isBlank()) {
            return Optional.empty();
        }

        try {
            String query = URLEncoder.encode(author.trim(), StandardCharsets.UTF_8);
            String url = openAlexAuthorSuggestUrl + "?search=" + query;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(openAlexAuthorSuggestTimeoutMs))
                    .GET();

            if (openAlexApiKey != null && !openAlexApiKey.isBlank()) {
                reqBuilder.header("api_key", openAlexApiKey.trim());
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }

            String orgNorm = CurationTextNormalizer.normalizeText(organizationName);

            for (JsonNode authorNode : results) {
                JsonNode affils = authorNode.path("affiliations");
                if (affils.isArray()) {
                    for (JsonNode aff : affils) {
                        JsonNode inst = aff.path("institution");
                        String instName = inst.path("display_name").asText("");
                        String countryCode = inst.path("country_code").asText("");
                        String instType = inst.path("type").asText("");

                        double score = CurationTextNormalizer.calculateNameMatchScore(orgNorm, CurationTextNormalizer.normalizeText(instName), false);
                        if (score >= 0.65) {
                            String resolvedCountry = countryCode;
                            if (context != null && context.countryCatalog() != null) {
                                resolvedCountry = context.countryCatalog().stream()
                                        .filter(c -> c.label().equalsIgnoreCase(countryCode) || c.normalizedLabel().equalsIgnoreCase(countryCode))
                                        .map(CountryAggregate::label)
                                        .findFirst()
                                        .orElse(countryCode);
                            }

                            InferenceResult cRes = new InferenceResult(resolvedCountry, Math.min(0.95, score * 0.95), "openalex-affiliation:" + instName);
                            TypeInferenceResult tRes = !instType.isBlank()
                                    ? new TypeInferenceResult(instType, Math.min(0.92, score * 0.90), "openalex-type")
                                    : TypeInferenceResult.noSignal();
                            FundingInferenceResult fRes = "education".equalsIgnoreCase(instType)
                                    ? new FundingInferenceResult("Publica", 0.90, "openalex-education")
                                    : FundingInferenceResult.noSignal();

                            return Optional.of(new MetadataInferenceResult(cRes, tRes, fRes));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("OpenAlex inference failed for '{}': {}", organizationName, e.getMessage());
        }

        return Optional.empty();
    }
}
