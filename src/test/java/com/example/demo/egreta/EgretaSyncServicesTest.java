package com.example.demo.egreta;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EgretaSyncServicesTest {

    @Test
    void testNewSyncServicesProperties() {
        EthicalReviewsSyncService ethicalReviews = new EthicalReviewsSyncService();
        assertEquals("EthicalReviews", ethicalReviews.collectionName());
        assertEquals("ethical-reviews", ethicalReviews.getEndpoint());

        ImpactsSyncService impacts = new ImpactsSyncService();
        assertEquals("Impacts", impacts.collectionName());
        assertEquals("impacts", impacts.getEndpoint());

        MilestonesSyncService milestones = new MilestonesSyncService();
        assertEquals("Milestones", milestones.collectionName());
        assertEquals("milestones", milestones.getEndpoint());

        PersonExpertisesSyncService personExpertises = new PersonExpertisesSyncService();
        assertEquals("PersonExpertises", personExpertises.collectionName());
        assertEquals("person-expertises", personExpertises.getEndpoint());

        ThesauriSyncService thesauri = new ThesauriSyncService();
        assertEquals("Thesauri", thesauri.collectionName());
        assertEquals("thesauri", thesauri.getEndpoint());

        ClassificationschemesSyncService classificationSchemes = new ClassificationschemesSyncService();
        assertEquals("Classificationschemes", classificationSchemes.collectionName());
        assertEquals("classification-schemes", classificationSchemes.getEndpoint());
    }

    @Test
    void testClassificationSchemesWebClientAcceptHeader() {
        org.springframework.web.reactive.function.client.WebClient client = 
            org.springframework.web.reactive.function.client.WebClient.builder()
                .defaultHeader(org.springframework.http.HttpHeaders.ACCEPT, org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
                .build();

        var res = client.get()
                .uri("https://egreta.uab.cat/ws/api/classification-schemes?size=1")
                .header("api-key", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(java.util.Map.class)
                .block();

        assertNotNull(res);
        assertTrue(res.getStatusCode().is2xxSuccessful());
        java.util.Map body = res.getBody();
        assertNotNull(body);
        assertEquals(377, ((Number) body.get("count")).intValue());
    }

    @Test
    void testAdminPageCategoriesAndCollections() throws IOException {
        Path adminPath = Path.of("src/main/resources/static/admin/index.html");
        assertTrue(Files.exists(adminPath), "admin/index.html must exist");

        String content = Files.readString(adminPath);

        // Verify categories
        assertTrue(content.contains("Personal i Organitzacions"), "Should have 'Personal i Organitzacions' category");
        assertTrue(content.contains("Producció Científica i Contingut"), "Should have 'Producció Científica i Contingut' category");
        assertTrue(content.contains("Finançament i Projectes"), "Should have 'Finançament i Projectes' category");
        assertTrue(content.contains("Activitats i Reconeixements"), "Should have 'Activitats i Reconeixements' category");
        assertTrue(content.contains("Classificacions i Coneixement"), "Should have 'Classificacions i Coneixement' category");

        // Verify newly added collections
        List<String> newCollections = List.of(
                "EthicalReviews",
                "Impacts",
                "Milestones",
                "PersonExpertises",
                "Thesauri"
        );
        for (String col : newCollections) {
            assertTrue(content.contains("data-collection=\"" + col + "\""), "Admin UI must contain button for " + col);
        }

        // Verify excluded collections are NOT in the admin UI
        List<String> excludedCollections = List.of(
                "JCR",
                "ExternalOrganizationsDifferent",
                "PersoPublicationCandidates",
                "ResearchoutputsAiAbstracts",
                "ResearchoutputsCorrections"
        );
        for (String col : excludedCollections) {
            assertFalse(content.contains("data-collection=\"" + col + "\""), "Admin UI must NOT contain button for " + col);
        }
    }
}
