package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PublishersSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "publishers"; }
    @Override
    protected String getCollectionName() { return "Publishers"; }
}
