package com.example.demo.controller;

import com.example.demo.model.Publicacion;
import com.example.demo.repository.PublicacionRepository;
import com.example.demo.service.PublicacionStatsService;
import com.example.demo.service.ResearchOutputJournalLinkService;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/pure")
@CrossOrigin(origins = "*")
public class PublicacionController {

    @Value("${app.scopus.api-key:}")
    private String scopusApiKey;

    private final PublicacionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;
    private final PublicacionStatsService publicacionStatsService;

    @Autowired
    public PublicacionController(
            PublicacionRepository repository,
            MongoTemplate mongoTemplate,
            ResearchOutputJournalLinkService researchOutputJournalLinkService,
            PublicacionStatsService publicacionStatsService) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
        this.publicacionStatsService = publicacionStatsService;
    }

    @GetMapping
    public Page<Publicacion> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("submissionYear").descending());
        if (buscar.isEmpty()) {
            return repository.findAll(pageable);
        } else {
            return repository.findByTitleValueContainingIgnoreCase(buscar, pageable);
        }
    }

    @GetMapping("/search")
    public Page<Publicacion> buscarPorAnio(
            @RequestParam(required = false) Integer anio,
            @RequestParam(defaultValue = "0") int page) {
        Pageable pageable = PageRequest.of(page, 10, Sort.by("modifiedDate").descending());
        if (anio != null) {
            return repository.findBySubmissionYear(anio, pageable);
        } else {
            return repository.findAll(pageable);
        }
    }

    @GetMapping("/{publicationUuid}/raw")
    public ResponseEntity<Object> getRawDocument(@PathVariable String publicationUuid) {
        Document doc = mongoTemplate.findOne(
                Query.query(Criteria.where("uuid").is(publicationUuid)),
                Document.class, "Researchoutputs");
        if (doc == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(doc.toJson());
    }

    @GetMapping("/{publicationUuid}/journal-jcr")
    public ResponseEntity<Map<String, Object>> getJournalAndJcrByPublication(@PathVariable String publicationUuid) {
        return researchOutputJournalLinkService.linkByPublicationUuid(publicationUuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/journal-jcr/resumen")
    public Map<String, Object> getJournalJcrSummary(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("submissionYear").descending());
        Page<Publicacion> publications;

        if (buscar == null || buscar.isBlank()) {
            publications = repository.findAll(pageable);
        } else {
            publications = repository.findByTitleValueContainingIgnoreCase(buscar, pageable);
        }

        List<Map<String, Object>> items = publications.getContent().stream()
                .map(publicacion -> researchOutputJournalLinkService
                        .summarizeByPublicationUuid(publicacion.getId())
                        .orElseGet(() -> {
                            Map<String, Object> fallback = new LinkedHashMap<>();
                            fallback.put("publicationUuid", publicacion.getId());
                            fallback.put("publicationTitle", publicacion.getFullTitle());
                            fallback.put("publicationYear", publicacion.getSubmissionYear());
                            fallback.put("journalFound", false);
                            fallback.put("jcrCount", 0);
                            return fallback;
                        }))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", publications.getNumber());
        response.put("size", publications.getSize());
        response.put("totalElements", publications.getTotalElements());
        response.put("totalPages", publications.getTotalPages());
        response.put("items", items);
        return response;
    }

    @GetMapping("/stats/quartiles")
    public List<Map<String, Object>> getQuartilesByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartileDistributionByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/quartiles/articles")
    public List<Map<String, Object>> getQuartileArticlesByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartileArticlesByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/quartiles/evolution")
    public List<Map<String, Object>> getQuartileEvolutionByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartileEvolutionByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/quartiles/dashboard")
    public Map<String, Object> getQuartilesDashboardByDepartment(
            @RequestParam(required = false) String deptUuid,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String filtrePersonal,
            @RequestParam(required = false) String personUuid) {
        return researchOutputJournalLinkService.quartilesDashboardByDepartment(deptUuid, desde, hasta, filtrePersonal, personUuid);
    }

    @GetMapping("/stats/years")
    public List<Map> statsAnios() {
        try {
            Aggregation agg = Aggregation.newAggregation(
                Aggregation.group("publicationDate.year").count().as("total"),
                Aggregation.project("total").and("_id").as("anio"),
                Aggregation.sort(Sort.Direction.ASC, "anio")
            );
            AggregationResults<Map> results = mongoTemplate.aggregate(agg, "Researchoutputs", Map.class);
            return results.getMappedResults();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats/scopus")
    public Map<String, Object> statsScopus() {
        Map<String, Object> response = new LinkedHashMap<>();
        if (scopusApiKey == null || scopusApiKey.isBlank()) {
            response.put("error", "API Key not configured");
            return response;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            for (int year = 2020; year <= 2026; year++) {
                String query = "AF-ID(60012977) AND PUBYEAR(" + year + ")";
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                String urlString = "https://api.elsevier.com/content/search/scopus?query=" + encodedQuery;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .header("X-ELS-APIKey", scopusApiKey.trim())
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(8))
                        .GET()
                        .build();

                HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (httpResponse.statusCode() == 200) {
                    Document doc = Document.parse(httpResponse.body());
                    Document searchResults = (Document) doc.get("search-results");
                    if (searchResults != null) {
                        String totalResultsStr = searchResults.getString("opensearch:totalResults");
                        if (totalResultsStr != null) {
                            try {
                                int count = Integer.parseInt(totalResultsStr);
                                response.put(String.valueOf(year), count);
                            } catch (NumberFormatException e) {
                                response.put(String.valueOf(year), 0);
                            }
                        }
                    }
                } else {
                    response.put("error", "HTTP " + httpResponse.statusCode() + " from Scopus API");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("error", e.getMessage());
        }

        return response;
    }

    @GetMapping("/stats/types")
    public List<Map> statsTipos() {
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.group("type.term.ca_ES").count().as("total"),
            Aggregation.project("total").and("_id").as("tipo")
        );
        return mongoTemplate.aggregate(agg, "Researchoutputs", Map.class).getMappedResults();
    }

    @GetMapping("/stats/types-by-year")
    public List<Map> statsTiposPorAnio(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid,
            @RequestParam(required = false, defaultValue = "vigent") String filtrePersonal,
            @RequestParam(required = false, defaultValue = "false") boolean managedByDepartment) {
        return publicacionStatsService.getStatsTiposPorAnio(desde, hasta, deptUuid, filtrePersonal, managedByDepartment);
    }

    @GetMapping("/stats/persona-resumen")
    public List<Map> statsPersonaResumen(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid) {
        return publicacionStatsService.getStatsPersonaResumen(desde, hasta, deptUuid);
    }

    @GetMapping("/stats/apa")
    public List<Map<String, Object>> statsApaList(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) List<String> deptUuid,
            @RequestParam(required = false, defaultValue = "vigent") String filtrePersonal,
            @RequestParam(required = false, defaultValue = "false") boolean managedByDepartment) {
        return publicacionStatsService.getStatsApaList(desde, hasta, deptUuid, filtrePersonal, managedByDepartment);
    }
}
