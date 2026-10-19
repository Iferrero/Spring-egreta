package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class MilestonesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "milestones"; }
    @Override
    protected String getCollectionName() { return "Milestones"; }
}
