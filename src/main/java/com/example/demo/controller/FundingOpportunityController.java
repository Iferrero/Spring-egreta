package com.example.demo.controller;

import com.example.demo.model.FundingOpportunity;
import com.example.demo.repository.FundingOpportunityRepository;
import org.bson.Document;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/funding-opportunities", "/funding-opportunities", "/otr/api/funding-opportunities"})
@CrossOrigin(origins = "*")
public class FundingOpportunityController {

    private final FundingOpportunityRepository repository;
    private final MongoTemplate mongoTemplate;

    public FundingOpportunityController(FundingOpportunityRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public Page<FundingOpportunity> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uuid"));

        String collectionName = resolveFundingCollection();
        if (collectionName == null) {
            if (buscar == null || buscar.isBlank()) {
                return repository.findAll(pageable);
            }
            return repository.findByTitleValueContainingIgnoreCase(buscar, pageable);
        }

        Query query = new Query();
        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            query.addCriteria(Criteria.where("title.value").regex(escaped, "i"));
        }

        long total = mongoTemplate.count(query, collectionName);
        query.with(pageable);
        List<FundingOpportunity> items = mongoTemplate.find(query, FundingOpportunity.class, collectionName);
        return new PageImpl<>(items, pageable, total);
    }

    @GetMapping("/describe")
    public Map<String, Object> describeCollection(@RequestParam(defaultValue = "20") int sampleSize) {
        String collectionName = resolveFundingCollection();
        if (collectionName == null) {
            return Map.of(
                "collection", "not-found",
                "totalDocuments", 0,
                "sampleSize", 0,
                "fieldPaths", List.of(),
                "samples", List.of()
            );
        }

        int safeSampleSize = Math.max(1, Math.min(sampleSize, 200));
        List<Document> docs = mongoTemplate
            .getCollection(collectionName)
            .find()
            .limit(safeSampleSize)
            .into(new ArrayList<>());

        Set<String> paths = new LinkedHashSet<>();
        List<Map<String, Object>> samples = new ArrayList<>();

        for (Document doc : docs) {
            collectFieldPaths(doc, "", paths);

            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("uuid", doc.get("uuid"));
            sample.put("title", doc.get("title"));
            sample.put("type", doc.get("type"));
            sample.put("workflow", doc.get("workflow"));
            samples.add(sample);
        }

        long totalDocuments = mongoTemplate.getCollection(collectionName).countDocuments();
        return Map.of(
            "collection", collectionName,
            "totalDocuments", totalDocuments,
            "sampleSize", docs.size(),
            "fieldPaths", new ArrayList<>(paths),
            "samples", samples
        );
    }

    private void collectFieldPaths(Object value, String prefix, Set<String> paths) {
        if (value == null) {
            return;
        }

        if (value instanceof Document doc) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String path = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
                paths.add(path);
                collectFieldPaths(entry.getValue(), path, paths);
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                String path = prefix.isBlank() ? key : prefix + "." + key;
                paths.add(path);
                collectFieldPaths(entry.getValue(), path, paths);
            }
            return;
        }

        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return;
            }
            String listPath = prefix + "[]";
            paths.add(listPath);
            collectFieldPaths(list.get(0), listPath, paths);
        }
    }

    private String resolveFundingCollection() {
        List<String> candidates = List.of(
            "FundingOpportunities",
            "Fundingopportunities",
            "fundingOpportunities",
            "fundingopportunities",
            "FundingOpportunity",
            "fundingopportunity"
        );

        for (String name : candidates) {
            if (!mongoTemplate.collectionExists(name)) {
                continue;
            }

            if (mongoTemplate.getCollection(name).countDocuments() > 0) {
                return name;
            }
        }

        for (String name : candidates) {
            if (mongoTemplate.collectionExists(name)) {
                return name;
            }
        }

        return null;
    }
}
