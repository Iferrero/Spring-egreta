package com.example.demo;

import com.example.demo.controller.ExternalOrganizationController;
import com.example.demo.model.ExternalOrganization;
import com.example.demo.repository.ExternalOrganizationDifferentRepository;
import com.example.demo.repository.ExternalOrganizationRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A type can only be linked to one of the categories that exist in Egreta's list; nothing new may appear.
 * The fixture reproduces the real shape of the classification scheme: term stored as {ca_ES, es_ES, en_GB},
 * the Pure defaults flagged "disabled", an "unknown" entry and the scheme's root container.
 */
public class ExternalOrganizationTypeCatalogTest {

    private static final String BASE = "/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes/ueoexternalorganisation";

    private ExternalOrganizationController controller;

    private static Document entry(String tail, String ca, String es, String en, boolean disabled) {
        return new Document("uri", BASE + "/" + tail)
                .append("term", new Document("ca_ES", ca).append("es_ES", es).append("en_GB", en))
                .append("disabled", disabled);
    }

    @BeforeEach
    void setUp() {
        Document scheme = new Document("baseUri", "/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes")
                .append("containedClassifications", List.of(
                        new Document("uri", "/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes/ueoexternalorganisation")
                                .append("term", new Document("ca_ES", "Tipus d'Organitzacions externes")).append("disabled", false),
                        entry("unknown", "Desconegut", "Pendiente de Clasificación", "Rating Pending", false),
                        entry("academic", "Acadèmic", "Académico", "Academic", true),
                        entry("000", "Universitat", "Universidad", "University", false),
                        entry("040", "Fundació", "Fundación", "Foundation", false),
                        entry("130", "Associacions i agrupacions", "Asociaciones y agrupaciones", "Associations and groups", false)));

        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("Classificationschemes"))).thenReturn(scheme);
        controller = new ExternalOrganizationController(
                mock(ExternalOrganizationRepository.class), mock(ExternalOrganizationDifferentRepository.class), mongo);
    }

    @SuppressWarnings("unchecked")
    private <T> T call(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = ExternalOrganizationController.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (T) m.invoke(controller, args);
    }

    private ExternalOrganization.UriTerm byLabel(String label) throws Exception {
        return call("resolveTypeUriTerm", new Class<?>[]{String.class, ExternalOrganization.UriTerm.class}, label, null);
    }

    private ExternalOrganization.UriTerm byUri(String uri) throws Exception {
        return call("resolveTypeUriTermByUri", new Class<?>[]{String.class, ExternalOrganization.UriTerm.class}, uri, null);
    }

    @Test
    void catalogComesFromTheSchemeAndHasOnlyEnabledCategories() {
        List<String> labels = controller.typeCatalog().stream().map(t -> t.get("label")).toList();
        assertEquals(List.of("Associacions i agrupacions", "Fundació", "Universitat"), labels);
    }

    @Test
    void anExistingCategoryResolvesByLabelInAnyLanguage() throws Exception {
        ExternalOrganization.UriTerm byCa = byLabel("Fundació");
        assertNotNull(byCa);
        assertEquals(BASE + "/040", byCa.getUri());
        assertEquals("Fundación", byCa.getTerm().get("es_ES"));
        assertEquals("Foundation", byCa.getTerm().get("en_GB"));

        assertEquals(BASE + "/000", byLabel("University").getUri());
        assertEquals(BASE + "/130", byLabel("asociaciones y agrupaciones").getUri());
    }

    @Test
    void aLabelThatIsNotACategoryIsRefusedInsteadOfCreatingOne() throws Exception {
        assertNull(byLabel("Hospital"));
        assertNull(byLabel("Administració pública"));
        assertNull(byLabel("funder"));
        assertNull(byLabel("Acadèmic"), "Pure defaults are disabled and must not be assignable");
        assertNull(byLabel("Desconegut"), "'unknown' is not a category either");
    }

    @Test
    void anExistingUriResolvesAndAnInventedUriIsRefused() throws Exception {
        ExternalOrganization.UriTerm term = byUri(BASE + "/040");
        assertNotNull(term);
        assertEquals("Fundació", term.getTerm().get("ca_ES"));

        assertNull(byUri(BASE + "/999"));
        assertNull(byUri(BASE + "/academic"));
        assertNull(byUri("/some/other/uri"));
    }

    @Test
    void suggestedTypesOutsideTheCatalogAreDropped() throws Exception {
        List<ExternalOrganizationController.TypeAggregate> catalog = List.of(
                new ExternalOrganizationController.TypeAggregate("Fundació", "fundacio", 10),
                new ExternalOrganizationController.TypeAggregate("Universitat", "universitat", 10));
        Class<?> resultType = ExternalOrganizationController.TypeInferenceResult.class;

        Object free = new ExternalOrganizationController.TypeInferenceResult("Hospital", 0.95, "openalex-type");
        ExternalOrganizationController.TypeInferenceResult dropped = call("onlyCatalogType", new Class<?>[]{resultType, List.class}, free, catalog);
        assertEquals("", dropped.typeLabel());
        assertEquals(0.0, dropped.confidence());
        assertTrue(dropped.reason().startsWith("not-in-catalog"));

        Object ok = new ExternalOrganizationController.TypeInferenceResult("fundacio", 0.9, "keyword-hint");
        ExternalOrganizationController.TypeInferenceResult kept = call("onlyCatalogType", new Class<?>[]{resultType, List.class}, ok, catalog);
        assertEquals("Fundació", kept.typeLabel());
        assertEquals(0.9, kept.confidence());

    }
}
