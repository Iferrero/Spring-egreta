package com.example.demo.curation.model;

/**
 * The 5 types of automated batch reconciliation and validation jobs for external organizations.
 */
public enum ReconciliationType {
    COUNTRY("auto-apply-country", "logs/auto-apply-country.log"),
    TYPE("auto-apply-type", "logs/auto-apply-type.log"),
    FUNDING("auto-apply-funding", "logs/auto-apply-funding.log"),
    NAME("auto-apply-name", "logs/auto-apply-name.log"),
    VALIDATE("auto-validate", "logs/auto-validate.log");

    private final String key;
    private final String logFilePath;

    ReconciliationType(String key, String logFilePath) {
        this.key = key;
        this.logFilePath = logFilePath;
    }

    public String getKey() {
        return key;
    }

    public String getLogFilePath() {
        return logFilePath;
    }
}
