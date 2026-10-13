package com.example.demo.curation.service;

import com.example.demo.curation.model.OrgSimilarityProfile;
import com.example.demo.curation.model.SimilarityPairResult;
import com.example.demo.curation.util.CurationTextNormalizer;
import com.example.demo.model.ExternalOrganization;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
     * Finds pairwise candidate duplicate matches with token-length upper-bound pruning
     * and optimistic Levenshtein alignment.
     */
    public SimilarityPairResult buildSimilarityPairs(
            List<OrgSimilarityProfile> profiles,
            double threshold,
            int maxPairs,
            IntConsumer progressCallback,
            Set<String> ignoredKeys
    ) {
        List<Map<String, Object>> pairs = new ArrayList<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> connectedNodeIds = new HashSet<>();
        boolean truncated = false;

        if (profiles == null || profiles.size() < 2) {
            return new SimilarityPairResult(pairs, connectedNodeIds, false);
        }

        int n = profiles.size();
        long totalComparisons = ((long) n * (n - 1)) / 2;
        long processedComparisons = 0;
        long progressTick = Math.max(1, totalComparisons / 120);

        for (int i = 0; i < profiles.size(); i++) {
            for (int j = i + 1; j < profiles.size(); j++) {
                processedComparisons++;
                OrgSimilarityProfile p1 = profiles.get(i);
                OrgSimilarityProfile p2 = profiles.get(j);

                if (progressCallback != null && (processedComparisons % progressTick == 0 || processedComparisons == totalComparisons)) {
                    int p = totalComparisons == 0 ? 98 : (int) (16 + (processedComparisons * 82 / totalComparisons));
                    progressCallback.accept(Math.min(98, p));
                }

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

                String leftWorkflow = p1.workflowStep();
                String rightWorkflow = p2.workflowStep();

                pairs.add(Map.of(
                        "from", id1,
                        "to", id2,
                        "leftLabel", p1.displayName(),
                        "rightLabel", p2.displayName(),
                        "leftPureId", p1.pureId() == null ? "" : String.valueOf(p1.pureId()),
                        "rightPureId", p2.pureId() == null ? "" : String.valueOf(p2.pureId()),
                        "value", Math.round(similarity * 100.0) / 100.0,
                        "title", "Similitud: " + Math.round(similarity * 100.0) + "%",
                        "leftWorkflow", leftWorkflow != null ? leftWorkflow : "",
                        "rightWorkflow", rightWorkflow != null ? rightWorkflow : ""
                ));
                connectedNodeIds.add(id1);
                connectedNodeIds.add(id2);

                if (pairs.size() >= maxPairs) {
                    truncated = true;
                    if (progressCallback != null) {
                        progressCallback.accept(98);
                    }
                    return new SimilarityPairResult(pairs, connectedNodeIds, truncated);
                }
            }
        }

        return new SimilarityPairResult(pairs, connectedNodeIds, truncated);
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
