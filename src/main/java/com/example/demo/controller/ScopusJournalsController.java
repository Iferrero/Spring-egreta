package com.example.demo.controller;

import com.example.demo.service.ScopusJournalsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/scopus")
@CrossOrigin(origins = "*")
@Tag(name = "Scopus Journals", description = "Estadístiques de revistes de Scopus i articles amb o sense ScopusID")
public class ScopusJournalsController {

    private final ScopusJournalsService scopusJournalsService;

    public ScopusJournalsController(ScopusJournalsService scopusJournalsService) {
        this.scopusJournalsService = scopusJournalsService;
    }

        public Map<String, Object> getJournalsStats(boolean refresh) {
        return getJournalsStats(refresh, "prod");
    }

    @GetMapping("/journals-stats")
    @Operation(summary = "Obtenir estadístiques de revistes Scopus i articles",
               description = "Retorna el recompte d'articles totals, amb ScopusID i sense ScopusID per a cada revista de Scopus.")
    public Map<String, Object> getJournalsStats(
            @Parameter(description = "Forçar recàlcul ignorant la memòria cau")
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
            @Parameter(description = "Entorn de dades (prod per a kraken, test per a kraken_test)")
            @RequestParam(name = "env", defaultValue = "prod") String env) {
        return scopusJournalsService.getJournalsStats(refresh, env);
    }

        public Map<String, Object> getArticlesByJournal(String uuid, int limit, int offset, Boolean hasScopusId, String search) {
        return getArticlesByJournal(uuid, limit, offset, hasScopusId, search, "prod");
    }

    @GetMapping("/journals/{uuid}/articles")
    @Operation(summary = "Obtenir llistat d'articles d'una revista Scopus",
               description = "Retorna els articles associats a la revista indicant si tenen o no scopusID, amb suport de cerca i paginació.")
    public Map<String, Object> getArticlesByJournal(
            @Parameter(description = "UUID de la revista")
            @PathVariable("uuid") String uuid,
            @Parameter(description = "Límit d'articles a retornar (per defecte 50, màxim 200)")
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @Parameter(description = "Desplaçament o offset per a paginació")
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @Parameter(description = "Filtrar per si té ScopusID (true), no en té (false), o tots (null)")
            @RequestParam(name = "hasScopusId", required = false) Boolean hasScopusId,
            @Parameter(description = "Cerca per títol, autor o DOI")
            @RequestParam(name = "search", required = false) String search,
            @Parameter(description = "Entorn de dades (prod per a kraken, test per a kraken_test)")
            @RequestParam(name = "env", defaultValue = "prod") String env) {
        return scopusJournalsService.getArticlesByJournal(uuid, limit, offset, hasScopusId, search, env);
    }

        public Map<String, Object> lookupScopusId(String uuid, String apiKey) {
        return lookupScopusId(uuid, apiKey, "prod");
    }

    @GetMapping("/articles/{uuid}/lookup")
    @Operation(summary = "Cercar el Scopus ID d'un article a Scopus",
               description = "Cerca el Scopus ID corresponent a un article mitjançant DOI o títol a través de l'API d'Elsevier o genera l'enllaç directe de cerca.")
    public Map<String, Object> lookupScopusId(
            @Parameter(description = "UUID de l'article")
            @PathVariable("uuid") String uuid,
            @Parameter(description = "Clau d'API de Scopus opcional")
            @RequestParam(name = "apiKey", required = false) String apiKey,
            @Parameter(description = "Entorn de dades (prod per a kraken, test per a kraken_test)")
            @RequestParam(name = "env", defaultValue = "prod") String env) {
        return scopusJournalsService.lookupScopusId(uuid, apiKey, env);
    }

    @PostMapping("/articles/{uuid}/assign-scopus-id")
    @Operation(summary = "Assignar un Scopus ID a un article i sincronitzar amb Egreta",
               description = "Afegeix o actualitza l'identificador Scopus a l'article a MongoDB i sincronitza automàticament el canvi amb l'API d'Egreta.")
    public Map<String, Object> assignScopusId(
            @Parameter(description = "UUID de l'article")
            @PathVariable("uuid") String uuid,
            @RequestBody Map<String, String> payload) {
        String scopusId = payload != null ? payload.get("scopusId") : null;
        String env = payload != null ? payload.getOrDefault("env", "prod") : "prod";
        return scopusJournalsService.assignScopusId(uuid, scopusId, env);
    }

        public Map<String, Object> getBatchCandidatesCount(String journalUuid) {
        return getBatchCandidatesCount(journalUuid, "prod");
    }

    @GetMapping("/batch/candidates-count")
    @Operation(summary = "Comptar articles pendents de Scopus ID",
               description = "Retorna el nombre d'articles de revistes Scopus que no tenen Scopus ID.")
    public Map<String, Object> getBatchCandidatesCount(
            @Parameter(description = "UUID opcional de revista per filtrar només els seus articles")
            @RequestParam(name = "journalUuid", required = false) String journalUuid,
            @Parameter(description = "Entorn de dades (prod per a kraken, test per a kraken_test)")
            @RequestParam(name = "env", defaultValue = "prod") String env) {
        return scopusJournalsService.getBatchCandidatesStats(journalUuid, env);
    }

    @PostMapping("/batch/start")
    @Operation(summary = "Iniciar procés automàtic de cerca i assignació de Scopus ID per DOI",
               description = "Inicia una tasca asíncrona que processa articles amb DOI sense Scopus ID en revistes Scopus.")
    public Map<String, Object> startBatchJob(@RequestBody(required = false) Map<String, Object> payload) {
        int limit = 50;
        boolean dryRun = false;
        String env = "prod";
        String journalUuid = null;
        String apiKey = null;

        if (payload != null) {
            if (payload.get("limit") instanceof Number n) limit = n.intValue();
            else if (payload.get("limit") instanceof String s && !s.isBlank()) {
                try { limit = Integer.parseInt(s.trim()); } catch (Exception ignored) {}
            }
            if (payload.get("dryRun") instanceof Boolean b) dryRun = b;
            if (payload.get("env") instanceof String e && !e.isBlank()) env = e.trim();
            if (payload.get("journalUuid") instanceof String j && !j.isBlank()) journalUuid = j.trim();
            if (payload.get("apiKey") instanceof String k && !k.isBlank()) apiKey = k.trim();
        }

        return scopusJournalsService.startBatchJob(limit, dryRun, env, journalUuid, apiKey);
    }

    @GetMapping("/batch/status")
    @Operation(summary = "Consultar l'estat del procés automàtic de Scopus ID",
               description = "Retorna el progrés, articles processats, trobats, assignats i logs en temps real.")
    public Map<String, Object> getBatchJobStatus() {
        return scopusJournalsService.getBatchJobStatus();
    }

    @PostMapping("/batch/stop")
    @Operation(summary = "Aturar el procés automàtic de Scopus ID",
               description = "Sol·licita la cancel·lació de la tasca batch en execució.")
    public Map<String, Object> stopBatchJob() {
        return scopusJournalsService.stopBatchJob();
    }
}
