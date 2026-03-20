package com.example.demo.repository;

import com.example.demo.model.StudentThesis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface StudentThesisRepository extends MongoRepository<StudentThesis, String> {

    @Query("{ '$or': [ { 'type.term.es_ES': { $regex: 'tesis doctoral', $options: 'i' } }, { 'type.term.ca_ES': { $regex: 'tesi doctoral', $options: 'i' } }, { 'type.term.en_GB': { $regex: 'doctoral thesis|phd thesis', $options: 'i' } } ] }")
    Page<StudentThesis> findDoctoral(Pageable pageable);

    @Query("{ '$and': [ { '$or': [ { 'type.term.es_ES': { $regex: 'tesis doctoral', $options: 'i' } }, { 'type.term.ca_ES': { $regex: 'tesi doctoral', $options: 'i' } }, { 'type.term.en_GB': { $regex: 'doctoral thesis|phd thesis', $options: 'i' } } ] }, { 'title.value': { $regex: ?0, $options: 'i' } } ] }")
    Page<StudentThesis> findDoctoralByTitleContainingIgnoreCase(String title, Pageable pageable);

    @Query("{ '$and': [ { '$or': [ { 'type.term.es_ES': { $regex: 'tesis doctoral', $options: 'i' } }, { 'type.term.ca_ES': { $regex: 'tesi doctoral', $options: 'i' } }, { 'type.term.en_GB': { $regex: 'doctoral thesis|phd thesis', $options: 'i' } } ] }, { 'awardDate.year': ?0 } ] }")
    Page<StudentThesis> findDoctoralByAwardDateYear(Integer year, Pageable pageable);
}
