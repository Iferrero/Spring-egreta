package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "ExternalOrganizationsDifferent")
@CompoundIndexes({
    @CompoundIndex(name = "org_pair_idx", def = "{'org1Uuid': 1, 'org2Uuid': 1}", unique = true)
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalOrganizationDifferent {

    @Id
    private String id; // Canonical key: "minUuid:maxUuid"

    @Indexed
    private String org1Uuid;

    @Indexed
    private String org2Uuid;

    private String org1Name;
    private String org2Name;

    private Date createdDate;

    public ExternalOrganizationDifferent() {
    }

    public ExternalOrganizationDifferent(String org1Uuid, String org2Uuid, String org1Name, String org2Name) {
        if (org1Uuid != null && org2Uuid != null && org1Uuid.compareTo(org2Uuid) > 0) {
            this.org1Uuid = org2Uuid;
            this.org2Uuid = org1Uuid;
            this.org1Name = org2Name;
            this.org2Name = org1Name;
        } else {
            this.org1Uuid = org1Uuid;
            this.org2Uuid = org2Uuid;
            this.org1Name = org1Name;
            this.org2Name = org2Name;
        }
        this.id = this.org1Uuid + ":" + this.org2Uuid;
        this.createdDate = new Date();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrg1Uuid() {
        return org1Uuid;
    }

    public void setOrg1Uuid(String org1Uuid) {
        this.org1Uuid = org1Uuid;
    }

    public String getOrg2Uuid() {
        return org2Uuid;
    }

    public void setOrg2Uuid(String org2Uuid) {
        this.org2Uuid = org2Uuid;
    }

    public String getOrg1Name() {
        return org1Name;
    }

    public void setOrg1Name(String org1Name) {
        this.org1Name = org1Name;
    }

    public String getOrg2Name() {
        return org2Name;
    }

    public void setOrg2Name(String org2Name) {
        this.org2Name = org2Name;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }
}
