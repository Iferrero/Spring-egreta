package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class AuthorcollaborationsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "author-collaborations"; }
    @Override
    protected String getCollectionName() { return "AuthorCollaborations"; }
}
