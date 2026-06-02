package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ConceptsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "concepts"; }
    @Override
    protected String getCollectionName() { return "Concepts"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            mongoTemplate.getCollection(getCollectionName()).createIndex(new org.bson.Document("name.text.value", "text"));
            logger.info("[Concepts] Rebuilt text index for name.text.value");
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding Concepts text index: " + e.getMessage(), e);
        }
    }
}
