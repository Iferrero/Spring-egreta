package com.example.demo.curation.model;

/**
 * Normalized token profile for high-performance pairwise organization similarity matching.
 */
public record OrgSimilarityProfile(
        String id,
        String displayName,
        Long pureId,
        String normalizedName,
        String[] tokens,
        String typeLabel,
        String countryLabel,
        String workflowStep
) {}
