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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class JournalJcrService {

    private final JournalRepository journalRepository;
    private final MongoTemplate jcrMongoTemplate;

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
        String normalizedInput = normalizeIssn(issn);

        List<Jcr> filtered = new ArrayList<>();
        for (Jcr item : candidates) {
            if (item != null && item.matchesIssn(normalizedInput)) {
                filtered.add(item);
            }
        }
        return dedupeById(filtered);
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