package com.example.demo.curation.model;

/**
 * Estado del ciclo de vida y curacion de un candidato a publicacion.
 */
public enum CandidateStatus {
    /** Pendiente de revision por el analista */
    PENDING,
    /** Aprobado para importacion hacia EGRETA */
    APPROVED_IMPORT,
    /** Descartado por ser persona distinta / homonimo */
    REJECTED_HOMONYM,
    /** Descartado por ser la misma persona pero no atribuible a la UAB */
    REJECTED_NOT_UAB,
    /** Descarte generico */
    DISMISSED
}
