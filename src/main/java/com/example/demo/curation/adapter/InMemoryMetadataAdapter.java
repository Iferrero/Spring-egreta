package com.example.demo.curation.adapter;

import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.port.MetadataProviderPort;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory test double and fixture provider for MetadataProviderPort.
 * Provides deterministic metadata without any network or database I/O.
 */
public class InMemoryMetadataAdapter implements MetadataProviderPort {

    private final String name;
    private final int priority;
    private boolean enabled = true;
    private final Map<String, MetadataInferenceResult> fixtures = new ConcurrentHashMap<>();

    public InMemoryMetadataAdapter(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public void addFixture(String organizationName, MetadataInferenceResult result) {
        fixtures.put(organizationName.trim().toLowerCase(), result);
    }

    public void clearFixtures() {
        fixtures.clear();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String providerName() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Optional<MetadataInferenceResult> inferMetadata(String organizationName, ProviderQueryContext context) {
        if (!enabled || organizationName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fixtures.get(organizationName.trim().toLowerCase()));
    }
}
