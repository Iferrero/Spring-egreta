package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

@Document(collection = "Events")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {

    @Id
    private String id;

    private Long pureId;
    private String uuid;
    private String createdBy;
    private String createdDate;
    private String modifiedBy;
    private String modifiedDate;
    private String version;
    private String systemName;

    /** Multilingual title: en_GB, es_ES, ca_ES */
    private Map<String, String> title;

    private UriTerm type;

    private String city;
    private String location;

    private UriTerm country;

    private UriTerm degreeOfRecognition;

    private Lifecycle lifecycle;

    private List<Identifier> identifiers;

    private WorkflowStatus workflow;

    // -------------------------------------------------------------------------
    // Convenience helpers
    // -------------------------------------------------------------------------

    public String getDisplayTitle() {
        if (title == null) return null;
        return title.getOrDefault("ca_ES",
               title.getOrDefault("es_ES",
               title.getOrDefault("en_GB", null)));
    }

    public String getTypeName() {
        return resolveLocalized(type != null ? type.getTerm() : null);
    }

    public String getCountryName() {
        return resolveLocalized(country != null ? country.getTerm() : null);
    }

    public String getDegreeOfRecognitionName() {
        return resolveLocalized(degreeOfRecognition != null ? degreeOfRecognition.getTerm() : null);
    }

    public Integer getStartYear() {
        if (lifecycle == null || lifecycle.getStartDate() == null) return null;
        String d = lifecycle.getStartDate();
        try { return Integer.parseInt(d.substring(0, 4)); } catch (Exception e) { return null; }
    }

    private static String resolveLocalized(Map<String, String> map) {
        if (map == null) return null;
        return map.getOrDefault("ca_ES",
               map.getOrDefault("es_ES",
               map.getOrDefault("en_GB", null)));
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UriTerm {
        private String uri;
        private Map<String, String> term;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public Map<String, String> getTerm() { return term; }
        public void setTerm(Map<String, String> term) { this.term = term; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Lifecycle {
        /** ISO date string: "yyyy-MM-dd" */
        private String startDate;
        private String endDate;

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Identifier {
        private String typeDiscriminator;
        private String idSource;
        private String value;

        public String getTypeDiscriminator() { return typeDiscriminator; }
        public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
        public String getIdSource() { return idSource; }
        public void setIdSource(String idSource) { this.idSource = idSource; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkflowStatus {
        private String step;
        private Map<String, String> description;

        public String getStep() { return step; }
        public void setStep(String step) { this.step = step; }
        public Map<String, String> getDescription() { return description; }
        public void setDescription(Map<String, String> description) { this.description = description; }
    }

    // -------------------------------------------------------------------------
    // Top-level getters & setters
    // -------------------------------------------------------------------------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getPureId() { return pureId; }
    public void setPureId(Long pureId) { this.pureId = pureId; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public String getModifiedDate() { return modifiedDate; }
    public void setModifiedDate(String modifiedDate) { this.modifiedDate = modifiedDate; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSystemName() { return systemName; }
    public void setSystemName(String systemName) { this.systemName = systemName; }
    public Map<String, String> getTitle() { return title; }
    public void setTitle(Map<String, String> title) { this.title = title; }
    public UriTerm getType() { return type; }
    public void setType(UriTerm type) { this.type = type; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public UriTerm getCountry() { return country; }
    public void setCountry(UriTerm country) { this.country = country; }
    public UriTerm getDegreeOfRecognition() { return degreeOfRecognition; }
    public void setDegreeOfRecognition(UriTerm degreeOfRecognition) { this.degreeOfRecognition = degreeOfRecognition; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public List<Identifier> getIdentifiers() { return identifiers; }
    public void setIdentifiers(List<Identifier> identifiers) { this.identifiers = identifiers; }
    public WorkflowStatus getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowStatus workflow) { this.workflow = workflow; }
}
