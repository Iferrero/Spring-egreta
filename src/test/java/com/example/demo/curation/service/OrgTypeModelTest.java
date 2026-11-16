package com.example.demo.curation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model is trained in Python (scikit-learn) and scored in Java. These tests prove both give the same numbers:
 * the fixtures were produced by scikit-learn from exactly the weights stored in models/org-type-model.json.gz.
 */
public class OrgTypeModelTest {

    @Test
    void javaScoringMatchesScikitLearn() throws Exception {
        OrgTypeModel model = new OrgTypeModel();
        assertTrue(model.isAvailable(), "models/org-type-model.json.gz must be on the classpath");

        JsonNode fixtures;
        try (InputStream in = getClass().getResourceAsStream("/org-type-model-fixtures.json")) {
            assertNotNull(in, "fixtures file missing: run scripts/train_type_model.py");
            fixtures = new ObjectMapper().readTree(in);
        }
        assertTrue(fixtures.size() >= 50);

        int checked = 0;
        for (JsonNode f : fixtures) {
            String name = f.get("name").asText();
            if (name.isBlank()) {
                assertTrue(model.topOptions(name, f.get("country").asText(), 3).isEmpty(), "a blank name has no options");
                continue;
            }
            JsonNode expected = f.get("top");
            List<OrgTypeModel.Option> got = model.topOptions(name, f.get("country").asText(), expected.size());
            assertEquals(expected.size(), got.size(), name);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).get("label").asText(), got.get(i).label(), "rank " + (i + 1) + " for '" + name + "'");
                assertEquals(expected.get(i).get("p").asDouble(), got.get(i).probability(), 2e-4,
                        "probability of rank " + (i + 1) + " for '" + name + "'");
            }
            checked++;
        }
        assertTrue(checked >= 50, "only " + checked + " fixtures were compared");
    }

    @Test
    void optionsAreSortedAndFormADistribution() {
        OrgTypeModel model = new OrgTypeModel();
        List<OrgTypeModel.Option> top = model.topOptions("Fundació Privada Pare Manel", "Spain", 3);
        assertEquals(3, top.size());
        assertTrue(top.get(0).probability() >= top.get(1).probability());
        assertTrue(top.get(1).probability() >= top.get(2).probability());
        assertTrue(top.stream().anyMatch(o -> o.label().equals("Fundació")), "a foundation must be among its own top 3: " + top);

        List<OrgTypeModel.Option> all = model.topOptions("Fundació Privada Pare Manel", "Spain", 100);
        assertEquals(1.0, all.stream().mapToDouble(OrgTypeModel.Option::probability).sum(), 1e-6);
    }

    @Test
    void aMissingModelDisablesTheOptionsInsteadOfFailing() throws Exception {
        OrgTypeModel model = new OrgTypeModel();
        Field resource = OrgTypeModel.class.getDeclaredField("resource");
        resource.setAccessible(true);
        resource.set(model, "models/does-not-exist.json.gz");

        assertFalse(model.isAvailable());
        assertTrue(model.topOptions("Universitat de Prova", "Spain", 3).isEmpty());
    }

    @Test
    void featureExtractionFollowsScikitLearn() {
        // char_wb pads each word with spaces, takes n-grams of 2..5 and counts a word shorter than n only once
        assertEquals(List.of(" a", "a ", " a "), OrgTypeModel.charWbNgrams("a"));
        assertEquals(List.of(" a", "ab", "b ", " ab", "ab ", " ab "), OrgTypeModel.charWbNgrams("ab"));
        // unigrams followed by bigrams
        assertEquals(List.of("fundacio", "pare", "manel", "fundacio pare", "pare manel"), OrgTypeModel.wordNgrams("fundacio pare manel"));
    }
}
