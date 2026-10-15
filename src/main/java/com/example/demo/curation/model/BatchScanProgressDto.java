package com.example.demo.curation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO que reporta el estado y métricas de progreso de un escaneo por lotes en segundo plano.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchScanProgressDto {

    public enum BatchScanStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private String jobId;
    private BatchScanStatus status = BatchScanStatus.RUNNING;
    private int totalAuthors;
    private int processedAuthors;
    private int failedAuthors;
    private int greenCandidatesCount;
    private int yellowCandidatesCount;
    private int redCandidatesCount;
    private Instant startedAt;
    private Instant finishedAt;
    private List<String> errors = new ArrayList<>();

    public BatchScanProgressDto() {
    }

    public BatchScanProgressDto(String jobId, BatchScanStatus status, int totalAuthors, int processedAuthors,
                                int failedAuthors, int greenCandidatesCount, int yellowCandidatesCount,
                                int redCandidatesCount, Instant startedAt, Instant finishedAt, List<String> errors) {
        this.jobId = jobId;
        this.status = status;
        this.totalAuthors = totalAuthors;
        this.processedAuthors = processedAuthors;
        this.failedAuthors = failedAuthors;
        this.greenCandidatesCount = greenCandidatesCount;
        this.yellowCandidatesCount = yellowCandidatesCount;
        this.redCandidatesCount = redCandidatesCount;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public BatchScanStatus getStatus() {
        return status;
    }

    public void setStatus(BatchScanStatus status) {
        this.status = status;
    }

    public int getTotalAuthors() {
        return totalAuthors;
    }

    public void setTotalAuthors(int totalAuthors) {
        this.totalAuthors = totalAuthors;
    }

    public int getTotalCount() {
        return totalAuthors;
    }

    public int getProcessedAuthors() {
        return processedAuthors;
    }

    public void setProcessedAuthors(int processedAuthors) {
        this.processedAuthors = processedAuthors;
    }

    public int getProcessedCount() {
        return processedAuthors;
    }

    public int getFailedAuthors() {
        return failedAuthors;
    }

    public void setFailedAuthors(int failedAuthors) {
        this.failedAuthors = failedAuthors;
    }

    public int getFailedCount() {
        return failedAuthors;
    }

    public int getGreenCandidatesCount() {
        return greenCandidatesCount;
    }

    public void setGreenCandidatesCount(int greenCandidatesCount) {
        this.greenCandidatesCount = greenCandidatesCount;
    }

    public int getGreenCount() {
        return greenCandidatesCount;
    }

    public int getYellowCandidatesCount() {
        return yellowCandidatesCount;
    }

    public void setYellowCandidatesCount(int yellowCandidatesCount) {
        this.yellowCandidatesCount = yellowCandidatesCount;
    }

    public int getYellowCount() {
        return yellowCandidatesCount;
    }

    public int getRedCandidatesCount() {
        return redCandidatesCount;
    }

    public void setRedCandidatesCount(int redCandidatesCount) {
        this.redCandidatesCount = redCandidatesCount;
    }

    public int getRedCount() {
        return redCandidatesCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public List<String> getErrors() {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "BatchScanProgressDto{" +
                "jobId='" + jobId + '\'' +
                ", status=" + status +
                ", totalAuthors=" + totalAuthors +
                ", processedAuthors=" + processedAuthors +
                ", failedAuthors=" + failedAuthors +
                ", greenCandidatesCount=" + greenCandidatesCount +
                ", yellowCandidatesCount=" + yellowCandidatesCount +
                ", redCandidatesCount=" + redCandidatesCount +
                ", startedAt=" + startedAt +
                ", finishedAt=" + finishedAt +
                ", errorsCount=" + (errors != null ? errors.size() : 0) +
                '}';
    }
}
