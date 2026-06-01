package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ResearchoutputsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "research-outputs"; }
    @Override
    protected String getCollectionName() { return "Researchoutputs"; }
}
