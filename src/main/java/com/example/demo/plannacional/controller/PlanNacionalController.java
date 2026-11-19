package com.example.demo.plannacional.controller;

import com.example.demo.plannacional.model.ProposalValidationResponse;
import com.example.demo.plannacional.model.ReevaluateTeamRequest;
import com.example.demo.plannacional.model.RuleCatalogResponse;
import com.example.demo.plannacional.parser.InvalidPdfException;
import com.example.demo.plannacional.parser.ScannedPdfException;
import com.example.demo.plannacional.service.PlanNacionalValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

/**
 * Endpoints REST per validar sol·licituds del Pla Nacional contra la col·lecció Persons d'Egreta.
 */
@RestController
@RequestMapping("/api/plan-nacional")
@CrossOrigin(origins = "*")
public class PlanNacionalController {

    private final PlanNacionalValidationService validationService;

    @Autowired
    public PlanNacionalController(PlanNacionalValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping(value = "/validar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> validarPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            @RequestParam(required = false) Integer durationYears,
            @RequestParam(defaultValue = "true") boolean useAiFallback) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "No s'ha adjuntat cap fitxer PDF vàlid."));
        }
        try {
            ProposalValidationResponse response = validationService.validatePdf(
                    file.getOriginalFilename(),
                    file.getBytes(),
                    referenceDate,
                    durationYears,
                    useAiFallback
            );
            return ResponseEntity.ok(response);
        } catch (InvalidPdfException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (ScannedPdfException e) {
            return ResponseEntity.status(HttpStatus.valueOf(422)).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "No s'han pogut llegir els bytes del fitxer PDF pujat."));
        }
    }

    @PostMapping(value = "/validar-equipo", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validarEquipo(@RequestBody(required = false) ReevaluateTeamRequest request) {
        try {
            ProposalValidationResponse response = validationService.reevaluateTeam(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/reglas")
    public ResponseEntity<RuleCatalogResponse> getReglas() {
        return ResponseEntity.ok(validationService.getRuleCatalog());
    }
}
