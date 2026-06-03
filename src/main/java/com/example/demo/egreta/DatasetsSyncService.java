package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class DatasetsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "data-sets"; }

    @Override
    protected int getPageSize() { return 250; }

    @Override
    protected int getMaxBufferSizeMB() { return 20; }

    @Override
    protected String getCollectionName() { return "Datasets"; }
}
