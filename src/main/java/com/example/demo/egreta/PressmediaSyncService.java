package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PressmediaSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "pressmedia"; }
    @Override
    protected String getCollectionName() { return "Pressmedia"; }
}
