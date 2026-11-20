package com.example.demo.linkaudit.model;

import java.time.Instant;

public record AuditJobState(
        String jobId,
        AuditJobStatus status,
        String scope,
        Instant startedAt,
        Instant finishedAt,
        String currentCollection,
        int collectionsTotal,
        int collectionsCompleted,
        long currentCollectionUniqueUrls,
        long currentCollectionCheckedUrls,
        long totalUrlsChecked,
        long brokenFound,
        long blockedFound,
        String lastError
) {
}
