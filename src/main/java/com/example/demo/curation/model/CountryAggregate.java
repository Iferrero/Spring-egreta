package com.example.demo.curation.model;

/**
 * Aggregated country count from database or cache for normalization.
 */
public record CountryAggregate(String label, String normalizedLabel, int count) {}
