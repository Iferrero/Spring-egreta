package com.example.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class ExternalOrganizationRorInferenceTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    public void testRorInferenceForKnownUniversity() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Universitat Autònoma de Barcelona")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedCountry").isNotEmpty())
                .andExpect(jsonPath("$.countryConfidence").value(greaterThanOrEqualTo(0.85)))
                .andExpect(jsonPath("$.countryReason").value(org.hamcrest.Matchers.anyOf(containsString("ror-match"), containsString("alias-match"))))
                .andExpect(jsonPath("$.suggestedFunding").value("Publica"))
                .andExpect(jsonPath("$.fundingConfidence").value(greaterThanOrEqualTo(0.85)));
    }

    @Test
    public void testRorInferenceForOxford() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "University of Oxford")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedCountry").isNotEmpty())
                .andExpect(jsonPath("$.countryConfidence").value(greaterThanOrEqualTo(0.85)))
                .andExpect(jsonPath("$.suggestedFunding").value("Publica"))
                .andExpect(jsonPath("$.fundingConfidence").value(greaterThanOrEqualTo(0.85)));
    }

    @Test
    public void testDepartmentNameDoesNotMatchFalsePositive() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Dept Gastrointestinal Surg")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Must not falsely match Japan DEPT Corporation
                .andExpect(jsonPath("$.countryReason").value(org.hamcrest.Matchers.not(containsString("ror-match:https://ror.org/05kyy0717"))));
    }

    @Test
    public void testOpenAlexAuthorInferenceWhenAssociatedPersonMatchesInstitution() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Hospital Clinic de Barcelona")
                .param("associatedPerson", "Elías Campo (Persona externa)")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedCountry").isNotEmpty())
                .andExpect(jsonPath("$.countryConfidence").value(greaterThanOrEqualTo(0.85)));
    }

    @Test
    public void testOpenAlexWorksAffiliationForDepartmentAndAuthor() throws Exception {
        var mvcResult = mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Clinical Investigation and Acces to Bioresources Department")
                .param("associatedPerson", "Marie Noelle Ungeheuer (Persona externa)")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String json = mvcResult.getResponse().getContentAsString();
        if (json.contains("openalex")) {
            org.hamcrest.MatcherAssert.assertThat(json, org.hamcrest.Matchers.anyOf(
                    containsString("França"), containsString("Francia"), containsString("France")
            ));
        }
    }

    @Test
    public void testOpenAlexRejectsUnrelatedInstitutionName() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Random Unrelated Company XYZ 987")
                .param("associatedPerson", "Albert Einstein (Persona externa)")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Must not falsely match Einstein's institutions for an unrelated company
                .andExpect(jsonPath("$.countryReason").value(org.hamcrest.Matchers.not(containsString("openalex"))));
    }

    @Test
    public void testClearCacheEndpoint() throws Exception {
        mockMvc.perform(post("/api/external-organizations/stats/clear-cache")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.clearedEntries.openAlexInferences").exists())
                .andExpect(jsonPath("$.clearedEntries.crossrefInferences").exists())
                .andExpect(jsonPath("$.clearedEntries.rorInferences").exists());
    }

    @Test
    public void testRorNameProposal() throws Exception {
        var mvcResult = mockMvc.perform(get("/api/external-organizations/stats/suggest-name")
                .param("name", "Harvard University, Inc.")
                .characterEncoding("UTF-8")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String json = mvcResult.getResponse().getContentAsString();
        if (json.contains("\"suggestedName\"") && !json.contains("\"suggestedName\":\"\"")) {
            org.hamcrest.MatcherAssert.assertThat(json, containsString("Harvard University"));
            org.hamcrest.MatcherAssert.assertThat(json, containsString("rorId"));
        }
    }

    @Test
    public void testNameProposalsEndpointWithFilters() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/name-proposals")
                .param("onlyWithoutRor", "true")
                .param("onlyWithoutCountry", "true")
                .param("limit", "10")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items").isArray());
    }
}
