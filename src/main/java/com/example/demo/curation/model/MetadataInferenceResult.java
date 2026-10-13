package com.example.demo.curation.model;

/**
 * Cohesive metadata inference result combining country, organization type, and funding character.
 */
public record MetadataInferenceResult(
        InferenceResult country,
        TypeInferenceResult type,
        FundingInferenceResult funding
) {
    public static MetadataInferenceResult empty() {
        return new MetadataInferenceResult(
                InferenceResult.noSignal(),
                TypeInferenceResult.noSignal(),
                FundingInferenceResult.noSignal()
        );
    }

    public boolean hasAnySignal() {
        return (country != null && country.confidence() > 0.0)
                || (type != null && type.confidence() > 0.0)
                || (funding != null && funding.confidence() > 0.0);
    }
}
