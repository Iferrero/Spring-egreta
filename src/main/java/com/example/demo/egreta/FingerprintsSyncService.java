package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class FingerprintsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "fingerprints"; }
    @Override
    protected String getCollectionName() { return "Fingerprints"; }
}
