package com.example.demo.curation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Data transfer object representing a scholarly publication retrieved from OpenAlex API.
 * Encapsulates identifiers, bibliometrics, authorship, affiliations, concepts, and topics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAlexWorkDto {

    private static final String[] DOI_PREFIXES = {
            "https://doi.org/",
            "http://doi.org/",
            "https://dx.doi.org/",
            "http://dx.doi.org/",
            "doi.org/",
            "dx.doi.org/",
            "doi:"
    };

    private String id;
    private String doi;
    private String title;
    private Integer publicationYear;
    private String venue;
    private List<AuthorDto> authors = new ArrayList<>();
    private List<String> concepts = new ArrayList<>();
    private List<String> topics = new ArrayList<>();

    public OpenAlexWorkDto() {
    }

    public OpenAlexWorkDto(String id, String doi, String title, Integer publicationYear, String venue) {
        this.id = id;
        this.doi = doi;
        this.title = title;
        this.publicationYear = publicationYear;
        this.venue = venue;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    /**
     * Returns the canonical normalized DOI without URI schemes or prefixes (e.g. "10.1000/182").
     */
    public String getNormalizedDoi() {
        if (doi == null || doi.isBlank()) {
            return null;
        }
        String clean = doi.trim().toLowerCase(Locale.ROOT);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : DOI_PREFIXES) {
                if (clean.startsWith(prefix)) {
                    clean = clean.substring(prefix.length()).trim();
                    stripped = true;
                }
            }
        }
        return clean.isEmpty() ? null : clean;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getPublicationYear() {
        return publicationYear;
    }

    public void setPublicationYear(Integer publicationYear) {
        this.publicationYear = publicationYear;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public String getJournal() {
        return venue;
    }

    public void setJournal(String journal) {
        this.venue = journal;
    }

    public List<AuthorDto> getAuthors() {
        return authors;
    }

    public void setAuthors(List<AuthorDto> authors) {
        this.authors = authors != null ? authors : new ArrayList<>();
    }

    public List<String> getConcepts() {
        return concepts;
    }

    public void setConcepts(List<String> concepts) {
        this.concepts = concepts != null ? concepts : new ArrayList<>();
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics != null ? topics : new ArrayList<>();
    }

    /**
     * Extracts all raw affiliation strings across all authors in the work.
     */
    public List<String> getAllRawAffiliations() {
        List<String> result = new ArrayList<>();
        if (authors != null) {
            for (AuthorDto author : authors) {
                if (author.getRawAffiliationStrings() != null) {
                    for (String aff : author.getRawAffiliationStrings()) {
                        if (aff != null && !aff.isBlank() && !result.contains(aff.trim())) {
                            result.add(aff.trim());
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Extracts all institution IDs across all authors in the work.
     */
    public List<String> getAllInstitutionIds() {
        List<String> result = new ArrayList<>();
        if (authors != null) {
            for (AuthorDto author : authors) {
                if (author.getInstitutionIds() != null) {
                    for (String instId : author.getInstitutionIds()) {
                        if (instId != null && !instId.isBlank() && !result.contains(instId.trim())) {
                            result.add(instId.trim());
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Extracts display names of all authors.
     */
    public List<String> getAuthorNames() {
        List<String> result = new ArrayList<>();
        if (authors != null) {
            for (AuthorDto author : authors) {
                if (author.getDisplayName() != null && !author.getDisplayName().isBlank()) {
                    result.add(author.getDisplayName().trim());
                }
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OpenAlexWorkDto that = (OpenAlexWorkDto) o;
        if (this.id != null || that.id != null) {
            return Objects.equals(this.id, that.id);
        }
        String thisDoi = getNormalizedDoi();
        String thatDoi = that.getNormalizedDoi();
        return thisDoi != null && Objects.equals(thisDoi, thatDoi);
    }

    @Override
    public int hashCode() {
        if (id != null) {
            return Objects.hash(id);
        }
        String normDoi = getNormalizedDoi();
        if (normDoi != null) {
            return Objects.hash(normDoi);
        }
        return 0;
    }

    @Override
    public String toString() {
        return "OpenAlexWorkDto{" +
                "id='" + id + '\'' +
                ", doi='" + doi + '\'' +
                ", title='" + title + '\'' +
                ", publicationYear=" + publicationYear +
                ", venue='" + venue + '\'' +
                ", authorsCount=" + (authors != null ? authors.size() : 0) +
                '}';
    }

    /**
     * DTO for individual authorship entries inside an OpenAlex work.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorDto {
        private String authorId;
        private String displayName;
        private String orcid;
        private List<String> rawAffiliationStrings = new ArrayList<>();
        private List<String> institutionIds = new ArrayList<>();

        public AuthorDto() {
        }

        public AuthorDto(String authorId, String displayName, String orcid) {
            this(authorId, displayName, orcid, new ArrayList<>(), new ArrayList<>());
        }

        public AuthorDto(String authorId, String displayName, String orcid,
                         List<String> rawAffiliationStrings, List<String> institutionIds) {
            this.authorId = authorId;
            this.displayName = displayName;
            this.orcid = orcid;
            this.rawAffiliationStrings = rawAffiliationStrings != null ? rawAffiliationStrings : new ArrayList<>();
            this.institutionIds = institutionIds != null ? institutionIds : new ArrayList<>();
        }

        public String getAuthorId() {
            return authorId;
        }

        public void setAuthorId(String authorId) {
            this.authorId = authorId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getOrcid() {
            return orcid;
        }

        public void setOrcid(String orcid) {
            this.orcid = orcid;
        }

        public List<String> getRawAffiliationStrings() {
            return rawAffiliationStrings;
        }

        public void setRawAffiliationStrings(List<String> rawAffiliationStrings) {
            this.rawAffiliationStrings = rawAffiliationStrings != null ? rawAffiliationStrings : new ArrayList<>();
        }

        public List<String> getInstitutionIds() {
            return institutionIds;
        }

        public void setInstitutionIds(List<String> institutionIds) {
            this.institutionIds = institutionIds != null ? institutionIds : new ArrayList<>();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AuthorDto authorDto = (AuthorDto) o;
            return Objects.equals(authorId, authorDto.authorId) && Objects.equals(orcid, authorDto.orcid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(authorId, orcid);
        }

        @Override
        public String toString() {
            return "AuthorDto{" +
                    "authorId='" + authorId + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", orcid='" + orcid + '\'' +
                    ", rawAffiliationStrings=" + rawAffiliationStrings +
                    ", institutionIds=" + institutionIds +
                    '}';
        }
    }
}
