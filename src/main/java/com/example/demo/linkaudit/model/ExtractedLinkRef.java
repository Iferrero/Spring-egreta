package com.example.demo.linkaudit.model;

public record ExtractedLinkRef(
        String collection,
        String recordId,
        String recordUuid,
        Long recordPureId,
        String recordTitle,
        String recordType,
        String sourceField,
        String subType,
        String linkLabel,
        String rawUrl
) {
}
