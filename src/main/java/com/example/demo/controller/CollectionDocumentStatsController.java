package com.example.demo.controller;

import com.example.demo.service.CollectionDocumentStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/mongo")
@Tag(name = "MongoDB Collection Document Stats", description = "Estadístiques de fitxers i documents adjunts per col·lecció a MongoDB (kraken)")
public class CollectionDocumentStatsController {

    private final CollectionDocumentStatsService statsService;

    public CollectionDocumentStatsController(CollectionDocumentStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/collections/document-stats")
    @Operation(summary = "Obtenir estadístiques de documents i megabytes per col·lecció",
               description = "Retorna el recompte de registres amb fitxers adjunts, total de fitxers, bytes, MB i GB per col·lecció.")
    public Map<String, Object> getDocumentStats(
            @Parameter(description = "Forçar recàlcul ignorant la memòria cau")
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return statsService.getDocumentStats(refresh);
    }
}
