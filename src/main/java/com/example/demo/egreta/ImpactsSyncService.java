package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ImpactsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "impacts"; }
    @Override
    protected String getCollectionName() { return "Impacts"; }
}
