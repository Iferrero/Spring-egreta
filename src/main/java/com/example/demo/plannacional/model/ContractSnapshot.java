package com.example.demo.plannacional.model;

import java.time.LocalDate;

/**
 * Instantània d'una associació contractual o de recerca a la col·lecció Persons.
 */
public record ContractSnapshot(
        String source,
        String organizationName,
        String organizationUuid,
        String employmentType,
        String contractType,
        String staffType,
        LocalDate startDate,
        LocalDate endDate,
        boolean primaryAssociation,
        boolean researchAffiliationOnly
) {}
