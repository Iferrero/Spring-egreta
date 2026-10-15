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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class ExternalOrganizationRorMongoTest {

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
    public void testSuggestNameWithMongoRorCollection() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-name")
                .param("name", "Universitat Autònoma de Barcelona")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedName").isNotEmpty())
                .andExpect(jsonPath("$.similarity").value(greaterThanOrEqualTo(0.85)))
                .andExpect(jsonPath("$.rorId").value(containsString("ror.org/052g8jq94")))
                .andExpect(jsonPath("$.country").value(not(emptyOrNullString())));
    }

    @Test
    public void testSuggestNameWithCsic() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-name")
                .param("name", "Consejo Superior de Investigaciones Científicas")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedName").isNotEmpty())
                .andExpect(jsonPath("$.similarity").value(greaterThanOrEqualTo(0.90)))
                .andExpect(jsonPath("$.rorId").value(containsString("ror.org/02gfc7t72")));
    }

    @Test
    public void testNameProposalsEndpointUsesMongoRor() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/name-proposals")
                .param("onlyWithoutRor", "true")
                .param("limit", "15")
                .param("minSimilarity", "0.75")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    public void testPreciseTypeInference() throws Exception {
        // Test 1: Department
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Department of Neurological Surgery")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(result -> System.out.println("DEPARTMENT RESULT: " + result.getResponse().getContentAsString()));

        // Test 2: CSIC
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Consejo Superior de Investigaciones Científicas")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(result -> System.out.println("CSIC RESULT: " + result.getResponse().getContentAsString()));

        // Test 3: Akademie (Association)
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Akademie für Fürhrungs-kräfte der Wirstschaft, E.W.")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(result -> System.out.println("AKADEMIE RESULT: " + result.getResponse().getContentAsString()));

        // Test 4: Hospital
        mockMvc.perform(get("/api/external-organizations/stats/suggest-metadata")
                .param("name", "Hospital Universitari Vall d'Hebron")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andDo(result -> System.out.println("HOSPITAL RESULT: " + result.getResponse().getContentAsString()));
    }

    @Test
    public void testOpenAlexAndRorNameProposals() throws Exception {
        mockMvc.perform(get("/api/external-organizations/stats/suggest-name")
                .param("name", "Max Planck Institute for Biochemistry")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedName").isNotEmpty())
                .andExpect(jsonPath("$.similarity").value(greaterThanOrEqualTo(0.70)))
                .andDo(result -> System.out.println("MAX PLANCK NAME PROPOSAL: " + result.getResponse().getContentAsString()));
    }
}
