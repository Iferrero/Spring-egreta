package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Document(collection = "Publishers")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Publisher {

    @Id
    private String id;
    private Integer pureId;
    private String uuid;
    private String createdBy;
    private String createdDate;
    private String modifiedBy;
    private String modifiedDate;
    private String version;
    private String name;
    private LocalizedTerm type;
    private Workflow workflow;
    private String systemName;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getPureId() {
        return pureId;
    }

    public void setPureId(Integer pureId) {
        this.pureId = pureId;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    public String getModifiedDate() {
        return modifiedDate;
    }

    public void setModifiedDate(String modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalizedTerm getType() {
        return type;
    }

    public void setType(LocalizedTerm type) {
        this.type = type;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalizedTerm {
        private String uri;
        private Map<String, String> term;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public Map<String, String> getTerm() {
            return term;
        }

        public void setTerm(Map<String, String> term) {
            this.term = term;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Workflow {
        private String step;
        private Map<String, String> description;

        public String getStep() {
            return step;
        }

        public void setStep(String step) {
            this.step = step;
        }

        public Map<String, String> getDescription() {
            return description;
        }

        public void setDescription(Map<String, String> description) {
            this.description = description;
        }
    }
}
