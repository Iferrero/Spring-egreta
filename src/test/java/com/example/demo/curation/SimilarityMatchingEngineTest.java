package com.example.demo.curation;

import com.example.demo.curation.model.OrgSimilarityProfile;
import com.example.demo.curation.model.SimilarityPairResult;
import com.example.demo.curation.service.SimilarityMatchingEngine;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.example.demo.model.ExternalOrganization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SimilarityMatchingEngineTest {

    private SimilarityMatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SimilarityMatchingEngine();
    }

    @Test
    void testCalculateNameSimilarityExactAndFuzzy() {
        String[] t1 = "universitat autonoma de barcelona".split("\\s+");
        String[] t2 = "universitat autonoma de barcelona".split("\\s+");
        assertEquals(1.0, CurationTextNormalizer.calculateNameSimilarity(t1, t2, 0.8), 0.001);

        // Minor typo ("barcelon" instead of "barcelona", dist = 1)
        String[] t3 = "universitat autonoma de barcelon".split("\\s+");
        double fuzzySim = CurationTextNormalizer.calculateNameSimilarity(t1, t3, 0.8);
        assertTrue(fuzzySim >= 0.9, "Fuzzy match should exceed 0.9, was: " + fuzzySim);

        // Completely different
        String[] t4 = "tokyo institute of technology".split("\\s+");
        double diffSim = CurationTextNormalizer.calculateNameSimilarity(t1, t4, 0.5);
        assertEquals(0.0, diffSim, 0.001);
    }

    @Test
    void testBuildSimilarityProfilesFromOrganizations() {
        ExternalOrganization org1 = new ExternalOrganization();
        org1.setUuid("uuid-1");
        org1.setPureId(101L);
        Map<String, String> names = new HashMap<>();
        names.put("ca_ES", "Institut de Ciències del Mar");
        org1.setName(names);

        List<OrgSimilarityProfile> profiles = engine.buildSimilarityProfiles(List.of(org1));
        assertEquals(1, profiles.size());
        OrgSimilarityProfile p = profiles.get(0);
        assertEquals("uuid-1", p.id());
        assertEquals("Institut de Ciències del Mar", p.displayName());
        assertTrue(p.normalizedName().contains("ciencies"));
        assertTrue(p.tokens().length >= 4);
    }

    @Test
    void testBuildSimilarityPairsWithPruningAndIgnored() {
        OrgSimilarityProfile p1 = new OrgSimilarityProfile(
                "org-1", "Hospital Clinic de Barcelona", 10L, "hospital clinic de barcelona",
                "hospital clinic de barcelona".split("\\s+"), "Hospital", "Spain", "validated"
        );
        OrgSimilarityProfile p2 = new OrgSimilarityProfile(
                "org-2", "Hospital Clínic Barcelona", 11L, "hospital clinic barcelona",
                "hospital clinic barcelona".split("\\s+"), "Hospital", "Spain", "approved"
        );
        OrgSimilarityProfile p3 = new OrgSimilarityProfile(
                "org-3", "University of Cambridge", 12L, "university of cambridge",
                "university of cambridge".split("\\s+"), "University", "United Kingdom", "approved"
        );

        AtomicInteger progressReached = new AtomicInteger(0);
        SimilarityPairResult res = engine.buildSimilarityPairs(
                List.of(p1, p2, p3),
                0.70,
                10,
                progressReached::set,
                Set.of()
        );

        assertFalse(res.truncated());
        assertEquals(1, res.pairs().size());
        Map<String, Object> pair = res.pairs().get(0);
        assertEquals("org-1", pair.get("from"));
        assertEquals("org-2", pair.get("to"));
        assertTrue(res.connectedNodeIds().contains("org-1"));
        assertTrue(res.connectedNodeIds().contains("org-2"));
        assertFalse(res.connectedNodeIds().contains("org-3"));

        // Now test with ignored keys
        String edgeKey = "org-1|org-2";
        SimilarityPairResult ignoredRes = engine.buildSimilarityPairs(
                List.of(p1, p2, p3),
                0.70,
                10,
                null,
                Set.of(edgeKey)
        );
        assertEquals(0, ignoredRes.pairs().size(), "Should have ignored the pair");
    }

    @Test
    void testBuildGraphSimilarity() {
        OrgSimilarityProfile p1 = new OrgSimilarityProfile(
                "a", "Institute Alpha", 1L, "institute alpha",
                "institute alpha".split("\\s+"), "Research", "Spain", "step"
        );
        OrgSimilarityProfile p2 = new OrgSimilarityProfile(
                "b", "Institute Alfa", 2L, "institute alfa",
                "institute alfa".split("\\s+"), "Research", "Spain", "step"
        );
        OrgSimilarityProfile p3 = new OrgSimilarityProfile(
                "c", "Completely Separate Org", 3L, "completely separate org",
                "completely separate org".split("\\s+"), "Other", "France", "step"
        );

        Map<String, Object> graph = engine.buildGraphSimilarity(List.of(p1, p2, p3), 0.70, 100, 3, Set.of());
        assertNotNull(graph);
        assertEquals(2, ((List<?>) graph.get("nodes")).size()); // only a and b are connected
        assertEquals(1, ((List<?>) graph.get("edges")).size());
        assertEquals(3, graph.get("totalAvailableOrgs"));
    }
}
