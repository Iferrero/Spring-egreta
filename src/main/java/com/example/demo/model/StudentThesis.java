package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Document(collection = "StudentTheses")
@JsonIgnoreProperties(ignoreUnknown = true)
public class StudentThesis {

    @Id
    private String id;

    private Long pureId;
    private String uuid;
    private String createdBy;
    private String createdDate;
    private String modifiedBy;
    private String modifiedDate;
    private String portalUrl;
    private String version;
    private String systemName;

    private List<String> prettyUrlIdentifiers;

    private Title title;
    private Title subTitle;

    private UriTerm language;
    private UriTerm type;

    private OrgRef managingOrganization;

    private List<Identifier> identifiers;
    private List<OrgRef> organizations;
    private List<OrgRef> supervisorOrganizations;

    private List<Link> links;
    private List<KeywordGroup> keywordGroups;
    private List<Contributor> contributors;
    private List<Supervisor> supervisors;
    private List<AwardingInstitution> awardingInstitutions;

    private Visibility visibility;
    private WorkflowStatus workflow;
    private AwardDate awardDate;

    private Map<String, String> translatedTitle;
    private Map<String, String> translatedSubTitle;

    private Map<String, Object> customDefinedFields;

    // -------------------------------------------------------------------------
    // Convenience helpers
    // -------------------------------------------------------------------------

    public String getFullTitle() {
        return title != null ? title.getValue() : "Sin título";
    }

    public Integer getYear() {
        return awardDate != null ? awardDate.getYear() : null;
    }

    public String getTypeName() {
        if (type == null || type.getTerm() == null) return null;
        Map<String, String> t = type.getTerm();
        return t.getOrDefault("ca_ES", t.getOrDefault("es_ES", t.getOrDefault("en_GB", null)));
    }

    public String getLanguageName() {
        if (language == null || language.getTerm() == null) return null;
        Map<String, String> t = language.getTerm();
        return t.getOrDefault("ca_ES", t.getOrDefault("es_ES", t.getOrDefault("en_GB", null)));
    }

    public String getWorkflowStep() {
        return workflow != null ? workflow.getStep() : null;
    }

    public String getAuthorsNames() {
        if (contributors == null || contributors.isEmpty()) return "Sin autor";
        return contributors.stream()
            .map(Contributor::getDisplayName)
            .filter(n -> n != null && !n.isBlank())
            .collect(Collectors.joining(" | "));
    }

    public String getDirectorsNames() {
        if (supervisors == null || supervisors.isEmpty()) return "Sin director";
        return supervisors.stream()
            .map(Supervisor::getDisplayName)
            .filter(n -> n != null && !n.isBlank())
            .collect(Collectors.joining(" | "));
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
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
    public String getPortalUrl() { return portalUrl; }
    public void setPortalUrl(String portalUrl) { this.portalUrl = portalUrl; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSystemName() { return systemName; }
    public void setSystemName(String systemName) { this.systemName = systemName; }
    public List<String> getPrettyUrlIdentifiers() { return prettyUrlIdentifiers; }
    public void setPrettyUrlIdentifiers(List<String> prettyUrlIdentifiers) { this.prettyUrlIdentifiers = prettyUrlIdentifiers; }
    public Title getTitle() { return title; }
    public void setTitle(Title title) { this.title = title; }
    public Title getSubTitle() { return subTitle; }
    public void setSubTitle(Title subTitle) { this.subTitle = subTitle; }
    public UriTerm getLanguage() { return language; }
    public void setLanguage(UriTerm language) { this.language = language; }
    public UriTerm getType() { return type; }
    public void setType(UriTerm type) { this.type = type; }
    public OrgRef getManagingOrganization() { return managingOrganization; }
    public void setManagingOrganization(OrgRef managingOrganization) { this.managingOrganization = managingOrganization; }
    public List<Identifier> getIdentifiers() { return identifiers; }
    public void setIdentifiers(List<Identifier> identifiers) { this.identifiers = identifiers; }
    public List<OrgRef> getOrganizations() { return organizations; }
    public void setOrganizations(List<OrgRef> organizations) { this.organizations = organizations; }
    public List<OrgRef> getSupervisorOrganizations() { return supervisorOrganizations; }
    public void setSupervisorOrganizations(List<OrgRef> supervisorOrganizations) { this.supervisorOrganizations = supervisorOrganizations; }
    public List<Link> getLinks() { return links; }
    public void setLinks(List<Link> links) { this.links = links; }
    public List<KeywordGroup> getKeywordGroups() { return keywordGroups; }
    public void setKeywordGroups(List<KeywordGroup> keywordGroups) { this.keywordGroups = keywordGroups; }
    public List<Contributor> getContributors() { return contributors; }
    public void setContributors(List<Contributor> contributors) { this.contributors = contributors; }
    public List<Supervisor> getSupervisors() { return supervisors; }
    public void setSupervisors(List<Supervisor> supervisors) { this.supervisors = supervisors; }
    public List<AwardingInstitution> getAwardingInstitutions() { return awardingInstitutions; }
    public void setAwardingInstitutions(List<AwardingInstitution> awardingInstitutions) { this.awardingInstitutions = awardingInstitutions; }
    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }
    public WorkflowStatus getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowStatus workflow) { this.workflow = workflow; }
    public AwardDate getAwardDate() { return awardDate; }
    public void setAwardDate(AwardDate awardDate) { this.awardDate = awardDate; }
    public Map<String, String> getTranslatedTitle() { return translatedTitle; }
    public void setTranslatedTitle(Map<String, String> translatedTitle) { this.translatedTitle = translatedTitle; }
    public Map<String, String> getTranslatedSubTitle() { return translatedSubTitle; }
    public void setTranslatedSubTitle(Map<String, String> translatedSubTitle) { this.translatedSubTitle = translatedSubTitle; }
    public Map<String, Object> getCustomDefinedFields() { return customDefinedFields; }
    public void setCustomDefinedFields(Map<String, Object> customDefinedFields) { this.customDefinedFields = customDefinedFields; }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /** Simple text wrapper used for title and subTitle */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Title {
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    /** uri + multilingual term map (en_GB / es_ES / ca_ES) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UriTerm {
        private String uri;
        private Map<String, String> term;
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public Map<String, String> getTerm() { return term; }
        public void setTerm(Map<String, String> term) { this.term = term; }
    }

    /** Lightweight reference to an Organization or Person by systemName + uuid */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrgRef {
        private String systemName;
        private String uuid;
        public String getSystemName() { return systemName; }
        public void setSystemName(String systemName) { this.systemName = systemName; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
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
    public static class Link {
        private Long pureId;
        private String url;
        private String alias;
        private UriTerm linkType;
        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public UriTerm getLinkType() { return linkType; }
        public void setLinkType(UriTerm linkType) { this.linkType = linkType; }
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
    public static class Visibility {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwardDate {
        private Integer year;
        private Integer month;
        private Integer day;
        public Integer getYear() { return year; }
        public void setYear(Integer year) { this.year = year; }
        public Integer getMonth() { return month; }
        public void setMonth(Integer month) { this.month = month; }
        public Integer getDay() { return day; }
        public void setDay(Integer day) { this.day = day; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersonName {
        private String firstName;
        private String lastName;
        public String getFirstName() { return firstName != null ? firstName : ""; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName != null ? lastName : ""; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contributor {
        private String typeDiscriminator;
        private Long pureId;
        private PersonName name;
        private UriTerm role;
        private OrgRef person;

        public String getTypeDiscriminator() { return typeDiscriminator; }
        public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public PersonName getName() { return name; }
        public void setName(PersonName name) { this.name = name; }
        public UriTerm getRole() { return role; }
        public void setRole(UriTerm role) { this.role = role; }
        public OrgRef getPerson() { return person; }
        public void setPerson(OrgRef person) { this.person = person; }

        public String getDisplayName() {
            if (name == null) return "";
            return name.getLastName() + ", " + name.getFirstName();
        }

        public String getRoleName() {
            if (role == null || role.getTerm() == null) return null;
            Map<String, String> t = role.getTerm();
            return t.getOrDefault("ca_ES", t.getOrDefault("es_ES", t.getOrDefault("en_GB", null)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Supervisor {
        private Long pureId;
        private PersonName name;
        private OrgRef person;
        private List<OrgRef> organizations;
        private UriTerm role;

        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public PersonName getName() { return name; }
        public void setName(PersonName name) { this.name = name; }
        public OrgRef getPerson() { return person; }
        public void setPerson(OrgRef person) { this.person = person; }
        public List<OrgRef> getOrganizations() { return organizations; }
        public void setOrganizations(List<OrgRef> organizations) { this.organizations = organizations; }
        public UriTerm getRole() { return role; }
        public void setRole(UriTerm role) { this.role = role; }

        public String getDisplayName() {
            if (name == null) return "";
            return name.getLastName() + ", " + name.getFirstName();
        }

        public String getRoleName() {
            if (role == null || role.getTerm() == null) return null;
            Map<String, String> t = role.getTerm();
            return t.getOrDefault("ca_ES", t.getOrDefault("es_ES", t.getOrDefault("en_GB", null)));
        }
    }

    /** awardingInstitutions[] — both internal org and external org references */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwardingInstitution {
        private OrgRef organizationRef;
        private OrgRef externalOrganizationRef;
        public OrgRef getOrganizationRef() { return organizationRef; }
        public void setOrganizationRef(OrgRef organizationRef) { this.organizationRef = organizationRef; }
        public OrgRef getExternalOrganizationRef() { return externalOrganizationRef; }
        public void setExternalOrganizationRef(OrgRef externalOrganizationRef) { this.externalOrganizationRef = externalOrganizationRef; }
    }
}
