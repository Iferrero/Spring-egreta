package com.example.demo.curation.model;

import java.util.Collections;
import java.util.List;

/**
 * Contextual hints passed to metadata providers during inference queries.
 */
public record ProviderQueryContext(
        String associatedPerson,
        String publicationTitle,
        String doi,
        List<CountryAggregate> countryCatalog,
        List<TypeAggregate> typeCatalog
) {
    public static ProviderQueryContext empty() {
        return new ProviderQueryContext(null, null, null, Collections.emptyList(), Collections.emptyList());
    }

    public static ProviderQueryContext withCatalogs(List<CountryAggregate> countryCatalog, List<TypeAggregate> typeCatalog) {
        return new ProviderQueryContext(null, null, null, countryCatalog, typeCatalog);
    }
}
