package com.example.demo.plannacional.model;

/**
 * Membre de l'equip extret del PDF de sol·licitud oficial AEI/MICIU.
 */
public record ExtractedMember(
        MemberRole role,
        String fullName,
        String firstName,
        String lastName,
        String nif,
        String entity,
        String declaredLinkage,
        String rawLine
) {}
