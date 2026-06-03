package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PersonsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "persons"; }

    @Override
    protected int getPageSize() { return 500; }

    
    @Override
    protected int getMaxBufferSizeMB() {
        return 20;
    }

    @Override
    protected String getCollectionName() { return "Persons"; }
}
