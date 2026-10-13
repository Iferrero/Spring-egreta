package com.example.demo.curation;

import com.example.demo.curation.adapter.RorMetadataProviderAdapter;
import com.example.demo.curation.model.CountryAggregate;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeAggregate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RorMetadataProviderAdapterTest {

    private RorMetadataProviderAdapter adapter;

    @BeforeEach
    public void setup() {
        adapter = new RorMetadataProviderAdapter(null, null, new ObjectMapper());
    }

    @Test
    public void testContractAndPriority() {
        assertEquals("ror", adapter.providerName());
        assertEquals(10, adapter.priority());
        assertTrue(adapter.isEnabled());
    }

    @Test
    public void testEmptyNameReturnsEmpty() {
        Optional<MetadataInferenceResult> result = adapter.inferMetadata("", ProviderQueryContext.empty());
        assertTrue(result.isEmpty());
    }

    @Test
    public void testContextWithCatalogs() {
        List<CountryAggregate> countries = List.of(new CountryAggregate("Espanya", "espanya", 500));
        List<TypeAggregate> types = List.of(new TypeAggregate("Universitat", "universitat", 200));
        ProviderQueryContext ctx = ProviderQueryContext.withCatalogs(countries, types);

        assertEquals(1, ctx.countryCatalog().size());
        assertEquals(1, ctx.typeCatalog().size());
    }
}
