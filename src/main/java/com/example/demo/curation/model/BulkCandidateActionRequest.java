package com.example.demo.curation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Petición para acción masiva sobre una lista de candidatos a publicación.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BulkCandidateActionRequest {

    private List<String> candidateIds = new ArrayList<>();
    private CandidateStatus targetStatus;
    private String reviewer = "analista";

    public BulkCandidateActionRequest() {
    }

    public BulkCandidateActionRequest(List<String> candidateIds, CandidateStatus targetStatus, String reviewer) {
        this.candidateIds = candidateIds != null ? new ArrayList<>(candidateIds) : new ArrayList<>();
        this.targetStatus = targetStatus;
        this.reviewer = (reviewer != null && !reviewer.isBlank()) ? reviewer : "analista";
    }

    public BulkCandidateActionRequest(List<String> candidateIds, CandidateStatus targetStatus) {
        this(candidateIds, targetStatus, "analista");
    }

    public List<String> getCandidateIds() {
        if (candidateIds == null) {
            candidateIds = new ArrayList<>();
        }
        return candidateIds;
    }

    public void setCandidateIds(List<String> candidateIds) {
        this.candidateIds = candidateIds != null ? new ArrayList<>(candidateIds) : new ArrayList<>();
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

    @Override
    public String toString() {
        return "BulkCandidateActionRequest{" +
                "candidateIds=" + candidateIds +
                ", targetStatus=" + targetStatus +
                ", reviewer='" + reviewer + '\'' +
                '}';
    }
}
