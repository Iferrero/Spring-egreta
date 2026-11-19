package com.example.demo.plannacional.model;

/**
 * Nivell de confiança en l'emparellament amb la col·lecció Persons d'Egreta.
 */
public enum MatchConfidence {
    NIF_EXACTE,
    NOM_EXACTE,
    NOM_APROXIMAT,
    AMBIGU,
    NO_TROBAT
}
