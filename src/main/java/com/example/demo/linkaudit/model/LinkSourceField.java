package com.example.demo.linkaudit.model;

public enum LinkSourceField {
    DOCUMENTS("documents"),
    LINKS("links"),
    ELECTRONIC_VERSIONS("electronicVersions");

    private final String fieldName;

    LinkSourceField(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    /**
     * Resolves a {@link LinkSourceField} from its MongoDB field name or enum name.
     */
    public static LinkSourceField fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        for (LinkSourceField field : values()) {
            if (field.fieldName.equalsIgnoreCase(trimmed) || field.name().equalsIgnoreCase(trimmed)) {
                return field;
            }
        }
        throw new IllegalArgumentException("Unknown LinkSourceField: " + value);
    }
}
