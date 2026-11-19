package com.example.demo.plannacional.model;

import java.time.LocalDate;

/**
 * Petició JSON per recalcular el veredicte d'un equip ja extret sense re-pujar el PDF.
 */
public record ReevaluateTeamRequest(
        ExtractedProposal proposal,
        LocalDate referenceDate,
        Integer durationYears
) {}
