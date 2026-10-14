package com.example.demo.curation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entidad que representa una publicacion detectada en OpenAlex atribuible como candidata
 * a un investigador de EGRETA, su scoring heuristico y su estado de revision.
 */
@Document(collection = "PersonPublicationCandidates")
@CompoundIndexes({
        @CompoundIndex(name = "person_doi_unique_idx", def = "{'personUuid': 1, 'doi': 1}", unique = true, partialFilter = "{'doi': {'$type': 'string'}}"),
        @CompoundIndex(name = "person_openalex_unique_idx", def = "{'personUuid': 1, 'openAlexWorkId': 1}", unique = true, partialFilter = "{'openAlexWorkId': {'$type': 'string'}}"),
        @CompoundIndex(name = "person_status_verdict_idx", def = "{'personUuid': 1, 'status': 1, 'verdict': 1}"),
        @CompoundIndex(name = "department_status_idx", def = "{'departmentId': 1, 'status': 1}")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class PersonPublicationCandidate {

    @Id
    private String id;

    private String personUuid;
    private String personName;
    private String departmentId;
    private String departmentName;

    private String doi;
    private String title;
    private Integer year;
    private String venue;
    private String openAlexWorkId;

    private AffiliationStatus affiliationStatus;
    private List<String> matchedAffiliations = new ArrayList<>();
    private String coauthorSignals;

    private CandidateVerdict verdict;
    private Double confidenceScore;
    private List<String> reasons = new ArrayList<>();

    private CandidateStatus status;
    private String reviewedBy;
    private Instant reviewedAt;

    public PersonPublicationCandidate() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPersonUuid() {
        return personUuid;
    }

    public void setPersonUuid(String personUuid) {
        this.personUuid = personUuid;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public String getOpenAlexWorkId() {
        return openAlexWorkId;
    }

    public void setOpenAlexWorkId(String openAlexWorkId) {
        this.openAlexWorkId = openAlexWorkId;
    }

    public AffiliationStatus getAffiliationStatus() {
        return affiliationStatus;
    }

    public void setAffiliationStatus(AffiliationStatus affiliationStatus) {
        this.affiliationStatus = affiliationStatus;
    }

    public List<String> getMatchedAffiliations() {
        if (matchedAffiliations == null) {
            matchedAffiliations = new ArrayList<>();
        }
        return matchedAffiliations;
    }

    public void setMatchedAffiliations(List<String> matchedAffiliations) {
        this.matchedAffiliations = (matchedAffiliations != null) ? new ArrayList<>(matchedAffiliations) : new ArrayList<>();
    }

    public String getCoauthorSignals() {
        return coauthorSignals;
    }

    public void setCoauthorSignals(String coauthorSignals) {
        this.coauthorSignals = coauthorSignals;
    }

    public CandidateVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(CandidateVerdict verdict) {
        this.verdict = verdict;
    }

    public Double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public List<String> getReasons() {
        if (reasons == null) {
            reasons = new ArrayList<>();
        }
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = (reasons != null) ? new ArrayList<>(reasons) : new ArrayList<>();
    }

    public CandidateStatus getStatus() {
        return status;
    }

    public void setStatus(CandidateStatus status) {
        this.status = status;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PersonPublicationCandidate that = (PersonPublicationCandidate) o;
        return Objects.equals(personUuid, that.personUuid) && Objects.equals(doi, that.doi);
    }

    @Override
    public int hashCode() {
        return Objects.hash(personUuid, doi);
    }

    @Override
    public String toString() {
        return "PersonPublicationCandidate{" +
                "id='" + id + '\'' +
                ", personUuid='" + personUuid + '\'' +
                ", personName='" + personName + '\'' +
                ", departmentId='" + departmentId + '\'' +
                ", departmentName='" + departmentName + '\'' +
                ", doi='" + doi + '\'' +
                ", title='" + title + '\'' +
                ", year=" + year +
                ", venue='" + venue + '\'' +
                ", openAlexWorkId='" + openAlexWorkId + '\'' +
                ", affiliationStatus=" + affiliationStatus +
                ", verdict=" + verdict +
                ", confidenceScore=" + confidenceScore +
                ", status=" + status +
                ", reviewedBy='" + reviewedBy + '\'' +
                ", reviewedAt=" + reviewedAt +
                '}';
    }
}
