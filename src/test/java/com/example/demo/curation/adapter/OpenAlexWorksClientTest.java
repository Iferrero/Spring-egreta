package com.example.demo.curation.adapter;

import com.example.demo.curation.model.OpenAlexWorkDto;
import com.example.demo.curation.model.OpenAlexWorkDto.AuthorDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenAlexWorksClientTest {

    private HttpClient mockHttpClient;
    private HttpResponse<String> mockResponse;
    private CrossrefAffiliationEnricher crossrefEnricher;
    private OpenAlexWorksClient openAlexWorksClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        mockResponse = mock(HttpResponse.class);
        objectMapper = new ObjectMapper();

        crossrefEnricher = new CrossrefAffiliationEnricher(mockHttpClient, objectMapper);
        openAlexWorksClient = new OpenAlexWorksClient(mockHttpClient, objectMapper, crossrefEnricher);
    }

    @Test
    @DisplayName("Successfully deserializes OpenAlex works JSON including authors, affiliations, concepts and topics")
    @SuppressWarnings("unchecked")
    void testFetchWorksByOrcid_SuccessfulDeserialization() throws Exception {
        String json = """
        {
          "meta": {
            "count": 1,
            "next_cursor": null
          },
          "results": [
            {
              "id": "https://openalex.org/W2016949000",
              "doi": "https://doi.org/10.1111/j.1529-8817.2012.01224.x",
              "title": "EFFECTS OF CLIMATE CHANGE ON GLOBAL SEAWEED COMMUNITIES",
              "publication_year": 2012,
              "primary_location": {
                "source": {
                  "display_name": "Journal of Phycology"
                }
              },
              "authorships": [
                {
                  "author": {
                    "id": "https://openalex.org/A5032113172",
                    "display_name": "Christopher D. G. Harley",
                    "orcid": "https://orcid.org/0000-0003-4099-943X"
                  },
                  "institutions": [
                    {
                      "id": "https://openalex.org/I141945490",
                      "display_name": "University of British Columbia"
                    }
                  ],
                  "raw_affiliation_strings": [
                    "Department of Zoology, University of British Columbia, Vancouver, BC, Canada"
                  ]
                },
                {
                  "author": {
                    "id": "https://openalex.org/A5088060456",
                    "display_name": "Kathryn M. Anderson",
                    "orcid": "https://orcid.org/0000-0001-8107-4594"
                  },
                  "institutions": [],
                  "raw_affiliation_strings": []
                }
              ],
              "concepts": [
                { "id": "https://openalex.org/C1", "display_name": "Ecology" },
                { "id": "https://openalex.org/C2", "display_name": "Biology" }
              ],
              "topics": [
                { "id": "https://openalex.org/T1", "display_name": "Marine Biology and Ecology Research" }
              ]
            }
          ]
        }
        """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        List<OpenAlexWorkDto> works = openAlexWorksClient.fetchWorksByAuthor("0000-0003-4099-943X", "Christopher Harley", true);

        assertThat(works).hasSize(1);
        OpenAlexWorkDto work = works.get(0);
        assertThat(work.getId()).isEqualTo("https://openalex.org/W2016949000");
        assertThat(work.getDoi()).isEqualTo("https://doi.org/10.1111/j.1529-8817.2012.01224.x");
        assertThat(work.getNormalizedDoi()).isEqualTo("10.1111/j.1529-8817.2012.01224.x");
        assertThat(work.getTitle()).isEqualTo("EFFECTS OF CLIMATE CHANGE ON GLOBAL SEAWEED COMMUNITIES");
        assertThat(work.getPublicationYear()).isEqualTo(2012);
        assertThat(work.getVenue()).isEqualTo("Journal of Phycology");
        assertThat(work.getJournal()).isEqualTo("Journal of Phycology");

        // Authors verification
        assertThat(work.getAuthors()).hasSize(2);
        AuthorDto author1 = work.getAuthors().get(0);
        assertThat(author1.getAuthorId()).isEqualTo("https://openalex.org/A5032113172");
        assertThat(author1.getDisplayName()).isEqualTo("Christopher D. G. Harley");
        assertThat(author1.getOrcid()).isEqualTo("https://orcid.org/0000-0003-4099-943X");
        assertThat(author1.getRawAffiliationStrings()).containsExactly("Department of Zoology, University of British Columbia, Vancouver, BC, Canada");
        assertThat(author1.getInstitutionIds()).containsExactly("https://openalex.org/I141945490");

        AuthorDto author2 = work.getAuthors().get(1);
        assertThat(author2.getDisplayName()).isEqualTo("Kathryn M. Anderson");
        assertThat(author2.getOrcid()).isEqualTo("https://orcid.org/0000-0001-8107-4594");
        assertThat(author2.getRawAffiliationStrings()).isEmpty();

        // Concepts & topics
        assertThat(work.getConcepts()).containsExactly("Ecology", "Biology");
        assertThat(work.getTopics()).containsExactly("Marine Biology and Ecology Research");
    }

    @Test
    @DisplayName("Sends polite pool User-Agent header with mailto and api_key header when configured")
    @SuppressWarnings("unchecked")
    void testPolitePoolUserAgentHeader() throws Exception {
        String json = "{\"meta\": {\"count\": 0, \"next_cursor\": null}, \"results\": []}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        openAlexWorksClient.setMailto("bibliometria@uab.cat");
        openAlexWorksClient.setApiKey("test-openalex-key");

        openAlexWorksClient.fetchWorksByAuthor("0000-0001-2345-6789", null, true);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());

        HttpRequest request = captor.getValue();
        assertThat(request.headers().firstValue("User-Agent"))
                .isPresent()
                .hasValueSatisfying(ua -> assertThat(ua).contains("mailto:bibliometria@uab.cat"));
        assertThat(request.headers().firstValue("api_key"))
                .isPresent()
                .hasValue("test-openalex-key");
    }

    @Test
    @DisplayName("Supports cursor pagination across multiple pages")
    @SuppressWarnings("unchecked")
    void testCursorPagination() throws Exception {
        String page1Json = """
        {
          "meta": { "count": 2, "next_cursor": "cursor_page_2" },
          "results": [
            { "id": "https://openalex.org/W1", "title": "Paper One", "publication_year": 2021 }
          ]
        }
        """;

        String page2Json = """
        {
          "meta": { "count": 2, "next_cursor": null },
          "results": [
            { "id": "https://openalex.org/W2", "title": "Paper Two", "publication_year": 2022 }
          ]
        }
        """;

        HttpResponse<String> resp1 = mock(HttpResponse.class);
        when(resp1.statusCode()).thenReturn(200);
        when(resp1.body()).thenReturn(page1Json);

        HttpResponse<String> resp2 = mock(HttpResponse.class);
        when(resp2.statusCode()).thenReturn(200);
        when(resp2.body()).thenReturn(page2Json);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        List<OpenAlexWorkDto> works = openAlexWorksClient.fetchWorksByAuthor("0000-0001-2345-6789", null, true);

        assertThat(works).hasSize(2);
        assertThat(works.get(0).getTitle()).isEqualTo("Paper One");
        assertThat(works.get(1).getTitle()).isEqualTo("Paper Two");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient, times(2)).send(captor.capture(), any());

        List<HttpRequest> capturedRequests = captor.getAllValues();
        URI uri1 = capturedRequests.get(0).uri();
        URI uri2 = capturedRequests.get(1).uri();

        assertThat(uri1.toString()).contains("cursor=*");
        assertThat(uri2.toString()).contains("cursor=cursor_page_2");
    }

    @Test
    @DisplayName("Strict ORCID returns empty when ORCID is missing or blank without HTTP calls")
    void testStrictOrcid_EmptyWhenMissing() throws Exception {
        List<OpenAlexWorkDto> resNull = openAlexWorksClient.fetchWorksByAuthor(null, "John Doe", true);
        List<OpenAlexWorkDto> resBlank = openAlexWorksClient.fetchWorksByAuthor("   ", "John Doe", true);

        assertThat(resNull).isEmpty();
        assertThat(resBlank).isEmpty();
        verifyNoInteractions(mockHttpClient);
    }

    @Test
    @DisplayName("Non-strict ORCID falls back to author name search when ORCID is missing")
    @SuppressWarnings("unchecked")
    void testNonStrictOrcid_FallbackToAuthorSearchWhenNoOrcid() throws Exception {
        String json = """
        {
          "meta": { "count": 1, "next_cursor": null },
          "results": [
            { "id": "https://openalex.org/W100", "title": "Author Search Paper", "publication_year": 2020 }
          ]
        }
        """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(json);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        List<OpenAlexWorkDto> works = openAlexWorksClient.fetchWorksByAuthor(null, "Elena Garcia", false);

        assertThat(works).hasSize(1);
        assertThat(works.get(0).getTitle()).isEqualTo("Author Search Paper");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).contains("Elena");
    }

    @Test
    @DisplayName("Non-strict ORCID falls back to author name when ORCID returns 0 works")
    @SuppressWarnings("unchecked")
    void testNonStrictOrcid_FallbackToAuthorSearchWhenOrcidYieldsEmpty() throws Exception {
        String emptyJson = "{\"meta\": {\"count\": 0, \"next_cursor\": null}, \"results\": []}";
        String authorJson = """
        {
          "meta": { "count": 1, "next_cursor": null },
          "results": [
            { "id": "https://openalex.org/W200", "title": "Found by Name", "publication_year": 2023 }
          ]
        }
        """;

        HttpResponse<String> resp1 = mock(HttpResponse.class);
        when(resp1.statusCode()).thenReturn(200);
        when(resp1.body()).thenReturn(emptyJson);

        HttpResponse<String> resp2 = mock(HttpResponse.class);
        when(resp2.statusCode()).thenReturn(200);
        when(resp2.body()).thenReturn(authorJson);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp1)
                .thenReturn(resp2);

        List<OpenAlexWorkDto> works = openAlexWorksClient.fetchWorksByAuthor("0000-0001-9999-9999", "Elena Garcia", false);

        assertThat(works).hasSize(1);
        assertThat(works.get(0).getTitle()).isEqualTo("Found by Name");
        verify(mockHttpClient, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("Gracefully handles HTTP errors and empty responses without throwing exceptions")
    @SuppressWarnings("unchecked")
    void testErrorHandling() throws Exception {
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        List<OpenAlexWorkDto> works500 = openAlexWorksClient.fetchWorksByAuthor("0000-0001-2345-6789", null, true);
        assertThat(works500).isEmpty();

        // Network error simulation
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection reset"));

        List<OpenAlexWorkDto> worksNetError = openAlexWorksClient.fetchWorksByAuthor("0000-0001-2345-6789", null, true);
        assertThat(worksNetError).isEmpty();
    }

    @Test
    @DisplayName("CrossrefAffiliationEnricher successfully extracts author affiliation strings from Crossref API")
    @SuppressWarnings("unchecked")
    void testCrossrefAffiliationEnricher_SuccessfulExtraction() throws Exception {
        String crossrefJson = """
        {
          "status": "ok",
          "message-type": "work",
          "message": {
            "title": ["Sample Paper"],
            "author": [
              {
                "given": "Maria",
                "family": "Sola",
                "affiliation": [
                  { "name": "Department of Physics, Universitat Autònoma de Barcelona, 08193 Bellaterra, Spain" }
                ]
              },
              {
                "given": "Joan",
                "family": "Puig",
                "affiliation": [
                  { "name": "Institut de Fisica d'Altes Energies (IFAE), The Barcelona Institute of Science and Technology, Bellaterra, Spain" }
                ]
              }
            ]
          }
        }
        """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(crossrefJson);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        Optional<List<String>> result = crossrefEnricher.enrichAffiliationsFromCrossref("10.1000/182");

        assertThat(result).isPresent();
        assertThat(result.get()).contains(
                "Department of Physics, Universitat Autònoma de Barcelona, 08193 Bellaterra, Spain",
                "Institut de Fisica d'Altes Energies (IFAE), The Barcelona Institute of Science and Technology, Bellaterra, Spain"
        );

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).contains("10.1000/182");
        assertThat(request.headers().firstValue("User-Agent"))
                .isPresent()
                .hasValueSatisfying(ua -> assertThat(ua).contains("mailto:"));
    }

    @Test
    @DisplayName("CrossrefAffiliationEnricher returns empty for 404, invalid DOI, or missing author affiliation")
    @SuppressWarnings("unchecked")
    void testCrossrefAffiliationEnricher_ErrorAndEmpty() throws Exception {
        // Blank DOI
        assertThat(crossrefEnricher.enrichAffiliationsFromCrossref("   ")).isEmpty();
        assertThat(crossrefEnricher.enrichAffiliationsFromCrossref(null)).isEmpty();
        verifyNoInteractions(mockHttpClient);

        // 404 response
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        assertThat(crossrefEnricher.enrichAffiliationsFromCrossref("10.1000/not-found")).isEmpty();

        // 200 but author has no affiliation
        String noAffilJson = "{\"status\":\"ok\",\"message\":{\"author\":[{\"given\":\"A\",\"affiliation\":[]}]}}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(noAffilJson);

        assertThat(crossrefEnricher.enrichAffiliationsFromCrossref("10.1000/no-affil")).isEmpty();
    }

    @Test
    @DisplayName("OpenAlexWorksClient automatically falls back to Crossref when OpenAlex affiliations are missing")
    @SuppressWarnings("unchecked")
    void testOpenAlexWorksClient_CrossrefFallbackIntegration() throws Exception {
        String openAlexJson = """
        {
          "meta": { "count": 1, "next_cursor": null },
          "results": [
            {
              "id": "https://openalex.org/W999",
              "doi": "https://doi.org/10.1016/j.cell.2023.01.001",
              "title": "A Cell Study Without OpenAlex Affiliations",
              "publication_year": 2023,
              "authorships": [
                {
                  "author": {
                    "id": "https://openalex.org/A111",
                    "display_name": "Anna Gomez",
                    "orcid": "https://orcid.org/0000-0002-1111-2222"
                  },
                  "institutions": [],
                  "raw_affiliation_strings": []
                }
              ]
            }
          ]
        }
        """;

        String crossrefJson = """
        {
          "status": "ok",
          "message": {
            "author": [
              {
                "given": "Anna",
                "family": "Gomez",
                "affiliation": [
                  { "name": "Departament de Bioquimica, Universitat Autonoma de Barcelona, Spain" }
                ]
              }
            ]
          }
        }
        """;

        HttpResponse<String> openAlexResp = mock(HttpResponse.class);
        when(openAlexResp.statusCode()).thenReturn(200);
        when(openAlexResp.body()).thenReturn(openAlexJson);

        HttpResponse<String> crossrefResp = mock(HttpResponse.class);
        when(crossrefResp.statusCode()).thenReturn(200);
        when(crossrefResp.body()).thenReturn(crossrefJson);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(openAlexResp)
                .thenReturn(crossrefResp);

        List<OpenAlexWorkDto> works = openAlexWorksClient.fetchWorksByAuthor("0000-0002-1111-2222", null, true);

        assertThat(works).hasSize(1);
        OpenAlexWorkDto work = works.get(0);
        assertThat(work.getAuthors().get(0).getRawAffiliationStrings())
                .containsExactly("Departament de Bioquimica, Universitat Autonoma de Barcelona, Spain");
        assertThat(work.getAllRawAffiliations())
                .containsExactly("Departament de Bioquimica, Universitat Autonoma de Barcelona, Spain");
    }

    @Test
    @DisplayName("OpenAlexWorkDto equals and hashCode consistency for IDs, DOIs, and null safety")
    void testWorkDto_EqualsAndHashCodeContract() {
        OpenAlexWorkDto w1 = new OpenAlexWorkDto("https://openalex.org/W1", "10.1000/1", "Title 1", 2020, "V1");
        OpenAlexWorkDto w1Copy = new OpenAlexWorkDto("https://openalex.org/W1", "10.1000/different", "Title 1 Copy", 2020, "V1");
        OpenAlexWorkDto w2 = new OpenAlexWorkDto("https://openalex.org/W2", "10.1000/1", "Title 2", 2021, "V2");

        // Same ID -> equal and matching hashCode
        assertThat(w1).isEqualTo(w1Copy);
        assertThat(w1.hashCode()).isEqualTo(w1Copy.hashCode());

        // Different ID -> not equal
        assertThat(w1).isNotEqualTo(w2);

        // Null IDs, same normalized DOI -> equal and matching hashCode
        OpenAlexWorkDto noId1 = new OpenAlexWorkDto(null, "https://doi.org/10.1000/abc", "Title A", 2022, "V");
        OpenAlexWorkDto noId2 = new OpenAlexWorkDto(null, "10.1000/abc", "Title A", 2022, "V");
        assertThat(noId1).isEqualTo(noId2);
        assertThat(noId1.hashCode()).isEqualTo(noId2.hashCode());

        // Null IDs, different DOIs -> not equal
        OpenAlexWorkDto noIdDiff = new OpenAlexWorkDto(null, "10.1000/xyz", "Title B", 2022, "V");
        assertThat(noId1).isNotEqualTo(noIdDiff);

        // Null ID and null DOI -> not equal to each other (no null collision)
        OpenAlexWorkDto blank1 = new OpenAlexWorkDto(null, null, "Blank 1", 2020, "V");
        OpenAlexWorkDto blank2 = new OpenAlexWorkDto(null, null, "Blank 2", 2020, "V");
        assertThat(blank1).isNotEqualTo(blank2);

        // Reflexivity, null safety, type safety
        assertThat(w1).isEqualTo(w1);
        assertThat(w1).isNotEqualTo(null);
        assertThat(w1).isNotEqualTo("some string");
    }

    @Test
    @DisplayName("CrossrefAffiliationEnricher restores thread interrupt flag on InterruptedException")
    @SuppressWarnings("unchecked")
    void testCrossrefAffiliationEnricher_InterruptedExceptionRestoresFlag() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("Interrupted"));

        Optional<List<String>> result = crossrefEnricher.enrichAffiliationsFromCrossref("10.1000/182");

        assertThat(result).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // Clear interrupt status so subsequent tests/teardown are not affected
        Thread.interrupted();
    }
}
