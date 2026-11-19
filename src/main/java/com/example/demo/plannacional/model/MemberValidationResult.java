package com.example.demo.plannacional.model;

import java.util.List;

/**
 * Resultat complet de l'avaluació d'elegibilitat per a un membre de l'equip.
 */
public record MemberValidationResult(
        ExtractedMember extractedMember,
        MatchConfidence matchConfidence,
        String matchedPersonUuid,
        Long matchedPureId,
        String matchedFullName,
        String egretaEditorUrl,
        ContractSnapshot primaryContract,
        List<ContractSnapshot> activeContracts,
        ContractSnapshot latestHistoricalContract,
        List<PersonCandidateSummary> ambiguousCandidates,
        EmploymentCategory employmentCategory,
        EligibilityStatus status,
        String reasonCode,
        String reasonMessage
) {
    public MemberValidationResult {
        activeContracts = activeContracts != null ? List.copyOf(activeContracts) : List.of();
        ambiguousCandidates = ambiguousCandidates != null ? List.copyOf(ambiguousCandidates) : List.of();
    }
}
