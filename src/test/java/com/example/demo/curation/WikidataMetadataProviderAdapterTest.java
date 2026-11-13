package com.example.demo.curation;

import com.example.demo.curation.adapter.CrossrefMetadataProviderAdapter;
import com.example.demo.curation.adapter.OpenAlexMetadataProviderAdapter;
import com.example.demo.curation.adapter.WikidataMetadataProviderAdapter;
import com.example.demo.curation.model.CountryAggregate;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.example.demo.curation.model.TypeAggregate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class WikidataMetadataProviderAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Adapter that answers from canned Wikibase entities instead of calling the network. */
    private static class FakeWikidata extends WikidataMetadataProviderAdapter {
        private final List<String> searchResult;
        private final Map<String, ObjectNode> entities;

        FakeWikidata(List<String> searchResult, Map<String, ObjectNode> entities) {
            super(MAPPER);
            this.searchResult = searchResult;
            this.entities = entities;
        }

        @Override
        protected JsonNode fetchJson(String url) {
            ObjectNode root = MAPPER.createObjectNode();
            if (url.contains("action=wbsearchentities")) {
                var arr = root.putArray("search");
                searchResult.forEach(id -> arr.addObject().put("id", id));
                return root;
            }
            String ids = URLDecoder.decode(url.substring(url.indexOf("&ids=") + 5), StandardCharsets.UTF_8);
            ObjectNode out = root.putObject("entities");
            for (String id : ids.split("\\|")) {
                if (entities.containsKey(id)) out.set(id, entities.get(id));
                else out.putObject(id).put("missing", "");
            }
            return root;
        }
    }

    private static ObjectNode entity(String labelCa, Map<String, List<String>> claims) {
        ObjectNode e = MAPPER.createObjectNode();
        if (labelCa != null) {
            e.putObject("labels").putObject("ca").put("value", labelCa);
        }
        ObjectNode cl = e.putObject("claims");
        claims.forEach((prop, values) -> {
            var arr = cl.putArray(prop);
            for (String v : values) {
                ObjectNode snak = arr.addObject().putObject("mainsnak").putObject("datavalue");
                if (prop.equals("P297")) snak.put("value", v);
                else snak.putObject("value").put("id", v);
            }
        });
        return e;
    }

    private static ProviderQueryContext catalogs() {
        return ProviderQueryContext.withCatalogs(
                List.of(new CountryAggregate("Espanya", "espanya", 100), new CountryAggregate("França", "franca", 50)),
                List.of(new TypeAggregate("Fundació", "fundacio", 10), new TypeAggregate("Universitat", "universitat", 10)));
    }

    @Test
    public void testContractAndPriorityRunsAfterOpenAlex() {
        WikidataMetadataProviderAdapter adapter = new WikidataMetadataProviderAdapter(MAPPER);
        assertEquals("wikidata", adapter.providerName());
        assertTrue(adapter.isEnabled());
        assertTrue(adapter.priority() > new OpenAlexMetadataProviderAdapter(MAPPER).priority());
        assertEquals(25, adapter.priority());
    }

    @Test
    public void testSmallFoundationWithCountryOnlyThroughLocatedInChain() {
        // "Fundació Pare Manel": generic "organization" class, no country, only "located in" -> Barcelona -> Spain.
        FakeWikidata adapter = new FakeWikidata(List.of("Q21402414"), Map.of(
                "Q21402414", entity("Fundació Pare Manel", Map.of("P31", List.of("Q43229"), "P131", List.of("Q1492"))),
                "Q43229", entity("organització", Map.of()),
                "Q1492", entity("Barcelona", Map.of("P17", List.of("Q29"))),
                "Q29", entity("Espanya", Map.of("P297", List.of("ES")))));

        Optional<MetadataInferenceResult> res = adapter.inferMetadata("Fundació Pare Manel", catalogs());

        assertTrue(res.isPresent());
        assertEquals("Espanya", res.get().country().countryLabel());
        assertTrue(res.get().country().confidence() > 0.80 && res.get().country().confidence() <= 0.88);
        assertEquals("Fundació", res.get().type().typeLabel());
        assertTrue(res.get().type().confidence() <= 0.88, "Wikidata alone must stay below the auto-apply zone");
        assertTrue(res.get().funding().fundingLabel().isBlank(), "a generic organization says nothing about funding");
    }

    @Test
    public void testSubclassWalkFindsPublicUniversityAndFunding() {
        // Item is an instance of a subclass of "public university"; the walk must reach it.
        FakeWikidata adapter = new FakeWikidata(List.of("Q1"), Map.of(
                "Q1", entity("Universitat de Prova", Map.of("P31", List.of("Q900"), "P17", List.of("Q142"))),
                "Q900", entity("universitat regional", Map.of("P279", List.of("Q875538"))),
                "Q875538", entity("universitat pública", Map.of("P279", List.of("Q3918"))),
                "Q3918", entity("universitat", Map.of()),
                "Q142", entity("França", Map.of("P297", List.of("FR")))));

        MetadataInferenceResult res = adapter.inferMetadata("Universitat de Prova", catalogs()).orElseThrow();

        assertEquals("França", res.country().countryLabel());
        assertEquals("Universitat", res.type().typeLabel());
        assertEquals("Publica", res.funding().fundingLabel());
    }

    @Test
    public void testNonOrganizationIsRejected() {
        // Same name, but the item is a human being (Q5): it must not be used.
        FakeWikidata adapter = new FakeWikidata(List.of("Q2"), Map.of(
                "Q2", entity("Fundació Pare Manel", Map.of("P31", List.of("Q5"))),
                "Q5", entity("ésser humà", Map.of())));

        assertTrue(adapter.inferMetadata("Fundació Pare Manel", catalogs()).isEmpty());
    }

    @Test
    public void testWeakNameMatchIsRejected() {
        // A different foundation that only shares the word "Fundació" must not be accepted.
        FakeWikidata adapter = new FakeWikidata(List.of("Q3"), Map.of(
                "Q3", entity("Fundació Prevent", Map.of("P31", List.of("Q43229"), "P17", List.of("Q29"))),
                "Q43229", entity("organització", Map.of()),
                "Q29", entity("Espanya", Map.of("P297", List.of("ES")))));

        assertTrue(adapter.inferMetadata("Fundació Pare Manel", catalogs()).isEmpty());
    }

    @Test
    public void testNameCountryConflictLowersConfidence() {
        FakeWikidata adapter = new FakeWikidata(List.of("Q4"), Map.of(
                "Q4", entity("National Land Survey of Sweden", Map.of("P31", List.of("Q327333"), "P17", List.of("Q33"))),
                "Q327333", entity("agència governamental", Map.of()),
                "Q33", entity("Finlàndia", Map.of("P297", List.of("FI")))));

        MetadataInferenceResult res = adapter.inferMetadata("National Land Survey of Sweden", ProviderQueryContext.empty()).orElseThrow();

        assertTrue(res.country().confidence() <= 0.55);
        assertTrue(res.country().reason().contains("name-country-conflict"));
    }

    @Test
    public void testDisabledReturnsEmpty() {
        FakeWikidata adapter = new FakeWikidata(List.of("Q1"), Map.of());
        adapter.setEnabled(false);
        assertFalse(adapter.isEnabled());
        assertTrue(adapter.inferMetadata("Universitat de Prova", catalogs()).isEmpty());
    }

    @Test
    public void testPriorityOrderingAgainstCrossref() {
        assertTrue(new WikidataMetadataProviderAdapter(MAPPER).priority() < new CrossrefMetadataProviderAdapter(MAPPER).priority());
    }
}
