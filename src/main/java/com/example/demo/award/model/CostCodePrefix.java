package com.example.demo.award.model;

import java.util.Locale;

/**
 * Supported cost code prefixes for financial grant filtering.
 */
public enum CostCodePrefix {
    PK("PK", "^PK"),
    EA("EA", "^EA"),
    PO("PO", "^PO"),
    ALL("ALL", "^(PK|EA|PO)");

    private final String prefix;
    private final String mongoRegex;

    CostCodePrefix(String prefix, String mongoRegex) {
        this.prefix = prefix;
        this.mongoRegex = mongoRegex;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getMongoRegex() {
        return mongoRegex;
    }

    public boolean matches(String costCode) {
        if (costCode == null || costCode.isBlank()) {
            return false;
        }
        String upper = costCode.trim().toUpperCase(Locale.ROOT);
        if (this == ALL) {
            return upper.startsWith("PK") || upper.startsWith("EA") || upper.startsWith("PO");
        }
        return upper.startsWith(this.prefix);
    }
}
