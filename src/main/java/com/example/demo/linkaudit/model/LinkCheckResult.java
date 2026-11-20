package com.example.demo.linkaudit.model;

import java.time.Instant;

public record LinkCheckResult(
        String rawUrl,
        String normalizedUrl,
        String finalUrl,
        LinkStatus status,
        FailureReason reason,
        Integer httpStatus,
        String errorMessage,
        Instant checkedAt
) {
}
