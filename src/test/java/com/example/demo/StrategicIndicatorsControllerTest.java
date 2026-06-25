package com.example.demo;

import com.example.demo.controller.StrategicIndicatorsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class StrategicIndicatorsControllerTest {

    @Autowired
    private StrategicIndicatorsController controller;

    @Test
    public void testGetStatsOverall2024() {
        Map<String, Object> stats = controller.getStats(2024, "all");
        assertNotNull(stats);
        assertEquals(2024, stats.get("year"));
        assertEquals("all", stats.get("dept"));
        
        // The exact value for 2024 UAB overall from Excel should be 1132
        assertTrue(stats.containsKey("tramsVius"));
        Object tramsViusVal = stats.get("tramsVius");
        assertNotNull(tramsViusVal);
        long tramsVius = ((Number) tramsViusVal).longValue();
        assertEquals(1132L, tramsVius, "The overall tramsVius for 2024 should be 1132");
    }

    @Test
    public void testGetStatsOverall2025() {
        Map<String, Object> stats = controller.getStats(2025, "all");
        assertNotNull(stats);
        assertEquals(2025, stats.get("year"));
        assertEquals("all", stats.get("dept"));

        // The exact value for 2025 UAB overall from Excel should be 1247
        assertTrue(stats.containsKey("tramsVius"));
        Object tramsViusVal = stats.get("tramsVius");
        assertNotNull(tramsViusVal);
        long tramsVius = ((Number) tramsViusVal).longValue();
        assertEquals(1247L, tramsVius, "The overall tramsVius for 2025 should be 1247");
    }

    @Test
    public void testGetStatsDepartmentScaling() {
        // Test with department UUID: since fake scaling is removed, we expect 0
        Map<String, Object> stats = controller.getStats(2025, "dept-abc-123");
        assertNotNull(stats);
        assertEquals(2025, stats.get("year"));
        assertEquals("dept-abc-123", stats.get("dept"));

        assertTrue(stats.containsKey("tramsVius"));
        Object tramsViusVal = stats.get("tramsVius");
        assertNotNull(tramsViusVal);
        long tramsVius = ((Number) tramsViusVal).longValue();
        
        // It should be 0 because we don't invent/scale data for departments
        assertEquals(0L, tramsVius);
    }

    @Autowired
    private com.example.demo.service.ResearchOutputJournalLinkService researchOutputJournalLinkService;

    @Test
    public void testPrintServiceStats() {
        for (String mode : java.util.List.of("vigent", "periode")) {
            System.out.println("=== MODE: " + mode + " ===");
            for (int y = 2021; y <= 2025; y++) {
                Map<String, Object> pubStats = researchOutputJournalLinkService.quartilesDashboardByDepartment(
                        null, y, y, mode, null);
                long q1q2 = 0;
                if (pubStats != null && pubStats.containsKey("quartiles")) {
                    for (Object q : (java.util.List<?>) pubStats.get("quartiles")) {
                        Map<?, ?> qMap = (Map<?, ?>) q;
                        String label = String.valueOf(qMap.get("quartile"));
                        if ("Q1".equalsIgnoreCase(label) || "Q2".equalsIgnoreCase(label)) {
                            q1q2 += ((Number) qMap.get("total")).longValue();
                        }
                    }
                }
                System.out.println("  " + y + " = " + q1q2);
            }
        }
    }
}
