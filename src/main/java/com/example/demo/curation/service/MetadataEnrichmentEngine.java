package com.example.demo.curation.service;

import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeInferenceResult;
import com.example.demo.curation.port.MetadataProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deep orchestration module for organization metadata enrichment.
 * Queries registered MetadataProviderPorts in priority order, short-circuiting on high confidence,
 * and synthesizing the best country, type, and funding inferences behind a clean seam.
 */
@Service
public class MetadataEnrichmentEngine {

    private static final Logger log = LoggerFactory.getLogger(MetadataEnrichmentEngine.class);
    private static final double SHORT_CIRCUIT_CONFIDENCE = 0.90;
    private static final long DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes

    private record CachedInference(MetadataInferenceResult result, long timestamp) {}

    private final List<MetadataProviderPort> providers;
    private final Map<String, CachedInference> inferenceCache = new ConcurrentHashMap<>();
    private long cacheTtlMs = DEFAULT_CACHE_TTL_MS;

    @Autowired
    public MetadataEnrichmentEngine(List<MetadataProviderPort> providers) {
        this.providers = new ArrayList<>(providers);
        this.providers.sort(Comparator.comparingInt(MetadataProviderPort::priority));
    }

    public void setCacheTtlMs(long ttlMs) {
        this.cacheTtlMs = ttlMs;
    }

    public void clearCache() {
        inferenceCache.clear();
    }

    public List<MetadataProviderPort> getProviders() {
        return List.copyOf(providers);
    }

    /**
     * Synchronously infers cohesive metadata for an organization name using provider hierarchy and caching.
     */
    public MetadataInferenceResult inferMetadata(String organizationName, ProviderQueryContext context) {
        if (organizationName == null || organizationName.isBlank()) {
            return MetadataInferenceResult.empty();
        }

        String cacheKey = organizationName.trim().toLowerCase();
        long now = System.currentTimeMillis();
        CachedInference cached = inferenceCache.get(cacheKey);
        if (cached != null && (now - cached.timestamp()) < cacheTtlMs) {
            return cached.result();
        }

        InferenceResult bestCountry = null;
        TypeInferenceResult bestType = null;
        FundingInferenceResult bestFunding = null;

        for (MetadataProviderPort provider : providers) {
            if (!provider.isEnabled()) {
                continue;
            }

            try {
                Optional<MetadataInferenceResult> optResult = provider.inferMetadata(organizationName, context);
                if (optResult.isEmpty()) {
                    continue;
                }

                MetadataInferenceResult res = optResult.get();

                // Country candidate
                if (res.country() != null && res.country().confidence() > 0) {
                    if (bestCountry == null || res.country().confidence() > bestCountry.confidence()) {
                        bestCountry = res.country();
                    }
                }

                // Type candidate
                if (res.type() != null && res.type().confidence() > 0) {
                    if (bestType == null || res.type().confidence() > bestType.confidence()) {
                        bestType = res.type();
                    }
                }

                // Funding candidate
                if (res.funding() != null && res.funding().confidence() > 0) {
                    if (bestFunding == null || res.funding().confidence() > bestFunding.confidence()) {
                        bestFunding = res.funding();
                    }
                }

                // Short-circuit check: if all three dimensions have high confidence, stop querying lower priority providers
                boolean countryComplete = bestCountry != null && bestCountry.confidence() >= SHORT_CIRCUIT_CONFIDENCE;
                boolean typeComplete = bestType != null && bestType.confidence() >= SHORT_CIRCUIT_CONFIDENCE;
                boolean fundingComplete = bestFunding != null && bestFunding.confidence() >= SHORT_CIRCUIT_CONFIDENCE;

                if (countryComplete && typeComplete && fundingComplete) {
                    log.debug("Short-circuiting metadata enrichment for '{}' at provider '{}'", organizationName, provider.providerName());
                    break;
                }
            } catch (Exception e) {
                log.warn("Error querying metadata provider '{}' for '{}': {}", provider.providerName(), organizationName, e.getMessage());
            }
        }

        MetadataInferenceResult finalResult = new MetadataInferenceResult(
                bestCountry != null ? bestCountry : InferenceResult.noSignal(),
                bestType != null ? bestType : TypeInferenceResult.noSignal(),
                bestFunding != null ? bestFunding : FundingInferenceResult.noSignal()
        );

        inferenceCache.put(cacheKey, new CachedInference(finalResult, now));
        return finalResult;
    }

    /**
     * Asynchronously infers metadata for an organization name.
     */
    public CompletableFuture<MetadataInferenceResult> inferMetadataAsync(String organizationName, ProviderQueryContext context) {
        return CompletableFuture.supplyAsync(() -> inferMetadata(organizationName, context));
    }
}
