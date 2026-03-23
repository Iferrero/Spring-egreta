package com.example.demo.controller;

import com.example.demo.model.Award;
import com.example.demo.repository.AwardRepository;
import com.example.demo.service.AwardService;

import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/awards", "/awards", "/otr/api/awards"})
@CrossOrigin(origins = "*")
public class AwardController {

    private final AwardRepository repository;
    private final AwardService service;

    public AwardController(AwardRepository repository,
                           AwardService service) {

        this.repository = repository;
        this.service = service;
    }

    /*
    ===============================
    LISTADO BÁSICO
    ===============================
    */

    @GetMapping
    public PagedModel<Award> getAwards(
            @RequestParam(defaultValue = "0") int page) {

        return new PagedModel<>(
                repository.findValidated(PageRequest.of(page, 10)));
    }

    /*
    ===============================
    ESTADÍSTICAS GENERALES
    ===============================
    */

    @GetMapping("/stats/categories")
    public List<String> getCategorias() {
        return service.getCategorias();
    }

    @GetMapping("/stats/tipus")
    public List<String> getTipus() {
        return service.getTipus();
    }

    @GetMapping("/stats/tipus-per-categoria")
    public List<Document> getTipusPerCategoria() {
        return service.getTipusPerCategoria();
    }

    @GetMapping("/stats/total")
    public Map<String, Object> getTotalStats() {
        return service.getTotalStats();
    }

    /*
    ===============================
    POWER TABLE
    ===============================
    */

    @GetMapping("/stats/powertable")
    public List<Document> getPowerTable(
            @RequestParam(required = false) Integer desde,
            @RequestParam(required = false) Integer hasta,
            @RequestParam(defaultValue = "awardDate") String modoAnio) {

        return service.getPowerTable(desde, hasta, modoAnio);
    }

    @GetMapping("/stats/powertable/category-debug")
    public List<Map> getPowerTableCategoryDebug(
            @RequestParam(defaultValue = "50") int limit) {

        return service.getPowerTableCategoryDebug(limit);
    }

    /*
    ===============================
    PERSONA RESUMEN
    ===============================
    */

    @GetMapping("/stats/persona-resumen")
    public List<Document> getPersonaResumen(

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(required = false)
            String persona,

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            String gestionadosPorDept,

            @RequestParam(required = false)
            List<String> categoria,

            @RequestParam(required = false)
            List<String> tipus) {

        return service.getPersonaResumen(
                collaboratorUuid,
                deptUuid,
                persona,
                desde,
                hasta,
                modoAnio,
                gestionadosPorDept,
                categoria,
                tipus);
    }

    /*
    ===============================
    AWARDS POR PERSONA
    ===============================
    */

    @GetMapping("/stats/persona-awards")
    public List<Document> getAwardsByPersona(

            @RequestParam String personUuid,

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            List<String> categoria,

            @RequestParam(required = false)
            List<String> tipus) {

        return service.getAwardsByPersona(
                personUuid,
                desde,
                hasta,
                collaboratorUuid,
                deptUuid,
                modoAnio,
                categoria,
                tipus);
    }

    /*
    ===============================
    PROYECTOS POR AÑO
    ===============================
    */

    @GetMapping("/stats/proyectos-anio")
    public List<Document> getProyectosPorAnio(

            @RequestParam(required = false)
            Integer desde,

            @RequestParam(required = false)
            Integer hasta,

            @RequestParam(defaultValue =
                    "84443078-1a60-462d-9d0a-b04312afd9eb")
            String collaboratorUuid,

            @RequestParam(required = false)
            String deptUuid,

            @RequestParam(required = false)
            String persona,

            @RequestParam(defaultValue = "awardDate")
            String modoAnio,

            @RequestParam(required = false)
            List<String> categoria,

            @RequestParam(required = false)
            List<String> tipus) {

        return service.getProyectosPorAnio(
                desde,
                hasta,
                collaboratorUuid,
                deptUuid,
                persona,
                modoAnio,
                categoria,
                tipus
                );
    }
}