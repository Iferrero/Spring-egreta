package com.example.demo.controller;

import com.example.demo.model.ExternalOrganization;
import com.example.demo.repository.ExternalOrganizationRepository;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/external-organizations", "/external-organizations", "/otr/api/external-organizations"})
@CrossOrigin(origins = "*")
public class ExternalOrganizationController {

    private final ExternalOrganizationRepository repository;
    private final MongoTemplate mongoTemplate;

    public ExternalOrganizationController(ExternalOrganizationRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations  — paginated list with optional name search
    // -------------------------------------------------------------------------
    @GetMapping
    public Page<ExternalOrganization> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("name.en_GB").ascending());

        if (buscar == null || buscar.isBlank()) {
            return repository.findAll(pageable);
        }

        String escaped = Pattern.quote(buscar.trim());
        Query query = new Query(new Criteria().orOperator(
            Criteria.where("name.ca_ES").regex(escaped, "i"),
            Criteria.where("name.es_ES").regex(escaped, "i"),
            Criteria.where("name.en_GB").regex(escaped, "i")
        )).with(pageable);

        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/unlinked
    // Two-phase approach:
    //  1. Sample N documents per collection → discover all dotted paths that
    //     contain UUID-format values.
    //  2. Run distinct(path) on each discovered path → fast, index-friendly.
    // This never iterates full collections document-by-document.
    // -------------------------------------------------------------------------
    @GetMapping("/unlinked")
    public Page<ExternalOrganization> unlinked(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Set<String> referencedUuids = collectReferencedUuids();

        Criteria notReferenced = Criteria.where("uuid").nin(referencedUuids);
        Query query = new Query(notReferenced);

        if (buscar != null && !buscar.isBlank()) {
            String escaped = Pattern.quote(buscar.trim());
            Criteria nameCriteria = new Criteria().orOperator(
                Criteria.where("name.ca_ES").regex(escaped, "i"),
                Criteria.where("name.es_ES").regex(escaped, "i"),
                Criteria.where("name.en_GB").regex(escaped, "i")
            );
            query = new Query(new Criteria().andOperator(notReferenced, nameCriteria));
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by("name.en_GB").ascending());
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), ExternalOrganization.class);
        query.with(pageable);
        List<ExternalOrganization> items = mongoTemplate.find(query, ExternalOrganization.class);
        return new PageImpl<>(items, pageable, total);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/unlinked/count  — quick total count
    // -------------------------------------------------------------------------
    @GetMapping("/unlinked/count")
    public Map<String, Long> unlinkedCount() {
        Set<String> referencedUuids = collectReferencedUuids();
        long total = mongoTemplate.count(
            new Query(Criteria.where("uuid").nin(referencedUuids)),
            ExternalOrganization.class
        );
        long grand = repository.count();
        return Map.of("unlinked", total, "total", grand);
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/by-type
    // Aggregation: number of external organizations grouped by type (en_GB).
    // -------------------------------------------------------------------------
    @GetMapping("/stats/by-type")
    public List<Map<String, Object>> statsByType() {
        List<Document> pipeline = List.of(
            new Document("$group", new Document("_id", "$type.term.en_GB")
                .append("count", new Document("$sum", 1))),
            new Document("$sort", new Document("count", -1))
        );
        List<Map<String, Object>> result = new ArrayList<>();
        mongoTemplate.getDb().getCollection(resolveExternalOrganizationCollection())
            .aggregate(pipeline)
            .forEach(doc -> {
                String label = doc.getString("_id");
                result.add(Map.of(
                    "label", label != null ? label : "(desconegut)",
                    "count", doc.getInteger("count", 0)
                ));
            });
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/stats/by-country[?type=<en_GB type label>]
    // Aggregation: number of external organizations grouped by country (en_GB).
    // Optional ?type= filter restricts to orgs of a specific type.
    // -------------------------------------------------------------------------
    @GetMapping("/stats/by-country")
    public List<Map<String, Object>> statsByCountry(
            @RequestParam(required = false) String type) {
        List<Document> pipeline = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            pipeline.add(new Document("$match", new Document("type.term.en_GB", type.trim())));
        }
        pipeline.add(new Document("$group", new Document("_id", "$address.country.term.en_GB")
            .append("count", new Document("$sum", 1))));
        pipeline.add(new Document("$sort", new Document("count", -1)));

        List<Map<String, Object>> result = new ArrayList<>();
        mongoTemplate.getDb().getCollection(resolveExternalOrganizationCollection())
            .aggregate(pipeline)
            .forEach(doc -> {
                String label = doc.getString("_id");
                result.add(Map.of(
                    "label", label != null ? label : "(desconegut)",
                    "count", doc.getInteger("count", 0)
                ));
            });
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/debug/references?uuid=<uuid>
    // Diagnostic: shows which collections + paths reference a given UUID,
    // using the same two-phase discovery used by /unlinked.
    // -------------------------------------------------------------------------
    @GetMapping("/debug/references")
    public Map<String, Object> debugReferences(@RequestParam String uuid) {
        String normalizedUuid = uuid.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", normalizedUuid);

        Map<String, List<String>> pathsPerCollection = new LinkedHashMap<>();
        int collectionsScanned = 0;
        Document inFilter = new Document("$in", List.of(normalizedUuid));

        for (String collection : resolveAllCollections()) {
            if (isExtOrgCollection(collection)) continue;
            collectionsScanned++;

            String collectionLower = collection.toLowerCase();
            Set<String> matchingPaths = new LinkedHashSet<>();

            // Strategy A — known paths (unfiltered distinct + Java check)
            List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
                .filter(e -> collectionLower.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .distinct()
                .collect(Collectors.toList());
            for (String path : knownPaths) {
                try {
                    boolean found = mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, String.class)
                        .into(new ArrayList<>())
                        .stream()
                        .anyMatch(normalizedUuid::equals);
                    if (found) matchingPaths.add(path);
                } catch (Exception ignored) {}
            }

            // Strategy B — sampled paths with $in filter
            Set<String> knownPathSet = new HashSet<>(knownPaths);
            List<String> sampledPaths = discoverUuidPaths(collection, 100)
                .stream().filter(p -> !knownPathSet.contains(p)).collect(Collectors.toList());
            for (String path : sampledPaths) {
                Document filter = new Document(path, inFilter);
                try {
                    List<String> values = mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, filter, String.class)
                        .into(new ArrayList<>());
                    if (!values.isEmpty()) matchingPaths.add(path);
                } catch (Exception ignored) {}
            }

            if (!matchingPaths.isEmpty()) {
                pathsPerCollection.put(collection, new ArrayList<>(matchingPaths));
            }
        }

        result.put("referencedIn", pathsPerCollection);
        result.put("collectionsScanned", collectionsScanned);
        return result;
    }

    // -------------------------------------------------------------------------
    // GET /external-organizations/debug/schema?collection=Activities
    // Diagnostic: shows UUID-shaped paths discovered by sampling 200 random
    // documents from the specified collection, plus the KNOWN_PATHS applied.
    // -------------------------------------------------------------------------
    @GetMapping("/debug/schema")
    public Map<String, Object> debugSchema(@RequestParam String collection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collection", collection);

        String collectionLower = collection.toLowerCase();
        List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
            .filter(e -> collectionLower.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .distinct()
            .collect(Collectors.toList());
        result.put("knownPaths", knownPaths);

        List<String> discovered = discoverUuidPaths(collection, 200);
        result.put("discoveredPaths", discovered);

        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers — two-phase discovery
    // -------------------------------------------------------------------------

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    /**
     * Well-known paths per collection family that are ALWAYS checked,
     * regardless of whether the sampling phase discovers them.
     * Keys are lowercase collection name substrings; values are path lists.
     */
    private static final Map<String, List<String>> KNOWN_PATHS = Map.of(
        // Activities: Membership, Prize, etc. link to external orgs
        "activities", List.of(
            "memberOf.externalOrganization.uuid",
            "hostedBy.externalOrganization.uuid",
            "associatedOrganizations.externalOrganization.uuid"
        ),
        // Events: organisers / co-organisers may be external orgs
        "events", List.of(
            "organiser.externalOrganization.uuid",
            "coOrganisers.externalOrganization.uuid",
            "associatedOrganizations.externalOrganization.uuid"
        ),
        // Applications / Projects
        "applications", List.of(
            "participants.externalOrganization.uuid",
            "collaboratingOrganisations.externalOrganization.uuid",
            "managingOrganisation.uuid"
        ),
        // Research outputs
        "researchoutputs", List.of(
            "contributors.affiliations.externalOrganization.uuid",
            "externalOrganizations.uuid"
        ),
        // Funding opportunities
        "fundingopportunities", List.of(
            "funders.externalOrganization.uuid",
            "managingOrganisation.uuid"
        )
    );

    /**
     * Phase 1 + Phase 2:
     *  - Load all ExtOrg UUIDs via distinct on their own collection.
     *  - For each other collection, ALWAYS run both strategies:
     *      a) KNOWN_PATHS: distinct(path) without filter + Java-side intersection
     *         (paths are specific → result bounded, no 16MB risk).
     *      b) SAMPLED paths: sample 30 docs to discover extra UUID paths, then
     *         distinct(path, {$in: extOrgUuids}) → bounded by extOrgUuids size.
     *  Running both ensures that references through unexpected paths are not missed.
     */
    private Set<String> collectReferencedUuids() {
        Set<String> extOrgUuids = mongoTemplate
            .getDb()
            .getCollection(resolveExternalOrganizationCollection())
            .distinct("uuid", String.class)
            .into(new ArrayList<>())
            .stream()
            .filter(Objects::nonNull)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());

        if (extOrgUuids.isEmpty()) {
            return Set.of();
        }

        Set<String> referenced = new HashSet<>();
        List<String> extOrgUuidsList = new ArrayList<>(extOrgUuids);
        Document inFilter = new Document("$in", extOrgUuidsList);

        for (String collection : resolveAllCollections()) {
            if (isExtOrgCollection(collection)) continue;

            String collectionLower = collection.toLowerCase();

            // Strategy A — KNOWN_PATHS: unfiltered distinct + Java intersection
            List<String> knownPaths = KNOWN_PATHS.entrySet().stream()
                .filter(e -> collectionLower.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .distinct()
                .collect(Collectors.toList());

            for (String path : knownPaths) {
                try {
                    mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, String.class)
                        .into(new ArrayList<>())
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(v -> !v.isBlank())
                        .filter(extOrgUuids::contains)
                        .forEach(referenced::add);
                } catch (Exception ignored) {}
            }

            // Strategy B — SAMPLED paths: discover UUID-shaped fields and query
            // with $in filter so the result is always bounded by extOrgUuids.size()
            Set<String> knownPathSet = new HashSet<>(knownPaths);
            List<String> sampledPaths = discoverUuidPaths(collection, 100)
                .stream()
                .filter(p -> !knownPathSet.contains(p)) // skip paths already covered above
                .collect(Collectors.toList());

            for (String path : sampledPaths) {
                Document filter = new Document(path, inFilter);
                try {
                    mongoTemplate.getDb()
                        .getCollection(collection)
                        .distinct(path, filter, String.class)
                        .into(new ArrayList<>())
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(v -> !v.isBlank())
                        .forEach(referenced::add);
                } catch (Exception ignored) {}
            }

            if (referenced.size() >= extOrgUuids.size()) break;
        }

        return referenced;
    }

    /**
     * Samples up to {@code sampleSize} random documents from the collection and
     * walks the BSON tree to discover every dotted path whose leaf value looks
     * like a UUID.  Uses $sample aggregation so documents are chosen at random
     * (avoids the bias of .find().limit() which returns insertion-order docs).
     */
    private List<String> discoverUuidPaths(String collection, int sampleSize) {
        Set<String> paths = new LinkedHashSet<>();
        mongoTemplate.getDb()
            .getCollection(collection)
            .aggregate(List.of(new Document("$sample", new Document("size", sampleSize))))
            .forEach(doc -> collectUuidPaths(doc, "", paths));
        return new ArrayList<>(paths);
    }

    /** Recursive BSON walker that records paths whose leaf is UUID-shaped. */
    private void collectUuidPaths(Object node, String prefix, Set<String> paths) {
        if (node instanceof Document doc) {
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                Object val = entry.getValue();
                if (val instanceof String str && UUID_PATTERN.matcher(str).matches()) {
                    paths.add(path);
                } else if (val instanceof Document || val instanceof List) {
                    collectUuidPaths(val, path, paths);
                }
            }
        } else if (node instanceof List<?> list) {
            // For arrays use the parent path; inspect only first non-null element
            // to discover the sub-structure without exploding into duplicates.
            for (Object item : list) {
                if (item instanceof Document || item instanceof List) {
                    collectUuidPaths(item, prefix, paths);
                    break;
                }
            }
        }
    }

    private String resolveExternalOrganizationCollection() {
        for (String c : List.of("ExternalOrganizations", "externalOrganizations",
                "ExternalOrganisation", "ExternalOrganisations")) {
            if (mongoTemplate.collectionExists(c)) return c;
        }
        return "ExternalOrganizations";
    }

    private boolean isExtOrgCollection(String name) {
        return name.toLowerCase().contains("externalorg");
    }

    private List<String> resolveAllCollections() {
        return mongoTemplate.getDb()
            .listCollectionNames()
            .into(new ArrayList<>())
            .stream()
            .filter(n -> !n.startsWith("system."))
            .collect(Collectors.toList());
    }
}
