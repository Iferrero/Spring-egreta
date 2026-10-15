package com.example.demo.curation.controller;

import com.example.demo.curation.model.BulkCandidateActionRequest;
import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.CandidateStatusUpdateRequest;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.BatchScanProgressDto;
import com.example.demo.curation.model.BatchScanRequest;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.curation.service.BatchReconciliationService;
import com.example.demo.curation.service.OpenAlexReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Controlador REST para la operativa de conciliación y curación individual y masiva
 * de publicaciones de OpenAlex contra el catálogo institucional de EGRETA.
 */
@RestController
@RequestMapping("/api/curation")
@CrossOrigin(origins = "*")
public class CurationReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(CurationReconciliationController.class);

    private final OpenAlexReconciliationService openAlexService;
    private final BatchReconciliationService batchService;
    private final PersonPublicationCandidateRepository candidateRepository;

    public CurationReconciliationController(
            OpenAlexReconciliationService openAlexService,
            BatchReconciliationService batchService,
            PersonPublicationCandidateRepository candidateRepository) {
        this.openAlexService = Objects.requireNonNull(openAlexService, "openAlexService must not be null");
        this.batchService = Objects.requireNonNull(batchService, "batchService must not be null");
        this.candidateRepository = Objects.requireNonNull(candidateRepository, "candidateRepository must not be null");
    }

    /**
     * Escaneo individual en tiempo real de publicaciones en OpenAlex para un investigador de EGRETA.
     */
    @PostMapping("/authors/{personUuid}/scan")
    public ResponseEntity<List<PersonPublicationCandidate>> scanAuthor(
            @PathVariable String personUuid,
            @RequestParam(name = "strictOrcidOnly", defaultValue = "false") boolean strictOrcidOnly) {
        log.info("REST scan request for author {} (strictOrcidOnly={})", personUuid, strictOrcidOnly);
        try {
            List<PersonPublicationCandidate> candidates = openAlexService.scanPerson(personUuid, strictOrcidOnly);
            return ResponseEntity.ok(candidates != null ? candidates : Collections.emptyList());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid author scan request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Listado de candidatas para un investigador, opcionalmente filtradas por estado y/o veredicto (semáforo).
     */
    @GetMapping("/authors/{personUuid}/candidates")
    public ResponseEntity<List<PersonPublicationCandidate>> getCandidates(
            @PathVariable String personUuid,
            @RequestParam(name = "status", required = false) CandidateStatus status,
            @RequestParam(name = "verdict", required = false) CandidateVerdict verdict) {
        List<PersonPublicationCandidate> candidates;
        if (status != null && verdict != null) {
            candidates = candidateRepository.findByPersonUuidAndStatusAndVerdict(personUuid, status, verdict);
        } else if (status != null) {
            candidates = candidateRepository.findByPersonUuidAndStatus(personUuid, status);
        } else if (verdict != null) {
            candidates = candidateRepository.findByPersonUuidAndVerdict(personUuid, verdict);
        } else {
            candidates = candidateRepository.findByPersonUuid(personUuid);
        }
        return ResponseEntity.ok(candidates != null ? candidates : Collections.emptyList());
    }

    /**
     * Actualiza la decisión de curación (estado, revisor y comentarios) de un candidato individual.
     */
    @PutMapping("/candidates/{candidateId}/status")
    public ResponseEntity<PersonPublicationCandidate> updateCandidateStatus(
            @PathVariable String candidateId,
            @RequestBody CandidateStatusUpdateRequest request) {
        Optional<PersonPublicationCandidate> optionalCandidate = candidateRepository.findById(candidateId);
        if (optionalCandidate.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        PersonPublicationCandidate candidate = optionalCandidate.get();
        if (request != null && request.getTargetStatus() != null) {
            candidate.setStatus(request.getTargetStatus());
        }
        String reviewer = (request != null && request.getReviewer() != null && !request.getReviewer().isBlank())
                ? request.getReviewer().trim()
                : "analista";
        candidate.setReviewedBy(reviewer);
        candidate.setReviewedAt(Instant.now());

        if (request != null && request.getComments() != null && !request.getComments().isBlank()) {
            candidate.getReasons().add("Comentario de revisión: " + request.getComments().trim());
        }

        PersonPublicationCandidate saved = candidateRepository.save(candidate);
        return ResponseEntity.ok(saved);
    }

    /**
     * Acción masiva sobre múltiples candidatos (aprobar o descartar lote de IDs).
     */
    @PostMapping("/candidates/bulk-action")
    public ResponseEntity<Map<String, Object>> bulkCandidateAction(@RequestBody BulkCandidateActionRequest request) {
        if (request == null || request.getCandidateIds() == null || request.getCandidateIds().isEmpty()) {
            return ResponseEntity.ok(Map.of("updatedCount", 0, "candidates", Collections.emptyList()));
        }

        List<String> validIds = request.getCandidateIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .toList();

        if (validIds.isEmpty()) {
            return ResponseEntity.ok(Map.of("updatedCount", 0, "candidates", Collections.emptyList()));
        }

        String reviewer = (request.getReviewer() != null && !request.getReviewer().isBlank())
                ? request.getReviewer().trim()
                : "analista";
        Instant now = Instant.now();

        Iterable<PersonPublicationCandidate> candidates = candidateRepository.findAllById(validIds);
        List<PersonPublicationCandidate> toUpdate = new ArrayList<>();
        for (PersonPublicationCandidate c : candidates) {
            if (c == null) continue;
            if (request.getTargetStatus() != null) {
                c.setStatus(request.getTargetStatus());
            }
            c.setReviewedBy(reviewer);
            c.setReviewedAt(now);
            toUpdate.add(c);
        }

        List<PersonPublicationCandidate> saved = candidateRepository.saveAll(toUpdate);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updatedCount", saved.size());
        response.put("candidates", saved);
        return ResponseEntity.ok(response);
    }

    /**
     * Inicia un trabajo de escaneo de conciliación por lotes en segundo plano.
     */
    @PostMapping("/batch-scan")
    public ResponseEntity<Map<String, Object>> launchBatchScan(@RequestBody BatchScanRequest request) {
        try {
            String jobId = batchService.launchBatchScan(request);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid batch scan request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Consulta el progreso y semáforos de un trabajo de escaneo por lotes.
     */
    @GetMapping("/batch-scan/{jobId}/progress")
    public ResponseEntity<BatchScanProgressDto> getBatchProgress(@PathVariable String jobId) {
        BatchScanProgressDto progress = batchService.getBatchProgress(jobId);
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(progress);
    }

    /**
     * Cancela la ejecución de un trabajo por lotes en curso.
     */
    @PostMapping("/batch-scan/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelBatchScan(@PathVariable String jobId) {
        batchService.cancelJob(jobId);
        return ResponseEntity.ok(Map.of("status", "cancelled", "jobId", jobId));
    }

    /**
     * Exportación en formato CSV de candidatos y evidencias para analistas o investigadores.
     */
    @GetMapping(value = "/candidates/export", produces = "text/csv; charset=utf-8")
    public ResponseEntity<String> exportCandidatesCsv(
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "personUuid", required = false) String personUuid,
            @RequestParam(name = "status", required = false) CandidateStatus status) {

        List<PersonPublicationCandidate> candidates;
        if (personUuid != null && !personUuid.isBlank()) {
            if (status != null) {
                candidates = candidateRepository.findByPersonUuidAndStatus(personUuid.trim(), status);
            } else {
                candidates = candidateRepository.findByPersonUuid(personUuid.trim());
            }
        } else if (departmentId != null && !departmentId.isBlank()) {
            if (status != null) {
                candidates = candidateRepository.findByDepartmentIdAndStatus(departmentId.trim(), status);
            } else {
                candidates = candidateRepository.findByDepartmentId(departmentId.trim());
            }
        } else if (status != null) {
            candidates = candidateRepository.findByStatus(status);
        } else {
            candidates = candidateRepository.findAll();
        }

        if (candidates == null) {
            candidates = Collections.emptyList();
        }

        String csvContent = buildCsv(candidates);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"curation-candidates.csv\"");

        return ResponseEntity.ok()
                .headers(headers)
                .body(csvContent);
    }

    private String buildCsv(List<PersonPublicationCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"Person Name\",\"Person UUID\",\"DOI\",\"Title\",\"Year\",\"Venue\",\"Affiliation Status\",\"Matched Affiliations\",\"Verdict\",\"Confidence Score\",\"Status\",\"Reviewed By\"\n");

        for (PersonPublicationCandidate c : candidates) {
            if (c == null) continue;
            sb.append(escapeCsv(c.getPersonName())).append(",");
            sb.append(escapeCsv(c.getPersonUuid())).append(",");
            sb.append(escapeCsv(c.getDoi())).append(",");
            sb.append(escapeCsv(c.getTitle())).append(",");
            sb.append(escapeCsv(c.getYear() != null ? String.valueOf(c.getYear()) : "")).append(",");
            sb.append(escapeCsv(c.getVenue())).append(",");
            sb.append(escapeCsv(c.getAffiliationStatus() != null ? c.getAffiliationStatus().name() : "")).append(",");
            sb.append(escapeCsv(c.getMatchedAffiliations() != null ? String.join("; ", c.getMatchedAffiliations()) : "")).append(",");
            sb.append(escapeCsv(c.getVerdict() != null ? c.getVerdict().name() : "")).append(",");
            sb.append(escapeCsv(c.getConfidenceScore() != null ? String.format(Locale.ROOT, "%.2f", c.getConfidenceScore()) : "")).append(",");
            sb.append(escapeCsv(c.getStatus() != null ? c.getStatus().name() : "")).append(",");
            sb.append(escapeCsv(c.getReviewedBy())).append("\n");
        }

        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "\"\"";
        }
        String sanitized = value;
        String trimmed = sanitized.stripLeading();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@")) {
            sanitized = "'" + sanitized;
        }
        return "\"" + sanitized.replace("\"", "\"\"") + "\"";
    }
}
