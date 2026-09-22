package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class UsersSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() {
        return "users";
    }

    @Override
    protected String getCollectionName() {
        return "Users";
    }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("userName", 1));
            col.createIndex(new org.bson.Document("email", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            logger.warn("Error rebuilding custom indexes for '{}': {}", getCollectionName(), e.getMessage());
        }
    }
}
