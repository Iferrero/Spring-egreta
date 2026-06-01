package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class EventsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "events"; }
    @Override
    protected String getCollectionName() { return "Events"; }
}
