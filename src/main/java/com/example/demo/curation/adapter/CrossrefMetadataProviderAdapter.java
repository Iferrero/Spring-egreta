package com.example.demo.curation.adapter;

import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
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
import java.util.Optional;

/**
 * MetadataProviderPort adapter for Crossref Works API.
 * Infers institution metadata based on publication author affiliations.
 */
@Component
public class CrossrefMetadataProviderAdapter implements MetadataProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CrossrefMetadataProviderAdapter.class);

    @Value("${app.crossref.suggest.enabled:true}")
    private boolean crossrefSuggestEnabled = true;

    public void setEnabled(boolean enabled) {
        this.crossrefSuggestEnabled = enabled;
    }

    @Value("${app.crossref.suggest.url:https://api.crossref.org/works}")
    private String crossrefSuggestUrl;

    @Value("${app.crossref.suggest.timeout-ms:3500}")
    private int crossrefSuggestTimeoutMs;

    @Value("${app.crossref.mailto:egreta@uab.cat}")
    private String crossrefMailto;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public CrossrefMetadataProviderAdapter(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    @Override
    public String providerName() {
        return "crossref";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean isEnabled() {
        return crossrefSuggestEnabled;
    }

    @Override
    public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
        if (!crossrefSuggestEnabled || organizationName == null || organizationName.isBlank()) {
            return Optional.empty();
        }

        String doi = context != null ? context.doi() : null;
        String queryTitle = context != null ? context.publicationTitle() : null;
        if ((doi == null || doi.isBlank()) && (queryTitle == null || queryTitle.isBlank())) {
            return Optional.empty();
        }

        try {
            String url = crossrefSuggestUrl;
            if (doi != null && !doi.isBlank()) {
                url += "/" + URLEncoder.encode(doi.trim(), StandardCharsets.UTF_8);
            } else {
                url += "?query.bibliographic=" + URLEncoder.encode(queryTitle.trim(), StandardCharsets.UTF_8) + "&rows=1";
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(crossrefSuggestTimeoutMs))
                    .header("User-Agent", "Spring-egreta/1.0 (mailto:" + crossrefMailto + ")")
                    .GET();

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode message = root.path("message");
            JsonNode authors = message.has("author") ? message.path("author") : message.path("items").path(0).path("author");

            if (!authors.isArray() || authors.isEmpty()) {
                return Optional.empty();
            }

            String orgNorm = CurationTextNormalizer.normalizeText(organizationName);

            for (JsonNode author : authors) {
                JsonNode affils = author.path("affiliation");
                if (affils.isArray()) {
                    for (JsonNode aff : affils) {
                        String name = aff.path("name").asText("");
                        double score = CurationTextNormalizer.calculateNameMatchScore(orgNorm, CurationTextNormalizer.normalizeText(name), false);
                        if (score >= 0.70) {
                            return Optional.of(new MetadataInferenceResult(
                                    InferenceResult.noSignal(),
                                    new TypeInferenceResult("Centre de recerca", score * 0.85, "crossref-affiliation"),
                                    FundingInferenceResult.noSignal()
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Crossref inference failed for '{}': {}", organizationName, e.getMessage());
        }

        return Optional.empty();
    }
}
