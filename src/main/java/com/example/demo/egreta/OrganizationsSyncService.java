package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class OrganizationsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() {
        return "organizations";
    }

    @Override
    protected String getCollectionName() {
        return "Organizations";
    }

    // Si necesitas normalización o post-procesado, sobreescribe aquí
}
