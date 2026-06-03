package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class FingerprintsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "fingerprints"; }
    @Override
    protected String getCollectionName() { return "Fingerprints"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            mongoTemplate.getCollection(getCollectionName()).createIndex(new org.bson.Document("contentFamily", 1));
            logger.info("[Fingerprints] Rebuilt index for contentFamily");
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding Fingerprints index: " + e.getMessage(), e);
        }
    }
}
