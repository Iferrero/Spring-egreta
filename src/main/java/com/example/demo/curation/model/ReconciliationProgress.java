package com.example.demo.curation.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable status snapshot of an asynchronous reconciliation job.
 */
public record ReconciliationProgress(
        ReconciliationType type,
        boolean running,
        int total,
        int processed,
        int applied,
        double percentage,
        double threshold,
        List<String> logs
) {
    public static ReconciliationProgress idle(ReconciliationType type, double threshold) {
        return new ReconciliationProgress(type, false, 0, 0, 0, 0.0, threshold, List.of());
    }

    /**
     * Converts to JSON-compatible map preserving the exact contract expected by web dashboards.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", running);
        map.put("total", total);
        map.put("processed", processed);
        map.put("applied", applied);
        map.put("percentage", percentage);
        if (threshold > 0) {
            map.put("confidenceThreshold", threshold);
            map.put("similarityThreshold", threshold);
        }
        map.put("logs", logs != null ? String.join("\n", logs) : "");
        return map;
    }
}
