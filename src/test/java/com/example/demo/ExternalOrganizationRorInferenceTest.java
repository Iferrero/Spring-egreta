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
                .andExpect(jsonPath("$.countryReason", containsString("ror-match")))
                .andExpect(jsonPath("$.suggestedFunding").value("Publica"))
                .andExpect(jsonPath("$.fundingConfidence").value(greaterThanOrEqualTo(0.90)));
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
                .andExpect(jsonPath("$.fundingConfidence").value(greaterThanOrEqualTo(0.90)));
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
    public void testClearCacheEndpoint() throws Exception {
        mockMvc.perform(post("/api/external-organizations/stats/clear-cache")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.clearedEntries").exists());
    }
}
