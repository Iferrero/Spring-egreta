package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class StudentthesesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "student-theses"; }
    @Override
    protected String getCollectionName() { return "StudentTheses"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("workflow.step", 1).append("managingOrganization.uuid", 1));
            col.createIndex(new org.bson.Document("supervisors.person.uuid", 1));
            col.createIndex(new org.bson.Document("supervisors.externalPerson.uuid", 1));
            col.createIndex(new org.bson.Document("type.term.ca_ES", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
