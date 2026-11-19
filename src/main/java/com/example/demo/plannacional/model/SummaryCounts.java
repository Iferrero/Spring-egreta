package com.example.demo.plannacional.model;

/**
 * Recomptes agregats de membres validats en una sol·licitud.
 */
public record SummaryCounts(
        int total,
        int ok,
        int avis,
        int noOk,
        int extern
) {}
