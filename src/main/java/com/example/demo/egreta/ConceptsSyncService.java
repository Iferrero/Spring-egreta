package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ConceptsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "concepts"; }
    @Override
    protected String getCollectionName() { return "Concepts"; }
}
