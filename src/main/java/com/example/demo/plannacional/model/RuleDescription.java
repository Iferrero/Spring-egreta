package com.example.demo.plannacional.model;

/**
 * Descripció d'una regla d'elegibilitat exposada a la interfície.
 */
public record RuleDescription(
        String code,
        String title,
        String ipRule,
        String eiRule,
        String etRule,
        String notes
) {}
