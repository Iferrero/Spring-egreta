package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class FundingoppportunitiesSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "funding-opportunities"; }
    @Override
    protected String getCollectionName() { return "FundingOpportunities"; }
}
