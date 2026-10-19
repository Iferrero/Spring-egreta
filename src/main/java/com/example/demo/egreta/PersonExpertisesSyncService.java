package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PersonExpertisesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "person-expertises"; }
    @Override
    protected String getCollectionName() { return "PersonExpertises"; }
}
