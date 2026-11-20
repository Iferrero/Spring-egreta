package com.example.demo.linkaudit.controller;

import com.example.demo.linkaudit.LinkAuditConstants;
import com.example.demo.linkaudit.model.AuditJobState;
import com.example.demo.linkaudit.model.AuditSummaryResponse;
import com.example.demo.linkaudit.model.LinkAuditIssue;
import com.example.demo.linkaudit.model.LinkIssuesPageResponse;
import com.example.demo.linkaudit.model.ScanRequest;
import com.example.demo.linkaudit.service.LinkAuditService;
import com.example.demo.linkaudit.service.ScanAlreadyRunningException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REST controller exposing asynchronous HTTP link audit operations,
 * paginated issue queries, and streaming UTF-8 CSV export.
 */
@RestController
@RequestMapping("/api/mongo/collections/link-audit")
@Tag(name = "MongoDB Collection Link Audit", description = "Auditoria HTTP d'enllaços trencats i bloquejats per col·lecció a MongoDB (kraken)")
public class CollectionLinkAuditController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CSV_CRLF = "\r\n";
    private static final String CSV_HEADER = String.join(",",
            "Coleccio",
            "Estat",
            "Motiu",
            "Codi_HTTP",
            "Camp",
            "Subtipus",
            "Etiqueta_Enllac",
            "URL",
            "URL_Final",
            "Error",
            "ID_Registre",
            "UUID_Registre",
            "PureID_Registre",
            "Titol_Registre",
            "Tipus_Registre",
            "Enllac_Egreta",
            "Data_Comprovacio"
    );

    private final LinkAuditService linkAuditService;

    public CollectionLinkAuditController(LinkAuditService linkAuditService) {
        this.linkAuditService = Objects.requireNonNull(linkAuditService, "linkAuditService must not be null");
    }

    @GetMapping("/summary")
    @Operation(summary = "Obtenir resum global d'auditoria d'enllaços",
            description = "Retorna els KPIs globals, l'estat del treball actual i els resums persistits per col·lecció.")
    public ResponseEntity<AuditSummaryResponse> getSummary() {
        return ResponseEntity.ok(linkAuditService.getSummary());
    }

    @PostMapping("/scan")
    @Operation(summary = "Iniciar una auditoria HTTP d'enllaços",
            description = "Llança un escaneig global (body buit o null) o d'una col·lecció concreta en segon pla.")
    public ResponseEntity<AuditJobState> startScan(@RequestBody(required = false) ScanRequest request) {
        String targetCollection = (request != null && request.collection() != null && !request.collection().isBlank())
                ? request.collection().trim()
                : null;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(linkAuditService.startScan(targetCollection));
    }

    @GetMapping("/status")
    @Operation(summary = "Consultar l'estat en temps real de l'auditoria",
            description = "Retorna l'estat actual del treball d'auditoria en curs o l'últim finalitzat.")
    public ResponseEntity<AuditJobState> getStatus() {
        return ResponseEntity.ok(linkAuditService.getStatus());
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel·lar l'auditoria en curs",
            description = "Sol·licita una aturada neta del treball actiu preservant les col·leccions ja completades.")
    public ResponseEntity<AuditJobState> cancelScan() {
        return ResponseEntity.ok(linkAuditService.cancelScan());
    }

    @GetMapping("/{collection}/issues")
    @Operation(summary = "Llistar incidències d'enllaços d'una col·lecció",
            description = "Consulta paginada i filtrable de les incidències de l'última auditoria completada d'una col·lecció.")
    public ResponseEntity<LinkIssuesPageResponse> getIssues(
            @Parameter(description = "Nom de la col·lecció de MongoDB")
            @PathVariable("collection") String collection,
            @RequestParam(name = "status", defaultValue = "BROKEN") String status,
            @RequestParam(name = "field", required = false) String field,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size
    ) {
        String targetCollection = validateCollectionParam(collection);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return ResponseEntity.ok(linkAuditService.getIssues(targetCollection, status, field, reason, q, safePage, safeSize));
    }

    @GetMapping("/{collection}/issues/export")
    @Operation(summary = "Exportar incidències d'una col·lecció a CSV",
            description = "Genera en streaming un fitxer CSV UTF-8 amb BOM amb totes les incidències filtrades.")
    public void exportIssues(
            @Parameter(description = "Nom de la col·lecció de MongoDB")
            @PathVariable("collection") String collection,
            @RequestParam(name = "status", defaultValue = "ALL") String status,
            @RequestParam(name = "field", required = false) String field,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "q", required = false) String q,
            HttpServletResponse response
    ) throws IOException {
        String targetCollection = validateCollectionParam(collection);
        String contentDisposition = buildContentDisposition(targetCollection);
        AtomicReference<BufferedWriter> writerRef = new AtomicReference<>();

        try {
            linkAuditService.streamIssuesForExport(targetCollection, status, field, reason, q, issue -> {
                try {
                    BufferedWriter writer = ensureCsvWriterInitialized(writerRef, response, contentDisposition);
                    writer.write(formatCsvRow(issue));
                    writer.write(CSV_CRLF);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            BufferedWriter writer = ensureCsvWriterInitialized(writerRef, response, contentDisposition);
            writer.flush();
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Sol·licitud invàlida";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler(ScanAlreadyRunningException.class)
    public ResponseEntity<AuditJobState> handleScanAlreadyRunning(ScanAlreadyRunningException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getActiveJobState());
    }

    private static BufferedWriter ensureCsvWriterInitialized(
            AtomicReference<BufferedWriter> writerRef,
            HttpServletResponse response,
            String contentDisposition
    ) throws IOException {
        BufferedWriter existing = writerRef.get();
        if (existing != null) {
            return existing;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);

        ServletOutputStream outputStream = response.getOutputStream();
        outputStream.write(UTF8_BOM);

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write(CSV_HEADER);
        writer.write(CSV_CRLF);
        writerRef.set(writer);
        return writer;
    }

    private static String validateCollectionParam(String collection) {
        if (LinkAuditConstants.isInternalCollection(collection)) {
            throw new IllegalArgumentException("Col·lecció invàlida o interna: " + collection);
        }
        return collection.trim();
    }

    private static String buildContentDisposition(String collection) {
        String sanitized = collection.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "collection";
        }
        String filename = "enllacos_incorrectes_" + sanitized + "_" + LocalDate.now() + ".csv";
        return "attachment; filename=\"" + filename + "\"";
    }

    private static String formatCsvRow(LinkAuditIssue issue) {
        String[] columns = new String[]{
                issue.collection(),
                issue.status() != null ? issue.status().name() : "",
                issue.reason() != null ? issue.reason().name() : "",
                issue.httpStatus() != null ? issue.httpStatus().toString() : "",
                issue.sourceField(),
                sanitizeFormula(issue.subType()),
                sanitizeFormula(issue.linkLabel()),
                sanitizeCsvUrl(issue.url()),
                sanitizeCsvUrl(issue.finalUrl()),
                sanitizeFormula(issue.errorMessage()),
                issue.recordId(),
                issue.recordUuid(),
                issue.recordPureId() != null ? issue.recordPureId().toString() : "",
                sanitizeFormula(issue.recordTitle()),
                sanitizeFormula(issue.recordType()),
                issue.egretaUrl(),
                issue.checkedAt() != null ? issue.checkedAt().toString() : ""
        };
        StringBuilder row = new StringBuilder(256);
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(escapeCsv(columns[i]));
        }
        return row.toString();
    }

    private static String sanitizeCsvUrl(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String trimmedLower = value.trim().toLowerCase(Locale.ROOT);
        if ((trimmedLower.startsWith("http://") || trimmedLower.startsWith("https://"))
                && value.charAt(0) != '\t' && value.charAt(0) != '\r') {
            return value;
        }
        return sanitizeFormula(value);
    }

    private static String sanitizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static String escapeCsv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                needsQuotes = true;
                break;
            }
        }
        if (!needsQuotes) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 4);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append("\"\"");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
