package com.example.demo.curation;

import com.example.demo.curation.adapter.RorMetadataProviderAdapter;
import com.example.demo.curation.model.MetadataInferenceResult;
import com.example.demo.curation.model.ProviderQueryContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registries are full of homonyms in different countries. The ROR search must use the country it is given (Egreta's own,
 * or one named in the name) to pick the right one, and must not offer a confident country when it cannot tell them apart.
 */
public class RorHomonymSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An adapter fed with a fixed list of ROR records instead of the local copy and the ROR API. */
    private static class FixedRor extends RorMetadataProviderAdapter {
        private final String json;
        Set<String> hintsSeen;

        FixedRor(List<ObjectNode> records) {
            super(null, null, MAPPER);
            ObjectNode wrapper = MAPPER.createObjectNode();
            ArrayNode items = wrapper.putArray("items");
            records.forEach(items::add);
            this.json = wrapper.toString();
        }

        @Override
        protected String fetchRorRawJson(String orgName, Set<String> countryHints) {
            this.hintsSeen = countryHints;
            return json;
        }
    }

    private static ObjectNode record(String id, String name, String iso, String country, String... types) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("id", "https://ror.org/" + id);
        ArrayNode names = r.putArray("names");
        ObjectNode n = names.addObject();
        n.put("value", name);
        n.putArray("types").add("ror_display").add("label");
        ArrayNode t = r.putArray("types");
        for (String type : types) t.add(type);
        ObjectNode geo = r.putArray("locations").addObject().putObject("geonames_details");
        geo.put("country_code", iso);
        geo.put("country_name", country);
        return r;
    }

    private static final String MINISTRY = "Ministry of Education and Science";

    private static List<ObjectNode> ministries() {
        return List.of(
                record("lv1", MINISTRY, "LV", "Latvia", "government"),
                record("bg1", MINISTRY, "BG", "Bulgaria", "government"),
                record("ua1", MINISTRY, "UA", "Ukraine", "government"));
    }

    @Test
    void sameNameInSeveralCountriesAndNothingToTellThemApartGivesNoConfidentCountry() {
        Optional<MetadataInferenceResult> res = new FixedRor(ministries()).inferMetadata(MINISTRY, ProviderQueryContext.empty());

        assertTrue(res.isPresent());
        assertTrue(res.get().country().confidence() <= 0.55, "a guess among homonyms must not look reliable: " + res.get().country());
        assertTrue(res.get().country().reason().contains("homonym-countries=BG,LV,UA"), res.get().country().reason());
    }

    @Test
    void theCountryEgretaAlreadyHasPicksTheRightHomonym() {
        FixedRor ror = new FixedRor(ministries());
        MetadataInferenceResult res = ror.inferMetadata(MINISTRY, ProviderQueryContext.empty().withKnownCountry("ua")).orElseThrow();

        assertEquals(Set.of("UA"), ror.hintsSeen);
        assertEquals("Ukraine", res.country().countryLabel());
        assertTrue(res.country().reason().contains("ror.org/ua1"), res.country().reason());
        assertFalse(res.country().reason().contains("homonym"), "with the country known there is nothing left to be ambiguous");
        assertTrue(res.country().confidence() >= 0.90, "" + res.country().confidence());
    }

    @Test
    void aCountryNamedInTheNameIsEnoughToChooseWhenEgretaHasNone() {
        FixedRor ror = new FixedRor(List.of(
                record("lv1", MINISTRY + " of Ukraine", "LV", "Latvia", "government"),
                record("ua1", MINISTRY + " of Ukraine", "UA", "Ukraine", "government")));
        MetadataInferenceResult res = ror.inferMetadata(MINISTRY + " of Ukraine", ProviderQueryContext.empty()).orElseThrow();

        assertTrue(ror.hintsSeen.contains("UA"), "the country in the name must be passed as a hint: " + ror.hintsSeen);
        assertEquals("Ukraine", res.country().countryLabel());
        assertTrue(res.country().reason().contains("ror.org/ua1"));
    }

    @Test
    void ifEgretaSaysSpainAHomonymAbroadIsNotTheOrganization() {
        // Egreta knows the organization is in Spain; ROR only has the same name in Chile. Linking it would be wrong.
        FixedRor ror = new FixedRor(List.of(record("cl1", "Universidad SEK", "CL", "Chile", "education")));

        assertTrue(ror.inferMetadata("Universidad SEK", ProviderQueryContext.empty().withKnownCountry("ES")).isEmpty());
        // without that knowledge it is still the only candidate and is offered (it may well be right)
        assertTrue(ror.inferMetadata("Universidad SEK", ProviderQueryContext.empty()).isPresent());
    }

    @Test
    void aUniqueOrganizationKeepsItsConfidence() {
        FixedRor ror = new FixedRor(List.of(record("ab1", "University of Aberdeen", "GB", "United Kingdom", "education")));
        MetadataInferenceResult res = ror.inferMetadata("University of Aberdeen", ProviderQueryContext.empty()).orElseThrow();

        assertEquals("United Kingdom", res.country().countryLabel());
        assertTrue(res.country().confidence() >= 0.95);
        assertFalse(res.country().reason().contains("homonym"));
    }

    @Test
    void homonymsInTheSameCountryDoNotRaiseTheCountryFlag() {
        FixedRor ror = new FixedRor(List.of(
                record("es1", "Hospital de Sant Pau", "ES", "Spain", "healthcare"),
                record("es2", "Hospital de Sant Pau", "ES", "Spain", "healthcare")));
        MetadataInferenceResult res = ror.inferMetadata("Hospital de Sant Pau", ProviderQueryContext.empty()).orElseThrow();

        assertEquals("Spain", res.country().countryLabel());
        assertFalse(res.country().reason().contains("homonym-countries"), "same country: the country is not in doubt");
    }

    @Test
    void theRightCandidateBeyondTheFirstFiveIsStillConsidered() {
        // It used to inspect only the first five results, so a good match further down was never seen.
        List<ObjectNode> records = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            records.add(record("x" + i, "Completely Different Institute " + i, "FR", "France", "education"));
        }
        records.add(record("target", "Fundació Pare Manel", "ES", "Spain", "nonprofit"));
        MetadataInferenceResult res = new FixedRor(records).inferMetadata("Fundació Pare Manel", ProviderQueryContext.empty()).orElseThrow();

        assertEquals("Spain", res.country().countryLabel());
        assertTrue(res.country().reason().contains("ror.org/target"));
    }

    private static ObjectNode chosen(ObjectNode record) {
        record.put("_chosen", true);
        return record;
    }

    @Test
    void aChosenRecordWhoseNameIsNotInOurNameIsNotAccepted() {
        // ROR's affiliation matcher "chose" UK Research and Innovation (score 0.96) for this company. It is another
        // organization that merely sits in the same country, so it must not be linked.
        FixedRor ror = new FixedRor(List.of(chosen(record("ukri", "UK Research and Innovation", "GB", "United Kingdom", "funder", "government"))));

        assertTrue(ror.inferMetadata("Modus Research and Innovation LTD", ProviderQueryContext.empty()).isEmpty());
    }

    @Test
    void aChosenRecordContainedInANoisyNameIsAccepted() {
        // "Department of Physics, The University of Chicago" is noisy but contains the registered name.
        FixedRor ror = new FixedRor(List.of(chosen(record("uc", "University of Chicago", "US", "United States", "education"))));
        MetadataInferenceResult res = ror.inferMetadata("Department of Physics, The University of Chicago", ProviderQueryContext.empty()).orElseThrow();

        assertEquals("United States", res.country().countryLabel());
        assertTrue(res.country().reason().contains("ror.org/uc"));
    }

    /** Exposes the protected merge so the test can check it. */
    private static class Merger extends RorMetadataProviderAdapter {
        Merger() {
            super(null, null, MAPPER);
        }

        String merge(List<String> parts) {
            return mergeItems(parts);
        }
    }

    @Test
    void mergingTwoRoutesKeepsTheChosenMarkOfARecordFoundByBoth() throws Exception {
        String viaSearch = "{\"items\":[{\"id\":\"https://ror.org/a\"},{\"id\":\"https://ror.org/b\"}]}";
        String viaAffiliation = "{\"items\":[{\"id\":\"https://ror.org/a\",\"_chosen\":true}]}";

        var merged = MAPPER.readTree(new Merger().merge(List.of(viaSearch, viaAffiliation))).path("items");

        assertEquals(2, merged.size(), "the same record must not be listed twice");
        assertTrue(merged.get(0).path("_chosen").asBoolean(false), "the mark was lost when the record arrived first without it");
        assertFalse(merged.get(1).path("_chosen").asBoolean(false));
    }

    @Test
    void aWeakNameMatchIsStillRejected() {
        FixedRor ror = new FixedRor(List.of(record("pv", "Fundació Prevent", "ES", "Spain", "nonprofit")));
        assertTrue(ror.inferMetadata("Fundació Pare Manel", ProviderQueryContext.empty()).isEmpty());
    }
}
