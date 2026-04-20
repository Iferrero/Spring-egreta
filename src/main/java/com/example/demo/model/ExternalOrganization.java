package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

@Document(collection = "ExternalOrganizations")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalOrganization {

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

    private List<String> prettyUrlIdentifiers;

    /** Multilingual name: en_GB, es_ES, ca_ES */
    private Map<String, String> name;

    private UriTerm type;

    private List<UriTerm> natureTypes;

    private List<Identifier> identifiers;

    private Address address;

    private List<Link> links;

    private List<KeywordGroup> keywordGroups;

    private KeyValue visibility;

    private WorkflowStatus workflow;

    // -------------------------------------------------------------------------
    // Convenience helpers
    // -------------------------------------------------------------------------

    public String getDisplayName() {
        if (name == null) return null;
        return name.getOrDefault("ca_ES",
               name.getOrDefault("es_ES",
               name.getOrDefault("en_GB", null)));
    }

    public String getTypeName() {
        if (type == null || type.getTerm() == null) return null;
        Map<String, String> term = type.getTerm();
        return term.getOrDefault("ca_ES",
               term.getOrDefault("es_ES",
               term.getOrDefault("en_GB", null)));
    }

    public String getCountryName() {
        if (address == null || address.getCountry() == null
                || address.getCountry().getTerm() == null) return null;
        Map<String, String> term = address.getCountry().getTerm();
        return term.getOrDefault("ca_ES",
               term.getOrDefault("es_ES",
               term.getOrDefault("en_GB", null)));
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
    public static class Identifier {
        private String typeDiscriminator;
        private Long pureId;
        /** value for PrimaryId; id for ClassifiedId */
        private String value;
        private String id;
        private String idSource;
        private UriTerm type;

        public String getTypeDiscriminator() { return typeDiscriminator; }
        public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getIdSource() { return idSource; }
        public void setIdSource(String idSource) { this.idSource = idSource; }
        public UriTerm getType() { return type; }
        public void setType(UriTerm type) { this.type = type; }

        /** Returns the effective identifier value regardless of discriminator type. */
        public String getEffectiveValue() {
            return value != null ? value : id;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {
        private String address1;
        private String address2;
        private String postalCode;
        private String city;
        private UriTerm country;
        private UriTerm subdivision;
        private GeoLocation geoLocation;

        public String getAddress1() { return address1; }
        public void setAddress1(String address1) { this.address1 = address1; }
        public String getAddress2() { return address2; }
        public void setAddress2(String address2) { this.address2 = address2; }
        public String getPostalCode() { return postalCode; }
        public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public UriTerm getCountry() { return country; }
        public void setCountry(UriTerm country) { this.country = country; }
        public UriTerm getSubdivision() { return subdivision; }
        public void setSubdivision(UriTerm subdivision) { this.subdivision = subdivision; }
        public GeoLocation getGeoLocation() { return geoLocation; }
        public void setGeoLocation(GeoLocation geoLocation) { this.geoLocation = geoLocation; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeoLocation {
        /** Stored as "lat,lon" string, e.g. "51.968,7.591" */
        private String point;

        public String getPoint() { return point; }
        public void setPoint(String point) { this.point = point; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {
        private Long pureId;
        private String url;

        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeywordGroup {
        private String typeDiscriminator;
        private Long pureId;
        private String logicalName;
        private Map<String, String> name;
        private List<UriTerm> classifications;

        public String getTypeDiscriminator() { return typeDiscriminator; }
        public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public String getLogicalName() { return logicalName; }
        public void setLogicalName(String logicalName) { this.logicalName = logicalName; }
        public Map<String, String> getName() { return name; }
        public void setName(Map<String, String> name) { this.name = name; }
        public List<UriTerm> getClassifications() { return classifications; }
        public void setClassifications(List<UriTerm> classifications) { this.classifications = classifications; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyValue {
        private String key;
        private Map<String, String> description;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Map<String, String> getDescription() { return description; }
        public void setDescription(Map<String, String> description) { this.description = description; }
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
    public List<String> getPrettyUrlIdentifiers() { return prettyUrlIdentifiers; }
    public void setPrettyUrlIdentifiers(List<String> prettyUrlIdentifiers) { this.prettyUrlIdentifiers = prettyUrlIdentifiers; }
    public Map<String, String> getName() { return name; }
    public void setName(Map<String, String> name) { this.name = name; }
    public UriTerm getType() { return type; }
    public void setType(UriTerm type) { this.type = type; }
    public List<UriTerm> getNatureTypes() { return natureTypes; }
    public void setNatureTypes(List<UriTerm> natureTypes) { this.natureTypes = natureTypes; }
    public List<Identifier> getIdentifiers() { return identifiers; }
    public void setIdentifiers(List<Identifier> identifiers) { this.identifiers = identifiers; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
    public List<Link> getLinks() { return links; }
    public void setLinks(List<Link> links) { this.links = links; }
    public List<KeywordGroup> getKeywordGroups() { return keywordGroups; }
    public void setKeywordGroups(List<KeywordGroup> keywordGroups) { this.keywordGroups = keywordGroups; }
    public KeyValue getVisibility() { return visibility; }
    public void setVisibility(KeyValue visibility) { this.visibility = visibility; }
    public WorkflowStatus getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowStatus workflow) { this.workflow = workflow; }
}
