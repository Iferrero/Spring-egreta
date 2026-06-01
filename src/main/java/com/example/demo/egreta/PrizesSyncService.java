package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class PrizesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "prizes"; }
    @Override
    protected String getCollectionName() { return "Prizes"; }
}
