package com.example.demo.repository;

import com.example.demo.model.Organizacion;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface OrganizacionRepository extends MongoRepository<Organizacion, String> {
    Optional<Organizacion> findByUuid(String uuid);
}
