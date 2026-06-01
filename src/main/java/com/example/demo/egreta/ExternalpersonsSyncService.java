package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ExternalpersonsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "external-persons"; }
    @Override
    protected String getCollectionName() { return "ExternalPersons"; }
}
