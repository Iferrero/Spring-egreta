package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PersonsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "persons"; }

    @Override
    protected int getPageSize() { return 500; }

    @Override
    protected int getMaxBufferSizeMB() {
        return 20;
    }

    @Override
    protected String getCollectionName() { return "Persons"; }

    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("name.lastName", 1));
            col.createIndex(new org.bson.Document("staffOrganizationAssociations.organization.uuid", 1));
            col.createIndex(new org.bson.Document()
                .append("staffOrganizationAssociations.organization.uuid", 1)
                .append("staffOrganizationAssociations.period.endDate", 1)
                .append("staffOrganizationAssociations.period.startDate", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
