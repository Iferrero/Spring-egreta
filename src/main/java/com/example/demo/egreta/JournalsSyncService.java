package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class JournalsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "journals"; }
    @Override
    protected String getCollectionName() { return "Journals"; }
}
