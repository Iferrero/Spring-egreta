package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class ProjectsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "projects"; }
    @Override
    protected String getCollectionName() { return "Projects"; }
}
