package com.example.demo.repository;

import com.example.demo.model.OrgaAmbit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrgaAmbitRepository extends MongoRepository<OrgaAmbit, String> {

    Optional<OrgaAmbit> findByUuid(String uuid);

    Optional<OrgaAmbit> findByIdentificador(String identificador);

    @Query("{ 'ambit': ?0 }")
    List<OrgaAmbit> findByAmbit(String ambit);

    List<OrgaAmbit> findAllByOrderByAmbitAscOrgaAsc();
}
