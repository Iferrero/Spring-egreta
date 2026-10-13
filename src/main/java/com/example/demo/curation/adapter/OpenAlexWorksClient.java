package com.example.demo.curation.adapter;

import com.example.demo.curation.model.OpenAlexWorkDto;
import com.example.demo.curation.model.OpenAlexWorkDto.AuthorDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * HTTP client adapter for the OpenAlex Works API (https://api.openalex.org/works).
 * Implements cursor pagination, polite pool courtesy headers, strict/non-strict ORCID search,
 * and automatic fallback to Crossref affiliation enrichment.
 */
@Component
public class OpenAlexWorksClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexWorksClient.class);

    @Value("${app.openalex.works.url:https://api.openalex.org/works}")
    private String worksApiUrl = "https://api.openalex.org/works";

    @Value("${app.openalex.mailto:bibliometria@uab.cat}")
    private String mailto = "bibliometria@uab.cat";

    @Value("${app.openalex.api-key:}")
    private String apiKey = "";

    @Value("${app.openalex.timeout-ms:5000}")
    private int timeoutMs = 5000;

    @Value("${app.openalex.page-size:50}")
    private int pageSize = 50;

    @Value("${app.openalex.max-pages:10}")
    private int maxPages = 10;

    @Value("${app.openalex.crossref-fallback.enabled:true}")
    private boolean crossrefFallbackEnabled = true;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CrossrefAffiliationEnricher crossrefEnricher;

    public OpenAlexWorksClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(), null);
    }

    @Autowired
    public OpenAlexWorksClient(@Autowired(required = false) ObjectMapper objectMapper,
                               @Autowired(required = false) CrossrefAffiliationEnricher crossrefEnricher) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper != null ? objectMapper : new ObjectMapper(),
                crossrefEnricher);
    }

    public OpenAlexWorksClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, null);
    }

    public OpenAlexWorksClient(HttpClient httpClient, ObjectMapper objectMapper,
                               CrossrefAffiliationEnricher crossrefEnricher) {
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.crossrefEnricher = crossrefEnricher;
    }

    public void setWorksApiUrl(String worksApiUrl) {
        this.worksApiUrl = worksApiUrl;
    }

    public void setMailto(String mailto) {
        this.mailto = mailto;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public void setCrossrefFallbackEnabled(boolean crossrefFallbackEnabled) {
        this.crossrefFallbackEnabled = crossrefFallbackEnabled;
    }

    /**
     * Delegates Crossref affiliation enrichment directly if needed.
     */
    public Optional<List<String>> enrichAffiliationsFromCrossref(String doi) {
        if (crossrefEnricher != null) {
            return crossrefEnricher.enrichAffiliationsFromCrossref(doi);
        }
        return Optional.empty();
    }

    /**
     * Fetches scholarly works authored by a person from OpenAlex.
     *
     * @param orcid       ORCID identifier (e.g. "0000-0002-1825-0097" or "https://orcid.org/...")
     * @param authorName  Full or variant name of the author
     * @param strictOrcid If true, requires non-blank ORCID and only filters by ORCID
     * @return List of retrieved and deserialized OpenAlex works
     */
    public List<OpenAlexWorkDto> fetchWorksByAuthor(String orcid, String authorName, boolean strictOrcid) {
        String cleanOrcid = cleanOrcid(orcid);

        if (strictOrcid) {
            if (cleanOrcid == null || cleanOrcid.isBlank()) {
                log.debug("strictOrcid is true but no valid ORCID was provided");
                return Collections.emptyList();
            }
            return fetchWorksWithFilter("author.orcid:" + cleanOrcid);
        }

        // Non-strict mode:
        if (cleanOrcid != null && !cleanOrcid.isBlank()) {
            List<OpenAlexWorkDto> worksByOrcid = fetchWorksWithFilter("author.orcid:" + cleanOrcid);
            if (!worksByOrcid.isEmpty()) {
                return worksByOrcid;
            }
            log.debug("No works found in OpenAlex for ORCID {}, falling back to authorName search '{}'", cleanOrcid, authorName);
        }

        if (authorName != null && !authorName.isBlank()) {
            return fetchWorksWithFilter("raw_author_name.search:" + authorName.trim());
        }

        return Collections.emptyList();
    }

    /**
     * Performs cursor-paginated GET requests to OpenAlex works API using the given filter.
     */
    private List<OpenAlexWorkDto> fetchWorksWithFilter(String filterExpression) {
        List<OpenAlexWorkDto> allWorks = new ArrayList<>();
        String currentCursor = "*";
        int pagesFetched = 0;

        try {
            while (currentCursor != null && pagesFetched < maxPages) {
                pagesFetched++;
                String encodedFilter = URLEncoder.encode(filterExpression, StandardCharsets.UTF_8);
                String encodedCursor = URLEncoder.encode(currentCursor, StandardCharsets.UTF_8);

                String url = worksApiUrl.replaceAll("/+$", "") +
                        "?filter=" + encodedFilter +
                        "&per-page=" + pageSize +
                        "&cursor=" + encodedCursor;

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("User-Agent", "Spring-EGRETA-Curation/1.0 (mailto:" + mailto.trim() + ")")
                        .GET();

                if (apiKey != null && !apiKey.isBlank()) {
                    reqBuilder.header("api_key", apiKey.trim());
                }

                HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                    log.warn("OpenAlex works API returned status {} for filter '{}'", response.statusCode(), filterExpression);
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) {
                    break;
                }

                for (JsonNode item : results) {
                    OpenAlexWorkDto work = parseWork(item);
                    if (work != null) {
                        applyCrossrefFallbackIfNeeded(work);
                        allWorks.add(work);
                    }
                }

                JsonNode nextCursorNode = root.path("meta").path("next_cursor");
                String nextCursor = (!nextCursorNode.isMissingNode() && !nextCursorNode.isNull()) ? nextCursorNode.asText(null) : null;

                if (nextCursor == null || nextCursor.isBlank() || nextCursor.equals(currentCursor)) {
                    break;
                }
                currentCursor = nextCursor;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("OpenAlex works request interrupted: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("OpenAlex works request failed for filter '{}': {}", filterExpression, e.getMessage());
        }

        return allWorks;
    }

    /**
     * Parses a single JSON node representing an OpenAlex work into an OpenAlexWorkDto.
     */
    private OpenAlexWorkDto parseWork(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }

        String id = item.path("id").asText(null);
        String doi = item.hasNonNull("doi") ? item.path("doi").asText(null) : null;
        String title = item.path("title").asText("");
        Integer pubYear = item.hasNonNull("publication_year") ? item.path("publication_year").asInt() : null;

        String venue = extractVenue(item);

        OpenAlexWorkDto work = new OpenAlexWorkDto(id, doi, title, pubYear, venue);

        // Parse authorships
        JsonNode authorships = item.path("authorships");
        if (authorships.isArray()) {
            for (JsonNode authNode : authorships) {
                JsonNode authorObj = authNode.path("author");
                String authorId = authorObj.path("id").asText(null);
                String displayName = authorObj.path("display_name").asText("");
                if (displayName.isBlank()) {
                    displayName = authNode.path("raw_author_name").asText("");
                }
                String authorOrcid = authorObj.path("orcid").asText(null);

                List<String> rawAffiliations = new ArrayList<>();
                JsonNode rawAffStrings = authNode.path("raw_affiliation_strings");
                if (rawAffStrings.isArray()) {
                    for (JsonNode s : rawAffStrings) {
                        String text = s.asText("");
                        if (!text.isBlank() && !rawAffiliations.contains(text.trim())) {
                            rawAffiliations.add(text.trim());
                        }
                    }
                }
                if (rawAffiliations.isEmpty() && authNode.hasNonNull("raw_affiliation_string")) {
                    String single = authNode.path("raw_affiliation_string").asText("");
                    if (!single.isBlank()) rawAffiliations.add(single.trim());
                }
                // Also check affiliations[] objects
                JsonNode affilsArray = authNode.path("affiliations");
                if (affilsArray.isArray()) {
                    for (JsonNode affObj : affilsArray) {
                        String s = affObj.path("raw_affiliation_string").asText("");
                        if (!s.isBlank() && !rawAffiliations.contains(s.trim())) {
                            rawAffiliations.add(s.trim());
                        }
                    }
                }

                List<String> instIds = new ArrayList<>();
                JsonNode institutions = authNode.path("institutions");
                if (institutions.isArray()) {
                    for (JsonNode inst : institutions) {
                        String instId = inst.path("id").asText(null);
                        if (instId != null && !instId.isBlank() && !instIds.contains(instId.trim())) {
                            instIds.add(instId.trim());
                        }
                    }
                }

                work.getAuthors().add(new AuthorDto(authorId, displayName, authorOrcid, rawAffiliations, instIds));
            }
        }

        // Parse concepts
        JsonNode concepts = item.path("concepts");
        if (concepts.isArray()) {
            for (JsonNode c : concepts) {
                String cName = c.path("display_name").asText("");
                if (!cName.isBlank() && !work.getConcepts().contains(cName.trim())) {
                    work.getConcepts().add(cName.trim());
                }
            }
        }

        // Parse topics
        JsonNode topics = item.path("topics");
        if (topics.isArray()) {
            for (JsonNode t : topics) {
                String tName = t.path("display_name").asText("");
                if (!tName.isBlank() && !work.getTopics().contains(tName.trim())) {
                    work.getTopics().add(tName.trim());
                }
            }
        }

        return work;
    }

    private String extractVenue(JsonNode item) {
        JsonNode primLoc = item.path("primary_location");
        if (!primLoc.isMissingNode()) {
            JsonNode source = primLoc.path("source");
            if (!source.isMissingNode() && source.hasNonNull("display_name")) {
                String name = source.path("display_name").asText("");
                if (!name.isBlank()) return name;
            }
            if (primLoc.hasNonNull("source_name")) {
                String name = primLoc.path("source_name").asText("");
                if (!name.isBlank()) return name;
            }
        }

        JsonNode hostVenue = item.path("host_venue");
        if (!hostVenue.isMissingNode() && hostVenue.hasNonNull("display_name")) {
            String name = hostVenue.path("display_name").asText("");
            if (!name.isBlank()) return name;
        }

        JsonNode biblio = item.path("biblio");
        if (!biblio.isMissingNode() && biblio.hasNonNull("journal")) {
            String name = biblio.path("journal").asText("");
            if (!name.isBlank()) return name;
        }

        return null;
    }

    /**
     * If the work has no raw affiliations across authors, tries to enrich them via Crossref.
     */
    private void applyCrossrefFallbackIfNeeded(OpenAlexWorkDto work) {
        if (!crossrefFallbackEnabled || crossrefEnricher == null) {
            return;
        }
        if (work.getDoi() == null || work.getDoi().isBlank()) {
            return;
        }
        if (!work.getAllRawAffiliations().isEmpty()) {
            return;
        }

        Optional<List<String>> enriched = crossrefEnricher.enrichAffiliationsFromCrossref(work.getDoi());
        if (enriched.isPresent() && !enriched.get().isEmpty()) {
            List<String> affiliations = enriched.get();
            if (work.getAuthors().isEmpty()) {
                work.getAuthors().add(new AuthorDto(null, "Unknown Author", null, new ArrayList<>(affiliations), new ArrayList<>()));
            } else {
                for (AuthorDto author : work.getAuthors()) {
                    if (author.getRawAffiliationStrings().isEmpty()) {
                        author.setRawAffiliationStrings(new ArrayList<>(affiliations));
                    }
                }
            }
        }
    }

    private String cleanOrcid(String orcid) {
        if (orcid == null || orcid.isBlank()) {
            return null;
        }
        String clean = orcid.trim();
        clean = clean.replaceAll("^(https?://)?(dx\\.)?orcid\\.org/", "");
        return clean.isBlank() ? null : clean;
    }
}
