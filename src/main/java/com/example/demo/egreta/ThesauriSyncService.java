package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ThesauriSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "thesauri"; }
    @Override
    protected String getCollectionName() { return "Thesauri"; }
}
