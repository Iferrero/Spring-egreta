package com.example.demo.linkaudit.model;

import java.time.Instant;

public record LinkAuditIssue(
        String collection,
        String scanId,
        LinkStatus status,
        FailureReason reason,
        Integer httpStatus,
        String errorMessage,
        String url,
        String finalUrl,
        String sourceField,
        String subType,
        String linkLabel,
        String recordId,
        String recordUuid,
        Long recordPureId,
        String recordTitle,
        String recordType,
        String egretaUrl,
        Instant checkedAt
) {
}
