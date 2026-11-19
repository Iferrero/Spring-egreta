package com.example.demo.plannacional.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Dades estructurades extretes en memòria d'un PDF de sol·licitud del Pla Nacional.
 */
public record ExtractedProposal(
        String fileName,
        String projectCode,
        String acronym,
        String title,
        Integer durationYears,
        LocalDate expectedStartDate,
        LocalDate expectedEndDate,
        String applicantEntity,
        ExtractionMethod extractionMethod,
        List<String> warnings,
        String rawTextPreview,
        List<ExtractedMember> members
) {
    public ExtractedProposal {
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
        members = members != null ? List.copyOf(members) : List.of();
    }
}
