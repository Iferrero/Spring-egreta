package com.example.demo.curation.service;

import com.example.demo.curation.model.OrgSimilarityProfile;
import com.example.demo.curation.model.SimilarityPairResult;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.example.demo.model.ExternalOrganization;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * Deep module encapsulating tokenization, similarity scoring, duplicate graph generation,
 * and high-performance pair discovery for external organizations.
 */
@Service
public class SimilarityMatchingEngine {

    /**
     * Converts a list of external organizations into normalized similarity profiles.
     */
    public List<OrgSimilarityProfile> buildSimilarityProfiles(List<ExternalOrganization> orgs) {
        if (orgs == null || orgs.isEmpty()) {
            return List.of();
        }
        List<OrgSimilarityProfile> profiles = new ArrayList<>(orgs.size());
        for (ExternalOrganization org : orgs) {
            String name = extractOrgDisplayName(org);
            if (name == null || name.isBlank()) continue;

            String normalized = CurationTextNormalizer.normalizeText(name);
            if (normalized.isBlank()) continue;

            String[] tokens = normalized.split("\\s+");
            if (tokens.length == 0) continue;

            String id = org.getUuid() != null && !org.getUuid().isBlank() ? org.getUuid() : org.getId();
            if (id == null || id.isBlank()) continue;

            profiles.add(new OrgSimilarityProfile(
                    id,
                    name,
                    org.getPureId(),
                    normalized,
                    tokens,
                    extractOrgTypeLabel(org),
                    extractOrgCountryLabel(org),
                    (org.getWorkflow() == null ? null : org.getWorkflow().getStep())
            ));
        }
        return profiles;
    }

    /**
     * Finds the pairs of organizations whose name similarity reaches {@code threshold}.
     *
     * <p>Candidates are generated through an inverted token index instead of comparing every pair:
     * a pair can only reach {@code threshold} if it matches at least {@code ceil(threshold * maxLen)}
     * tokens, so each organization only needs to be probed through its {@code len - need + 1} rarest
     * tokens (prefix filter). "Matching" includes the fuzzy rule of
     * {@link CurationTextNormalizer#calculateNameSimilarity} (edit distance &lt;= 2), which the index
     * honours through a precomputed token neighbourhood, so the result is the same as an exhaustive scan.
     *
     * <p>When more than {@code maxPairs} pairs qualify, the {@code maxPairs} most similar ones are kept
     * (ties broken by edge id) and {@code truncated} is set.
     */
    public SimilarityPairResult buildSimilarityPairs(
            List<OrgSimilarityProfile> profiles,
            double threshold,
            int maxPairs,
            IntConsumer progressCallback,
            Set<String> ignoredKeys
    ) {
        Set<String> connectedNodeIds = new HashSet<>();
        if (profiles == null || profiles.size() < 2) {
            return new SimilarityPairResult(new ArrayList<>(), connectedNodeIds, false);
        }

        int limit = Math.max(1, maxPairs);
        int n = profiles.size();
        TokenIndex index = TokenIndex.build(profiles);

        // Min-heap on quality: the root is the worst pair kept so far.
        PriorityQueue<ScoredPair> kept = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(a.similarity(), b.similarity());
            return c != 0 ? c : b.edgeId().compareTo(a.edgeId());
        });
        Set<String> edgeIds = new HashSet<>();
        boolean truncated = false;

        int[] seen = new int[n];
        int[] candidates = new int[n];
        int progressStep = Math.max(1, n / 100);

        for (int i = 0; i < n; i++) {
            if (progressCallback != null && i % progressStep == 0) {
                progressCallback.accept(Math.min(98, 16 + (int) ((long) i * 82 / n)));
            }
            OrgSimilarityProfile p1 = profiles.get(i);
            int count = index.collectCandidates(i, threshold, seen, candidates);

            for (int c = 0; c < count; c++) {
                int j = candidates[c];
                OrgSimilarityProfile p2 = profiles.get(j);

                int maxTokenLen = Math.max(p1.tokens().length, p2.tokens().length);
                int minTokenLen = Math.min(p1.tokens().length, p2.tokens().length);
                double maxPossible = maxTokenLen == 0 ? 0.0 : (double) minTokenLen / maxTokenLen;
                if (maxPossible < threshold) {
                    continue;
                }

                double similarity = CurationTextNormalizer.calculateNameSimilarity(p1.tokens(), p2.tokens(), threshold);
                if (similarity < threshold) {
                    continue;
                }

                String id1 = p1.id();
                String id2 = p2.id();
                String edgeId = id1.compareTo(id2) < 0 ? id1 + "|" + id2 : id2 + "|" + id1;
                if (ignoredKeys != null && ignoredKeys.contains(edgeId)) {
                    continue;
                }
                if (!edgeIds.add(edgeId)) {
                    continue;
                }

                kept.add(new ScoredPair(i, j, similarity, edgeId));
                if (kept.size() > limit) {
                    kept.poll();
                    truncated = true;
                }
            }
        }

        List<ScoredPair> ranked = new ArrayList<>(kept);
        ranked.sort((a, b) -> {
            int c = Double.compare(b.similarity(), a.similarity());
            return c != 0 ? c : a.edgeId().compareTo(b.edgeId());
        });

        List<Map<String, Object>> pairs = new ArrayList<>(ranked.size());
        for (ScoredPair sp : ranked) {
            OrgSimilarityProfile p1 = profiles.get(sp.left());
            OrgSimilarityProfile p2 = profiles.get(sp.right());
            String leftWorkflow = p1.workflowStep();
            String rightWorkflow = p2.workflowStep();
            pairs.add(Map.of(
                    "from", p1.id(),
                    "to", p2.id(),
                    "leftLabel", p1.displayName(),
                    "rightLabel", p2.displayName(),
                    "leftPureId", p1.pureId() == null ? "" : String.valueOf(p1.pureId()),
                    "rightPureId", p2.pureId() == null ? "" : String.valueOf(p2.pureId()),
                    // 4 decimals so clients can re-filter a result for a higher threshold without drift
                    "value", Math.round(sp.similarity() * 10000.0) / 10000.0,
                    "title", "Similitud: " + Math.round(sp.similarity() * 100.0) + "%",
                    "leftWorkflow", leftWorkflow != null ? leftWorkflow : "",
                    "rightWorkflow", rightWorkflow != null ? rightWorkflow : ""
            ));
            connectedNodeIds.add(p1.id());
            connectedNodeIds.add(p2.id());
        }

        if (progressCallback != null) {
            progressCallback.accept(98);
        }
        return new SimilarityPairResult(pairs, connectedNodeIds, truncated);
    }

    private record ScoredPair(int left, int right, double similarity, String edgeId) {}

    /**
     * Inverted token index with fuzzy token neighbourhoods (edit distance &lt;= 2), built once per run.
     */
    private static final class TokenIndex {
        private final int[][] tokensOf;   // per organization: token ids, one per occurrence
        private final int[][] postings;   // per token: ascending organization indexes containing it
        private final int[][] neighbours; // per token: other tokens within edit distance 2

        private TokenIndex(int[][] tokensOf, int[][] postings, int[][] neighbours) {
            this.tokensOf = tokensOf;
            this.postings = postings;
            this.neighbours = neighbours;
        }

        static TokenIndex build(List<OrgSimilarityProfile> profiles) {
            int n = profiles.size();
            Map<String, Integer> ids = new HashMap<>();
            List<String> vocab = new ArrayList<>();
            int[][] tokensOf = new int[n][];
            for (int i = 0; i < n; i++) {
                String[] tokens = profiles.get(i).tokens();
                int[] arr = new int[tokens.length];
                for (int k = 0; k < tokens.length; k++) {
                    Integer id = ids.get(tokens[k]);
                    if (id == null) {
                        id = vocab.size();
                        ids.put(tokens[k], id);
                        vocab.add(tokens[k]);
                    }
                    arr[k] = id;
                }
                tokensOf[i] = arr;
            }

            int v = vocab.size();
            int[] df = new int[v];
            int[] lastOrg = new int[v];
            Arrays.fill(lastOrg, -1);
            for (int i = 0; i < n; i++) {
                for (int t : tokensOf[i]) {
                    if (lastOrg[t] != i) {
                        lastOrg[t] = i;
                        df[t]++;
                    }
                }
            }
            int[][] postings = new int[v][];
            for (int t = 0; t < v; t++) {
                postings[t] = new int[df[t]];
            }
            int[] fill = new int[v];
            Arrays.fill(lastOrg, -1);
            for (int i = 0; i < n; i++) {
                for (int t : tokensOf[i]) {
                    if (lastOrg[t] != i) {
                        lastOrg[t] = i;
                        postings[t][fill[t]++] = i;
                    }
                }
            }
            return new TokenIndex(tokensOf, postings, buildNeighbours(vocab));
        }

        /**
         * Finds all token pairs at edit distance &lt;= 2 with a deletion index: two strings within
         * distance d share a variant obtained by deleting at most d characters from each. Variants are
         * stored as packed (40-bit hash, 24-bit token id) longs; hash collisions are harmless because every
         * candidate pair is verified with the real Levenshtein distance.
         */
        private static int[][] buildNeighbours(List<String> vocab) {
            int v = vocab.size();
            long total = 0;
            for (String s : vocab) {
                long len = s.length();
                total += 1 + len + len * (len - 1) / 2;
            }
            long[] entries = new long[(int) total];
            int m = 0;
            for (int id = 0; id < v; id++) {
                String s = vocab.get(id);
                int len = s.length();
                entries[m++] = pack(variantHash(s, -1, -1), id);
                for (int a = 0; a < len; a++) {
                    entries[m++] = pack(variantHash(s, a, -1), id);
                    for (int b = a + 1; b < len; b++) {
                        entries[m++] = pack(variantHash(s, a, b), id);
                    }
                }
            }
            Arrays.sort(entries, 0, m);

            long[] edges = new long[1024];
            int edgeCount = 0;
            int start = 0;
            while (start < m) {
                long key = entries[start] >>> 24;
                int end = start + 1;
                while (end < m && (entries[end] >>> 24) == key) end++;
                for (int a = start; a < end - 1; a++) {
                    int ida = (int) (entries[a] & 0xFFFFFFL);
                    String sa = vocab.get(ida);
                    for (int b = a + 1; b < end; b++) {
                        int idb = (int) (entries[b] & 0xFFFFFFL);
                        if (ida == idb) continue;
                        String sb = vocab.get(idb);
                        if (Math.abs(sa.length() - sb.length()) > 2) continue;
                        if (CurationTextNormalizer.levenshteinDistance(sa, sb) > 2) continue;
                        if (edgeCount == edges.length) edges = Arrays.copyOf(edges, edges.length * 2);
                        edges[edgeCount++] = ((long) Math.min(ida, idb) << 32) | Math.max(ida, idb);
                    }
                }
                start = end;
            }

            Arrays.sort(edges, 0, edgeCount);
            int[] degree = new int[v];
            long previous = -1;
            int unique = 0;
            for (int e = 0; e < edgeCount; e++) {
                if (edges[e] == previous) continue;
                previous = edges[e];
                edges[unique++] = previous;
                degree[(int) (previous >>> 32)]++;
                degree[(int) (previous & 0xFFFFFFFFL)]++;
            }
            int[][] neighbours = new int[v][];
            for (int t = 0; t < v; t++) neighbours[t] = new int[degree[t]];
            int[] pos = new int[v];
            for (int e = 0; e < unique; e++) {
                int a = (int) (edges[e] >>> 32);
                int b = (int) (edges[e] & 0xFFFFFFFFL);
                neighbours[a][pos[a]++] = b;
                neighbours[b][pos[b]++] = a;
            }
            return neighbours;
        }

        private static long pack(long hash, int id) {
            return ((hash >>> 24) << 24) | id;
        }

        private static long variantHash(String s, int skipA, int skipB) {
            long h = 0xcbf29ce484222325L;
            for (int k = 0; k < s.length(); k++) {
                if (k == skipA || k == skipB) continue;
                h ^= s.charAt(k);
                h *= 0x100000001b3L;
            }
            return h ^ (h >>> 29);
        }

        /**
         * Writes into {@code out} every organization {@code j > i} that may reach {@code threshold}
         * against organization {@code i}, and returns how many were written.
         */
        int collectCandidates(int i, double threshold, int[] seen, int[] out) {
            int n = tokensOf.length;
            int count = 0;
            if (threshold <= 0.0) {
                for (int j = i + 1; j < n; j++) out[count++] = j;
                return count;
            }

            int[] tokens = tokensOf[i];
            int len = tokens.length;
            int need = Math.min(len, Math.max(1, (int) Math.ceil(threshold * len - 1e-9)));
            int probe = len - need + 1;

            // Probe through the rarest tokens only: matching `need` of `len` tokens leaves at most
            // `len - need` unmatched, so one of any `len - need + 1` tokens must match.
            Integer[] order = new Integer[len];
            for (int k = 0; k < len; k++) order[k] = tokens[k];
            Arrays.sort(order, (a, b) -> {
                int c = Integer.compare(postings[a].length, postings[b].length);
                return c != 0 ? c : Integer.compare(a, b);
            });

            int stamp = i + 1;
            for (int k = 0; k < probe; k++) {
                int token = order[k];
                count = collectFrom(postings[token], i, stamp, seen, out, count);
                for (int neighbour : neighbours[token]) {
                    count = collectFrom(postings[neighbour], i, stamp, seen, out, count);
                }
            }
            return count;
        }

        private static int collectFrom(int[] posting, int i, int stamp, int[] seen, int[] out, int count) {
            int idx = Arrays.binarySearch(posting, i);
            idx = idx >= 0 ? idx + 1 : -idx - 1;
            for (; idx < posting.length; idx++) {
                int j = posting[idx];
                if (seen[j] != stamp) {
                    seen[j] = stamp;
                    out[count++] = j;
                }
            }
            return count;
        }
    }

    /**
     * Builds graph structure (nodes & edges) representing interconnected organizations.
     */
    public Map<String, Object> buildGraphSimilarity(
            List<OrgSimilarityProfile> profiles,
            double threshold,
            int limit,
            int totalAvailableOrgs,
            Set<String> ignoredKeys
    ) {
        List<Map<String, Object>> nodes = profiles.stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.id(),
                        "label", p.displayName(),
                        "title", p.displayName() + " | " + (p.typeLabel().isBlank() ? "(sin tipo)" : p.typeLabel()) + " | " + (p.countryLabel().isBlank() ? "(sin país)" : p.countryLabel()),
                        "type", p.typeLabel().isBlank() ? "(sin tipo)" : p.typeLabel(),
                        "country", p.countryLabel().isBlank() ? "(sin país)" : p.countryLabel(),
                        "color", p.typeLabel().isBlank() ? "#ccc" : "#4CAF50"
                ))
                .collect(Collectors.toList());

        SimilarityPairResult pairResult = buildSimilarityPairs(profiles, threshold, Integer.MAX_VALUE, null, ignoredKeys);
        List<Map<String, Object>> edges = pairResult.pairs().stream()
                .map(p -> Map.of(
                        "from", p.get("from"),
                        "to", p.get("to"),
                        "value", p.get("value"),
                        "title", "Similitud: " + Math.round(((Number) p.get("value")).doubleValue() * 100.0) + "%"
                ))
                .collect(Collectors.toList());

        Set<String> connectedNodeIds = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            connectedNodeIds.add(String.valueOf(edge.get("from")));
            connectedNodeIds.add(String.valueOf(edge.get("to")));
        }

        List<Map<String, Object>> connectedNodes = nodes.stream()
                .filter(node -> connectedNodeIds.contains(String.valueOf(node.get("id"))))
                .collect(Collectors.toList());

        return Map.of(
                "nodes", connectedNodes,
                "edges", edges,
                "threshold", threshold,
                "limit", limit,
                "totalOrgs", connectedNodes.size(),
                "totalProcessedOrgs", profiles.size(),
                "totalAvailableOrgs", totalAvailableOrgs,
                "totalNodes", connectedNodes.size(),
                "totalEdges", edges.size()
        );
    }

    /**
     * Computes similarity pairs result with metadata counts.
     */
    public Map<String, Object> buildPairsSimilarityResult(
            List<OrgSimilarityProfile> profiles,
            double threshold,
            int limit,
            int maxPairs,
            int totalAvailableOrgs,
            Set<String> ignoredKeys,
            IntConsumer progressCallback
    ) {
        SimilarityPairResult pairResult = buildSimilarityPairs(profiles, threshold, maxPairs, progressCallback, ignoredKeys);

        return Map.of(
                "pairs", pairResult.pairs(),
                "threshold", threshold,
                "limit", limit,
                "maxPairs", maxPairs,
                "truncated", pairResult.truncated(),
                "totalPairs", pairResult.pairs().size(),
                "totalConnectedOrgs", pairResult.connectedNodeIds().size(),
                "totalProcessedOrgs", profiles.size(),
                "totalAvailableOrgs", totalAvailableOrgs
        );
    }

    public String extractOrgDisplayName(ExternalOrganization org) {
        if (org == null) return "";
        if (org.getDisplayName() != null && !org.getDisplayName().isBlank()) {
            return org.getDisplayName();
        }
        if (org.getName() != null) {
            return org.getName().getOrDefault("en_GB",
                    org.getName().getOrDefault("es_ES",
                            org.getName().getOrDefault("ca_ES", "")));
        }
        return "";
    }

    public String extractOrgTypeLabel(ExternalOrganization org) {
        if (org != null && org.getType() != null && org.getType().getTerm() != null) {
            return org.getType().getTerm().getOrDefault("en_GB",
                    org.getType().getTerm().getOrDefault("es_ES",
                            org.getType().getTerm().getOrDefault("ca_ES", "")));
        }
        return "";
    }

    public String extractOrgCountryLabel(ExternalOrganization org) {
        if (org != null && org.getAddress() != null && org.getAddress().getCountry() != null && org.getAddress().getCountry().getTerm() != null) {
            return org.getAddress().getCountry().getTerm().getOrDefault("en_GB",
                    org.getAddress().getCountry().getTerm().getOrDefault("es_ES",
                            org.getAddress().getCountry().getTerm().getOrDefault("ca_ES", "")));
        }
        return "";
    }
}
