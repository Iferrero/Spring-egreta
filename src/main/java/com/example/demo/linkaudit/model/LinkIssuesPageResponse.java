package com.example.demo.linkaudit.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LinkIssuesPageResponse(
        String collection,
        String scanId,
        Instant scannedAt,
        int page,
        int size,
        long totalItems,
        int totalPages,
        Map<String, Long> countsByStatus,
        Map<String, Long> countsByField,
        Map<String, Long> countsByReason,
        List<LinkAuditIssue> items
) {
}
