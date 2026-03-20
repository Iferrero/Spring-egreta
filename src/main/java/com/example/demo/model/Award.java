package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "Awards")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Award {

    @Id
    private String id;
    private String uuid;
    private Map<String, String> title;
    private Workflow workflow;
    private List<Funding> fundings;
    private List<AwardHolder> awardHolders;
    private Instant awardDate;
    private AwardType type;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public Map<String, String> getTitle() {
        return title;
    }

    public void setTitle(Map<String, String> title) {
        this.title = title;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
    }

    public List<Funding> getFundings() {
        return fundings;
    }

    public void setFundings(List<Funding> fundings) {
        this.fundings = fundings;
    }

    public List<AwardHolder> getAwardHolders() {
        return awardHolders;
    }

    public void setAwardHolders(List<AwardHolder> awardHolders) {
        this.awardHolders = awardHolders;
    }

    public Instant getAwardDate() {
        return awardDate;
    }

    public void setAwardDate(Instant awardDate) {
        this.awardDate = awardDate;
    }

    public AwardType getType() {
        return type;
    }

    public void setType(AwardType type) {
        this.type = type;
    }

    public String getTituloMostrar() {
        if (title == null || title.isEmpty()) {
            return "Sin título";
        }
        if (title.get("es_ES") != null && !title.get("es_ES").isBlank()) {
            return title.get("es_ES");
        }
        if (title.get("ca_ES") != null && !title.get("ca_ES").isBlank()) {
            return title.get("ca_ES");
        }
        if (title.get("en_GB") != null && !title.get("en_GB").isBlank()) {
            return title.get("en_GB");
        }
        return "Sin título";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Workflow {
        private String step;

        public String getStep() {
            return step;
        }

        public void setStep(String step) {
            this.step = step;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwardType {
        private Map<String, String> term;

        public Map<String, String> getTerm() {
            return term;
        }

        public void setTerm(Map<String, String> term) {
            this.term = term;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Funding {
        private Funder funder;
        private AwardAmount awardedAmount;
        private List<FundingCollaborator> fundingCollaborators;

        public Funder getFunder() {
            return funder;
        }

        public void setFunder(Funder funder) {
            this.funder = funder;
        }

        public AwardAmount getAwardedAmount() {
            return awardedAmount;
        }

        public void setAwardedAmount(AwardAmount awardedAmount) {
            this.awardedAmount = awardedAmount;
        }

        public List<FundingCollaborator> getFundingCollaborators() {
            return fundingCollaborators;
        }

        public void setFundingCollaborators(List<FundingCollaborator> fundingCollaborators) {
            this.fundingCollaborators = fundingCollaborators;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Funder {
        private String uuid;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwardAmount {
        private String value;
        private String currency;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FundingCollaborator {
        private Collaborator collaborator;
        private InstitutionalPart institutionalPart;

        public Collaborator getCollaborator() {
            return collaborator;
        }

        public void setCollaborator(Collaborator collaborator) {
            this.collaborator = collaborator;
        }

        public InstitutionalPart getInstitutionalPart() {
            return institutionalPart;
        }

        public void setInstitutionalPart(InstitutionalPart institutionalPart) {
            this.institutionalPart = institutionalPart;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Collaborator {
        private String uuid;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstitutionalPart {
        private Object value;
        private String currency;

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public Double getValueAsDouble() {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AwardHolder {
        private HolderName name;
        private HolderPerson person;
        private HolderRole role;

        public HolderName getName() {
            return name;
        }

        public void setName(HolderName name) {
            this.name = name;
        }

        public HolderPerson getPerson() {
            return person;
        }

        public void setPerson(HolderPerson person) {
            this.person = person;
        }

        public HolderRole getRole() {
            return role;
        }

        public void setRole(HolderRole role) {
            this.role = role;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HolderName {
        private String firstName;
        private String lastName;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HolderPerson {
        private String uuid;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HolderRole {
        private Map<String, String> term;

        public Map<String, String> getTerm() {
            return term;
        }

        public void setTerm(Map<String, String> term) {
            this.term = term;
        }
    }
}
