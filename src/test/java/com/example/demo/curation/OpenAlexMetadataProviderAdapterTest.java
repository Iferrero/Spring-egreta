package com.example.demo.curation;

import com.example.demo.curation.adapter.OpenAlexMetadataProviderAdapter;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAlexMetadataProviderAdapterTest {

    private OpenAlexMetadataProviderAdapter adapter;

    @BeforeEach
    public void setup() {
        adapter = new OpenAlexMetadataProviderAdapter(new ObjectMapper());
    }

    @Test
    public void testContractAndPriority() {
        assertEquals("openalex", adapter.providerName());
        assertEquals(20, adapter.priority());
        assertTrue(adapter.isEnabled());
    }

    @Test
    public void testWithoutAuthorReturnsEmpty() {
        Optional<MetadataInferenceResult> result = adapter.inferMetadata("Harvard University", ProviderQueryContext.empty());
        assertTrue(result.isEmpty());
    }
}
