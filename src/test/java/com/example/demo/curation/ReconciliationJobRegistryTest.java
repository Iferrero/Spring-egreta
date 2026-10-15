package com.example.demo.curation;

import com.example.demo.curation.model.ReconciliationProgress;
import com.example.demo.curation.model.ReconciliationType;
import com.example.demo.curation.reconciler.ReconciliationJobRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ReconciliationJobRegistryTest {

    private ReconciliationJobRegistry registry;

    @BeforeEach
    public void setup() {
        registry = new ReconciliationJobRegistry();
    }

    @Test
    public void testInitialStateNotRunning() {
        for (ReconciliationType type : ReconciliationType.values()) {
            assertFalse(registry.isRunning(type));
            ReconciliationProgress p = registry.getProgress(type);
            assertFalse(p.running());
            assertEquals(0, p.total());
            assertEquals(0, p.processed());
            assertEquals(0, p.applied());
        }
    }

    @Test
    public void testStartAndStopCycle() {
        ReconciliationType type = ReconciliationType.VALIDATE;

        boolean started = registry.startRun(type, "test", 0.90, "AUTO-VALIDATE");
        assertTrue(started);
        assertTrue(registry.isRunning(type));

        // Starting while running should fail
        boolean startAgain = registry.startRun(type, "test", 0.90, "AUTO-VALIDATE");
        assertFalse(startAgain);

        // Progress updates
        registry.setTotal(type, 100);
        registry.incrementProcessed(type);
        registry.incrementProcessed(type);
        registry.incrementApplied(type);

        ReconciliationProgress p = registry.getProgress(type);
        assertTrue(p.running());
        assertEquals(100, p.total());
        assertEquals(2, p.processed());
        assertEquals(1, p.applied());
        assertEquals(2.0, p.percentage());

        // Stop
        boolean stopped = registry.requestStop(type);
        assertTrue(stopped);
        assertFalse(registry.isRunning(type));

        // Stop again when not running should return false
        assertFalse(registry.requestStop(type));
    }

    @Test
    public void testProgressToMapContract() {
        ReconciliationType type = ReconciliationType.COUNTRY;
        registry.startRun(type, "test", 0.95, "AUTO-APPLY COUNTRY");
        registry.setTotal(type, 10);
        registry.incrementProcessed(type);
        registry.incrementApplied(type);
        registry.logMessage(type, "[APLICAT] Test Org -> Spain");

        Map<String, Object> map = registry.getProgress(type).toMap();
        assertTrue((Boolean) map.get("running"));
        assertEquals(10, map.get("total"));
        assertEquals(1, map.get("processed"));
        assertEquals(1, map.get("applied"));
        assertEquals(10.0, map.get("percentage"));
        assertEquals(0.95, (Double) map.get("confidenceThreshold"));
        assertTrue(((String) map.get("logs")).contains("[APLICAT] Test Org -> Spain"));

        registry.finishRun(type, "Completed successfully");
        assertFalse(registry.isRunning(type));
    }
}
