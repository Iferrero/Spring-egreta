package com.example.demo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organizations")
@CrossOrigin(origins = "*")
public class OrganizacionController {

    private final MongoTemplate mongoTemplate;

    @Autowired
    public OrganizacionController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/stats/research-structures")
    public Map<String, Long> getResearchStructuresStats() {
        Map<String, Long> stats = new HashMap<>();

        stats.put("departaments", countActiveByType("Departament"));
        stats.put("cers", countActiveByType("Centres d'Estudis i de Recerca"));
        stats.put("institutsPropis", countActiveByType("Instituts Universitaris de Recerca Propis"));
        
        stats.put("sgrs", countActiveByTypes(List.of("Grup de Recerca", "Grup de Recerca UAB")));
        
        stats.put("esfera", countActiveByTypes(List.of(
            "Centres amb conveni de participació en l'esfera UAB",
            "Empresa Esfera",
            "Centres de recerca en el campus de la UAB",
            "Centres de recerca participats",
            "Centres del CSIC amb conveni amb la UAB"
        )));

        return stats;
    }

    private long countActiveByType(String typeName) {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").is(typeName));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        return mongoTemplate.count(query, "Organizations");
    }

    private long countActiveByTypes(List<String> typeNames) {
        Query query = new Query();
        query.addCriteria(Criteria.where("type.term.ca_ES").in(typeNames));
        query.addCriteria(Criteria.where("lifecycle.endDate").is(null));
        return mongoTemplate.count(query, "Organizations");
    }
}
