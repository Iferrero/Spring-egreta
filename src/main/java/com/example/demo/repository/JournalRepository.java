package com.example.demo.repository;

import com.example.demo.model.Journal;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JournalRepository extends MongoRepository<Journal, String> {

    Optional<Journal> findByUuid(String uuid);

    Optional<Journal> findByPureId(Integer pureId);

    List<Journal> findByUuidIn(Collection<String> uuids);

    @Query("{ $or: [ { 'issns.issn': ?0 }, { 'additionalSearchableIssns.issn': ?0 } ] }")
    List<Journal> findByAnyIssn(String issn);
}