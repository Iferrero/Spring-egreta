package com.example.demo.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@Document(collection = "Persons") // Asegúrate que tu colección se llame así
@JsonIgnoreProperties(ignoreUnknown = true)
public class Persona {
    @Id
    private String id;
    @JsonProperty("uuid")
    private String uuid;
    private Name name;
    private String orcid;
    private List<StaffOrganizationAssociation> staffOrganizationAssociations;
    private EmploymentType employmentType;

    public EmploymentType getEmploymentType() { return employmentType; }
    public void setEmploymentType(EmploymentType employmentType) { this.employmentType = employmentType; }

    public static class EmploymentType {
    private LocalizedName term; // Reutiliza la clase LocalizedName que ya tienes
    public LocalizedName getTerm() { return term; }
    public void setTerm(LocalizedName term) { this.term = term; }
}

    public List<StaffOrganizationAssociation> getStaffOrganizationAssociations() { 
        return staffOrganizationAssociations; 
    }
    public void setStaffOrganizationAssociations(List<StaffOrganizationAssociation> staffOrganizationAssociations) { 
        this.staffOrganizationAssociations = staffOrganizationAssociations; 
    }
    /**
     * Verifica si la persona es activa.
     * 
     * Una persona es considerada activa si su fecha de fin de contrato es nula
     * o si es posterior a la fecha actual.
     * 
     * @return true si la persona es activa, false en caso contrario
     */
  
    // Métodos de conveniencia para el frontend
    public String getFullName() {
        if (name == null) return "Sin nombre";
        return name.getLastName() + ", " + name.getFirstName();
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getOrcid() { return orcid; }
    public void setOrcid(String orcid) { this.orcid = orcid; }
    public String getId() { return uuid; }
    public void setName(Name name) { this.name = name; }

    // Clase interna para el nombre
    public static class Name {
        private String firstName;
        private String lastName;
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    // --- CLASES PARA EL MAPEO ANIDADO ---
public static class StaffOrganizationAssociation {
        private StaffType staffType;
        private OrgDetail organization; // Cambiado el nombre para evitar conflictos
        private Period period;

        public StaffType getStaffType() { return staffType; }
        public void setStaffType(StaffType staffType) { this.staffType = staffType; }

        public OrgDetail getOrganization() { return organization; }
        public void setOrganization(OrgDetail organization) { this.organization = organization; }
        
        public Period getPeriod() { return period; }
        public void setPeriod(Period period) { this.period = period; }
    }

    public static class StaffType {
    private Map<String, String> term;
    public String getNombreEs() {
        return term != null ? term.getOrDefault("es_ES", term.getOrDefault("en_GB", "Sin categoría")) : "N/D";
    }
}

    public static class OrgDetail {
        private LocalizedName name;
        public LocalizedName getName() { return name; }
        public void setName(LocalizedName name) { this.name = name; }
    }

    public static class LocalizedName {
        // En Pure, el nombre suele venir como una lista de objetos {locale, value}
        private List<TextValue> text;
        
        public List<TextValue> getText() { return text; }
        public void setText(List<TextValue> text) { this.text = text; }

        // Método para extraer el nombre en español de la lista
        public String getNombre() {
            if (text == null || text.isEmpty()) return "Sin departamento";
            return text.stream()
                .filter(t -> "ca_ES".equals(t.getLocale()))
                .map(TextValue::getValue)
                .findFirst()
                .orElse(text.get(0).getValue()); // Si no hay español, devuelve el primero (ej. inglés)
        }
    }

    public static class TextValue {
        private String locale;
        private String value;
        public String getLocale() { return locale; }
        public void setLocale(String locale) { this.locale = locale; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
    public static class Period {
        private LocalDate endDate;
        public LocalDate getEndDate() { return endDate; }
        public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    }
}