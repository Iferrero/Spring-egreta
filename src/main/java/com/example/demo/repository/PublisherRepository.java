package com.example.demo.repository;

import com.example.demo.model.Publisher;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublisherRepository extends MongoRepository<Publisher, String> {

    Optional<Publisher> findByUuid(String uuid);

    Optional<Publisher> findByPureId(Integer pureId);

    List<Publisher> findByUuidIn(Collection<String> uuids);

    Optional<Publisher> findFirstByNameIgnoreCase(String name);
}
