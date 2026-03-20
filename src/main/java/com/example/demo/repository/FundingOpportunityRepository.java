package com.example.demo.repository;

import com.example.demo.model.FundingOpportunity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface FundingOpportunityRepository extends MongoRepository<FundingOpportunity, String> {

    @Query("{ '$or': [ { 'title.value': { $regex: ?0, $options: 'i' } }, { 'title.ca_ES': { $regex: ?0, $options: 'i' } }, { 'title.es_ES': { $regex: ?0, $options: 'i' } }, { 'title.en_GB': { $regex: ?0, $options: 'i' } } ] }")
    Page<FundingOpportunity> findByTitleValueContainingIgnoreCase(String title, Pageable pageable);
}
