package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ActivitiesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() {
        return "activities";
    }

    @Override
    protected String getCollectionName() {
        return "Activities";
    }

    // Si necesitas normalización o post-procesado, sobreescribe aquí
}
