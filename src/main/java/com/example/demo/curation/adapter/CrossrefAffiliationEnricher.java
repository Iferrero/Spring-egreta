package com.example.demo.curation.adapter;

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
import java.util.*;

/**
 * Adapter for triangulating and enriching publication author affiliations directly from Crossref API
 * when OpenAlex affiliations are missing or ambiguous.
 */
@Component
public class CrossrefAffiliationEnricher {

    private static final Logger log = LoggerFactory.getLogger(CrossrefAffiliationEnricher.class);

    private static final String[] DOI_PREFIXES = {
            "https://doi.org/",
            "http://doi.org/",
            "https://dx.doi.org/",
            "http://dx.doi.org/",
            "doi.org/",
            "dx.doi.org/",
            "doi:"
    };

    @Value("${app.crossref.works.url:https://api.crossref.org/works}")
    private String crossrefWorksUrl = "https://api.crossref.org/works";

    @Value("${app.crossref.mailto:bibliometria@uab.cat}")
    private String mailto = "bibliometria@uab.cat";

    @Value("${app.crossref.timeout-ms:4000}")
    private int timeoutMs = 4000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CrossrefAffiliationEnricher() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build(), new ObjectMapper());
    }

    @Autowired
    public CrossrefAffiliationEnricher(@Autowired(required = false) ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build(),
                objectMapper != null ? objectMapper : new ObjectMapper());
    }

    public CrossrefAffiliationEnricher(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    public void setCrossrefWorksUrl(String crossrefWorksUrl) {
        this.crossrefWorksUrl = crossrefWorksUrl;
    }

    public void setMailto(String mailto) {
        this.mailto = mailto;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Queries Crossref Works API for the given DOI and extracts author affiliation strings.
     *
     * @param doi raw or normalized DOI
     * @return Optional containing the list of extracted affiliation strings, or Optional.empty() if missing or error
     */
    public Optional<List<String>> enrichAffiliationsFromCrossref(String doi) {
        if (doi == null || doi.isBlank()) {
            return Optional.empty();
        }

        String normalizedDoi = normalizeDoi(doi);
        if (normalizedDoi == null || normalizedDoi.isBlank()) {
            return Optional.empty();
        }

        try {
            String escapedDoi = URLEncoder.encode(normalizedDoi, StandardCharsets.UTF_8).replace("%2F", "/");
            String url = crossrefWorksUrl.replaceAll("/+$", "") + "/" + escapedDoi;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "Spring-EGRETA-Curation/1.0 (mailto:" + mailto.trim() + ")")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                log.debug("Crossref response for DOI {} returned status {}", normalizedDoi, response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("message");
            JsonNode authors = message.has("author") ? message.path("author") : message.path("items").path(0).path("author");

            if (!authors.isArray() || authors.isEmpty()) {
                return Optional.empty();
            }

            List<String> affiliations = new ArrayList<>();
            for (JsonNode author : authors) {
                JsonNode affils = author.path("affiliation");
                if (affils.isArray()) {
                    for (JsonNode aff : affils) {
                        String name = aff.isObject() ? aff.path("name").asText("") : aff.asText("");
                        if (name != null && !name.isBlank()) {
                            String trimmed = name.trim();
                            if (!affiliations.contains(trimmed)) {
                                affiliations.add(trimmed);
                            }
                        }
                    }
                }
            }

            return affiliations.isEmpty() ? Optional.empty() : Optional.of(affiliations);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Crossref enrichment interrupted for DOI '{}'", doi);
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Crossref affiliation enrichment failed for DOI '{}': {}", doi, e.getMessage());
            return Optional.empty();
        }
    }

    private String normalizeDoi(String rawDoi) {
        if (rawDoi == null || rawDoi.isBlank()) {
            return null;
        }
        String clean = rawDoi.trim().toLowerCase(Locale.ROOT);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : DOI_PREFIXES) {
                if (clean.startsWith(prefix)) {
                    clean = clean.substring(prefix.length()).trim();
                    stripped = true;
                }
            }
        }
        return clean.isEmpty() ? null : clean;
    }
}
