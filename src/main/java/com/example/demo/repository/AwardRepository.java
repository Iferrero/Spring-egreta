package com.example.demo.repository;

import com.example.demo.model.Award;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface AwardRepository extends MongoRepository<Award, String> {

    @Query("{ 'workflow.step': 'validated' }")
    Page<Award> findValidated(Pageable pageable);

    @Query("""
        {
          'workflow.step': 'validated',
          '$or': [
            { 'title.ca_ES': { '$regex': ?0, '$options': 'i' } },
            { 'title.es_ES': { '$regex': ?0, '$options': 'i' } },
            { 'title.en_GB': { '$regex': ?0, '$options': 'i' } }
          ]
        }
        """)
    Page<Award> findByTitleValidated(String title, Pageable pageable);

    @Aggregation(pipeline = {
            "{ '$match': { 'workflow.step': 'validated' } }",
            "{ '$project': { '_id': 0, 'uuid': 1, 'title': 1, 'awardDate': 1, 'type': 1 } }",
            "{ '$sort': { 'awardDate': -1, 'uuid': 1 } }"
    })
    List<Document> getResumenConOrganismos();
}
