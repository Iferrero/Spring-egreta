package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Document(collection = "Journals")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Journal {

    @Id
    private String id;
    private Integer pureId;
    private String uuid;
    private String createdBy;
    private String createdDate;
    private String modifiedBy;
    private String modifiedDate;
    private List<String> previousUuids;
    private String version;
    private List<IssnEntry> issns;
    private List<SearchableIssn> additionalSearchableIssns;
    private LocalizedTerm country;
    private Boolean indexedInDoaj;
    private List<KeywordGroup> keywordGroups;
    private EntityRef publisher;
    private List<Identifier> identifiers;
    private List<JournalTitle> titles;
    private List<String> additionalSearchableTitles;
    private LocalizedTerm type;
    private Workflow workflow;
    private Map<String, Object> sherpaRomeoPolicy;
    private String systemName;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getPureId() {
        return pureId;
    }

    public void setPureId(Integer pureId) {
        this.pureId = pureId;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    public String getModifiedDate() {
        return modifiedDate;
    }

    public void setModifiedDate(String modifiedDate) {
        this.modifiedDate = modifiedDate;
    }

    public List<String> getPreviousUuids() {
        return previousUuids;
    }

    public void setPreviousUuids(List<String> previousUuids) {
        this.previousUuids = previousUuids;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<IssnEntry> getIssns() {
        return issns;
    }

    public void setIssns(List<IssnEntry> issns) {
        this.issns = issns;
    }

    public List<SearchableIssn> getAdditionalSearchableIssns() {
        return additionalSearchableIssns;
    }

    public void setAdditionalSearchableIssns(List<SearchableIssn> additionalSearchableIssns) {
        this.additionalSearchableIssns = additionalSearchableIssns;
    }

    public LocalizedTerm getCountry() {
        return country;
    }

    public void setCountry(LocalizedTerm country) {
        this.country = country;
    }

    public Boolean getIndexedInDoaj() {
        return indexedInDoaj;
    }

    public void setIndexedInDoaj(Boolean indexedInDoaj) {
        this.indexedInDoaj = indexedInDoaj;
    }

    public List<KeywordGroup> getKeywordGroups() {
        return keywordGroups;
    }

    public void setKeywordGroups(List<KeywordGroup> keywordGroups) {
        this.keywordGroups = keywordGroups;
    }

    public EntityRef getPublisher() {
        return publisher;
    }

    public void setPublisher(EntityRef publisher) {
        this.publisher = publisher;
    }

    public List<Identifier> getIdentifiers() {
        return identifiers;
    }

    public void setIdentifiers(List<Identifier> identifiers) {
        this.identifiers = identifiers;
    }

    public List<JournalTitle> getTitles() {
        return titles;
    }

    public void setTitles(List<JournalTitle> titles) {
        this.titles = titles;
    }

    public List<String> getAdditionalSearchableTitles() {
        return additionalSearchableTitles;
    }

    public void setAdditionalSearchableTitles(List<String> additionalSearchableTitles) {
        this.additionalSearchableTitles = additionalSearchableTitles;
    }

    public LocalizedTerm getType() {
        return type;
    }

    public void setType(LocalizedTerm type) {
        this.type = type;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
    }

    public Map<String, Object> getSherpaRomeoPolicy() {
        return sherpaRomeoPolicy;
    }

    public void setSherpaRomeoPolicy(Map<String, Object> sherpaRomeoPolicy) {
        this.sherpaRomeoPolicy = sherpaRomeoPolicy;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public String getMainTitle() {
        if (titles != null && !titles.isEmpty() && titles.get(0) != null) {
            return titles.get(0).getTitle();
        }
        return null;
    }

    public List<String> getAllIssnsForJoin() {
        Set<String> allIssns = new LinkedHashSet<>();

        for (IssnEntry item : safeList(issns)) {
            String normalized = normalizeIssn(item != null ? item.getIssn() : null);
            if (normalized != null) {
                allIssns.add(normalized);
            }
        }

        for (SearchableIssn item : safeList(additionalSearchableIssns)) {
            String normalized = normalizeIssn(item != null ? item.getIssn() : null);
            if (normalized != null) {
                allIssns.add(normalized);
            }
        }

        return new ArrayList<>(allIssns);
    }

    public boolean matchesIssn(String issnToMatch) {
        String normalized = normalizeIssn(issnToMatch);
        return normalized != null && getAllIssnsForJoin().contains(normalized);
    }

    private static String normalizeIssn(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssnEntry {
        private Integer pureId;
        private String issn;

        public Integer getPureId() {
            return pureId;
        }

        public void setPureId(Integer pureId) {
            this.pureId = pureId;
        }

        public String getIssn() {
            return issn;
        }

        public void setIssn(String issn) {
            this.issn = issn;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchableIssn {
        private String typeDiscriminator;
        private String issn;

        public String getTypeDiscriminator() {
            return typeDiscriminator;
        }

        public void setTypeDiscriminator(String typeDiscriminator) {
            this.typeDiscriminator = typeDiscriminator;
        }

        public String getIssn() {
            return issn;
        }

        public void setIssn(String issn) {
            this.issn = issn;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalizedTerm {
        private String uri;
        private Map<String, String> term;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public Map<String, String> getTerm() {
            return term;
        }

        public void setTerm(Map<String, String> term) {
            this.term = term;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeywordGroup {
        private String typeDiscriminator;
        private Integer pureId;
        private String logicalName;
        private Map<String, String> name;
        private List<Classification> classifications;

        public String getTypeDiscriminator() {
            return typeDiscriminator;
        }

        public void setTypeDiscriminator(String typeDiscriminator) {
            this.typeDiscriminator = typeDiscriminator;
        }

        public Integer getPureId() {
            return pureId;
        }

        public void setPureId(Integer pureId) {
            this.pureId = pureId;
        }

        public String getLogicalName() {
            return logicalName;
        }

        public void setLogicalName(String logicalName) {
            this.logicalName = logicalName;
        }

        public Map<String, String> getName() {
            return name;
        }

        public void setName(Map<String, String> name) {
            this.name = name;
        }

        public List<Classification> getClassifications() {
            return classifications;
        }

        public void setClassifications(List<Classification> classifications) {
            this.classifications = classifications;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Classification {
        private String uri;
        private Map<String, String> term;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public Map<String, String> getTerm() {
            return term;
        }

        public void setTerm(Map<String, String> term) {
            this.term = term;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntityRef {
        private String systemName;
        private String uuid;

        public String getSystemName() {
            return systemName;
        }

        public void setSystemName(String systemName) {
            this.systemName = systemName;
        }

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Identifier {
        private String typeDiscriminator;
        private Integer pureId;
        private String idSource;
        private String value;
        private String id;
        private LocalizedTerm type;

        public String getTypeDiscriminator() {
            return typeDiscriminator;
        }

        public void setTypeDiscriminator(String typeDiscriminator) {
            this.typeDiscriminator = typeDiscriminator;
        }

        public Integer getPureId() {
            return pureId;
        }

        public void setPureId(Integer pureId) {
            this.pureId = pureId;
        }

        public String getIdSource() {
            return idSource;
        }

        public void setIdSource(String idSource) {
            this.idSource = idSource;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public LocalizedTerm getType() {
            return type;
        }

        public void setType(LocalizedTerm type) {
            this.type = type;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JournalTitle {
        private Integer pureId;
        private String title;

        public Integer getPureId() {
            return pureId;
        }

        public void setPureId(Integer pureId) {
            this.pureId = pureId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Workflow {
        private String step;
        private Map<String, String> description;

        public String getStep() {
            return step;
        }

        public void setStep(String step) {
            this.step = step;
        }

        public Map<String, String> getDescription() {
            return description;
        }

        public void setDescription(Map<String, String> description) {
            this.description = description;
        }
    }
}