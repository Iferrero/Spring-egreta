package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class StudentthesesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "student-theses"; }
    @Override
    protected String getCollectionName() { return "StudentTheses"; }
}
