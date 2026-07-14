package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class JournalsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "journals"; }
    @Override
    protected String getCollectionName() { return "Journals"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("issns.issn", 1));
            col.createIndex(new org.bson.Document("additionalSearchableIssns.issn", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
