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

    private static OrgSimilarityProfile profile(String id, String name) {
        String normalized = CurationTextNormalizer.normalizeText(name);
        return new OrgSimilarityProfile(id, name, null, normalized, normalized.split("\\s+"), "", "", "step");
    }

    @Test
    void testIndexedSearchMatchesExhaustiveScan() {
        String[] words = {"universitat", "autonoma", "barcelona", "hospital", "clinic", "institut", "recerca",
                "ciencies", "mar", "de", "la", "of", "the", "university", "cambridge", "oxford", "lab", "a", "ab"};
        java.util.Random random = new java.util.Random(42);
        List<OrgSimilarityProfile> profiles = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            int len = 1 + random.nextInt(5);
            StringBuilder name = new StringBuilder();
            for (int k = 0; k < len; k++) {
                String w = words[random.nextInt(words.length)];
                if (w.length() > 3 && random.nextInt(4) == 0) {
                    int cut = random.nextInt(w.length());
                    w = random.nextBoolean() ? w.substring(0, cut) + w.substring(cut + 1) : w.substring(0, cut) + "x" + w.substring(cut);
                }
                name.append(w).append(' ');
            }
            profiles.add(profile("id-" + i, name.toString().trim()));
        }

        for (double threshold : new double[]{0.0, 0.5, 0.8, 0.85, 0.9, 1.0}) {
            Set<String> expected = new java.util.HashSet<>();
            for (int i = 0; i < profiles.size(); i++) {
                for (int j = i + 1; j < profiles.size(); j++) {
                    OrgSimilarityProfile a = profiles.get(i);
                    OrgSimilarityProfile b = profiles.get(j);
                    int max = Math.max(a.tokens().length, b.tokens().length);
                    int min = Math.min(a.tokens().length, b.tokens().length);
                    if ((double) min / max < threshold) continue;
                    if (CurationTextNormalizer.calculateNameSimilarity(a.tokens(), b.tokens(), threshold) >= threshold) {
                        expected.add(a.id() + "|" + b.id());
                    }
                }
            }

            SimilarityPairResult res = engine.buildSimilarityPairs(profiles, threshold, Integer.MAX_VALUE, null, Set.of());
            Set<String> actual = new java.util.HashSet<>();
            for (Map<String, Object> p : res.pairs()) {
                String from = (String) p.get("from");
                String to = (String) p.get("to");
                actual.add(from.compareTo(to) < 0 ? from + "|" + to : to + "|" + from);
            }
            Set<String> expectedNormalized = new java.util.HashSet<>();
            for (String e : expected) {
                String[] parts = e.split("\\|");
                expectedNormalized.add(parts[0].compareTo(parts[1]) < 0 ? e : parts[1] + "|" + parts[0]);
            }
            assertFalse(res.truncated());
            assertEquals(expectedNormalized, actual, "Pairs differ from the exhaustive scan at threshold " + threshold);
        }
    }

    @Test
    void testTruncationKeepsMostSimilarPairs() {
        // Early pairs are weaker (3 of 4 tokens) than the exact duplicates found later.
        List<OrgSimilarityProfile> profiles = List.of(
                profile("a1", "alpha beta gamma delta"),
                profile("a2", "alpha beta gamma omega"),
                profile("z1", "zulu yankee xray whiskey"),
                profile("z2", "zulu yankee xray whiskey"),
                profile("k1", "kilo lima mike november"),
                profile("k2", "kilo lima mike november")
        );

        SimilarityPairResult res = engine.buildSimilarityPairs(profiles, 0.7, 2, null, Set.of());

        assertTrue(res.truncated());
        assertEquals(2, res.pairs().size());
        for (Map<String, Object> p : res.pairs()) {
            assertEquals(1.0, (Double) p.get("value"), 0.0001, "Only the exact duplicates should survive truncation");
        }
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
