package com.example.demo.repository;

import com.example.demo.model.Jcr;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface JcrRepository extends MongoRepository<Jcr, String> {

    Optional<Jcr> findByIssn(String issn);

    Optional<Jcr> findByEIssn(String eIssn);

    List<Jcr> findByPreviousIssnContains(String previousIssn);

    @Query("{ $or: [ { 'issn': ?0 }, { 'eIssn': ?0 }, { 'previousIssn': ?0 } ] }")
    List<Jcr> findByAnyIssn(String issn);
}