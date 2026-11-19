package com.example.demo.plannacional.model;

import java.util.List;

/**
 * Resultat de la cerca d'un membre a la col·lecció Persons d'Egreta.
 */
public record PersonLookupMatch(
        MatchConfidence matchConfidence,
        String matchedPersonUuid,
        Long matchedPureId,
        String matchedFullName,
        String egretaEditorUrl,
        ContractSnapshot primaryContract,
        List<ContractSnapshot> activeContracts,
        ContractSnapshot latestHistoricalContract,
        List<PersonCandidateSummary> ambiguousCandidates
) {
    public PersonLookupMatch {
        activeContracts = activeContracts != null ? List.copyOf(activeContracts) : List.of();
        ambiguousCandidates = ambiguousCandidates != null ? List.copyOf(ambiguousCandidates) : List.of();
    }
}
