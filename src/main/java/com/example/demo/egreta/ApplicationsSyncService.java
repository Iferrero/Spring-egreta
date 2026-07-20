package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ApplicationsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() {
        return "applications";
    }

    @Override
    protected String getCollectionName() {
        return "Applications";
    }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("workflow.step", 1).append("managingOrganisation.uuid", 1));
            col.createIndex(new org.bson.Document("applicants.person.uuid", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
