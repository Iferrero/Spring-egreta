package com.example.demo.curation.model;

/**
 * Result of organization type inference with confidence score and reason explanation.
 */
public record TypeInferenceResult(String typeLabel, double confidence, String reason) {
    public static TypeInferenceResult noSignal() {
        return new TypeInferenceResult("", 0.0, "no-signal");
    }
}
