package com.example.demo.plannacional.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Resposta de validació completa d'una sol·licitud del Pla Nacional.
 */
public record ProposalValidationResponse(
        String fileName,
        String projectCode,
        String acronym,
        String title,
        Integer durationYears,
        LocalDate expectedStartDate,
        LocalDate expectedEndDate,
        String applicantEntity,
        ExtractionMethod extractionMethod,
        LocalDate referenceDate,
        int effectiveDurationYears,
        LocalDate effectiveProjectEndDate,
        EligibilityStatus overallStatus,
        SummaryCounts summaryCounts,
        List<MemberValidationResult> members,
        List<String> extractionWarnings,
        String rawTextPreview
) {
    public ProposalValidationResponse {
        members = members != null ? List.copyOf(members) : List.of();
        extractionWarnings = extractionWarnings != null ? List.copyOf(extractionWarnings) : List.of();
    }
}
