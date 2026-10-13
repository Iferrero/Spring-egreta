package com.example.demo.curation.model;

/**
 * Aggregated organization type count from database or cache for normalization.
 */
public record TypeAggregate(String label, String normalizedLabel, int count) {}
