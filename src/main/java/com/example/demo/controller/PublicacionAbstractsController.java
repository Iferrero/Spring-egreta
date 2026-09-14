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

    @Autowired
    public PublicacionAbstractsController(PublicacionAbstractsService abstractsService) {
        this.abstractsService = abstractsService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Obté estadístiques globals i KPIs d'abstracts (total, 3 idiomes, idèntics, etc.)")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String type) {
        return ResponseEntity.ok(abstractsService.getAbstractStats(desde, hasta, type));
    }

    @GetMapping("/years")
    @Operation(summary = "Obté la distribució d'abstracts per any de publicació")
    public ResponseEntity<List<Map<String, Object>>> getByYear(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return ResponseEntity.ok(abstractsService.getAbstractsByYear(desde, hasta));
    }

    @GetMapping("/types")
    @Operation(summary = "Obté la distribució d'abstracts per tipus de document")
    public ResponseEntity<List<Map<String, Object>>> getByType(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta) {
        return ResponseEntity.ok(abstractsService.getAbstractsByType(desde, hasta));
    }

    @GetMapping("/list")
    @Operation(summary = "Llistat paginat de publicacions amb filtrat per estat d'abstract")
    public ResponseEntity<Map<String, Object>> getList(
            @RequestParam(required = false, defaultValue = "all") String filter,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "submissionYear") String sortField,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEntity.ok(abstractsService.getAbstractsList(filter, search, desde, hasta, type, page, size, sortField, sortDir));
    }

    @GetMapping("/{uuid}")
    @Operation(summary = "Obté el detall i comparació dels textos d'abstract d'una publicació concreta")
    public ResponseEntity<Map<String, Object>> getDetail(@PathVariable String uuid) {
        return abstractsService.getAbstractDetail(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
