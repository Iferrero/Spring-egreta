package com.example.demo.curation.model;

/**
 * Result of country inference with confidence score and reason explanation.
 */
public record InferenceResult(String countryLabel, double confidence, String reason) {
    public static InferenceResult noSignal() {
        return new InferenceResult("", 0.0, "no-signal");
    }
}
