package com.example.demo.plannacional.model;

import java.util.List;

/**
 * Catàleg de regles i paràmetres per defecte per al modal de criteris de validació.
 */
public record RuleCatalogResponse(
        int defaultDurationYears,
        int emeritWarningMonths,
        List<RuleDescription> rules,
        List<CategoryDefinition> categoryDefinitions
) {
    public RuleCatalogResponse {
        rules = rules != null ? List.copyOf(rules) : List.of();
        categoryDefinitions = categoryDefinitions != null ? List.copyOf(categoryDefinitions) : List.of();
    }
}
