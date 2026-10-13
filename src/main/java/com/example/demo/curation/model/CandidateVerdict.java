package com.example.demo.curation.model;

/**
 * Semaforo de confianza explicable para la candidatura evaluada.
 */
public enum CandidateVerdict {
    /** Semaforo verde: alta certeza UAB (>= 80) */
    HIGH_CONFIDENCE_UAB,
    /** Semaforo amarillo: dudas o requiere revision (40-79) */
    REQUIRES_REVIEW,
    /** Semaforo rojo: probable falso positivo (< 40) */
    LIKELY_FALSE_POSITIVE
}
