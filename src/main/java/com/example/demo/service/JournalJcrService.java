package com.example.demo.service;

import com.example.demo.model.Jcr;
import com.example.demo.model.Journal;
import com.example.demo.repository.JournalRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JournalJcrService {

    private static final long ISSN_CACHE_TTL_MS = 600_000;

    private final JournalRepository journalRepository;
    private final MongoTemplate jcrMongoTemplate;
    private final Map<String, CacheEntry> jcrByIssnCache = new ConcurrentHashMap<>();

    private record CacheEntry(List<Jcr> data, long expiresAtMs) {}

    public JournalJcrService(
            JournalRepository journalRepository,
            @Qualifier("jcrMongoTemplate") MongoTemplate jcrMongoTemplate) {
        this.journalRepository = journalRepository;
        this.jcrMongoTemplate = jcrMongoTemplate;
    }

    public Optional<Map<String, Object>> findJcrByJournalUuid(String journalUuid) {
        return journalRepository.findByUuid(journalUuid).map(this::buildLinkResponse);
    }

    public List<Jcr> findJcrByIssn(String issn) {
        String normalizedKey = normalizeIssn(issn);
        if (normalizedKey == null) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = jcrByIssnCache.get(normalizedKey);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.data();
        }

        Set<String> variants = issnVariants(issn);
        if (variants.isEmpty()) {
            return List.of();
        }

        Query query = new Query(new Criteria().orOperator(
                Criteria.where("issn").in(variants),
                Criteria.where("eIssn").in(variants),
                Criteria.where("previousIssn").in(variants)
        ));

        List<Jcr> candidates = jcrMongoTemplate.find(query, Jcr.class, "Journals");
        String normalizedInput = normalizedKey;

        List<Jcr> filtered = new ArrayList<>();
        for (Jcr item : candidates) {
            if (item != null && item.matchesIssn(normalizedInput)) {
                filtered.add(item);
            }
        }
        List<Jcr> deduped = List.copyOf(dedupeById(filtered));
        jcrByIssnCache.put(normalizedKey, new CacheEntry(deduped, now + ISSN_CACHE_TTL_MS));
        cleanupExpiredIssnCache(now);
        return deduped;
    }

    private void cleanupExpiredIssnCache(long now) {
        if (jcrByIssnCache.size() <= 8192) {
            return;
        }
        jcrByIssnCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    /**
     * Batch-warms the ISSN cache for a set of raw ISSNs using a single MongoDB query.
     * ISSNs already cached (and still valid) are skipped. This should be called before
     * the per-publication resolution loop so that subsequent {@link #findJcrByIssn} calls
     * are always cache hits.
     */
    public void warmJcrCacheForIssns(Collection<String> rawIssns) {
        if (rawIssns == null || rawIssns.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();

        // Determine which normalized ISSNs are not yet cached.
        Set<String> uncachedNormalized = new LinkedHashSet<>();
        for (String raw : rawIssns) {
            String normalized = normalizeIssn(raw);
            if (normalized == null) {
                continue;
            }
            CacheEntry cached = jcrByIssnCache.get(normalized);
            if (cached == null || cached.expiresAtMs() <= now) {
                uncachedNormalized.add(normalized);
            }
        }

        if (uncachedNormalized.isEmpty()) {
            return;
        }

        // Build the full set of variants (with and without hyphen) for the $in query.
        Set<String> allVariants = new LinkedHashSet<>();
        for (String normalized : uncachedNormalized) {
            allVariants.add(normalized);
            if (normalized.length() == 8) {
                allVariants.add(normalized.substring(0, 4) + "-" + normalized.substring(4));
            }
        }

        Query query = new Query(new Criteria().orOperator(
                Criteria.where("issn").in(allVariants),
                Criteria.where("eIssn").in(allVariants),
                Criteria.where("previousIssn").in(allVariants)
        ));
        List<Jcr> candidates = jcrMongoTemplate.find(query, Jcr.class, "Journals");

        // Group results by every normalized ISSN they match and populate the cache.
        Map<String, List<Jcr>> grouped = new LinkedHashMap<>();
        for (String normalized : uncachedNormalized) {
            grouped.put(normalized, new ArrayList<>());
        }
        for (Jcr jcr : candidates) {
            if (jcr == null) {
                continue;
            }
            for (String normalized : uncachedNormalized) {
                if (jcr.matchesIssn(normalized)) {
                    grouped.get(normalized).add(jcr);
                }
            }
        }
        for (Map.Entry<String, List<Jcr>> entry : grouped.entrySet()) {
            List<Jcr> deduped = List.copyOf(dedupeById(entry.getValue()));
            jcrByIssnCache.put(entry.getKey(), new CacheEntry(deduped, now + ISSN_CACHE_TTL_MS));
        }
        cleanupExpiredIssnCache(now);
    }

    private Map<String, Object> buildLinkResponse(Journal journal) {
        List<String> journalIssns = journal.getAllIssnsForJoin();
        List<Jcr> allMatches = new ArrayList<>();
        for (String issn : journalIssns) {
            allMatches.addAll(findJcrByIssn(issn));
        }

        List<Jcr> uniqueMatches = dedupeById(allMatches);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("journalUuid", journal.getUuid());
        response.put("journalPureId", journal.getPureId());
        response.put("journalTitle", journal.getMainTitle());
        response.put("journalIssns", journalIssns);
        response.put("jcrCount", uniqueMatches.size());
        response.put("jcrMatches", uniqueMatches);
        return response;
    }

    private static List<Jcr> dedupeById(List<Jcr> input) {
        Map<String, Jcr> dedup = new LinkedHashMap<>();
        for (Jcr item : input) {
            if (item == null) {
                continue;
            }
            String key = item.getId() != null ? item.getId() : item.getBestIssnForJoin();
            if (key != null) {
                dedup.put(key, item);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static Set<String> issnVariants(String rawIssn) {
        String normalized = normalizeIssn(rawIssn);
        if (normalized == null) {
            return Set.of();
        }

        Set<String> out = new LinkedHashSet<>();
        out.add(normalized);
        if (normalized.length() == 8) {
            out.add(normalized.substring(0, 4) + "-" + normalized.substring(4));
        }
        return out;
    }

    private static String normalizeIssn(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}