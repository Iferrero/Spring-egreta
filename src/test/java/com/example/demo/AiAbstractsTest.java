package com.example.demo;

import com.example.demo.service.ResearchoutputsAiAbstractsService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class AiAbstractsTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ResearchoutputsAiAbstractsService aiAbstractsService;

    @Autowired
    private com.example.demo.service.PublicacionAbstractsService publicacionAbstractsService;

    @Test
    public void testDiscrepanciesAndIndexes() {
        // Verify index creation does not throw IndexKeySpecsConflict
        assertDoesNotThrow(() -> aiAbstractsService.ensureDiscrepancyIndexes());

        // Populate single discrepancy for test
        String testUuid = "458d485f-e48d-4712-9549-389b8b8adac0";
        var singleDisc = aiAbstractsService.populateSingleDiscrepancy(testUuid);
        assertTrue(singleDisc.isPresent());
        assertTrue(aiAbstractsService.hasDiscrepanciesPopulated());

        // Test querying discrepancies stats from ResearchoutputsAiAbstracts
        var aiStats = aiAbstractsService.getDiscrepanciesStatsFromAi(null, null, null, "publicationYear", 50, 1.5, 2);
        assertNotNull(aiStats);
        System.out.println("AI Stats: " + aiStats);
        assertEquals("ResearchoutputsAiAbstracts", aiStats.get("source"));

        // Test querying discrepancies list from ResearchoutputsAiAbstracts
        var aiList = aiAbstractsService.getDiscrepanciesListFromAi(
                null, null, null, "publicationYear", 50, 1.5, 2,
                null, null, null, null, 0, 10, "diffWords", "desc");
        assertNotNull(aiList);
        System.out.println("AI List total: " + aiList.get("totalElements"));
        assertEquals("ResearchoutputsAiAbstracts", aiList.get("source"));
    }

    @Test
    public void testAiAbstractsServiceMethods() {
        assertNotNull(aiAbstractsService);

        // 1. Filter Options
        Map<String, Object> filters = aiAbstractsService.getFilterOptions();
        assertNotNull(filters);
        assertTrue(filters.containsKey("models"));
        assertTrue(filters.containsKey("sourceLanguages"));
        System.out.println("Available models: " + filters.get("models"));
        System.out.println("Available source languages: " + filters.get("sourceLanguages"));

        // 2. Stats
        Map<String, Object> stats = aiAbstractsService.getAiStats(null, null, null, null, "publicationYear");
        assertNotNull(stats);
        long total = (Long) stats.get("total");
        assertTrue(total > 0, "Total should be > 0");
        System.out.println("Total AI abstracts: " + total);
        System.out.println("Synced: " + stats.get("synced") + " (" + stats.get("pctSynced") + "%)");
        System.out.println("Avg Latency (ms): " + stats.get("avgLatencyMs"));

        // 3. Years
        List<Map<String, Object>> years = aiAbstractsService.getAiAbstractsByYear(null, null, "publicationYear");
        assertNotNull(years);
        assertFalse(years.isEmpty());
        System.out.println("First year entry: " + years.get(0));

        // 4. List
        Map<String, Object> list = aiAbstractsService.getAiAbstractsList(
                null, null, null, null, null, null, "publicationYear", 0, 5, "publicationYear", "desc");
        assertNotNull(list);
        assertEquals(5, ((List<?>) list.get("items")).size());

        // 5. Detail
        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem = (Map<String, Object>) ((List<?>) list.get("items")).get(0);
        String uuid = (String) firstItem.get("uuid");
        var detailOpt = aiAbstractsService.getAiAbstractDetail(uuid);
        assertTrue(detailOpt.isPresent());
        System.out.println("Detail for " + uuid + ": " + detailOpt.get().get("title"));

        // 6. Test populateSingleDiscrepancy on real discrepant publication
        String testUuid = "458d485f-e48d-4712-9549-389b8b8adac0";
        var discOpt = aiAbstractsService.populateSingleDiscrepancy(testUuid);
        assertTrue(discOpt.isPresent(), "Discrepancy should be populated");
        Map<String, Object> disc = discOpt.get();
        System.out.println("Populated discrepancy for " + testUuid + ": " + disc);
        assertTrue((int) disc.get("words_en") > 200, "English words should be > 200");
        assertTrue((int) disc.get("diff_words") > 200, "Diff words should be > 200");
        assertEquals("critical", disc.get("severity"), "Severity should be critical");

        // Verify it is readable in getAiAbstractDetail
        var detailWithDisc = aiAbstractsService.getAiAbstractDetail(testUuid);
        assertTrue(detailWithDisc.isPresent());
        assertNotNull(detailWithDisc.get().get("discrepancyAnalysis"), "Detail should include discrepancyAnalysis");
    }
}
