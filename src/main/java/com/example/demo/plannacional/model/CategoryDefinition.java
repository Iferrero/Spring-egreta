package com.example.demo.plannacional.model;

import java.util.List;

/**
 * Definició d'una categoria laboral del model d'elegibilitat.
 */
public record CategoryDefinition(
        EmploymentCategory category,
        String label,
        String description,
        List<String> examples
) {
    public CategoryDefinition {
        examples = examples != null ? List.copyOf(examples) : List.of();
    }
}
