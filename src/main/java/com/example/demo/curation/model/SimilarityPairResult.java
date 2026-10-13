package com.example.demo.curation.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pairwise similarity computation outcome including connected node IDs and truncation status.
 */
public record SimilarityPairResult(
        List<Map<String, Object>> pairs,
        Set<String> connectedNodeIds,
        boolean truncated
) {}
