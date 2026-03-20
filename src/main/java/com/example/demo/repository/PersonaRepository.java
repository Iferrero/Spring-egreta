package com.example.demo.repository;

import com.example.demo.model.Persona;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.time.LocalDate;

public interface PersonaRepository extends MongoRepository<Persona, String> {

    // 1. Búsqueda normal por apellido (ignora mayúsculas/minúsculas)
    Page<Persona> findByNameLastNameContainingIgnoreCase(String lastName, Pageable pageable);

    // Consulta genérica que permite filtrar por apellido y/o departamento
    @Query("{ '$and': [" +
           "  { $or: [ { ?0: null }, { 'name.lastName': { '$regex': ?0, '$options': 'i' } } ] }," +
           "  { $or: [ { ?1: null }, { 'staffOrganizationAssociations.organization.uuid': ?1 } ] }" +
           "]}")
    Page<Persona> findByFilters(String apellido, String deptUuid, Pageable pageable);

    // Consulta para vigentes con filtros opcionales
    @Query("{ '$and': [" +
           "  { $or: [ { ?0: null }, { 'name.lastName': { '$regex': ?0, '$options': 'i' } } ] }," +
           "  { $or: [ { ?1: null }, { 'staffOrganizationAssociations.organization.uuid': ?1 } ] }," +
           "  { '$or': [ " +
           "    { 'staffOrganizationAssociations.period.endDate': null }, " +
           "    { 'staffOrganizationAssociations.period.endDate': { '$gte': ?2 } }" +
           "  ] }" +
           "]}")
    Page<Persona> findVigentesByFilters(String apellido, String deptUuid, LocalDate hoy, Pageable pageable);

    // Filtro combinado: Apellido + Vigencia
    @Query("{ '$and': [" +
           "  { 'name.lastName': { '$regex': ?0, '$options': 'i' } }," +
           "  { '$or': [ " +
           "    { 'staffOrganizationAssociations.period.endDate': null }, " +
           "    { 'staffOrganizationAssociations.period.endDate': { '$gte': ?1 } }" +
           "  ] }" +
           "]}")
    Page<Persona> findByApellidoAndVigentes(String apellido, LocalDate hoy, Pageable pageable);
}
