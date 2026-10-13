package com.example.demo.curation.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Clean text normalization, word matching, and Levenshtein similarity utilities.
 */
public final class CurationTextNormalizer {

    private static final ThreadLocal<int[]> LEVENSHTEIN_BUFFER = ThreadLocal.withInitial(() -> new int[128]);

    private static final Set<String> STOP_WORDS = Set.of(
            "de", "del", "la", "el", "les", "los", "las", "d", "l", "i", "y", "and", "of", "the",
            "for", "in", "a", "at", "on", "to", "und", "et", "du", "des", "von", "van", "der", "die", "das"
    );

    private CurationTextNormalizer() {}

    public static String normalizeText(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean containsWord(String text, String term) {
        if (text == null || term == null || text.isBlank() || term.isBlank()) {
            return false;
        }
        String normalizedText = normalizeText(text);
        String normalizedTerm = normalizeText(term);
        if (normalizedText.isBlank() || normalizedTerm.isBlank()) {
            return false;
        }
        String regex = "\\b" + Pattern.quote(normalizedTerm) + "\\b";
        return Pattern.compile(regex).matcher(normalizedText).find();
    }

    public static double calculateStringSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String n1 = normalizeText(s1).replaceAll("\\s+", "");
        String n2 = normalizeText(s2).replaceAll("\\s+", "");
        if (n1.isEmpty() && n2.isEmpty()) return 1.0;
        if (n1.isEmpty() || n2.isEmpty()) return 0.0;
        if (n1.equals(n2)) return 1.0;
        int maxLen = Math.max(n1.length(), n2.length());
        int dist = levenshteinDistance(n1, n2);
        return Math.max(0.0, (double) (maxLen - dist) / maxLen);
    }

    public static int levenshteinDistance(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        if (a.length() < b.length()) {
            String temp = a;
            a = b;
            b = temp;
        }

        int bLen = b.length();
        if (bLen >= 128) {
            int[] dp = new int[bLen + 1];
            return levenshteinDistanceWithArray(a, b, dp);
        }

        int[] dp = LEVENSHTEIN_BUFFER.get();
        return levenshteinDistanceWithArray(a, b, dp);
    }

    private static int levenshteinDistanceWithArray(String a, String b, int[] dp) {
        int bLen = b.length();
        for (int i = 0; i <= bLen; i++) {
            dp[i] = i;
        }

        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            char charA = a.charAt(i - 1);
            for (int j = 1; j <= bLen; j++) {
                int temp = dp[j];
                int cost = charA == b.charAt(j - 1) ? 0 : 1;
                dp[j] = Math.min(Math.min(dp[j - 1] + 1, dp[j] + 1), prev + cost);
                prev = temp;
            }
        }
        return dp[bLen];
    }

    public static double calculateNameMatchScore(String queryNorm, String candidateNorm, boolean isAcronym) {
        if (queryNorm.isBlank() || candidateNorm.isBlank()) return 0.0;
        if (queryNorm.equals(candidateNorm)) {
            return 1.0;
        }
        if (isAcronym && queryNorm.equalsIgnoreCase(candidateNorm)) {
            return 0.95;
        }

        String[] queryTokens = queryNorm.split("\\s+");
        String[] candTokens = candidateNorm.split("\\s+");
        Set<String> querySet = new HashSet<>(Arrays.asList(queryTokens));
        Set<String> candSet = new HashSet<>(Arrays.asList(candTokens));

        querySet.removeAll(STOP_WORDS);
        candSet.removeAll(STOP_WORDS);

        if (querySet.isEmpty() || candSet.isEmpty()) {
            return calculateStringSimilarity(queryNorm, candidateNorm);
        }

        int intersection = 0;
        for (String q : querySet) {
            if (candSet.contains(q)) intersection++;
        }

        double tokenScore = (double) (2 * intersection) / (querySet.size() + candSet.size());
        double strScore = calculateStringSimilarity(queryNorm, candidateNorm);
        return Math.max(tokenScore, strScore);
    }

    public static double calculateNameSimilarity(String[] parts1, String[] parts2, double threshold) {
        if (parts1 == null || parts2 == null || parts1.length == 0 || parts2.length == 0) return 0.0;
        if (parts1.length == parts2.length) {
            boolean allEqual = true;
            for (int i = 0; i < parts1.length; i++) {
                if (!parts1[i].equals(parts2[i])) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) return 1.0;
        }

        String[] shorter = parts1.length <= parts2.length ? parts1 : parts2;
        String[] longer = parts1.length <= parts2.length ? parts2 : parts1;
        int maxLen = longer.length;

        boolean[] usedLong = new boolean[longer.length];
        boolean[] matchedShort = new boolean[shorter.length];

        int exactMatches = 0;
        for (int i = 0; i < shorter.length; i++) {
            String token = shorter[i];
            for (int j = 0; j < longer.length; j++) {
                if (!usedLong[j] && token.equals(longer[j])) {
                    usedLong[j] = true;
                    matchedShort[i] = true;
                    exactMatches++;
                    break;
                }
            }

            int remaining = shorter.length - i;
            double optimistic = (exactMatches + remaining) / (double) maxLen;
            if (optimistic < threshold) {
                return exactMatches / (double) maxLen;
            }
        }

        int fuzzyMatches = 0;
        for (int i = 0; i < shorter.length; i++) {
            if (matchedShort[i]) {
                continue;
            }

            String token = shorter[i];
            int bestIndex = -1;
            int bestDistance = Integer.MAX_VALUE;

            for (int j = 0; j < longer.length; j++) {
                if (usedLong[j]) {
                    continue;
                }

                String candidate = longer[j];
                if (Math.abs(token.length() - candidate.length()) > 2) {
                    continue;
                }

                int distance = levenshteinDistance(token, candidate);
                if (distance <= 2 && distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = j;
                    if (distance == 0) {
                        break;
                    }
                }
            }

            if (bestIndex >= 0) {
                usedLong[bestIndex] = true;
                fuzzyMatches++;
            }

            int unmatchedRemaining = shorter.length - (i + 1);
            double optimistic = (exactMatches + fuzzyMatches + unmatchedRemaining) / (double) maxLen;
            if (optimistic < threshold) {
                break;
            }
        }

        return (exactMatches + fuzzyMatches) / (double) maxLen;
    }

    public static double confidenceFromCount(double base, int count) {
        double bonus = Math.min(0.10, Math.log10(Math.max(1, count) + 1) / 20.0);
        return Math.round(Math.min(0.98, base + bonus) * 100.0) / 100.0;
    }

    public static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
