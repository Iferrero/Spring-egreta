package com.example.demo.curation.model;

import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PersonPublicationCandidateRepositoryTest {

    @Autowired
    private PersonPublicationCandidateRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private String testPersonUuid;
    private String testDeptId;

    @BeforeEach
    public void setUp() {
        testPersonUuid = "test-person-" + UUID.randomUUID();
        testDeptId = "test-dept-" + UUID.randomUUID();

        // Asegurar que el indice compuesto unico este creado en MongoDB
        try {
            mongoTemplate.indexOps(PersonPublicationCandidate.class)
                    .ensureIndex(new CompoundIndexDefinition(new Document("personUuid", 1).append("doi", 1)).unique());
        } catch (Exception ignored) {
        }

        cleanup();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        if (testPersonUuid != null) {
            List<PersonPublicationCandidate> existing = repository.findByPersonUuid(testPersonUuid);
            for (PersonPublicationCandidate candidate : existing) {
                if (candidate.getId() != null) {
                    repository.deleteById(candidate.getId());
                }
            }
        }
    }

    @Test
    public void testPersistAndFindByPersonUuidAndDoi() {
        String doi = "10.1016/j.test." + UUID.randomUUID();

        PersonPublicationCandidate candidate = new PersonPublicationCandidate();
        candidate.setPersonUuid(testPersonUuid);
        candidate.setPersonName("Dr. Jane Doe");
        candidate.setDepartmentId(testDeptId);
        candidate.setDepartmentName("Departament de Bioquimica");
        candidate.setDoi(doi);
        candidate.setTitle("Advances in Molecular Biology");
        candidate.setYear(2024);
        candidate.setVenue("Nature Cell Biology");
        candidate.setOpenAlexWorkId("https://openalex.org/W9999999");
        candidate.setAffiliationStatus(AffiliationStatus.CONFIRMED_UAB);
        candidate.setMatchedAffiliations(List.of("Universitat Autonoma de Barcelona", "052gg0110"));
        candidate.setCoauthorSignals("Coautor habitual detectat: Joan Smith");
        candidate.setVerdict(CandidateVerdict.HIGH_CONFIDENCE_UAB);
        candidate.setConfidenceScore(85.0);
        candidate.setReasons(List.of("Afiliacio UAB confirmada", "Coautor habitual en EGRETA"));
        candidate.setStatus(CandidateStatus.PENDING);
        candidate.setReviewedBy("analyst_1");
        candidate.setReviewedAt(Instant.now());

        PersonPublicationCandidate saved = repository.save(candidate);
        assertNotNull(saved.getId(), "El ID generado de MongoDB no debe ser nulo");

        Optional<PersonPublicationCandidate> found = repository.findByPersonUuidAndDoi(testPersonUuid, doi);
        assertTrue(found.isPresent(), "El candidato debe encontrarse por personUuid y doi");
        assertEquals("Advances in Molecular Biology", found.get().getTitle());
        assertEquals(CandidateVerdict.HIGH_CONFIDENCE_UAB, found.get().getVerdict());
        assertEquals(AffiliationStatus.CONFIRMED_UAB, found.get().getAffiliationStatus());
        assertEquals(CandidateStatus.PENDING, found.get().getStatus());
        assertEquals(85.0, found.get().getConfidenceScore());
        assertEquals(2, found.get().getMatchedAffiliations().size());
        assertEquals(2, found.get().getReasons().size());
    }

    @Test
    public void testFindByPersonUuidAndStatus() {
        String doi1 = "10.1000/status1-" + UUID.randomUUID();
        String doi2 = "10.1000/status2-" + UUID.randomUUID();

        PersonPublicationCandidate c1 = new PersonPublicationCandidate();
        c1.setPersonUuid(testPersonUuid);
        c1.setDepartmentId(testDeptId);
        c1.setDoi(doi1);
        c1.setTitle("Work 1");
        c1.setStatus(CandidateStatus.PENDING);
        c1.setVerdict(CandidateVerdict.HIGH_CONFIDENCE_UAB);
        repository.save(c1);

        PersonPublicationCandidate c2 = new PersonPublicationCandidate();
        c2.setPersonUuid(testPersonUuid);
        c2.setDepartmentId(testDeptId);
        c2.setDoi(doi2);
        c2.setTitle("Work 2");
        c2.setStatus(CandidateStatus.APPROVED_IMPORT);
        c2.setVerdict(CandidateVerdict.HIGH_CONFIDENCE_UAB);
        repository.save(c2);

        List<PersonPublicationCandidate> pending = repository.findByPersonUuidAndStatus(testPersonUuid, CandidateStatus.PENDING);
        assertEquals(1, pending.size());
        assertEquals(doi1, pending.get(0).getDoi());

        List<PersonPublicationCandidate> approved = repository.findByPersonUuidAndStatus(testPersonUuid, CandidateStatus.APPROVED_IMPORT);
        assertEquals(1, approved.size());
        assertEquals(doi2, approved.get(0).getDoi());
    }

    @Test
    public void testFindByDepartmentIdAndStatus() {
        String doi = "10.1000/dept-" + UUID.randomUUID();

        PersonPublicationCandidate candidate = new PersonPublicationCandidate();
        candidate.setPersonUuid(testPersonUuid);
        candidate.setDepartmentId(testDeptId);
        candidate.setDoi(doi);
        candidate.setTitle("Department Work");
        candidate.setStatus(CandidateStatus.PENDING);
        candidate.setVerdict(CandidateVerdict.REQUIRES_REVIEW);
        repository.save(candidate);

        List<PersonPublicationCandidate> deptPending = repository.findByDepartmentIdAndStatus(testDeptId, CandidateStatus.PENDING);
        assertFalse(deptPending.isEmpty());
        assertTrue(deptPending.stream().anyMatch(c -> c.getDoi().equals(doi)));
    }

    @Test
    public void testPreventDuplicatesOnPersonUuidAndDoi() {
        String doi = "10.1000/duplicate-" + UUID.randomUUID();

        PersonPublicationCandidate candidate1 = new PersonPublicationCandidate();
        candidate1.setPersonUuid(testPersonUuid);
        candidate1.setDoi(doi);
        candidate1.setTitle("Initial work");
        candidate1.setStatus(CandidateStatus.PENDING);
        candidate1.setVerdict(CandidateVerdict.HIGH_CONFIDENCE_UAB);
        mongoTemplate.insert(candidate1);

        PersonPublicationCandidate candidate2 = new PersonPublicationCandidate();
        candidate2.setPersonUuid(testPersonUuid);
        candidate2.setDoi(doi);
        candidate2.setTitle("Duplicate work");
        candidate2.setStatus(CandidateStatus.PENDING);
        candidate2.setVerdict(CandidateVerdict.LIKELY_FALSE_POSITIVE);

        assertThrows(DuplicateKeyException.class, () -> {
            mongoTemplate.insert(candidate2);
        }, "No debe permitir insertar un duplicado con el mismo par (personUuid, doi)");
    }
}
