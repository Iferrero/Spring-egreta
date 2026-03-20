package com.example.demo.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.example.demo.model.Publicacion;

import java.util.List;

public interface PublicacionRepository extends MongoRepository<Publicacion, String> {
    // Buscar por año
    Page<Publicacion> findBySubmissionYear(Integer year, Pageable pageable);
    
    // Buscar por título (usando regex para que sea flexible)
    List<Publicacion> findByTitleValueRegex(String title, Pageable pageable);

    // Busca en el título ignorando mayúsculas/minúsculas con paginación
    @Query("{ 'title.value': { $regex: ?0, $options: 'i' } }")
    Page<Publicacion> findByTitleValueContainingIgnoreCase(String title, Pageable pageable);
    
}