package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ResearchoutputsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "research-outputs"; }

    @Override
    protected String getCollectionName() { return "Researchoutputs"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("workflow.step", 1).append("managingOrganization.uuid", 1));
            col.createIndex(new org.bson.Document("contributors.person.uuid", 1));
            col.createIndex(new org.bson.Document("contributors.externalPerson.uuid", 1));
            col.createIndex(new org.bson.Document("journalAssociation.journal.uuid", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
