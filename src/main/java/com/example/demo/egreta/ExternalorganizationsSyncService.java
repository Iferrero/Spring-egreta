package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ExternalorganizationsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "external-organizations"; }
    @Override
    protected String getCollectionName() { return "ExternalOrganizations"; }
}
