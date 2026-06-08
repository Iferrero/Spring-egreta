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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
public class ApiKeySecurityIntegrationTest {

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
    public void whenRequestWithoutApiKey_shouldFallbackToSecurityOrUnauthorized() throws Exception {
        mockMvc.perform(get("/api/journals/jcr-by-issn")
                .param("issn", "1234-5678")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void whenRequestWithInvalidApiKey_shouldReturn401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/journals/jcr-by-issn")
                .param("issn", "1234-5678")
                .header("api-key", "invalid-key")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid API Key"));
    }

    @Test
    public void whenRequestWithValidApiKey_shouldSucceed() throws Exception {
        mockMvc.perform(get("/api/journals/jcr-by-issn")
                .param("issn", "1234-5678")
                .header("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void whenRequestWithValidXApiKey_shouldSucceed() throws Exception {
        mockMvc.perform(get("/api/journals/jcr-by-issn")
                .param("issn", "1234-5678")
                .header("X-API-KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    public void whenRequestPublicEndpoint_shouldSucceedWithoutApiKey() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
