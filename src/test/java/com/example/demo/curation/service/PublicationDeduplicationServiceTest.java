package com.example.demo.curation.service;

import com.example.demo.curation.model.CandidateStatus;
import com.example.demo.curation.model.PersonPublicationCandidate;
import com.example.demo.curation.repository.PersonPublicationCandidateRepository;
import com.example.demo.model.Publicacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PublicationDeduplicationServiceTest {

    private PersonPublicationCandidateRepository candidateRepository;
    private PublicationDeduplicationService deduplicationService;

    @BeforeEach
    public void setUp() {
        candidateRepository = mock(PersonPublicationCandidateRepository.class);
        deduplicationService = new PublicationDeduplicationService(candidateRepository);
    }

    @Test
    public void testNormalizeDoi() {
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi(" 10.1016/j.cell.2020 "));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("https://doi.org/10.1016/J.CELL.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("http://dx.doi.org/10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("https://dx.doi.org/10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("http://doi.org/10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("doi.org/10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("dx.doi.org/10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("doi:10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("doi: 10.1016/j.cell.2020"));
        assertEquals("10.1016/j.cell.2020", deduplicationService.normalizeDoi("doi:https://doi.org/10.1016/j.cell.2020"));
        assertNull(deduplicationService.normalizeDoi(null));
        assertNull(deduplicationService.normalizeDoi(""));
        assertNull(deduplicationService.normalizeDoi("   "));
        assertNull(deduplicationService.normalizeDoi("doi:"));
    }

    @Test
    public void testCalculateTitleSimilarity() {
        String t1 = "CRISPR-Cas9 genome editing for cystic fibrosis";
        String t2 = "CRISPR-Cas9 genome editing for cystic fibrosis therapy";

        double similarity = deduplicationService.calculateTitleSimilarity(t1, t2);
        assertTrue(similarity > 0.85, "La similitud Jaccard deberia ser > 0.85 (obtenida: " + similarity + ")");

        assertEquals(1.0, deduplicationService.calculateTitleSimilarity("Same Title Here", "Same Title Here"));
        assertEquals(0.0, deduplicationService.calculateTitleSimilarity("Quantum computing algorithms", "Ancient Roman architecture"));
        assertEquals(0.0, deduplicationService.calculateTitleSimilarity(null, "Some Title"));
        assertEquals(0.0, deduplicationService.calculateTitleSimilarity("Some Title", null));
        assertEquals(0.0, deduplicationService.calculateTitleSimilarity("", ""));
        assertEquals(0.0, deduplicationService.calculateTitleSimilarity("   ", "Some Title"));

        // Normalizacion de acentos y puntuacion
        String ca1 = "Mecanismes d'adaptació al canvi climàtic a Catalunya";
        String ca2 = "Mecanismes d'adaptacio al canvi climatic a Catalunya.";
        assertEquals(1.0, deduplicationService.calculateTitleSimilarity(ca1, ca2), "Titulos identicos salvo acentos y signos deben tener similitud 1.0");

        // Similitud inferior al umbral (p. ej. 8 de 10 palabras coincidentes = 8/10 = 0.80 <= 0.85)
        String t3 = "one two three four five six seven eight nine ten";
        String t4 = "one two three four five six seven eight alpha beta";
        double simLower = deduplicationService.calculateTitleSimilarity(t3, t4);
        assertEquals(0.6666, simLower, 0.01);
        assertFalse(simLower > PublicationDeduplicationService.TITLE_SIMILARITY_THRESHOLD);
    }

    @Test
    public void testIsAlreadyKnownExactDoiInEgreta() {
        String personUuid = "person-123";
        String doi = "10.1016/j.cell.2020";

        Publicacion pub = new Publicacion();
        Publicacion.Identifier identifier = new Publicacion.Identifier();
        identifier.setValue("https://doi.org/10.1016/J.CELL.2020");
        identifier.setIdSource("doi");
        pub.setIdentifiers(List.of(identifier));

        when(candidateRepository.findByPersonUuidAndDoi(personUuid, doi)).thenReturn(Optional.empty());

        boolean result = deduplicationService.isAlreadyKnown(personUuid, "doi:" + doi, "Any title", List.of(pub));
        assertTrue(result, "Deberia reconocer que ya existe en EGRETA por coincidencia exacta de DOI");
    }

    @Test
    public void testIsAlreadyKnownFuzzyTitleMatchWhenCandidateDoiMissing() {
        String personUuid = "person-123";
        String egretaTitle = "CRISPR-Cas9 genome editing for cystic fibrosis";
        String candidateTitle = "CRISPR-Cas9 genome editing for cystic fibrosis therapy";

        Publicacion pub = new Publicacion();
        Publicacion.Title title = new Publicacion.Title();
        title.setValue(egretaTitle);
        pub.setTitle(title);

        when(candidateRepository.findByPersonUuid(personUuid)).thenReturn(Collections.emptyList());

        boolean known = deduplicationService.isAlreadyKnown(personUuid, null, candidateTitle, List.of(pub));
        assertTrue(known, "Deberia reconocer la obra por alta similitud de titulo (> 0.85) cuando falta el DOI en la candidata");

        boolean unknown = deduplicationService.isAlreadyKnown(personUuid, null, "Completely different title", List.of(pub));
        assertFalse(unknown, "No deberia marcar como conocida una obra con titulo disjunto");
    }

    @Test
    public void testIsAlreadyKnownFuzzyTitleMatchWhenEgretaDoiMissing() {
        String personUuid = "person-123";
        String egretaTitle = "Machine learning approaches in oncology: A comprehensive review";
        String candidateTitle = "Machine learning approaches in oncology: A comprehensive review study";
        String candidateDoi = "10.1038/s41586-024-0001";

        Publicacion pubWithoutDoi = new Publicacion();
        Publicacion.Title title = new Publicacion.Title();
        title.setValue(egretaTitle);
        pubWithoutDoi.setTitle(title);
        pubWithoutDoi.setIdentifiers(Collections.emptyList());

        when(candidateRepository.findByPersonUuidAndDoi(personUuid, candidateDoi)).thenReturn(Optional.empty());

        boolean known = deduplicationService.isAlreadyKnown(personUuid, candidateDoi, candidateTitle, List.of(pubWithoutDoi));
        assertTrue(known, "Deberia reconocer la obra si el candidato tiene DOI pero en EGRETA no tenia DOI y los titulos coinciden (> 0.85)");
    }

    @Test
    public void testIsNotKnownWhenDifferentDoisEvenIfTitlesAreSimilar() {
        String personUuid = "person-123";
        String egretaTitle = "Quantum computing algorithms for optimization problems Part 1";
        String candidateTitle = "Quantum computing algorithms for optimization problems Part 2";
        String candidateDoi = "10.1000/candidate-doi";
        String egretaDoi = "10.1000/egreta-doi";

        Publicacion pub = new Publicacion();
        Publicacion.Title title = new Publicacion.Title();
        title.setValue(egretaTitle);
        pub.setTitle(title);

        Publicacion.Identifier id = new Publicacion.Identifier();
        id.setValue(egretaDoi);
        pub.setIdentifiers(List.of(id));

        when(candidateRepository.findByPersonUuidAndDoi(personUuid, candidateDoi)).thenReturn(Optional.empty());

        boolean known = deduplicationService.isAlreadyKnown(personUuid, candidateDoi, candidateTitle, List.of(pub));
        assertFalse(known, "No debe considerarse duplicada si ambas obras tienen DOIs explicitos y distintos");
    }

    @Test
    public void testIsAlreadyKnownExclusionWhenExistsInCandidateRepository() {
        String personUuid = "person-123";
        String doi = "10.1016/j.cell.2020";

        PersonPublicationCandidate existingCandidate = new PersonPublicationCandidate();
        existingCandidate.setPersonUuid(personUuid);
        existingCandidate.setDoi(doi);
        existingCandidate.setStatus(CandidateStatus.REJECTED_HOMONYM);

        when(candidateRepository.findByPersonUuidAndDoi(personUuid, doi)).thenReturn(Optional.of(existingCandidate));

        boolean known = deduplicationService.isAlreadyKnown(personUuid, doi, "Any title", Collections.emptyList());
        assertTrue(known, "Deberia excluir si ya fue evaluada en el repositorio de candidatos aunque haya sido descartada");
    }

    @Test
    public void testIsAlreadyKnownByTitleInCandidateRepositoryWhenDoiMissing() {
        String personUuid = "person-123";
        String rawTitle = "CRISPR-Cas9 genome editing for cystic fibrosis therapy";

        PersonPublicationCandidate existingCandidate = new PersonPublicationCandidate();
        existingCandidate.setPersonUuid(personUuid);
        existingCandidate.setTitle("CRISPR-Cas9 genome editing for cystic fibrosis");
        existingCandidate.setStatus(CandidateStatus.PENDING);

        when(candidateRepository.findByPersonUuid(personUuid)).thenReturn(List.of(existingCandidate));

        boolean known = deduplicationService.isAlreadyKnown(personUuid, null, rawTitle, Collections.emptyList());
        assertTrue(known, "Deberia detectar duplicado en repositorio de candidatos por titulo si falta DOI");
    }

    @Test
    public void testIsNotKnownWhenNeitherInEgretaNorInCandidates() {
        String personUuid = "person-123";
        String doi = "10.1016/j.new.2024";

        when(candidateRepository.findByPersonUuidAndDoi(personUuid, doi)).thenReturn(Optional.empty());

        boolean known = deduplicationService.isAlreadyKnown(personUuid, doi, "Brand New Paper", Collections.emptyList());
        assertFalse(known, "Debe reportar falso cuando la obra no existe ni en EGRETA ni en candidatos");
    }

    @Test
    public void testEdgeCasesAndNullHandling() {
        assertFalse(deduplicationService.isAlreadyKnown(null, null, null, null));
        assertFalse(deduplicationService.isAlreadyKnown("person-123", null, null, Collections.emptyList()));

        Publicacion emptyPub = new Publicacion();
        assertFalse(deduplicationService.isAlreadyKnown("person-123", null, "Some title", List.of(emptyPub)));
    }
}
