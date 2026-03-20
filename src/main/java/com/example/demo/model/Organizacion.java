package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.Map;

@Document(collection = "Organizations") // Asegúrate de que este nombre coincida con tu colección en Mongo
@JsonIgnoreProperties(ignoreUnknown = true)
public class Organizacion {
    @Id
    private String id;
    private String uuid;
    private Map<String, String> name; // Mapea "es_ES", "ca_ES", "en_GB"
    private String type; // Campo para filtrar por "department"
    private Lifecycle lifecycle; // Clase interna para el ciclo de vida

    public String getNombre() {
        return name != null ? name.getOrDefault("ca_ES", name.getOrDefault("en_GB", "Sin nombre")) : "Sin nombre";
    }

    public static class Lifecycle {
        private LocalDate endDate;
        public LocalDate getEndDate() { return endDate; }
        public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    }

    // Getters y Setters
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public Map<String, String> getName() { return name; }
    public void setName(Map<String, String> name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
}