package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

@Document(collection = "Activities")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Activity {

    @Id
    private String id;

    private String typeDiscriminator;
    private Long pureId;
    private String uuid;
    private String createdBy;
    private String createdDate;
    private String modifiedBy;
    private String modifiedDate;
    private String version;
    private String systemName;

    private List<String> prettyUrlIdentifiers;

    /** Internal UAB organizations linked to this activity */
    private List<OrgRef> organizations;

    private List<ActivityPerson> persons;

    private Period period;

    private OrgRef managingOrganization;

    /** Activity type (uri + multilingual term) */
    private UriTerm type;

    private UriTerm degreeOfRecognition;

    private List<Description> descriptions;

    private KeyValue visibility;

    private WorkflowStatus workflow;

    /** For Membership activities: the external organization the person belongs to */
    private MemberOf memberOf;

    private Map<String, Object> customDefinedFields;

    // -------------------------------------------------------------------------
    // Convenience helpers
    // -------------------------------------------------------------------------

    public String getTypeName() {
        return resolveLocalized(type != null ? type.getTerm() : null);
    }

    public String getDegreeOfRecognitionName() {
        return resolveLocalized(degreeOfRecognition != null ? degreeOfRecognition.getTerm() : null);
    }

    public String getFirstDescription() {
        if (descriptions == null || descriptions.isEmpty()) return null;
        return resolveLocalized(descriptions.get(0).getValue());
    }

    public String getExternalOrganizationUuid() {
        if (memberOf == null || memberOf.getExternalOrganization() == null) return null;
        return memberOf.getExternalOrganization().getUuid();
    }

    private static String resolveLocalized(Map<String, String> map) {
        if (map == null) return null;
        return map.getOrDefault("ca_ES",
               map.getOrDefault("es_ES",
               map.getOrDefault("en_GB", null)));
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /** Lightweight reference to an Organization or ExternalOrganization by systemName + uuid */
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
    public static class UriTerm {
        private String uri;
        private Map<String, String> term;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public Map<String, String> getTerm() { return term; }
        public void setTerm(Map<String, String> term) { this.term = term; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActivityPerson {
        private String typeDiscriminator;
        private Long pureId;
        private PersonName name;
        private UriTerm role;
        /** Reference to the Person record */
        private OrgRef person;
        private List<OrgRef> organizations;

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
        public List<OrgRef> getOrganizations() { return organizations; }
        public void setOrganizations(List<OrgRef> organizations) { this.organizations = organizations; }

        public String getRoleName() {
            return resolveLocalized(role != null ? role.getTerm() : null);
        }

        public String getFullName() {
            if (name == null) return null;
            return name.getLastName() + ", " + name.getFirstName();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersonName {
        private String firstName;
        private String lastName;

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Period {
        private YearDate startDate;
        private YearDate endDate;

        public YearDate getStartDate() { return startDate; }
        public void setStartDate(YearDate startDate) { this.startDate = startDate; }
        public YearDate getEndDate() { return endDate; }
        public void setEndDate(YearDate endDate) { this.endDate = endDate; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class YearDate {
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
    public static class Description {
        private Long pureId;
        /** Localized text: en_GB / es_ES / ca_ES */
        private Map<String, String> value;
        private UriTerm type;

        public Long getPureId() { return pureId; }
        public void setPureId(Long pureId) { this.pureId = pureId; }
        public Map<String, String> getValue() { return value; }
        public void setValue(Map<String, String> value) { this.value = value; }
        public UriTerm getType() { return type; }
        public void setType(UriTerm type) { this.type = type; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemberOf {
        private String typeDiscriminator;
        private OrgRef externalOrganization;

        public String getTypeDiscriminator() { return typeDiscriminator; }
        public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
        public OrgRef getExternalOrganization() { return externalOrganization; }
        public void setExternalOrganization(OrgRef externalOrganization) { this.externalOrganization = externalOrganization; }
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
    public String getTypeDiscriminator() { return typeDiscriminator; }
    public void setTypeDiscriminator(String typeDiscriminator) { this.typeDiscriminator = typeDiscriminator; }
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
    public List<OrgRef> getOrganizations() { return organizations; }
    public void setOrganizations(List<OrgRef> organizations) { this.organizations = organizations; }
    public List<ActivityPerson> getPersons() { return persons; }
    public void setPersons(List<ActivityPerson> persons) { this.persons = persons; }
    public Period getPeriod() { return period; }
    public void setPeriod(Period period) { this.period = period; }
    public OrgRef getManagingOrganization() { return managingOrganization; }
    public void setManagingOrganization(OrgRef managingOrganization) { this.managingOrganization = managingOrganization; }
    public UriTerm getType() { return type; }
    public void setType(UriTerm type) { this.type = type; }
    public UriTerm getDegreeOfRecognition() { return degreeOfRecognition; }
    public void setDegreeOfRecognition(UriTerm degreeOfRecognition) { this.degreeOfRecognition = degreeOfRecognition; }
    public List<Description> getDescriptions() { return descriptions; }
    public void setDescriptions(List<Description> descriptions) { this.descriptions = descriptions; }
    public KeyValue getVisibility() { return visibility; }
    public void setVisibility(KeyValue visibility) { this.visibility = visibility; }
    public WorkflowStatus getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowStatus workflow) { this.workflow = workflow; }
    public MemberOf getMemberOf() { return memberOf; }
    public void setMemberOf(MemberOf memberOf) { this.memberOf = memberOf; }
    public Map<String, Object> getCustomDefinedFields() { return customDefinedFields; }
    public void setCustomDefinedFields(Map<String, Object> customDefinedFields) { this.customDefinedFields = customDefinedFields; }
}
