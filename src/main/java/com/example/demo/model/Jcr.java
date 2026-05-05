package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Document(collection = "Journals")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Jcr {

    @Id
    private String id;
    private String name;
    private String jcrTitle;
    private String isoTitle;
    private String issn;
    private List<String> previousIssn;
    private String eIssn;
    private Publisher publisher;
    private Integer frequency;
    private Integer firstIssueYear;
    private String language;
    private List<Category> categories;
    private List<JournalCitationReport> journalCitationReports;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJcrTitle() {
        return jcrTitle;
    }

    public void setJcrTitle(String jcrTitle) {
        this.jcrTitle = jcrTitle;
    }

    public String getIsoTitle() {
        return isoTitle;
    }

    public void setIsoTitle(String isoTitle) {
        this.isoTitle = isoTitle;
    }

    public String getIssn() {
        return issn;
    }

    public void setIssn(String issn) {
        this.issn = issn;
    }

    public List<String> getPreviousIssn() {
        return previousIssn;
    }

    public void setPreviousIssn(List<String> previousIssn) {
        this.previousIssn = previousIssn;
    }

    public String getEIssn() {
        return eIssn;
    }

    public void setEIssn(String eIssn) {
        this.eIssn = eIssn;
    }

    public Publisher getPublisher() {
        return publisher;
    }

    public void setPublisher(Publisher publisher) {
        this.publisher = publisher;
    }

    public Integer getFrequency() {
        return frequency;
    }

    public void setFrequency(Integer frequency) {
        this.frequency = frequency;
    }

    public Integer getFirstIssueYear() {
        return firstIssueYear;
    }

    public void setFirstIssueYear(Integer firstIssueYear) {
        this.firstIssueYear = firstIssueYear;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public List<Category> getCategories() {
        return categories;
    }

    public void setCategories(List<Category> categories) {
        this.categories = categories;
    }

    public List<JournalCitationReport> getJournalCitationReports() {
        return journalCitationReports;
    }

    public void setJournalCitationReports(List<JournalCitationReport> journalCitationReports) {
        this.journalCitationReports = journalCitationReports;
    }

    public String getBestIssnForJoin() {
        if (issn != null && !issn.isBlank()) {
            return issn;
        }
        return eIssn;
    }

    public boolean matchesIssn(String value) {
        String normalized = normalizeIssn(value);
        if (normalized == null) {
            return false;
        }

        if (normalized.equals(normalizeIssn(issn)) || normalized.equals(normalizeIssn(eIssn))) {
            return true;
        }

        for (String oldIssn : safeList(previousIssn)) {
            if (normalized.equals(normalizeIssn(oldIssn))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIssn(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static <T> List<T> safeList(List<T> input) {
        return input == null ? new ArrayList<>() : input;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Publisher {
        private String name;
        private String address;
        private String countryRegion;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getCountryRegion() {
            return countryRegion;
        }

        public void setCountryRegion(String countryRegion) {
            this.countryRegion = countryRegion;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Category {
        private String url;
        private String name;
        private String edition;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEdition() {
            return edition;
        }

        public void setEdition(String edition) {
            this.edition = edition;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JournalCitationReport {
        private Integer year;
        private String url;
        private Boolean suppressed;
        private JournalRef journal;
        private Metrics metrics;
        private Ranks ranks;
        private SourceData sourceData;
        private JournalProfile journalProfile;
        private JournalData journalData;

        public Integer getYear() {
            return year;
        }

        public void setYear(Integer year) {
            this.year = year;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Boolean getSuppressed() {
            return suppressed;
        }

        public void setSuppressed(Boolean suppressed) {
            this.suppressed = suppressed;
        }

        public JournalRef getJournal() {
            return journal;
        }

        public void setJournal(JournalRef journal) {
            this.journal = journal;
        }

        public Metrics getMetrics() {
            return metrics;
        }

        public void setMetrics(Metrics metrics) {
            this.metrics = metrics;
        }

        public Ranks getRanks() {
            return ranks;
        }

        public void setRanks(Ranks ranks) {
            this.ranks = ranks;
        }

        public SourceData getSourceData() {
            return sourceData;
        }

        public void setSourceData(SourceData sourceData) {
            this.sourceData = sourceData;
        }

        public JournalProfile getJournalProfile() {
            return journalProfile;
        }

        public void setJournalProfile(JournalProfile journalProfile) {
            this.journalProfile = journalProfile;
        }

        public JournalData getJournalData() {
            return journalData;
        }

        public void setJournalData(JournalData journalData) {
            this.journalData = journalData;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JournalRef {
        private String id;
        private String self;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSelf() {
            return self;
        }

        public void setSelf(String self) {
            this.self = self;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metrics {
        private Map<String, Object> impactMetrics;
        private Map<String, Object> influenceMetrics;
        private Map<String, Object> sourceMetrics;
        private Map<String, Object> citationDistribution;

        public Map<String, Object> getImpactMetrics() {
            return impactMetrics;
        }

        public void setImpactMetrics(Map<String, Object> impactMetrics) {
            this.impactMetrics = impactMetrics;
        }

        public Map<String, Object> getInfluenceMetrics() {
            return influenceMetrics;
        }

        public void setInfluenceMetrics(Map<String, Object> influenceMetrics) {
            this.influenceMetrics = influenceMetrics;
        }

        public Map<String, Object> getSourceMetrics() {
            return sourceMetrics;
        }

        public void setSourceMetrics(Map<String, Object> sourceMetrics) {
            this.sourceMetrics = sourceMetrics;
        }

        public Map<String, Object> getCitationDistribution() {
            return citationDistribution;
        }

        public void setCitationDistribution(Map<String, Object> citationDistribution) {
            this.citationDistribution = citationDistribution;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ranks {
        private List<RankItem> jif;
        private List<RankItem> articleInfluence;
        private List<RankItem> eigenFactorScore;
        private List<RankItem> immediacyIndex;
        private List<RankItem> jci;
        private List<RankItem> esiCitations;

        public List<RankItem> getJif() {
            return jif;
        }

        public void setJif(List<RankItem> jif) {
            this.jif = jif;
        }

        public List<RankItem> getArticleInfluence() {
            return articleInfluence;
        }

        public void setArticleInfluence(List<RankItem> articleInfluence) {
            this.articleInfluence = articleInfluence;
        }

        public List<RankItem> getEigenFactorScore() {
            return eigenFactorScore;
        }

        public void setEigenFactorScore(List<RankItem> eigenFactorScore) {
            this.eigenFactorScore = eigenFactorScore;
        }

        public List<RankItem> getImmediacyIndex() {
            return immediacyIndex;
        }

        public void setImmediacyIndex(List<RankItem> immediacyIndex) {
            this.immediacyIndex = immediacyIndex;
        }

        public List<RankItem> getJci() {
            return jci;
        }

        public void setJci(List<RankItem> jci) {
            this.jci = jci;
        }

        public List<RankItem> getEsiCitations() {
            return esiCitations;
        }

        public void setEsiCitations(List<RankItem> esiCitations) {
            this.esiCitations = esiCitations;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RankItem {
        private String category;
        private String edition;
        private String self;
        private String rank;
        private String quartile;
        private Double jifPercentile;
        private Double jciPercentile;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getEdition() {
            return edition;
        }

        public void setEdition(String edition) {
            this.edition = edition;
        }

        public String getSelf() {
            return self;
        }

        public void setSelf(String self) {
            this.self = self;
        }

        public String getRank() {
            return rank;
        }

        public void setRank(String rank) {
            this.rank = rank;
        }

        public String getQuartile() {
            return quartile;
        }

        public void setQuartile(String quartile) {
            this.quartile = quartile;
        }

        public Double getJifPercentile() {
            return jifPercentile;
        }

        public void setJifPercentile(Double jifPercentile) {
            this.jifPercentile = jifPercentile;
        }

        public Double getJciPercentile() {
            return jciPercentile;
        }

        public void setJciPercentile(Double jciPercentile) {
            this.jciPercentile = jciPercentile;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SourceData {
        private DataSummary articles;
        private DataSummary other;

        public DataSummary getArticles() {
            return articles;
        }

        public void setArticles(DataSummary articles) {
            this.articles = articles;
        }

        public DataSummary getOther() {
            return other;
        }

        public void setOther(DataSummary other) {
            this.other = other;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataSummary {
        private Integer count;
        private Integer references;

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }

        public Integer getReferences() {
            return references;
        }

        public void setReferences(Integer references) {
            this.references = references;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JournalProfile {
        private Integer startYear;
        private Integer endYear;
        private Map<String, Integer> citableItems;
        private Map<String, Integer> citations;
        private List<OccurrenceByCountry> occurrenceCountries;
        private List<OccurrenceByOrganization> occurrenceOrganizations;

        public Integer getStartYear() {
            return startYear;
        }

        public void setStartYear(Integer startYear) {
            this.startYear = startYear;
        }

        public Integer getEndYear() {
            return endYear;
        }

        public void setEndYear(Integer endYear) {
            this.endYear = endYear;
        }

        public Map<String, Integer> getCitableItems() {
            return citableItems;
        }

        public void setCitableItems(Map<String, Integer> citableItems) {
            this.citableItems = citableItems;
        }

        public Map<String, Integer> getCitations() {
            return citations;
        }

        public void setCitations(Map<String, Integer> citations) {
            this.citations = citations;
        }

        public List<OccurrenceByCountry> getOccurrenceCountries() {
            return occurrenceCountries;
        }

        public void setOccurrenceCountries(List<OccurrenceByCountry> occurrenceCountries) {
            this.occurrenceCountries = occurrenceCountries;
        }

        public List<OccurrenceByOrganization> getOccurrenceOrganizations() {
            return occurrenceOrganizations;
        }

        public void setOccurrenceOrganizations(List<OccurrenceByOrganization> occurrenceOrganizations) {
            this.occurrenceOrganizations = occurrenceOrganizations;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OccurrenceByCountry {
        private String countryRegion;
        private Integer occurrence;

        public String getCountryRegion() {
            return countryRegion;
        }

        public void setCountryRegion(String countryRegion) {
            this.countryRegion = countryRegion;
        }

        public Integer getOccurrence() {
            return occurrence;
        }

        public void setOccurrence(Integer occurrence) {
            this.occurrence = occurrence;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OccurrenceByOrganization {
        private String organization;
        private Integer occurrence;

        public String getOrganization() {
            return organization;
        }

        public void setOrganization(String organization) {
            this.organization = organization;
        }

        public Integer getOccurrence() {
            return occurrence;
        }

        public void setOccurrence(Integer occurrence) {
            this.occurrence = occurrence;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JournalData {
        private LinkCount cited;
        private LinkCount citing;

        public LinkCount getCited() {
            return cited;
        }

        public void setCited(LinkCount cited) {
            this.cited = cited;
        }

        public LinkCount getCiting() {
            return citing;
        }

        public void setCiting(LinkCount citing) {
            this.citing = citing;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LinkCount {
        private String url;
        private Integer count;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }
    }
}