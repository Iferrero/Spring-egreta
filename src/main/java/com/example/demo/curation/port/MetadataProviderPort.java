package com.example.demo.curation.port;

import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Port interface for external and local metadata providers (ROR, OpenAlex, CrossRef, LLM, etc.).
 * Implementations sit behind this seam and translate provider-specific schemas into normalized domain records.
 */
public interface MetadataProviderPort {

    /**
     * Unique identifier for this provider (e.g. "ror", "openalex", "crossref", "llm").
     */
    String providerName();

    /**
     * Priority order for fallback (lower number = higher priority).
     * Typically: ROR (10) -> OpenAlex (20) -> CrossRef (30) -> LLM (40).
     */
    int priority();

    /**
     * Whether this provider is enabled by application configuration.
     */
    boolean isEnabled();

    /**
     * Synchronously queries this provider for metadata suggestions on an organization name.
     */
    Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context);

    /**
     * Asynchronously queries this provider for metadata suggestions on an organization name.
     */
    default CompletableFuture<Optional<MetadataInferenceResult>> inferMetadataAsync(String organizationName, ProviderQueryContext context) {
        return CompletableFuture.supplyAsync(() -> inferMetadata(organizationName, context));
    }
}
