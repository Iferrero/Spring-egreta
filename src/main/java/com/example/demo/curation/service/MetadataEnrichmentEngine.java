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
 * Queries registered MetadataProviderPorts in priority order and synthesizes the best country, type, and funding
 * inferences behind a clean seam.
 *
 * <p>Once every field is already confident (>= 0.90) the remaining providers are skipped to save time and external
 * calls, <b>except</b> those that declare {@link MetadataProviderPort#alwaysConsulted()} (ROR and Wikidata): they are
 * the authoritative sources and must be consulted for every organization so that they can confirm or contradict the
 * answer. What every provider did is recorded in a {@link SourceTrace} so it can be shown to the user.
 */
@Service
public class MetadataEnrichmentEngine {

    private static final Logger log = LoggerFactory.getLogger(MetadataEnrichmentEngine.class);
    private static final double SHORT_CIRCUIT_CONFIDENCE = 0.90;
    private static final long DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes

    /** What happened with one provider: status is found | no-match | unavailable | error | skipped | disabled. */
    public record SourceTrace(String provider, String status, String detail) {}

    private record CachedInference(MetadataInferenceResult result, List<SourceTrace> trace, long timestamp) {}

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
     * What each provider did the last time this organization was resolved (still within the cache window), or an
     * empty list when it has not been resolved recently.
     */
    public List<SourceTrace> traceFor(String organizationName) {
        if (organizationName == null || organizationName.isBlank()) {
            return List.of();
        }
        CachedInference cached = inferenceCache.get(organizationName.trim().toLowerCase());
        if (cached == null || (System.currentTimeMillis() - cached.timestamp()) >= cacheTtlMs) {
            return List.of();
        }
        return cached.trace();
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
        List<SourceTrace> trace = new ArrayList<>();
        boolean allConfident = false;

        for (MetadataProviderPort provider : providers) {
            String name = provider.providerName();
            if (!provider.isEnabled()) {
                trace.add(new SourceTrace(name, "disabled", ""));
                continue;
            }
            if (allConfident && !provider.alwaysConsulted()) {
                log.debug("Skipping provider '{}' for '{}': every field is already confident", name, organizationName);
                trace.add(new SourceTrace(name, "skipped", "all-fields-confident"));
                continue;
            }

            try {
                Optional<MetadataInferenceResult> optResult = provider.inferMetadata(organizationName, context);
                if (optResult.isEmpty() || !optResult.get().hasAnySignal()) {
                    trace.add(new SourceTrace(name, provider.isUnavailable() ? "unavailable" : "no-match", ""));
                    continue;
                }

                MetadataInferenceResult res = optResult.get();
                List<String> fields = new ArrayList<>();

                // Country candidate (respects provider priority: ROR -> Scopus -> OpenAlex -> Crossref -> IA)
                if (res.country() != null && res.country().confidence() > 0 && !res.country().countryLabel().isBlank()) {
                    fields.add("country");
                    if (bestCountry == null || bestCountry.countryLabel().isBlank() || bestCountry.confidence() < 0.85) {
                        if (bestCountry == null || res.country().confidence() >= bestCountry.confidence()) {
                            bestCountry = res.country();
                        }
                    } else if (bestCountry.countryLabel().equalsIgnoreCase(res.country().countryLabel())) {
                        bestCountry = new InferenceResult(bestCountry.countryLabel(), 1.0, bestCountry.reason() + "+" + name + "-confirmed");
                    }
                }

                // Type candidate
                if (res.type() != null && res.type().confidence() > 0) {
                    fields.add("type");
                    if (bestType == null || res.type().confidence() > bestType.confidence()) {
                        bestType = res.type();
                    }
                }

                // Funding candidate
                if (res.funding() != null && res.funding().confidence() > 0) {
                    fields.add("funding");
                    if (bestFunding == null || res.funding().confidence() > bestFunding.confidence()) {
                        bestFunding = res.funding();
                    }
                }

                trace.add(new SourceTrace(name, fields.isEmpty() ? "no-match" : "found", String.join(",", fields)));

                allConfident = bestCountry != null && bestCountry.confidence() >= SHORT_CIRCUIT_CONFIDENCE
                        && bestType != null && bestType.confidence() >= SHORT_CIRCUIT_CONFIDENCE
                        && bestFunding != null && bestFunding.confidence() >= SHORT_CIRCUIT_CONFIDENCE;
            } catch (Exception e) {
                log.warn("Error querying metadata provider '{}' for '{}': {}", name, organizationName, e.getMessage());
                trace.add(new SourceTrace(name, "error", String.valueOf(e.getMessage())));
            }
        }

        MetadataInferenceResult finalResult = new MetadataInferenceResult(
                bestCountry != null ? bestCountry : InferenceResult.noSignal(),
                bestType != null ? bestType : TypeInferenceResult.noSignal(),
                bestFunding != null ? bestFunding : FundingInferenceResult.noSignal()
        );

        inferenceCache.put(cacheKey, new CachedInference(finalResult, List.copyOf(trace), now));
        return finalResult;
    }

    /**
     * Asynchronously infers metadata for an organization name.
     */
    public CompletableFuture<MetadataInferenceResult> inferMetadataAsync(String organizationName, ProviderQueryContext context) {
        return CompletableFuture.supplyAsync(() -> inferMetadata(organizationName, context));
    }
}
