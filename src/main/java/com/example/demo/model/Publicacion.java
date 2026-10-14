package com.example.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Document(collection = "Researchoutputs") 
@CompoundIndexes({
    @CompoundIndex(name = "workflow_step_type_submissionYear", def = "{'workflow.step': 1, 'type.term.ca_ES': 1, 'submissionYear': 1}"),
    @CompoundIndex(name = "contributors_person_uuid", def = "{'contributors.person.uuid': 1}"),
    @CompoundIndex(name = "contributors_externalPerson_uuid", def = "{'contributors.externalPerson.uuid': 1}"),
    @CompoundIndex(name = "journalAssociation_journal_uuid_submissionYear", def = "{'journalAssociation.journal.uuid': 1, 'submissionYear': -1}")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class Publicacion {
    
    @Id
    private String id;

    @Indexed(unique = true)
    private String uuid;

    @Indexed
    private Integer submissionYear;
    private Title title;
    private Type type;
    private List<Contributor> contributors;
    @org.springframework.data.mongodb.core.mapping.Field("abstract")
    @com.fasterxml.jackson.annotation.JsonProperty("abstract")
    private Map<String, String> abstracts;
    private List<Identifier> identifiers;
    private List<ElectronicVersion> electronicVersions;

    // Getters y Setters
    public String getId() { return uuid; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getFullTitle() { return title != null ? title.getValue() : "Sin título"; }
    public String getTypeName() { 
        return (type != null && type.getTerm() != null) ? type.getTerm().get("es_ES") : "Desconocido"; 
    }
    public Integer getSubmissionYear() { return submissionYear; }
    public void setTitle(Title title) { this.title = title; }
    public void setType(Type type) { this.type = type; }
    public void setSubmissionYear(Integer submissionYear) { this.submissionYear = submissionYear; }
    public List<Identifier> getIdentifiers() { return identifiers; }
    public void setIdentifiers(List<Identifier> identifiers) { this.identifiers = identifiers; }
    public List<ElectronicVersion> getElectronicVersions() { return electronicVersions; }
    public void setElectronicVersions(List<ElectronicVersion> electronicVersions) { this.electronicVersions = electronicVersions; }
    public Map<String, String> getAbstracts() { return abstracts; }
    public void setAbstracts(Map<String, String> abstracts) { this.abstracts = abstracts; }
    public List<Contributor> getContributors() { return contributors; }
    public void setContributors(List<Contributor> contributors) { this.contributors = contributors; }

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
                .map(c -> {
                    if (c == null || c.getName() == null) return "";
                    String ln = c.getName().getLastName() != null ? c.getName().getLastName() : "";
                    String fn = c.getName().getFirstName() != null ? c.getName().getFirstName() : "";
                    if (ln.isEmpty() && fn.isEmpty()) return "";
                    if (ln.isEmpty()) return fn;
                    if (fn.isEmpty()) return ln;
                    return ln + ", " + fn;
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" | "));
    }

    // Clases internas necesarias para el mapeo
    public static class Contributor {
        private ContributorName name;
        private PersonRef person;

        public ContributorName getName() { return name; }
        public void setName(ContributorName name) { this.name = name; }
        public PersonRef getPerson() { return person; }
        public void setPerson(PersonRef person) { this.person = person; }
    }

    public static class PersonRef {
        private String uuid;

        public PersonRef() {}
        public PersonRef(String uuid) { this.uuid = uuid; }

        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    public static class ContributorName {
        private String firstName;
        private String lastName;
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    public static class Identifier {
        private String typeDiscriminator;
        private Long pureId;
        private String idSource;
        private String value;

        public String getTypeDiscriminator() { return typeDiscriminator; }
        public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public String getIdSource() { return idSource; }
        public void setIdSource(String idSource) { this.idSource = idSource; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class ElectronicVersion {
        private String doi;
        public String getDoi() { return doi; }
        public void setDoi(String doi) { this.doi = doi; }
    }
}