package com.example.demo.controller;

import com.example.demo.service.PublicacionAbstractsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pure/abstracts")
@CrossOrigin(origins = "*")
@Tag(name = "Researchoutputs Abstracts", description = "Endpoints d'anàlisi lingüística, cobertura i duplicitat d'abstracts a Pure/Egreta")
public class PublicacionAbstractsController {

    private final PublicacionAbstractsService abstractsService;
    private final com.example.demo.egreta.ResearchoutputsSyncService researchoutputsSyncService;

    @Autowired
    public PublicacionAbstractsController(
            PublicacionAbstractsService abstractsService,
            com.example.demo.egreta.ResearchoutputsSyncService researchoutputsSyncService) {
        this.abstractsService = abstractsService;
        this.researchoutputsSyncService = researchoutputsSyncService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Obté estadístiques globals i KPIs d'abstracts (total, 3 idiomes, idèntics, etc.)")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType) {
        return ResponseEntity.ok(abstractsService.getAbstractStats(desde, hasta, type, language, yearType));
    }

    @GetMapping("/years")
    @Operation(summary = "Obté la distribució d'abstracts per any (per defecte: any de publicació)")
    public ResponseEntity<List<Map<String, Object>>> getByYear(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType) {
        return ResponseEntity.ok(abstractsService.getAbstractsByYear(desde, hasta, yearType));
    }

    @GetMapping("/years-submission")
    @Operation(summary = "Obté la distribució d'abstracts per any de submission (ingrés a Pure/Egreta)")
    public ResponseEntity<List<Map<String, Object>>> getBySubmissionYear(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return ResponseEntity.ok(abstractsService.getAbstractsByYear(desde, hasta, "submissionYear"));
    }

    @GetMapping("/years-languages")
    @Operation(summary = "Obté la distribució d'idiomes reconeguts d'abstracts per any de publicació/submission (per a barres apilades)")
    public ResponseEntity<Map<String, Object>> getLanguagesByYear(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType) {
        return ResponseEntity.ok(abstractsService.getAbstractsLanguagesByYear(desde, hasta, type, yearType));
    }

    @GetMapping("/types")
    @Operation(summary = "Obté la distribució d'abstracts per tipus de document")
    public ResponseEntity<List<Map<String, Object>>> getByType(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType) {
        return ResponseEntity.ok(abstractsService.getAbstractsByType(desde, hasta, yearType));
    }

    @GetMapping("/list")
    @Operation(summary = "Llistat paginat de publicacions amb filtrat per estat d'abstract i idioma")
    public ResponseEntity<Map<String, Object>> getList(
            @RequestParam(required = false, defaultValue = "all") String filter,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "publicationYear") String sortField,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEntity.ok(abstractsService.getAbstractsList(filter, search, desde, hasta, type, language, yearType, page, size, sortField, sortDir));
    }

    @GetMapping("/{uuid}")
    @Operation(summary = "Obté el detall i comparació dels textos d'abstract d'una publicació concreta")
    public ResponseEntity<Map<String, Object>> getDetail(@PathVariable String uuid) {
        return abstractsService.getAbstractDetail(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/cache/clear")
    @Operation(summary = "Neteja la memòria cau per forçar el recàlcul complet de tots els indicadors i gràfics")
    public ResponseEntity<Map<String, Object>> clearCache() {
        abstractsService.clearCache();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Caché neta correctament"));
    }

    @PostMapping("/sync/{uuid}")
    @Operation(summary = "Sincronitza i actualitza des de Pure/Egreta una única publicació pel seu UUID")
    public ResponseEntity<Map<String, Object>> syncOne(@PathVariable String uuid) {
        researchoutputsSyncService.syncOne(uuid);
        abstractsService.clearCache();
        return abstractsService.getAbstractDetail(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("uuid", uuid, "status", "synced")));
    }
}
