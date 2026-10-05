package com.example.demo.controller;

import com.example.demo.service.ResearchoutputsAiAbstractsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pure/ai-abstracts")
@CrossOrigin(origins = "*")
@Tag(name = "Researchoutputs AI Abstracts", description = "Monitorització, anàlisi i auditoria d'abstracts generats amb IA (salamandrata-7b-instruct) i sincronització a Egreta")
public class ResearchoutputsAiAbstractsController {

    private final ResearchoutputsAiAbstractsService aiAbstractsService;

    @Autowired
    public ResearchoutputsAiAbstractsController(ResearchoutputsAiAbstractsService aiAbstractsService) {
        this.aiAbstractsService = aiAbstractsService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Obté estadístiques globals i KPIs d'abstracts IA (total generats, sincronitzats, latència mitjana, distribució per llengua font i model)")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false) String sourceLang,
            @RequestParam(required = false) String genLang,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType) {
        return ResponseEntity.ok(aiAbstractsService.getAiStats(desde, hasta, sourceLang, genLang, yearType));
    }

    @GetMapping("/years")
    @Operation(summary = "Obté la distribució d'abstracts IA per any de publicació o submissió")
    public ResponseEntity<List<Map<String, Object>>> getByYear(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType) {
        return ResponseEntity.ok(aiAbstractsService.getAiAbstractsByYear(desde, hasta, yearType));
    }

    @GetMapping("/filters")
    @Operation(summary = "Obté les opcions de filtre disponibles (models, llengües font, llengües de destinació)")
    public ResponseEntity<Map<String, Object>> getFilterOptions() {
        return ResponseEntity.ok(aiAbstractsService.getFilterOptions());
    }

    @GetMapping("/list")
    @Operation(summary = "Llistat paginat de publicacions amb abstracts IA, amb filtrat per estat de sincronització, llengües i cerca de text")
    public ResponseEntity<Map<String, Object>> getList(
            @RequestParam(required = false) Boolean synced,
            @RequestParam(required = false) String sourceLang,
            @RequestParam(required = false) String genLang,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(required = false, defaultValue = "publicationYear") String yearType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "publicationYear") String sortField,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEntity.ok(aiAbstractsService.getAiAbstractsList(
                synced, sourceLang, genLang, search, desde, hasta, yearType, page, size, sortField, sortDir));
    }

    @GetMapping("/{uuid}")
    @Operation(summary = "Obté el detall d'un abstract IA concret per UUID, amb informació de traduccions, tokens i sincronització")
    public ResponseEntity<Map<String, Object>> getDetail(@PathVariable String uuid) {
        return aiAbstractsService.getAiAbstractDetail(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/cache/clear")
    @Operation(summary = "Neteja la memòria cau dels càlculs d'abstracts IA")
    public ResponseEntity<Map<String, Object>> clearCache() {
        aiAbstractsService.clearCache();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Caché d'abstracts IA neta correctament"));
    }

    @PostMapping("/discrepancies/populate")
    @Operation(summary = "Inicia el procés batch per calcular i desar les mètriques de discrepància a ResearchoutputsAiAbstracts")
    public ResponseEntity<Map<String, Object>> populateDiscrepancies(
            @RequestParam(required = false, defaultValue = "false") Boolean onlyDiscrepant,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") Boolean includeLingua) {
        return ResponseEntity.ok(aiAbstractsService.populateDiscrepanciesAsync(onlyDiscrepant, limit, includeLingua));
    }

    @GetMapping("/discrepancies/populate/status")
    @Operation(summary = "Obté l'estat i progrés en viu del procés de població de discrepàncies")
    public ResponseEntity<Map<String, Object>> getPopulateStatus() {
        return ResponseEntity.ok(aiAbstractsService.getPopulateStatus());
    }

    @PostMapping("/discrepancies/{uuid}")
    @Operation(summary = "Calcula i desa l'anàlisi de discrepància per a un UUID concret a ResearchoutputsAiAbstracts")
    public ResponseEntity<Map<String, Object>> populateSingleDiscrepancy(@PathVariable String uuid) {
        return aiAbstractsService.populateSingleDiscrepancy(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
