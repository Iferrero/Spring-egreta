package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ExternalorganizationsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "external-organizations"; }
    @Override
    protected String getCollectionName() { return "ExternalOrganizations"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("name.ca_ES", 1));
            col.createIndex(new org.bson.Document("name.es_ES", 1));
            col.createIndex(new org.bson.Document("name.en_GB", 1));
            col.createIndex(new org.bson.Document("type.term.ca_ES", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
