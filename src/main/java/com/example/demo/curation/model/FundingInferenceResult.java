package com.example.demo.curation.model;

/**
 * Result of funding character inference (Publica vs Privada) with confidence score.
 */
public record FundingInferenceResult(String fundingLabel, double confidence, String reason) {
    public static FundingInferenceResult noSignal() {
        return new FundingInferenceResult("", 0.0, "no-signal");
    }
}
