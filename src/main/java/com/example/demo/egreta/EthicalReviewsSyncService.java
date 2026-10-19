package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class EthicalReviewsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "ethical-reviews"; }
    @Override
    protected String getCollectionName() { return "EthicalReviews"; }
}
