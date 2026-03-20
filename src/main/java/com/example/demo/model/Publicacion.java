package com.example.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Document(collection = "Researchoutputs") 
@JsonIgnoreProperties(ignoreUnknown = true)
public class Publicacion {
    
    @Id
    private String id;
    private String uuid;
    private Integer submissionYear;
    private Title title;
    private Type type;
    private List<Contributor> contributors;

    // Getters y Setters
    public String getId() { return uuid; }
    public String getFullTitle() { return title != null ? title.getValue() : "Sin título"; }
    public String getTypeName() { 
        return (type != null && type.getTerm() != null) ? type.getTerm().get("es_ES") : "Desconocido"; 
    }
    public Integer getSubmissionYear() { return submissionYear; }
    public void setTitle(Title title) { this.title = title; }
    public void setType(Type type) { this.type = type; }
    public void setSubmissionYear(Integer submissionYear) { this.submissionYear = submissionYear; }

    // Clases internas para el mapeo del JSON anidado
    public static class Title {
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class Type {
        private Map<String, String> term; // Captura en_GB, es_ES, ca_ES
        public Map<String, String> getTerm() { return term; }
        public void setTerm(Map<String, String> term) { this.term = term; }
    }

    // Añade este método para que el frontend reciba un String limpio de autores
    public String getAuthorsNames() {
        if (contributors == null || contributors.isEmpty()) return "Sin autores";
        return contributors.stream()
                .map(c -> c.getName().getLastName() + ", " + c.getName().getFirstName())
                .collect(Collectors.joining(" | "));
    }

    // Clases internas necesarias para el mapeo
    public static class Contributor {
        private ContributorName name;
        public ContributorName getName() { return name; }
        public void setName(ContributorName name) { this.name = name; }
    }

    public static class ContributorName {
        private String firstName;
        private String lastName;
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }
}