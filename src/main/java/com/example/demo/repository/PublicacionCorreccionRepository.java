package com.example.demo.repository;

import com.example.demo.model.PublicacionCorreccion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PublicacionCorreccionRepository extends MongoRepository<PublicacionCorreccion, String> {
}
