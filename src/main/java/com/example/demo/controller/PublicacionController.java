package com.example.demo.controller;

// 1. Modelos y Repositorios propios
import com.example.demo.model.Publicacion;
import com.example.demo.repository.PublicacionRepository;

// 2. Spring Web (Anotaciones del Controlador)
import org.springframework.web.bind.annotation.*;

// 3. Spring Data - Paginación y Ordenación
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;

// 4. Spring Data MongoDB - Motor de consultas y Agregaciones (Gráficos)
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;

// 5. Utilidades de Java
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/pure", "/pure", "/otr/api/pure"})
@CrossOrigin(origins = "*")
public class PublicacionController {

    private final PublicacionRepository repository;
    private final MongoTemplate mongoTemplate;

    public PublicacionController(PublicacionRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public Page<Publicacion> listar(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        PageRequest pageable = PageRequest.of(page, size, Sort.by("submissionYear").descending());
        
        if (buscar.isEmpty()) {
            return repository.findAll(pageable);
        } else {
            return repository.findByTitleValueContainingIgnoreCase(buscar, pageable);
        }
    }

    @GetMapping("/buscar")
    public Page<Publicacion> buscarPorAnio(
            @RequestParam(required = false) Integer anio,
            @RequestParam(defaultValue = "0") int page) {
        
        // Configuramos para traer 10 resultados, ordenados por los más nuevos
        Pageable pageable = PageRequest.of(page, 10, Sort.by("modifiedDate").descending());

        if (anio != null) {
            return repository.findBySubmissionYear(anio, pageable);
        } else {
            return repository.findAll(pageable);
        }
    }
    
    // Estadísticas para Gráfico de Líneas (Años)
@GetMapping("/stats/anios")
public List<Map> statsAnios() {
    try {
        Aggregation agg = Aggregation.newAggregation(
            // Cambia esto por el nombre exacto del campo en tu JSON de Mongo
            Aggregation.group("publicationDate.year").count().as("total"),
            Aggregation.project("total").and("_id").as("anio"),
            Aggregation.sort(Sort.Direction.ASC, "anio")
        );

        // MUY IMPORTANTE: Cambia "researchoutputs" por el nombre real de tu colección
        // que ves en MongoDB Compass. A veces es "Researchoutputs" (con R mayúscula)
        AggregationResults<Map> results = mongoTemplate.aggregate(agg, "Researchoutputs", Map.class);
        return results.getMappedResults();
        
    } catch (Exception e) {
        // Esto imprimirá el error real en tu consola de Java
        e.printStackTrace();
        return List.of(Map.of("error", e.getMessage()));
    }
}

    // Estadísticas para Gráfico de Donut (Tipos)
    @GetMapping("/stats/tipos")
    public List<Map> statsTipos() {
        Aggregation agg = Aggregation.newAggregation(
            Aggregation.group("type.term.es_ES").count().as("total"),
            Aggregation.project("total").and("_id").as("tipo")
        );
        return mongoTemplate.aggregate(agg, "Researchoutputs", Map.class).getMappedResults();
    }

    

}