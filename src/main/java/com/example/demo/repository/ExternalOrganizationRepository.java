package com.example.demo.repository;

import com.example.demo.model.ExternalOrganization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ExternalOrganizationRepository extends MongoRepository<ExternalOrganization, String> {

    @Query("{ '$or': [ { 'name.ca_ES': { $regex: ?0, $options: 'i' } }, { 'name.es_ES': { $regex: ?0, $options: 'i' } }, { 'name.en_GB': { $regex: ?0, $options: 'i' } } ] }")
    Page<ExternalOrganization> findByNameContaining(String name, Pageable pageable);
}
