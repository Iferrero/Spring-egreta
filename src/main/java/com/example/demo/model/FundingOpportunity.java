package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.util.List;
import java.util.Map;

@Document(collection = "FundingOpportunities")
@CompoundIndexes({
    @CompoundIndex(name = "title_ca_ES", def = "{'title.ca_ES': 1}"),
    @CompoundIndex(name = "title_value", def = "{'title.value': 1}")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundingOpportunity {

    @Id
    private String id;

    @Indexed(unique = true)
    private String uuid;
    private Object title;
    private Object type;
    private Workflow workflow;
    private String openingDate;
    private String deadline;
    private Object visibility;

    public String getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public String getOpeningDate() {
        return openingDate;
    }

    public String getDeadline() {
        return deadline;
    }

    public Object getVisibility() {
        return visibility;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public void setTitle(Object title) {
        this.title = title;
    }

    public void setType(Object type) {
        this.type = type;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
    }

    public void setOpeningDate(String openingDate) {
        this.openingDate = openingDate;
    }

    public void setDeadline(String deadline) {
        this.deadline = deadline;
    }

    public void setVisibility(Object visibility) {
        this.visibility = visibility;
    }

    public String getVisibilityKey() {
        if (visibility instanceof Map<?, ?> vMap) {
            Object key = vMap.get("key");
            if (key != null) return String.valueOf(key);
        }
        return null;
    }

    public String getVisibilityDescription() {
        if (visibility instanceof Map<?, ?> vMap) {
            Object desc = vMap.get("description");
            String resolved = extractLocalizedValue(desc);
            if (resolved != null) return resolved;
        }
        return null;
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
        if (workflow != null && workflow.getStep() != null) {
            return workflow.getStep();
        }
        String vis = getVisibilityDescription();
        if (vis != null) return vis;
        return getVisibilityKey();
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
