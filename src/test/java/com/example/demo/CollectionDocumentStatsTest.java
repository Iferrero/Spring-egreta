package com.example.demo;

import com.example.demo.controller.CollectionDocumentStatsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class CollectionDocumentStatsTest {

    @Autowired(required = false)
    private CollectionDocumentStatsController controller;

    @Test
    public void testGetCollectionDocumentStatsWithTypes() {
        assertNotNull(controller, "CollectionDocumentStatsController should be registered");

        Map<String, Object> result = controller.getDocumentStats(false);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.containsKey("collections"), "Result must contain 'collections'");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> collections = (List<Map<String, Object>>) result.get("collections");
        assertFalse(collections.isEmpty(), "Collections list should not be empty");

        // Verify Awards exists and has types breakdown
        Map<String, Object> awards = collections.stream()
                .filter(c -> "Awards".equals(c.get("collection")))
                .findFirst()
                .orElse(null);
        assertNotNull(awards, "Awards collection should be in stats");
        assertTrue(awards.containsKey("recordTypes"), "Awards must have recordTypes");
        assertTrue(awards.containsKey("attachmentTypes"), "Awards must have attachmentTypes");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> awardRecTypes = (List<Map<String, Object>>) awards.get("recordTypes");
        assertFalse(awardRecTypes.isEmpty(), "Awards recordTypes should not be empty");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> awardAttTypes = (List<Map<String, Object>>) awards.get("attachmentTypes");
        assertFalse(awardAttTypes.isEmpty(), "Awards attachmentTypes should not be empty");

        // Verify Researchoutputs exists and has types breakdown
        Map<String, Object> ro = collections.stream()
                .filter(c -> "Researchoutputs".equals(c.get("collection")))
                .findFirst()
                .orElse(null);
        assertNotNull(ro, "Researchoutputs collection should be in stats");
        assertEquals("links", ro.get("field"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roRecTypes = (List<Map<String, Object>>) ro.get("recordTypes");
        assertFalse(roRecTypes.isEmpty(), "Researchoutputs recordTypes should not be empty");
        // Check that 'Article' is one of the record types
        assertTrue(roRecTypes.stream().anyMatch(t -> "Article".equals(t.get("typeName"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roAttTypes = (List<Map<String, Object>>) ro.get("attachmentTypes");
        assertFalse(roAttTypes.isEmpty(), "Researchoutputs attachmentTypes should not be empty");
    }
}
