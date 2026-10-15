package com.example.demo.curation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Petición de actualización de estado de curación de un candidato.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CandidateStatusUpdateRequest {

    private CandidateStatus targetStatus;
    private String reviewer = "analista";
    private String comments;

    public CandidateStatusUpdateRequest() {
    }

    public CandidateStatusUpdateRequest(CandidateStatus targetStatus, String reviewer, String comments) {
        this.targetStatus = targetStatus;
        this.reviewer = (reviewer != null && !reviewer.isBlank()) ? reviewer : "analista";
        this.comments = comments;
    }

    public CandidateStatusUpdateRequest(CandidateStatus targetStatus) {
        this(targetStatus, "analista", null);
    }

    public CandidateStatus getTargetStatus() {
        return targetStatus;
    }

    public void setTargetStatus(CandidateStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = (reviewer != null && !reviewer.isBlank()) ? reviewer : "analista";
    }

    public String getComments() {
        return comments;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    @Override
    public String toString() {
        return "CandidateStatusUpdateRequest{" +
                "targetStatus=" + targetStatus +
                ", reviewer='" + reviewer + '\'' +
                ", comments='" + comments + '\'' +
                '}';
    }
}
