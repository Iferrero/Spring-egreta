package com.example.demo.linkaudit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared constants and helper methods for the collection link audit module.
 */
public final class LinkAuditConstants {

    public static final String SUMMARY_COLLECTION = "CollectionLinkAuditSummary";
    public static final String ISSUES_COLLECTION = "CollectionLinkAuditIssues";
    public static final String UNSPECIFIED_LABEL = "Sense especificar";
    public static final String EGRETA_WORKSPACE_URL_PREFIX = "https://egreta.uab.cat/admin/workspace.xhtml?uuid=";

    private LinkAuditConstants() {
    }

    /**
     * Returns {@code true} if the given MongoDB collection name is an internal
     * audit or system collection that must be excluded from scans and stats.
     */
    public static boolean isInternalCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return true;
        }
        String trimmed = collectionName.trim();
        return SUMMARY_COLLECTION.equalsIgnoreCase(trimmed)
                || ISSUES_COLLECTION.equalsIgnoreCase(trimmed)
                || trimmed.toLowerCase(Locale.ROOT).startsWith("system.");
    }

    /**
     * Builds the direct Egreta admin workspace URL for a record UUID, or {@code null}
     * when no UUID is present.
     */
    public static String buildEgretaWorkspaceUrl(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        return EGRETA_WORKSPACE_URL_PREFIX + URLEncoder.encode(uuid, StandardCharsets.UTF_8);
    }
}
