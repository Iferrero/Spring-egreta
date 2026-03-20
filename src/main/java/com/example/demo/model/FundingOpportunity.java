package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

@Document(collection = "FundingOpportunities")
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundingOpportunity {

    @Id
    private String id;
    private String uuid;
    private Object title;
    private Object type;
    private Workflow workflow;

    public String getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public String getFullTitle() {
        String resolved = extractLocalizedValue(title);
        return resolved != null ? resolved : "Sin título";
    }

    public String getTypeName() {
        String resolved = extractTypeValue(type);
        if (resolved != null) {
            return resolved;
        }
        return "Desconocido";
    }

    public String getWorkflowStep() {
        return workflow != null ? workflow.getStep() : null;
    }

    private String extractTypeValue(Object rawType) {
        if (!(rawType instanceof Map<?, ?> typeMap)) {
            return extractLocalizedValue(rawType);
        }

        Object term = typeMap.get("term");
        String fromTerm = extractLocalizedValue(term);
        if (fromTerm != null) {
            return fromTerm;
        }

        return extractLocalizedValue(rawType);
    }

    private String extractLocalizedValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String str) {
            String clean = str.trim();
            return clean.isBlank() ? null : clean;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nested = extractLocalizedValue(item);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (value instanceof Map<?, ?> map) {
            String fromLocalizedTexts = extractFromLocalizedTextArray(map.get("text"));
            if (fromLocalizedTexts != null) {
                return fromLocalizedTexts;
            }

            String[] priorityKeys = {"value", "ca_ES", "es_ES", "en_GB", "text", "name", "label", "term"};
            for (String key : priorityKeys) {
                String nested = extractLocalizedValue(map.get(key));
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String extractFromLocalizedTextArray(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }

        String[] localePriority = {"ca_ES", "es_ES", "en_GB"};
        for (String locale : localePriority) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    continue;
                }

                Object localeValue = itemMap.get("locale");
                Object textValue = itemMap.get("value");
                if (!(localeValue instanceof String localeStr)) {
                    continue;
                }
                if (!locale.equals(localeStr)) {
                    continue;
                }

                String resolved = extractLocalizedValue(textValue);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                continue;
            }
            String resolved = extractLocalizedValue(itemMap.get("value"));
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
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
}
