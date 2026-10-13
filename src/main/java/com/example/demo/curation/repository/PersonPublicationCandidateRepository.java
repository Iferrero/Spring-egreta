package com.example.demo.curation.repository;

import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.PersonPublicationCandidate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonPublicationCandidateRepository extends MongoRepository<PersonPublicationCandidate, String> {

    Optional<PersonPublicationCandidate> findByPersonUuidAndDoi(String personUuid, String doi);

    List<PersonPublicationCandidate> findByPersonUuid(String personUuid);

    List<PersonPublicationCandidate> findByPersonUuidAndStatus(String personUuid, CandidateStatus status);

    List<PersonPublicationCandidate> findByPersonUuidAndVerdict(String personUuid, CandidateVerdict verdict);

    List<PersonPublicationCandidate> findByPersonUuidAndStatusAndVerdict(String personUuid, CandidateStatus status, CandidateVerdict verdict);

    List<PersonPublicationCandidate> findByDepartmentIdAndStatus(String departmentId, CandidateStatus status);

    List<PersonPublicationCandidate> findByDepartmentId(String departmentId);

    List<PersonPublicationCandidate> findByStatus(CandidateStatus status);
}
