package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PersonsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "persons"; }
    @Override
    protected String getCollectionName() { return "Persons"; }
}
