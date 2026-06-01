package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class CoursesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "courses"; }
    @Override
    protected String getCollectionName() { return "Courses"; }
}
