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

    // Puedes sobreescribir normalize o postProcess si es necesario
}
