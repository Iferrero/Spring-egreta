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
    private String uuid;
    private Title title;
    private AwardDate awardDate;
    private List<Contributor> contributors;
    private List<Supervisor> supervisors;
    private Workflow workflow;

    public String getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public String getFullTitle() {
        return title != null ? title.getValue() : "Sin título";
    }

    public Integer getYear() {
        return awardDate != null ? awardDate.getYear() : null;
    }

    public String getAuthorsNames() {
        if (contributors == null || contributors.isEmpty()) {
            return "Sin autor";
        }

        return contributors.stream()
            .map(Contributor::getDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.joining(" | "));
    }

    public String getDirectorsNames() {
        if (supervisors == null || supervisors.isEmpty()) {
            return "Sin director";
        }

        return supervisors.stream()
            .map(Supervisor::getDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.joining(" | "));
    }

    public String getWorkflowStep() {
        return workflow != null ? workflow.getStep() : null;
    }

    public static class Title {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class AwardDate {
        private Integer year;
        private Integer month;
        private Integer day;

        public Integer getYear() {
            return year;
        }

        public void setYear(Integer year) {
            this.year = year;
        }

        public Integer getMonth() {
            return month;
        }

        public void setMonth(Integer month) {
            this.month = month;
        }

        public Integer getDay() {
            return day;
        }

        public void setDay(Integer day) {
            this.day = day;
        }
    }

    public static class Workflow {
        private String step;

        public String getStep() {
            return step;
        }

        public void setStep(String step) {
            this.step = step;
        }
    }

    public static class Contributor {
        private PersonName name;
        private Role role;

        public PersonName getName() {
            return name;
        }

        public void setName(PersonName name) {
            this.name = name;
        }

        public Role getRole() {
            return role;
        }

        public void setRole(Role role) {
            this.role = role;
        }

        public String getDisplayName() {
            if (name == null) {
                return "";
            }
            return name.getLastName() + ", " + name.getFirstName();
        }
    }

    public static class Supervisor {
        private PersonName name;
        private Role role;

        public PersonName getName() {
            return name;
        }

        public void setName(PersonName name) {
            this.name = name;
        }

        public Role getRole() {
            return role;
        }

        public void setRole(Role role) {
            this.role = role;
        }

        public String getDisplayName() {
            if (name == null) {
                return "";
            }
            return name.getLastName() + ", " + name.getFirstName();
        }
    }

    public static class PersonName {
        private String firstName;
        private String lastName;

        public String getFirstName() {
            return firstName != null ? firstName : "";
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName != null ? lastName : "";
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
    }

    public static class Role {
        private Map<String, String> term;

        public Map<String, String> getTerm() {
            return term;
        }

        public void setTerm(Map<String, String> term) {
            this.term = term;
        }
    }
}
