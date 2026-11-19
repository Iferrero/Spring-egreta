package com.example.demo.plannacional.model;

/**
 * Resum d'un candidat quan la cerca a Egreta produeix múltiples coincidències.
 */
public record PersonCandidateSummary(
        String uuid,
        Long pureId,
        String fullName,
        String activeEmploymentType,
        String department
) {}
