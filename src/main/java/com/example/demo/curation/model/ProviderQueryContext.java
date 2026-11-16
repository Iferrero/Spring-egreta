package com.example.demo.curation.model;

import java.util.Collections;
import java.util.List;

/**
 * Contextual hints passed to metadata providers during inference queries.
 *
 * @param knownCountryIso ISO 3166-1 alpha-2 code of the country the organization already has in Egreta (it comes from
 *                        Pure, not from a guess), or null/blank when it is not known. Registries contain many
 *                        homonymous institutions in different countries ("Ministry of Education and Science" exists in
 *                        five); this is what tells them apart.
 */
public record ProviderQueryContext(
        String associatedPerson,
        String publicationTitle,
        String doi,
        List<CountryAggregate> countryCatalog,
        List<TypeAggregate> typeCatalog,
        String knownCountryIso
) {
    /** Keeps the original five-argument form: no known country. */
    public ProviderQueryContext(String associatedPerson, String publicationTitle, String doi,
                                List<CountryAggregate> countryCatalog, List<TypeAggregate> typeCatalog) {
        this(associatedPerson, publicationTitle, doi, countryCatalog, typeCatalog, null);
    }

    public static ProviderQueryContext empty() {
        return new ProviderQueryContext(null, null, null, Collections.emptyList(), Collections.emptyList(), null);
    }

    public static ProviderQueryContext withCatalogs(List<CountryAggregate> countryCatalog, List<TypeAggregate> typeCatalog) {
        return new ProviderQueryContext(null, null, null, countryCatalog, typeCatalog, null);
    }

    /** Same context, with the organization's already-known country. */
    public ProviderQueryContext withKnownCountry(String iso) {
        return new ProviderQueryContext(associatedPerson, publicationTitle, doi, countryCatalog, typeCatalog, iso);
    }
}
