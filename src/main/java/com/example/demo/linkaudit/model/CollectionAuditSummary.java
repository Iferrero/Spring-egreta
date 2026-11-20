package com.example.demo.linkaudit.model;

import java.time.Instant;
import java.util.Map;

public record CollectionAuditSummary(
        String collection,
        String scanId,
        Instant scannedAt,
        long durationMs,
        long recordsWithLinks,
        long totalLinks,
        long uniqueUrls,
        long okCount,
        long brokenCount,
        long blockedCount,
        Map<String, FieldAuditCounts> byField,
        String lastError
) {
}
