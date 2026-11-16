package com.example.demo.curation;

import com.example.demo.curation.adapter.InMemoryMetadataAdapter;
import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeInferenceResult;
import com.example.demo.curation.service.MetadataEnrichmentEngine;
import com.example.demo.curation.service.MetadataEnrichmentEngine.SourceTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ROR and Wikidata are authoritative: they must be queried for every organization, even when an earlier source already
 * gave a confident answer, so they can confirm or contradict it. The other sources are skipped in that case.
 */
public class MetadataEnrichmentEngineConsultationTest {

    private static final String ORG = "Universitat Autonoma de Barcelona";

    private InMemoryMetadataAdapter ror;
    private InMemoryMetadataAdapter wikidata;
    private InMemoryMetadataAdapter openAlex;
    private MetadataEnrichmentEngine engine;

    private static MetadataInferenceResult confident(String country, String reason) {
        return new MetadataInferenceResult(
                new InferenceResult(country, 0.98, reason),
                new TypeInferenceResult("Universitat", 0.96, reason),
                new FundingInferenceResult("Publica", 0.95, reason));
    }

    private static MetadataInferenceResult onlyCountry(String country, double confidence, String reason) {
        return new MetadataInferenceResult(new InferenceResult(country, confidence, reason),
                TypeInferenceResult.noSignal(), FundingInferenceResult.noSignal());
    }

    private SourceTrace trace(String provider) {
        return engine.traceFor(ORG).stream().filter(t -> t.provider().equals(provider)).findFirst().orElseThrow();
    }

    @BeforeEach
    void setUp() {
        ror = new InMemoryMetadataAdapter("ror", 10);
        openAlex = new InMemoryMetadataAdapter("openalex", 20);
        wikidata = new InMemoryMetadataAdapter("wikidata", 25);
        ror.setAlwaysConsulted(true);
        wikidata.setAlwaysConsulted(true);
        engine = new MetadataEnrichmentEngine(List.of(wikidata, openAlex, ror));
    }

    @Test
    void wikidataIsConsultedEvenWhenRorAlreadyGaveEverything() {
        ror.addFixture(ORG, confident("Spain", "ror-match"));
        wikidata.addFixture(ORG, onlyCountry("Spain", 0.88, "wikidata-country:Q43452"));
        openAlex.addFixture(ORG, onlyCountry("France", 0.85, "oa-match")); // must not be reached

        MetadataInferenceResult result = engine.inferMetadata(ORG, ProviderQueryContext.empty());

        assertEquals("found", trace("ror").status());
        assertEquals("found", trace("wikidata").status(), "Wikidata was skipped although it must always be consulted");
        assertEquals("skipped", trace("openalex").status(), "a non-authoritative source is still skipped once everything is confident");
        // Wikidata agreed with ROR on the country, so the engine raises the confidence
        assertEquals("Spain", result.country().countryLabel());
        assertEquals(1.0, result.country().confidence());
        assertTrue(result.country().reason().contains("wikidata-confirmed"));
    }

    @Test
    void wikidataCanContradictAConfidentSourceWithoutOverridingIt() {
        ror.addFixture(ORG, confident("Spain", "ror-match"));
        wikidata.addFixture(ORG, onlyCountry("France", 0.88, "wikidata-country:Q1"));

        MetadataInferenceResult result = engine.inferMetadata(ORG, ProviderQueryContext.empty());

        assertEquals("found", trace("wikidata").status());
        assertEquals("Spain", result.country().countryLabel(), "a confident registry answer must not be replaced by a weaker source");
        assertEquals(0.98, result.country().confidence());
    }

    @Test
    void theTraceDistinguishesNothingFoundFromUnavailableAndDisabled() {
        // ROR has nothing; Wikidata cannot be reached (paused after a rate limit); OpenAlex is switched off
        InMemoryMetadataAdapter paused = new InMemoryMetadataAdapter("wikidata", 25) {
            @Override
            public boolean isUnavailable() {
                return true;
            }
        };
        paused.setAlwaysConsulted(true);
        openAlex.setEnabled(false);
        engine = new MetadataEnrichmentEngine(List.of(ror, openAlex, paused));

        engine.inferMetadata(ORG, ProviderQueryContext.empty());

        assertEquals("no-match", trace("ror").status());
        assertEquals("unavailable", trace("wikidata").status());
        assertEquals("disabled", trace("openalex").status());
    }

    @Test
    void aFailingAuthoritativeSourceDoesNotStopTheOthers() {
        InMemoryMetadataAdapter broken = new InMemoryMetadataAdapter("wikidata", 25) {
            @Override
            public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
                throw new IllegalStateException("boom");
            }
        };
        broken.setAlwaysConsulted(true);
        ror.addFixture(ORG, onlyCountry("Spain", 0.9, "ror-match"));
        openAlex.addFixture(ORG, new MetadataInferenceResult(InferenceResult.noSignal(),
                new TypeInferenceResult("Universitat", 0.8, "oa-type"), FundingInferenceResult.noSignal()));
        engine = new MetadataEnrichmentEngine(List.of(ror, broken, openAlex));

        MetadataInferenceResult result = engine.inferMetadata(ORG, ProviderQueryContext.empty());

        assertEquals("error", trace("wikidata").status());
        assertEquals("found", trace("openalex").status());
        assertEquals("Universitat", result.type().typeLabel());
    }

    @Test
    void theTraceIsOnlyAvailableForOrganizationsAlreadyResolved() {
        assertTrue(engine.traceFor(ORG).isEmpty());
        ror.addFixture(ORG, confident("Spain", "ror-match"));
        engine.inferMetadata(ORG, ProviderQueryContext.empty());
        assertFalse(engine.traceFor(ORG).isEmpty());
        assertTrue(engine.traceFor("another organization").isEmpty());
    }
}
