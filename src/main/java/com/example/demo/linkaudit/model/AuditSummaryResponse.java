package com.example.demo.linkaudit.model;

import java.time.Instant;
import java.util.List;

public record AuditSummaryResponse(
        Instant generatedAt,
        long collectionsAudited,
        long totalLinksChecked,
        long totalBroken,
        long totalBlocked,
        Instant lastAuditedAt,
        AuditJobState job,
        List<CollectionAuditSummary> collections
) {
}
