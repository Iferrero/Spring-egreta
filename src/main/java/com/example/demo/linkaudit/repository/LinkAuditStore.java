package com.example.demo.linkaudit.repository;

import com.example.demo.linkaudit.model.CollectionAuditSummary;
import com.example.demo.linkaudit.model.LinkAuditIssue;
import com.example.demo.linkaudit.model.LinkIssuesPageResponse;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Persistence and query contract for collection link audit summaries and issues.
 */
public interface LinkAuditStore {

    void ensureIndexes();

    List<String> listBusinessCollections();

    boolean collectionExists(String collection);

    long estimatedDocumentCount(String collection);

    List<CollectionAuditSummary> findAllSummaries();

    Optional<CollectionAuditSummary> findSummary(String collection);

    void insertIssuesInBatches(List<LinkAuditIssue> issues);

    void upsertSummary(CollectionAuditSummary summary);

    long deleteIssuesOtherThanScan(String collection, String activeScanId);

    long deleteIssuesForScan(String collection, String scanId);

    LinkIssuesPageResponse findIssuesPage(
            String collection,
            String status,
            String field,
            String reason,
            String query,
            int page,
            int size
    );

    void streamIssuesForExport(
            String collection,
            String status,
            String field,
            String reason,
            String query,
            Consumer<LinkAuditIssue> consumer
    );
}
