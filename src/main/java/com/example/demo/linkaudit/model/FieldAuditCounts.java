package com.example.demo.linkaudit.model;

public record FieldAuditCounts(
        long totalLinks,
        long okCount,
        long brokenCount,
        long blockedCount
) {
}
