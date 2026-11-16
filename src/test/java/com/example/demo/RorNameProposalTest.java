package com.example.demo;

import com.example.demo.controller.ExternalOrganizationController;
import com.example.demo.controller.ExternalOrganizationController.RorNameProposal;
import com.example.demo.curation.adapter.RorMetadataProviderAdapter;
import com.example.demo.model.ExternalOrganization;
import com.example.demo.repository.ExternalOrganizationDifferentRepository;
import com.example.demo.repository.ExternalOrganizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The "suggested name" proposal built from ROR must use the same careful candidate search as the metadata inference:
 * no proposal for a record that merely shares a generic prefix, no country when the name is a homonym, and nothing
 * from another country when Egreta already knows the organization's.
 */
public class RorNameProposalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class FixedRor extends RorMetadataProviderAdapter {
        private final String json;

        FixedRor(List<ObjectNode> records) {
            super(null, null, MAPPER);
            ObjectNode wrapper = MAPPER.createObjectNode();
            ArrayNode items = wrapper.putArray("items");
            records.forEach(items::add);
            this.json = wrapper.toString();
        }

        @Override
        protected String fetchRorRawJson(String orgName, Set<String> countryHints) {
            return json;
        }
    }

    private static ObjectNode record(String id, String name, String iso, String country, String type) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("id", "https://ror.org/" + id);
        r.putArray("names").addObject().put("value", name).putArray("types").add("ror_display");
        r.putArray("types").add(type);
        ObjectNode geo = r.putArray("locations").addObject().putObject("geonames_details");
        geo.put("country_code", iso);
        geo.put("country_name", country);
        return r;
    }

    private ExternalOrganizationController controller;
    private MongoTemplate mongo;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        controller = new ExternalOrganizationController(
                mock(ExternalOrganizationRepository.class), mock(ExternalOrganizationDifferentRepository.class), mongo);
    }

    private void useRor(RorMetadataProviderAdapter adapter) throws Exception {
        Field f = ExternalOrganizationController.class.getDeclaredField("rorProviderAdapter");
        f.setAccessible(true);
        f.set(controller, adapter);
    }

    private void egretaKnowsCountry(String uuid, String iso) {
        ExternalOrganization org = new ExternalOrganization();
        ExternalOrganization.Address address = new ExternalOrganization.Address();
        ExternalOrganization.UriTerm country = new ExternalOrganization.UriTerm();
        country.setUri("/dk/atira/pure/core/countries/" + iso.toLowerCase());
        Map<String, String> term = new LinkedHashMap<>();
        term.put("en_GB", iso);
        country.setTerm(term);
        address.setCountry(country);
        org.setAddress(address);
        when(mongo.findOne(any(Query.class), eq(ExternalOrganization.class))).thenReturn(org);
    }

    private RorNameProposal propose(String uuid, String name) throws Exception {
        Method m = ExternalOrganizationController.class.getDeclaredMethod("executeRorNameProposalViaAdapter",
                String.class, Long.class, String.class, List.class, List.class);
        m.setAccessible(true);
        return (RorNameProposal) m.invoke(controller, uuid, null, name, List.of(), List.of());
    }

    @Test
    void aRecordThatOnlySharesAGenericPrefixIsNotProposed() throws Exception {
        // "Fundació Pare Manel" scored 71% against this unrelated foundation and was proposed as its new name.
        useRor(new FixedRor(List.of(record("pv", "Fundació Prevent", "ES", "Spain", "nonprofit"))));

        assertNull(propose("u1", "Fundació Pare Manel"));
    }

    @Test
    void aGoodMatchIsProposedWithItsRecordAndCountry() throws Exception {
        useRor(new FixedRor(List.of(record("ab", "University of Aberdeen", "GB", "United Kingdom", "education"))));

        RorNameProposal p = propose("u1", "University of Aberdeen");

        assertNotNull(p);
        assertEquals("University of Aberdeen", p.suggestedName());
        assertEquals("https://ror.org/ab", p.rorId());
        assertEquals("United Kingdom", p.country());
        assertTrue(p.similarity() >= 0.99);
        assertFalse(p.reason().contains("homonym"));
    }

    @Test
    void anAcronymInBracketsDoesNotLowerTheMatch() throws Exception {
        // Egreta often writes "Name (ACRONYM)"; it is the same organization and must reach the auto-apply zone.
        useRor(new FixedRor(List.of(record("sh", "Shanghai University", "CN", "China", "education"))));

        RorNameProposal p = propose("u1", "Shanghai University (SHU)");

        assertNotNull(p);
        assertEquals("https://ror.org/sh", p.rorId());
        assertTrue(p.similarity() >= 0.99, "similarity was " + p.similarity());
    }

    @Test
    void aLongerNameOfAnotherInstitutionStillDoesNotReachTheAutoApplyZone() throws Exception {
        // "Seoul National University of Education" is not "Seoul National University": being a superstring is not enough.
        useRor(new FixedRor(List.of(record("snu", "Seoul National University", "KR", "South Korea", "education"))));

        RorNameProposal p = propose("u1", "Seoul National University of Education");

        assertTrue(p == null || p.similarity() < 0.90, "proposed with similarity " + (p == null ? null : p.similarity()));
    }

    @Test
    void theSameNameInSeveralCountriesDoesNotProposeACountry() throws Exception {
        String name = "Ministry of Education and Science";
        useRor(new FixedRor(List.of(
                record("lv1", name, "LV", "Latvia", "government"),
                record("bg1", name, "BG", "Bulgaria", "government"),
                record("ua1", name, "UA", "Ukraine", "government"))));

        RorNameProposal p = propose("u1", name);

        assertNotNull(p);
        assertEquals("", p.country(), "the auto-apply writes the proposal's country: it cannot be a guess among homonyms");
        assertTrue(p.reason().contains("homonym-countries=BG,LV,UA"), p.reason());
    }

    @Test
    void theCountryEgretaAlreadyHasPicksTheRightHomonym() throws Exception {
        String name = "Ministry of Education and Science";
        useRor(new FixedRor(List.of(
                record("lv1", name, "LV", "Latvia", "government"),
                record("ua1", name, "UA", "Ukraine", "government"))));
        egretaKnowsCountry("u1", "UA");

        RorNameProposal p = propose("u1", name);

        assertNotNull(p);
        assertEquals("https://ror.org/ua1", p.rorId());
        assertEquals("Ukraine", p.country());
        assertFalse(p.reason().contains("homonym"));
    }

    @Test
    void aRecordFromAnotherCountryIsNotProposedWhenEgretaKnowsTheCountry() throws Exception {
        // Egreta: Spain. ROR: the same name only in Chile. Proposing it would assign the wrong ROR id and country.
        useRor(new FixedRor(List.of(record("cl1", "Universidad SEK", "CL", "Chile", "education"))));
        egretaKnowsCountry("u1", "ES");

        assertNull(propose("u1", "Universidad SEK"));
    }
}
