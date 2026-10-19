package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ClassificationschemesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "classification-schemes"; }
    @Override
    protected String getCollectionName() { return "Classificationschemes"; }
}
