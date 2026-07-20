package com.example.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Date;

@Document(collection = "ResearchoutputsCorrections")
@JsonIgnoreProperties(ignoreUnknown = true)
public class PublicacionCorreccion {
    @Id
    private String id; // equals to publication uuid
    private boolean reviewed;
    private String observations;
    private Date modifiedDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean reviewed) { this.reviewed = reviewed; }
    public String getObservations() { return observations; }
    public void setObservations(String observations) { this.observations = observations; }
    public Date getModifiedDate() { return modifiedDate; }
    public void setModifiedDate(Date modifiedDate) { this.modifiedDate = modifiedDate; }
}
