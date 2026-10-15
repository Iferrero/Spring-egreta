package com.example.demo.curation;

import com.example.demo.curation.adapter.InMemoryMetadataAdapter;
import com.example.demo.curation.model.FundingInferenceResult;
import com.example.demo.curation.model.InferenceResult;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeInferenceResult;
import com.example.demo.curation.service.MetadataEnrichmentEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataEnrichmentEngineTest {

    private InMemoryMetadataAdapter rorAdapter;
    private InMemoryMetadataAdapter openAlexAdapter;
    private MetadataEnrichmentEngine engine;

    @BeforeEach
    public void setup() {
        rorAdapter = new InMemoryMetadataAdapter("ror", 10);
        openAlexAdapter = new InMemoryMetadataAdapter("openalex", 20);
        engine = new MetadataEnrichmentEngine(List.of(openAlexAdapter, rorAdapter));
    }

    @Test
    public void testPriorityOrdering() {
        assertEquals("ror", engine.getProviders().get(0).providerName());
        assertEquals("openalex", engine.getProviders().get(1).providerName());
    }

    @Test
    public void testShortCircuitOnHighConfidence() {
        MetadataInferenceResult rorResult = new MetadataInferenceResult(
                new InferenceResult("Spain", 0.98, "ror-match"),
                new TypeInferenceResult("University", 0.95, "ror-type"),
                new FundingInferenceResult("Publica", 0.96, "ror-funding")
        );
        rorAdapter.addFixture("Universitat Autonoma de Barcelona", rorResult);

        // OpenAlex has different data that shouldn't be reached
        MetadataInferenceResult oaResult = new MetadataInferenceResult(
                new InferenceResult("France", 0.85, "oa-match"),
                new TypeInferenceResult("Company", 0.80, "oa-type"),
                new FundingInferenceResult("Privada", 0.80, "oa-funding")
        );
        openAlexAdapter.addFixture("Universitat Autonoma de Barcelona", oaResult);

        MetadataInferenceResult result = engine.inferMetadata("Universitat Autonoma de Barcelona", ProviderQueryContext.empty());

        assertEquals("Spain", result.country().countryLabel());
        assertEquals(0.98, result.country().confidence());
        assertEquals("University", result.type().typeLabel());
        assertEquals("Publica", result.funding().fundingLabel());
    }

    @Test
    public void testPartialSynthesisAcrossProviders() {
        // ROR only knows the country
        MetadataInferenceResult rorResult = new MetadataInferenceResult(
                new InferenceResult("Germany", 0.95, "ror-match"),
                TypeInferenceResult.noSignal(),
                FundingInferenceResult.noSignal()
        );
        rorAdapter.addFixture("Max Planck Institute", rorResult);

        // OpenAlex provides the missing type and funding
        MetadataInferenceResult oaResult = new MetadataInferenceResult(
                new InferenceResult("Germany", 0.80, "oa-match"),
                new TypeInferenceResult("Research Institute", 0.92, "oa-type"),
                new FundingInferenceResult("Publica", 0.91, "oa-funding")
        );
        openAlexAdapter.addFixture("Max Planck Institute", oaResult);

        MetadataInferenceResult result = engine.inferMetadata("Max Planck Institute", ProviderQueryContext.empty());

        // Country should be taken from ROR (highest confidence 0.95)
        assertEquals("Germany", result.country().countryLabel());
        assertEquals(0.95, result.country().confidence());

        // Type and Funding should be synthesized from OpenAlex
        assertEquals("Research Institute", result.type().typeLabel());
        assertEquals(0.92, result.type().confidence());
        assertEquals("Publica", result.funding().fundingLabel());
        assertEquals(0.91, result.funding().confidence());
    }

    @Test
    public void testCachingBehavior() {
        MetadataInferenceResult rorResult = new MetadataInferenceResult(
                new InferenceResult("Spain", 0.95, "ror-match"),
                new TypeInferenceResult("University", 0.95, "ror-type"),
                new FundingInferenceResult("Publica", 0.95, "ror-funding")
        );
        rorAdapter.addFixture("UAB", rorResult);

        MetadataInferenceResult first = engine.inferMetadata("UAB", ProviderQueryContext.empty());
        assertEquals("Spain", first.country().countryLabel());

        // Clear adapter fixture; cached result should still be returned
        rorAdapter.clearFixtures();
        MetadataInferenceResult second = engine.inferMetadata("UAB", ProviderQueryContext.empty());
        assertEquals("Spain", second.country().countryLabel());

        // Once cache is explicitly cleared, it should return empty
        engine.clearCache();
        MetadataInferenceResult third = engine.inferMetadata("UAB", ProviderQueryContext.empty());
        assertEquals("", third.country().countryLabel());
    }
}
