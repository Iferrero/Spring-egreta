package com.example.demo.repository;

import com.example.demo.model.ExternalOrganizationDifferent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExternalOrganizationDifferentRepository extends MongoRepository<ExternalOrganizationDifferent, String> {
    List<ExternalOrganizationDifferent> findByOrg1UuidOrOrg2Uuid(String org1Uuid, String org2Uuid);
}
