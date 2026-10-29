package com.example.demo;

import com.example.demo.controller.ScopusJournalsController;
import com.example.demo.service.ScopusJournalsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ScopusJournalsEndpointTest {

    @Autowired(required = false)
    private ScopusJournalsController controller;

    @Autowired(required = false)
    private ScopusJournalsService service;

    @Test
    public void testBeansInjected() {
        assertNotNull(controller, "ScopusJournalsController should be registered");
        assertNotNull(service, "ScopusJournalsService should be registered");
    }

    @Test
    public void testGetJournalsStats() {
        assertNotNull(controller, "Controller must not be null");

        Map<String, Object> response = controller.getJournalsStats(false);
        assertNotNull(response, "Response should not be null");
        assertTrue(response.containsKey("journals"), "Response should contain 'journals'");
        assertTrue(response.containsKey("totalScopusJournals"), "Response should contain 'totalScopusJournals'");
        assertTrue(response.containsKey("totalArticles"), "Response should contain 'totalArticles'");
        assertTrue(response.containsKey("totalWithScopusId"), "Response should contain 'totalWithScopusId'");
        assertTrue(response.containsKey("totalWithoutScopusId"), "Response should contain 'totalWithoutScopusId'");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> journals = (List<Map<String, Object>>) response.get("journals");
        assertFalse(journals.isEmpty(), "Journals list should not be empty");

        // First journal in descending order should have totalArticles > 0
        Map<String, Object> topJournal = journals.get(0);
        assertTrue((int) topJournal.get("totalArticles") > 0, "Top journal must have articles");
        assertNotNull(topJournal.get("uuid"), "Journal UUID must not be null");
        assertNotNull(topJournal.get("title"), "Journal title must not be null");
        assertTrue(topJournal.containsKey("withScopusId"), "Journal must have withScopusId");
        assertTrue(topJournal.containsKey("withoutScopusId"), "Journal must have withoutScopusId");
    }

    @Test
    public void testGetJournalArticlesDetail() {
        assertNotNull(controller, "Controller must not be null");

        // PLoS ONE UUID: 18cc4213-887e-4512-851e-b0cdca26d835
        String plosOneUuid = "18cc4213-887e-4512-851e-b0cdca26d835";
        Map<String, Object> detail = controller.getArticlesByJournal(plosOneUuid, 10, 0, null, null);

        assertNotNull(detail, "Detail response should not be null");
        assertTrue(detail.containsKey("journal"), "Detail should contain 'journal'");
        assertTrue(detail.containsKey("articles"), "Detail should contain 'articles'");
        assertTrue(detail.containsKey("total"), "Detail should contain 'total'");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> articles = (List<Map<String, Object>>) detail.get("articles");
        assertFalse(articles.isEmpty(), "Articles list should not be empty for PLoS ONE");

        Map<String, Object> art = articles.get(0);
        assertNotNull(art.get("uuid"), "Article uuid should not be null");
        assertNotNull(art.get("title"), "Article title should not be null");
        assertTrue(art.containsKey("hasScopusId"), "Article should have hasScopusId boolean flag");
    }

    @Test
    public void testLookupScopusId() {
        assertNotNull(controller, "Controller must not be null");

        // Use sample article uuid: 8c950aaf-20d8-432b-beb1-7aeb5b6b8cec
        String articleUuid = "8c950aaf-20d8-432b-beb1-7aeb5b6b8cec";
        Map<String, Object> result = controller.lookupScopusId(articleUuid, null, "prod");

        assertNotNull(result, "Result should not be null");
        assertTrue(result.containsKey("searchUrl"), "Result should always provide a Scopus searchUrl");
        String searchUrl = (String) result.get("searchUrl");
        assertTrue(searchUrl.contains("scopus.com"), "searchUrl must point to scopus.com");
    }

    @Test
    public void testAssignScopusIdEnvironmentsMongoDatabases() {
        assertNotNull(controller, "Controller must not be null");

        String articleUuid = "8c950aaf-20d8-432b-beb1-7aeb5b6b8cec";
        Map<String, Object> result = controller.assignScopusId(articleUuid, Map.of(
            "scopusId", "85065919046",
            "env", "test"
        ));

        assertNotNull(result, "Result should not be null");
        assertEquals("test", result.get("targetEnv"), "targetEnv must be test");
        assertEquals("kraken_test", result.get("database"), "database must be kraken_test when targetEnv is test");
        assertEquals(Boolean.TRUE, result.get("mongoUpdated"), "mongoUpdated must be true in kraken_test");

        // Verify stats endpoint env database resolution
        Map<String, Object> statsProd = controller.getJournalsStats(false, "prod");
        assertEquals("kraken", statsProd.get("database"), "Prod stats must be from kraken");

        Map<String, Object> statsTest = controller.getJournalsStats(false, "test");
        assertEquals("kraken_test", statsTest.get("database"), "Test stats must be from kraken_test");
    }

    @Test
    public void testBatchCandidatesCountAndStatus() {
        assertNotNull(controller, "Controller must not be null");

        // 1. Check candidates count for all Scopus journals
        Map<String, Object> stats = controller.getBatchCandidatesCount(null, "prod");
        assertEquals("kraken", stats.get("database"));

        Map<String, Object> statsTest = controller.getBatchCandidatesCount(null, "test");
        assertEquals("kraken_test", statsTest.get("database"));
        assertNotNull(stats, "Batch candidates stats should not be null");
        assertTrue(stats.containsKey("candidatesCount"), "Stats must contain candidatesCount");
        long count = ((Number) stats.get("candidatesCount")).longValue();
        assertTrue(count > 0, "Candidates count with DOI and no Scopus ID should be greater than 0");

        // 2. Check initial batch status
        Map<String, Object> status = controller.getBatchJobStatus();
        assertNotNull(status, "Status should not be null");
        assertTrue(status.containsKey("hasActiveJob"), "Status must contain hasActiveJob");

        // 3. Start a small dry-run batch with limit=1 to test API lookup without modifying any data
        Map<String, Object> started = controller.startBatchJob(Map.of(
            "limit", 1,
            "dryRun", true,
            "env", "test"
        ));
        assertNotNull(started, "Started job status must not be null");
        assertTrue(Boolean.TRUE.equals(started.get("hasActiveJob")), "hasActiveJob should be true");

        // 4. Test stop batch
        Map<String, Object> stopRes = controller.stopBatchJob();
        assertNotNull(stopRes, "Stop response must not be null");
    }

    @Test
    public void testAuthorAndTitleMatchingVerification() {
        // 1. Author matching logic
        List<String> authors = List.of("Escudero", "García-Doncel", "Pérez");
        assertTrue(ScopusJournalsService.matchesAuthor("Escudero J.A.", authors), "Should match exact last name");
        assertTrue(ScopusJournalsService.matchesAuthor("Garcia Doncel M.", authors), "Should match compound name without accents");
        assertFalse(ScopusJournalsService.matchesAuthor("Larivée A.", authors), "Should reject different author");
        assertFalse(ScopusJournalsService.matchesAuthor("Smith J.", authors), "Should reject non-matching author");

        // 2. Title overlap logic
        String t1 = "\"Being and Time\" and the tradition of care for the self";
        String t2 = "Being and time and the ancient philosophical tradition of care for the self: A tense or harmonious relationship?";
        double overlap = ScopusJournalsService.computeTitleOverlap(t1, t2);
        assertTrue(overlap >= 0.70, "Overlap of common key words should be high: " + overlap);

        String completelyDifferent = "Quantum mechanics and solid state physics";
        double zeroOverlap = ScopusJournalsService.computeTitleOverlap(t1, completelyDifferent);
        assertEquals(0.0, zeroOverlap, 0.001, "Completely different titles should have 0 overlap");

        // 3. Year matching logic
        assertTrue(ScopusJournalsService.matchesYear("2017-06-15", 2017), "Exact year should match");
        assertTrue(ScopusJournalsService.matchesYear("2018-01-01", 2017), "Year within +/-1 should match (advance publication)");
        assertFalse(ScopusJournalsService.matchesYear("2024-01-01", 2017), "Year difference > 1 should not match");
    }

    @Test
    public void testLookupMultilingualTitleLeucemiaAgudaLinfoblastica() {
        assertNotNull(service, "ScopusJournalsService must not be null");
        // Article UUID: d5d02003-a115-47d5-8fa9-fb084aa6bc99
        // Title: Leucemia aguda linfoblástica de precursores T: de la biología a la clínica
        // Verify matching logic unit test first (independent of external API rate limits)
        String originalSpanishTitle = "Leucemia aguda linfoblástica de precursores T: de la biología a la clínica";
        String scopusBilingualTitle = "Acute lymphoblastic leukemia of T progenitors: From biology to clinics Leucemia aguda linfoblástica de precursores T: de la biología a la clínica";
        double bilingualScore = ScopusJournalsService.computeTitleOverlap(originalSpanishTitle, scopusBilingualTitle);
        assertTrue(bilingualScore >= 0.70, "Bilingual abstract title must have high overlap: " + bilingualScore);
        assertTrue(ScopusJournalsService.matchesAuthor("Genescà E.", List.of("Genescà", "Ribera")), "Author must match");
        assertTrue(ScopusJournalsService.matchesBibliographic("144", "223-229", "144", "223-229"), "Bibliographic volume and pages must match");

        // Verify lookup method
        Map<String, Object> res = service.lookupScopusId("d5d02003-a115-47d5-8fa9-fb084aa6bc99", null);
        assertNotNull(res, "Lookup response should not be null");
        // If external Elsevier API is not rate-limited (HTTP 429), verify live match
        if (Boolean.TRUE.equals(res.get("found"))) {
            assertEquals("84925144439", res.get("scopusId"), "Should match correct Scopus ID");
            assertNotNull(res.get("scopusCreator"), "Creator should be populated");
            assertTrue(res.get("scopusCreator").toString().contains("Genesc"), "Creator should be Genescà");
        }
    }
}
