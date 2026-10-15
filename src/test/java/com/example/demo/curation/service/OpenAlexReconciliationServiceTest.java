package com.example.demo.curation.service;

import com.example.demo.curation.adapter.OpenAlexWorksClient;
import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.CandidateVerdict;
import com.example.demo.curation.model.OpenAlexWorkDto;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.curation.util.UabAffiliationCatalog;
import com.example.demo.model.Persona;
import com.example.demo.model.Publicacion;
import com.example.demo.repository.PersonaRepository;
import com.example.demo.repository.PublicacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAlexReconciliationServiceTest {

    private PersonaRepository personaRepository;
    private PublicacionRepository publicacionRepository;
    private OpenAlexWorksClient openAlexWorksClient;
    private PublicationDeduplicationService deduplicationService;
    private AuthorAffiliationValidatorService affiliationValidatorService;
    private PersonPublicationCandidateRepository candidateRepository;

    private OpenAlexReconciliationService reconciliationService;

    private Persona persona;
    private Publicacion pub1;
    private Publicacion pub2;

    @BeforeEach
    void setUp() {
        personaRepository = mock(PersonaRepository.class);
        publicacionRepository = mock(PublicacionRepository.class);
        openAlexWorksClient = mock(OpenAlexWorksClient.class);
        candidateRepository = mock(PersonPublicationCandidateRepository.class);

        // Real deduplication & validator services with mock candidate repo
        deduplicationService = new PublicationDeduplicationService(candidateRepository);
        affiliationValidatorService = new AuthorAffiliationValidatorService(new UabAffiliationCatalog());

        reconciliationService = new OpenAlexReconciliationService(
                personaRepository,
                publicacionRepository,
                openAlexWorksClient,
                deduplicationService,
                affiliationValidatorService,
                candidateRepository
        );

        // Prepare EGRETA persona
        persona = new Persona();
        persona.setUuid("person-uab-100");
        Persona.Name name = new Persona.Name();
        name.setFirstName("Maria");
        name.setLastName("Garcia");
        persona.setName(name);
        persona.setOrcid("0000-0002-1825-0097");

        Persona.StaffOrganizationAssociation assoc = new Persona.StaffOrganizationAssociation();
        Persona.OrgDetail org = new Persona.OrgDetail();
        org.setUuid("dept-bio-uab");
        Persona.LocalizedName locName = new Persona.LocalizedName();
        Persona.TextValue tv = new Persona.TextValue();
        tv.setLocale("ca_ES");
        tv.setValue("Departament de Bioquímica");
        locName.setText(List.of(tv));
        org.setName(locName);
        assoc.setOrganization(org);
        persona.setStaffOrganizationAssociations(List.of(assoc));

        // Prepare 2 existing EGRETA publications
        pub1 = new Publicacion();
        pub1.setUuid("pub-egreta-001");
        Publicacion.Title t1 = new Publicacion.Title();
        t1.setValue("Previous Cellular Study 1");
        pub1.setTitle(t1);
        Publicacion.Identifier id1 = new Publicacion.Identifier();
        id1.setIdSource("doi");
        id1.setValue("10.1016/j.cell.2021.001");
        pub1.setIdentifiers(List.of(id1));

        Publicacion.Contributor c1Author = new Publicacion.Contributor();
        Publicacion.ContributorName c1AuthorName = new Publicacion.ContributorName();
        c1AuthorName.setLastName("Garcia");
        c1AuthorName.setFirstName("Maria");
        c1Author.setName(c1AuthorName);

        Publicacion.Contributor c1Coauthor = new Publicacion.Contributor();
        Publicacion.ContributorName c1CoName = new Publicacion.ContributorName();
        c1CoName.setLastName("Sole");
        c1CoName.setFirstName("Joan");
        c1Coauthor.setName(c1CoName);
        pub1.setContributors(List.of(c1Author, c1Coauthor));

        pub2 = new Publicacion();
        pub2.setUuid("pub-egreta-002");
        Publicacion.Title t2 = new Publicacion.Title();
        t2.setValue("Previous Cellular Study 2");
        pub2.setTitle(t2);
        Publicacion.Identifier id2 = new Publicacion.Identifier();
        id2.setIdSource("doi");
        id2.setValue("10.1016/j.cell.2021.002");
        pub2.setIdentifiers(List.of(id2));

        Publicacion.Contributor c2Author = new Publicacion.Contributor();
        Publicacion.ContributorName c2AuthorName = new Publicacion.ContributorName();
        c2AuthorName.setLastName("Garcia");
        c2AuthorName.setFirstName("Maria");
        c2Author.setName(c2AuthorName);

        Publicacion.Contributor c2Coauthor = new Publicacion.Contributor();
        Publicacion.ContributorName c2CoName = new Publicacion.ContributorName();
        c2CoName.setLastName("Perez");
        c2CoName.setFirstName("Anna");
        c2Coauthor.setName(c2CoName);
        pub2.setContributors(List.of(c2Author, c2Coauthor));
    }

    @Test
    @DisplayName("🧪 scanPerson: 2 previas en EGRETA, 3 de OpenAlex (1 deduplicada, 1 nueva UAB verde, 1 homónimo roja)")
    void testScanPerson_deduplicatesAndClassifiesGreenAndRed() {
        when(personaRepository.findByUuid("person-uab-100")).thenReturn(Optional.of(persona));
        when(publicacionRepository.findByContributorsPersonUuid("person-uab-100")).thenReturn(List.of(pub1, pub2));

        // 3 works from OpenAlex:
        // Work 1: Already in EGRETA (DOI matches pub1)
        OpenAlexWorkDto work1 = new OpenAlexWorkDto(
                "https://openalex.org/W1001",
                "10.1016/j.cell.2021.001",
                "Previous Cellular Study 1",
                2021,
                "Cell"
        );
        work1.getAuthors().add(new OpenAlexWorkDto.AuthorDto("A1", "Maria Garcia", "0000-0002-1825-0097"));

        // Work 2: New UAB paper with historical coauthor Joan Sole -> Green (HIGH_CONFIDENCE_UAB)
        OpenAlexWorkDto work2 = new OpenAlexWorkDto(
                "https://openalex.org/W1002",
                "10.1016/j.cell.2023.002",
                "Novel Biochemical Pathways in Cells",
                2023,
                "Cell Biochemistry"
        );
        work2.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A1",
                "Garcia, Maria",
                "0000-0002-1825-0097",
                List.of("Universitat Autònoma de Barcelona, Bellaterra, Spain"),
                List.of("https://openalex.org/I123044942")
        ));
        work2.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A2",
                "Sole, Joan",
                null,
                List.of("Universitat Autònoma de Barcelona"),
                List.of("https://openalex.org/I123044942")
        ));

        // Work 3: Foreign homonym from another university -> Red (LIKELY_FALSE_POSITIVE)
        OpenAlexWorkDto work3 = new OpenAlexWorkDto(
                "https://openalex.org/W1003",
                "10.1088/astronomy.2024",
                "Deep Galaxy Survey Observations",
                2024,
                "Astrophysics Journal"
        );
        work3.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A3",
                "Maria Garcia",
                "0000-0009-9999-9999",
                List.of("Universidad de Buenos Aires, Argentina"),
                List.of("https://openalex.org/I_FOREIGN")
        ));
        work3.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A4",
                "Carlos Astronomo",
                null,
                List.of("Universidad de Buenos Aires"),
                List.of("https://openalex.org/I_FOREIGN")
        ));

        when(openAlexWorksClient.fetchWorksByAuthor(eq("0000-0002-1825-0097"), anyString(), eq(false)))
                .thenReturn(List.of(work1, work2, work3));

        // Simulate save in repository returning the candidate
        when(candidateRepository.save(any(PersonPublicationCandidate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<PersonPublicationCandidate> results = reconciliationService.scanPerson("person-uab-100", false);

        assertNotNull(results);
        assertEquals(2, results.size(), "Should have exactly 2 candidates saved (work1 was deduplicated)");

        // Verify Work 1 was skipped and not saved
        verify(candidateRepository, never()).save(argThat(c -> "10.1016/j.cell.2021.001".equals(c.getDoi())));

        // Candidate for Work 2
        PersonPublicationCandidate candidateUab = results.stream()
                .filter(c -> "10.1016/j.cell.2023.002".equals(c.getDoi()))
                .findFirst()
                .orElseThrow();
        assertEquals("person-uab-100", candidateUab.getPersonUuid());
        assertEquals("Garcia, Maria", candidateUab.getPersonName());
        assertEquals(CandidateVerdict.HIGH_CONFIDENCE_UAB, candidateUab.getVerdict(), "Should be GREEN");
        assertEquals(CandidateStatus.PENDING, candidateUab.getStatus());
        assertTrue(candidateUab.getConfidenceScore() >= 80.0);

        // Candidate for Work 3
        PersonPublicationCandidate candidateHomonym = results.stream()
                .filter(c -> "10.1088/astronomy.2024".equals(c.getDoi()))
                .findFirst()
                .orElseThrow();
        assertEquals("person-uab-100", candidateHomonym.getPersonUuid());
        assertEquals(CandidateVerdict.LIKELY_FALSE_POSITIVE, candidateHomonym.getVerdict(), "Should be RED");
        assertEquals(CandidateStatus.PENDING, candidateHomonym.getStatus());
        assertTrue(candidateHomonym.getConfidenceScore() < 40.0);

        // Verify save was invoked twice in MongoDB
        verify(candidateRepository, times(2)).save(any(PersonPublicationCandidate.class));
    }

    @Test
    @DisplayName("⏩ scanPerson: descarta publicaciones que ya están registradas en PersonPublicationCandidateRepository")
    void testScanPerson_skipsWorksAlreadyInCandidateRepository() {
        when(personaRepository.findByUuid("person-uab-100")).thenReturn(Optional.of(persona));
        when(publicacionRepository.findByContributorsPersonUuid("person-uab-100")).thenReturn(Collections.emptyList());

        OpenAlexWorkDto work = new OpenAlexWorkDto(
                "https://openalex.org/W999",
                "10.1016/j.cell.already.exists",
                "Already Known Candidate Paper",
                2023,
                "Cell"
        );
        when(openAlexWorksClient.fetchWorksByAuthor(any(), any(), eq(false)))
                .thenReturn(List.of(work));

        // Candidate repository already contains this candidate
        PersonPublicationCandidate existingCandidate = new PersonPublicationCandidate();
        existingCandidate.setPersonUuid("person-uab-100");
        existingCandidate.setDoi("10.1016/j.cell.already.exists");
        existingCandidate.setStatus(CandidateStatus.PENDING);

        when(candidateRepository.findByPersonUuidAndDoi("person-uab-100", "10.1016/j.cell.already.exists"))
                .thenReturn(Optional.of(existingCandidate));

        List<PersonPublicationCandidate> results = reconciliationService.scanPerson("person-uab-100", false);

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(candidateRepository, never()).save(any(PersonPublicationCandidate.class));
    }

    @Test
    @DisplayName("🎯 scanPerson con strictOrcidOnly propaga el parámetro al cliente OpenAlex")
    void testScanPerson_strictOrcidOnlyFlag() {
        when(personaRepository.findByUuid("person-uab-100")).thenReturn(Optional.of(persona));
        when(publicacionRepository.findByContributorsPersonUuid("person-uab-100")).thenReturn(Collections.emptyList());
        when(openAlexWorksClient.fetchWorksByAuthor(eq("0000-0002-1825-0097"), any(), eq(true)))
                .thenReturn(Collections.emptyList());

        List<PersonPublicationCandidate> results = reconciliationService.scanPerson("person-uab-100", true);

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(openAlexWorksClient).fetchWorksByAuthor(eq("0000-0002-1825-0097"), any(), eq(true));
    }

    @Test
    @DisplayName("⚠️ scanPerson lanza excepción si la persona no existe")
    void testScanPerson_whenPersonaNotFound_throwsException() {
        when(personaRepository.findByUuid("unknown-uuid")).thenReturn(Optional.empty());
        when(personaRepository.findById("unknown-uuid")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                reconciliationService.scanPerson("unknown-uuid", false)
        );
    }

    @Test
    @DisplayName("📋 getCandidatesForPerson recupera candidatos filtrados por estado o todos")
    void testGetCandidatesForPerson() {
        PersonPublicationCandidate c1 = new PersonPublicationCandidate();
        c1.setPersonUuid("person-uab-100");
        c1.setStatus(CandidateStatus.PENDING);

        PersonPublicationCandidate c2 = new PersonPublicationCandidate();
        c2.setPersonUuid("person-uab-100");
        c2.setStatus(CandidateStatus.APPROVED_IMPORT);

        when(candidateRepository.findByPersonUuidAndStatus("person-uab-100", CandidateStatus.PENDING))
                .thenReturn(List.of(c1));
        when(candidateRepository.findByPersonUuid("person-uab-100"))
                .thenReturn(List.of(c1, c2));

        List<PersonPublicationCandidate> pendingOnly = reconciliationService.getCandidatesForPerson("person-uab-100", CandidateStatus.PENDING);
        assertEquals(1, pendingOnly.size());
        assertEquals(CandidateStatus.PENDING, pendingOnly.get(0).getStatus());

        List<PersonPublicationCandidate> allCandidates = reconciliationService.getCandidatesForPerson("person-uab-100", null);
        assertEquals(2, allCandidates.size());

        List<PersonPublicationCandidate> defaultAll = reconciliationService.getCandidatesForPerson("person-uab-100");
        assertEquals(2, defaultAll.size());
    }

    @Test
    @DisplayName("🆕 scanPerson: devuelve únicamente las nuevas candidatas de esta ejecución y no el histórico")
    void testScanPerson_returnsOnlyNewlySavedCandidates_notHistoricalRecords() {
        when(personaRepository.findByUuid("person-uab-100")).thenReturn(Optional.of(persona));
        when(publicacionRepository.findByContributorsPersonUuid("person-uab-100")).thenReturn(Collections.emptyList());

        // Historical record already exists in repository for another DOI
        PersonPublicationCandidate historicalCandidate = new PersonPublicationCandidate();
        historicalCandidate.setPersonUuid("person-uab-100");
        historicalCandidate.setDoi("10.1016/j.cell.historical.approved");
        historicalCandidate.setStatus(CandidateStatus.APPROVED_IMPORT);

        // OpenAlex returns 1 brand new work
        OpenAlexWorkDto newWork = new OpenAlexWorkDto(
                "https://openalex.org/W2023",
                "10.1016/j.cell.2023.new.work",
                "Brand New Paper",
                2023,
                "Cell"
        );
        newWork.getAuthors().add(new OpenAlexWorkDto.AuthorDto("A1", "Garcia, Maria", "0000-0002-1825-0097"));

        when(openAlexWorksClient.fetchWorksByAuthor(any(), any(), eq(false)))
                .thenReturn(List.of(newWork));

        when(candidateRepository.save(any(PersonPublicationCandidate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stub findByPersonUuid to return both historical and newly saved
        PersonPublicationCandidate newlyCreated = new PersonPublicationCandidate();
        newlyCreated.setPersonUuid("person-uab-100");
        newlyCreated.setDoi("10.1016/j.cell.2023.new.work");

        when(candidateRepository.findByPersonUuid("person-uab-100"))
                .thenReturn(List.of(historicalCandidate, newlyCreated));

        List<PersonPublicationCandidate> scanResult = reconciliationService.scanPerson("person-uab-100", false);

        // Crucial verification: scanResult contains ONLY the 1 newly evaluated candidate, avoiding double-counting
        assertEquals(1, scanResult.size());
        assertEquals("10.1016/j.cell.2023.new.work", scanResult.get(0).getDoi());

        // Historical query returns both
        List<PersonPublicationCandidate> allCandidates = reconciliationService.getCandidatesForPerson("person-uab-100");
        assertEquals(2, allCandidates.size());
    }

    @Test
    @DisplayName("🛡️ Multi-author paper: autor objetivo sin afiliación declarada NO hereda afiliación ajena de coautores")
    void testScanPerson_multiAuthorPaperWithEmptyTargetAffiliations_doesNotInheritForeignAffiliations() {
        when(personaRepository.findByUuid("person-uab-100")).thenReturn(Optional.of(persona));
        when(publicacionRepository.findByContributorsPersonUuid("person-uab-100")).thenReturn(Collections.emptyList());

        // Multi-author paper: Maria Garcia (author 1) has empty affiliations, Joan Coauthor (author 2) has Harvard University
        OpenAlexWorkDto work = new OpenAlexWorkDto(
                "https://openalex.org/W8888",
                "10.1016/j.nature.2023.collab",
                "Collaborative Multi-Center Study",
                2023,
                "Nature"
        );
        work.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A1",
                "Maria Garcia",
                "0000-0002-1825-0097",
                Collections.emptyList(),
                Collections.emptyList()
        ));
        work.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A2",
                "Foreign Coauthor",
                null,
                List.of("Harvard University, Cambridge, MA, USA"),
                List.of("https://openalex.org/I_HARVARD")
        ));

        when(openAlexWorksClient.fetchWorksByAuthor(any(), any(), eq(false)))
                .thenReturn(List.of(work));

        when(candidateRepository.save(any(PersonPublicationCandidate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<PersonPublicationCandidate> scanResult = reconciliationService.scanPerson("person-uab-100", false);

        assertEquals(1, scanResult.size());
        PersonPublicationCandidate candidate = scanResult.get(0);

        // Because multi-author paper did NOT pass foreign Harvard affiliation to our target author,
        // it should NOT have -50 penalty. With ORCID matched (+20) and no affiliation (0), it qualifies as REQUIRES_REVIEW (🟡)
        assertFalse(candidate.getReasons().stream().anyMatch(r -> r.contains("-50")),
                "Should not be penalized with -50 for foreign coauthor's university");
        assertEquals(CandidateVerdict.REQUIRES_REVIEW, candidate.getVerdict());
    }

    @Test
    @DisplayName("👤 extractKnownCoauthors: si un colaborador en EGRETA no tiene person.uuid pero coincide por nombre, no se añade a sí mismo")
    void testExtractKnownCoauthors_whenPersonUuidMissingInContributor_doesNotAddSelfToCoauthors() {
        Publicacion pubWithoutPersonUuid = new Publicacion();
        pubWithoutPersonUuid.setUuid("pub-egreta-003");

        // Contributor is the author herself ("Garcia, Maria") but c.getPerson() is null
        Publicacion.Contributor selfContrib = new Publicacion.Contributor();
        Publicacion.ContributorName selfName = new Publicacion.ContributorName();
        selfName.setLastName("Garcia");
        selfName.setFirstName("Maria");
        selfContrib.setName(selfName);
        selfContrib.setPerson(null); // No person object

        // Real coauthor
        Publicacion.Contributor coContrib = new Publicacion.Contributor();
        Publicacion.ContributorName coName = new Publicacion.ContributorName();
        coName.setLastName("Vidal");
        coName.setFirstName("Carles");
        coContrib.setName(coName);

        pubWithoutPersonUuid.setContributors(List.of(selfContrib, coContrib));

        when(personaRepository.findByUuid("person-uab-100")).thenReturn(Optional.of(persona));
        when(publicacionRepository.findByContributorsPersonUuid("person-uab-100"))
                .thenReturn(List.of(pubWithoutPersonUuid));

        // OpenAlex returns work with "Vidal, Carles" and "Garcia, Maria"
        OpenAlexWorkDto work = new OpenAlexWorkDto(
                "https://openalex.org/W7777",
                "10.1016/j.cell.2023.vidal",
                "Cellular Interaction Study",
                2023,
                "Cell"
        );
        work.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A1", "Maria Garcia", "0000-0002-1825-0097",
                List.of("Universitat Autònoma de Barcelona"), List.of("https://openalex.org/I123044942")
        ));
        work.getAuthors().add(new OpenAlexWorkDto.AuthorDto(
                "A2", "Vidal, Carles", null,
                List.of("Universitat Autònoma de Barcelona"), List.of("https://openalex.org/I123044942")
        ));

        when(openAlexWorksClient.fetchWorksByAuthor(any(), any(), eq(false)))
                .thenReturn(List.of(work));
        when(candidateRepository.save(any(PersonPublicationCandidate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<PersonPublicationCandidate> scanResult = reconciliationService.scanPerson("person-uab-100", false);
        assertEquals(1, scanResult.size());
        PersonPublicationCandidate candidate = scanResult.get(0);

        // Coauthor signal should match Vidal Carles, but NOT match Maria Garcia as coauthor
        assertTrue(candidate.getCoauthorSignals().contains("Vidal"));
        assertFalse(candidate.getCoauthorSignals().contains("Garcia"));
    }
}

