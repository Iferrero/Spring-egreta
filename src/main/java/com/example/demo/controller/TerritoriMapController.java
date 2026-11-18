package com.example.demo.controller;

import com.example.demo.territori.TerritoriMapService;
import com.example.demo.territori.TerritoriMapService.NotReadyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consultes dinàmiques del mapa d'interaccions UAB–territori català.
 * Filtres (query params, tots opcionals): yearFrom, yearTo, prov, municipi, interaction, tipologia,
 * ambit, unitat, q, convTipus, convNat, projRol, projLider, projTipus.
 */
@RestController
@RequestMapping("/api/mapa-territori")
@CrossOrigin(origins = "*")
public class TerritoriMapController {

    private final TerritoriMapService service;

    public TerritoriMapController(TerritoriMapService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        boolean started = service.triggerRebuild();
        Map<String, Object> out = new LinkedHashMap<>(service.status());
        out.put("started", started);
        return out;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return service.meta();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam Map<String, String> params) {
        return service.summary(params);
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(
            @RequestParam Map<String, String> params,
            @RequestParam(defaultValue = "totInt") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "false") boolean all) {
        return service.agents(params, sort, "asc".equalsIgnoreCase(dir), Math.max(0, page), all);
    }

    @GetMapping("/convenis")
    public Map<String, Object> convenis(
            @RequestParam Map<String, String> params,
            @RequestParam(defaultValue = "any") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "false") boolean all) {
        return service.convenis(params, sort, "asc".equalsIgnoreCase(dir), Math.max(0, page), all);
    }

    @GetMapping("/projectes")
    public Map<String, Object> projectes(
            @RequestParam Map<String, String> params,
            @RequestParam(defaultValue = "any") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "false") boolean all) {
        return service.projectes(params, sort, "asc".equalsIgnoreCase(dir), Math.max(0, page), all);
    }

    @GetMapping("/agents/{idx}")
    public ResponseEntity<Map<String, Object>> agentDetail(@PathVariable int idx, @RequestParam Map<String, String> params) {
        Map<String, Object> out = service.agentDetail(idx, params);
        return out == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(out);
    }

    @ExceptionHandler(NotReadyException.class)
    public ResponseEntity<Map<String, Object>> notReady(NotReadyException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", e.state());
        body.put("message", e.getMessage());
        return ResponseEntity.status(503).header("Retry-After", "5").body(body);
    }
}
