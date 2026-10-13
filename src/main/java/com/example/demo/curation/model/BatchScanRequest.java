package com.example.demo.curation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Petición para iniciar un escaneo de conciliación por lotes en segundo plano.
 * Permite filtrar por departamento, facultad o especificar una lista explícita de UUIDs de investigadores.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchScanRequest {

    private String departmentId;
    private String facultyId;
    private List<String> personUuids = new ArrayList<>();
    private boolean strictOrcidOnly;

    public BatchScanRequest() {
    }

    public BatchScanRequest(String departmentId, String facultyId, List<String> personUuids, boolean strictOrcidOnly) {
        this.departmentId = departmentId;
        this.facultyId = facultyId;
        this.personUuids = personUuids != null ? new ArrayList<>(personUuids) : new ArrayList<>();
        this.strictOrcidOnly = strictOrcidOnly;
    }

    public BatchScanRequest(List<String> personUuids, boolean strictOrcidOnly) {
        this(null, null, personUuids, strictOrcidOnly);
    }

    public BatchScanRequest(String departmentId, boolean strictOrcidOnly) {
        this(departmentId, null, null, strictOrcidOnly);
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public String getFacultyId() {
        return facultyId;
    }

    public void setFacultyId(String facultyId) {
        this.facultyId = facultyId;
    }

    public List<String> getPersonUuids() {
        if (personUuids == null) {
            personUuids = new ArrayList<>();
        }
        return personUuids;
    }

    public void setPersonUuids(List<String> personUuids) {
        this.personUuids = personUuids != null ? new ArrayList<>(personUuids) : new ArrayList<>();
    }

    public boolean isStrictOrcidOnly() {
        return strictOrcidOnly;
    }

    public void setStrictOrcidOnly(boolean strictOrcidOnly) {
        this.strictOrcidOnly = strictOrcidOnly;
    }

    @Override
    public String toString() {
        return "BatchScanRequest{" +
                "departmentId='" + departmentId + '\'' +
                ", facultyId='" + facultyId + '\'' +
                ", personUuids=" + personUuids +
                ", strictOrcidOnly=" + strictOrcidOnly +
                '}';
    }
}
