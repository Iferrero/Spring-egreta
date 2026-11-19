package com.example.demo.plannacional.lookup;

import com.example.demo.plannacional.engine.PlanNacionalEligibilityEngine;
import com.example.demo.plannacional.model.*;
import com.mongodb.ServerAddress;
import com.mongodb.ServerCursor;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonPdiLookupServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> personsCollection;

    @Mock
    private FindIterable<Document> findIterable;

    private PersonPdiLookupService service;
    private static final LocalDate REF_DATE = LocalDate.of(2026, 11, 19);

    @BeforeEach
    void setUp() {
        service = new PersonPdiLookupService(mongoTemplate, new PlanNacionalEligibilityEngine(), 0L);
    }

    private void stubPersons(List<Document> docs) {
        when(mongoTemplate.getCollection("Persons")).thenReturn(personsCollection);
        when(personsCollection.find()).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.batchSize(anyInt())).thenReturn(findIterable);
        when(findIterable.iterator()).thenAnswer(inv -> new ListMongoCursor(docs));
    }

    private Document personDoc(String uuid, Long pureId, String firstName, String lastName, List<Document> identifiers,
                               List<Document> staffAssocs, List<Document> visitingAssocs) {
        Document d = new Document("uuid", uuid)
                .append("pureId", pureId)
                .append("name", new Document("firstName", firstName).append("lastName", lastName));
        if (identifiers != null) {
            d.append("identifiers", identifiers);
        }
        if (staffAssocs != null) {
            d.append("staffOrganizationAssociations", staffAssocs);
        }
        if (visitingAssocs != null) {
            d.append("visitingScholarOrganizationAssociations", visitingAssocs);
        }
        return d;
    }

    private Document idDoc(String typeCa, String value) {
        return new Document("type", new Document("term", new Document("ca_ES", typeCa)))
                .append("value", value);
    }

    private Document staffAssoc(String empType, String contractType, String staffType, String orgName, String orgUuid,
                                Object startDate, Object endDate, boolean primary) {
        return new Document("primaryAssociation", primary)
                .append("employmentType", new Document("term", new Document("ca_ES", empType)))
                .append("contractType", contractType != null ? new Document("term", new Document("ca_ES", contractType)) : null)
                .append("staffType", staffType != null ? new Document("term", new Document("ca_ES", staffType)) : null)
                .append("organization", new Document("uuid", orgUuid)
                        .append("name", new Document("text", List.of(new Document("locale", "ca_ES").append("value", orgName)))))
                .append("period", new Document("startDate", startDate).append("endDate", endDate));
    }

    private Document visitingAssoc(String jobTitle, String orgName, String orgUuid, Object startDate, Object endDate) {
        return new Document("jobTitle", new Document("term", new Document("ca_ES", jobTitle)))
                .append("organization", new Document("uuid", orgUuid)
                        .append("name", new Document("text", List.of(new Document("locale", "ca_ES").append("value", orgName)))))
                .append("period", new Document("startDate", startDate).append("endDate", endDate));
    }

    @Test
    void lookupMatchesByExactNormalizedNifAndBuildsEgretaUrl() {
        Document joan = personDoc("uuid-joan", 4200L, "Joan", "Garcia",
                List.of(idDoc("DNI", "12345678A")),
                List.of(staffAssoc("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", "Dept. Física", "org-1", "2010-01-01", null, true)),
                null);
        stubPersons(List.of(joan));

        ExtractedMember member = new ExtractedMember(MemberRole.IP, "Garcia, Joan", "Joan", "Garcia", "12.345.678-a", "UAB", null, "");
        PersonLookupMatch match = service.lookup(member, REF_DATE);

        assertEquals(MatchConfidence.NIF_EXACTE, match.matchConfidence());
        assertEquals("uuid-joan", match.matchedPersonUuid());
        assertEquals(4200L, match.matchedPureId());
        assertEquals("https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=4200",
                match.egretaEditorUrl());
        assertNotNull(match.primaryContract());
    }

    @Test
    void lookupMatchesMaskedNifWithSurnameOrMarksAmbiguous() {
        Document otherSurname = personDoc("uuid-1", 10L, "Anna", "Perez Mas",
                List.of(idDoc("DNI", "11145678X")),
                List.of(staffAssoc("Professor/a associat/da laboral", "Contracte laboral temporal", "Acadèmic", "Dept A", "o1", "2024-01-01", null, true)),
                null);
        Document matchingSurname = personDoc("uuid-2", 20L, "Anna", "Garcia Lopez",
                List.of(idDoc("DNI", "22245678Y")),
                List.of(staffAssoc("Professorat titular d'universitat", "Funcionari/a", "Acadèmic", "Dept B", "o2", "2015-01-01", null, true)),
                null);
        stubPersons(List.of(otherSurname, matchingSurname));

        ExtractedMember maskedSingle = new ExtractedMember(MemberRole.IP, "GARCIA LOPEZ, ANNA", "ANNA", "GARCIA LOPEZ", "***4567**", "UAB", null, "");
        PersonLookupMatch singleMatch = service.lookup(maskedSingle, REF_DATE);
        assertEquals(MatchConfidence.NIF_EXACTE, singleMatch.matchConfidence());
        assertEquals("uuid-2", singleMatch.matchedPersonUuid());

        // Ara 2 persones amb els mateixos dígits emmascarats i cognom compatible -> AMBIGU
        Document secondGarcia = personDoc("uuid-3", 30L, "Marta", "Garcia Puig",
                List.of(idDoc("DNI", "33345679Z")),
                List.of(staffAssoc("Professor/a lector/a ajudant doctor/a", "Contracte laboral temporal", "Acadèmic", "Dept C", "o3", "2024-01-01", null, true)),
                null);
        stubPersons(List.of(matchingSurname, secondGarcia));

        PersonLookupMatch ambiguous = service.lookup(maskedSingle, REF_DATE);
        assertEquals(MatchConfidence.AMBIGU, ambiguous.matchConfidence());
        assertEquals(2, ambiguous.ambiguousCandidates().size());
    }

    @Test
    void lookupMatchesByNormalizedNameOrderAndAccentsAndHandlesHomonyms() {
        Document maria = personDoc("uuid-maria", 101L, "Maria", "Garcia Lopez",
                List.of(),
                List.of(staffAssoc("Agregat/da contractat doctor/a", "Contracte laboral indefinit", "Acadèmic", "Dept D", "o4", "2018-01-01", null, true)),
                null);
        Document joanTorre = personDoc("uuid-torre", 102L, "Joan", "Torre Puig",
                List.of(),
                List.of(staffAssoc("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", "Dept E", "o5", "2010-01-01", null, true)),
                null);
        stubPersons(List.of(maria, joanTorre));

        ExtractedMember exactByName = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "GARCÍA LÓPEZ, MARÍA", "MARÍA", "GARCÍA LÓPEZ", null, "UAB", null, "");
        assertEquals(MatchConfidence.NOM_EXACTE, service.lookup(exactByName, REF_DATE).matchConfidence());

        ExtractedMember approxWithParticles = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Joan de la Torre Puig", "Joan", "de la Torre Puig", null, "UAB", null, "");
        PersonLookupMatch approxMatch = service.lookup(approxWithParticles, REF_DATE);
        assertEquals(MatchConfidence.NOM_APROXIMAT, approxMatch.matchConfidence());
        assertEquals("uuid-torre", approxMatch.matchedPersonUuid());

        ExtractedMember unknown = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Inexistent Total, Ningú", "Ningú", "Inexistent Total", null, "UAB", null, "");
        assertEquals(MatchConfidence.NO_TROBAT, service.lookup(unknown, REF_DATE).matchConfidence());

        // Homònims -> AMBIGU sense endevinar
        Document maria2 = personDoc("uuid-maria-2", 103L, "María", "García López", List.of(), List.of(), null);
        stubPersons(List.of(maria, maria2));
        PersonLookupMatch homonyms = service.lookup(exactByName, REF_DATE);
        assertEquals(MatchConfidence.AMBIGU, homonyms.matchConfidence());
        assertEquals(2, homonyms.ambiguousCandidates().size());
    }

    @Test
    void lookupRelegatesAdscripcioARecercaEvenWhenPrimaryAndSelectsRealContract() {
        Document assocDoc = staffAssoc("Professor/a associat/da laboral", "Contracte laboral temporal", "Acadèmic", "Dept. Economia", "dept-1", "2024-09-01", "2027-08-31", false);
        Document adscripcioDoc = staffAssoc("Adscripció a recerca", null, "Acadèmic", "ICTA", "inst-1", "2020-01-01", null, true);
        Document person = personDoc("uuid-dual", 500L, "Pere", "Vila", List.of(idDoc("DNI", "99887766A")), List.of(adscripcioDoc, assocDoc), null);
        stubPersons(List.of(person));

        ExtractedMember member = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Vila, Pere", "Pere", "Vila", "99887766A", "UAB", null, "");
        PersonLookupMatch match = service.lookup(member, REF_DATE);

        assertNotNull(match.primaryContract());
        assertEquals("Professor/a associat/da laboral", match.primaryContract().employmentType());
        assertFalse(match.primaryContract().researchAffiliationOnly());
        assertEquals(2, match.activeContracts().size());
    }

    @Test
    void lookupFlagsResearchAffiliationOnlyWhenNoOtherActiveContractExists() {
        Document adscripcioOnly = staffAssoc("Adscripció a recerca", null, "Acadèmic", "ICTA", "inst-1", "2020-01-01", null, true);
        Document person = personDoc("uuid-ads", 501L, "Núria", "Camps", List.of(idDoc("DNI", "88776655B")), List.of(adscripcioOnly), null);
        stubPersons(List.of(person));

        ExtractedMember member = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Camps, Núria", "Núria", "Camps", "88776655B", "UAB", null, "");
        PersonLookupMatch match = service.lookup(member, REF_DATE);

        assertNotNull(match.primaryContract());
        assertEquals("Adscripció a recerca", match.primaryContract().employmentType());
        assertTrue(match.primaryContract().researchAffiliationOnly());
    }

    @Test
    void lookupIncludesVisitingScholarAndParsesStringAndDatePeriods() {
        Date startAsDate = Date.from(LocalDate.of(2021, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
        Document staffPeriod = staffAssoc("Investigador/a ordinari/a", "Contracte laboral indefinit", "Acadèmic", "Dept. Bio", "dept-bio", startAsDate, "2028-08-31", false);
        Document icrea = visitingAssoc("Personal investigador ICREA", "CREAF", "inst-creaf", "2022-01-01", null);
        Document person = personDoc("uuid-icrea", 600L, "Laura", "Mir", List.of(idDoc("DNI", "77665544C")), List.of(staffPeriod), List.of(icrea));
        stubPersons(List.of(person));

        ExtractedMember member = new ExtractedMember(MemberRole.IP, "Mir, Laura", "Laura", "Mir", "77665544C", "UAB", null, "");
        List<PersonLookupMatch> results = service.lookupAll(List.of(member), REF_DATE);

        assertEquals(1, results.size());
        PersonLookupMatch match = results.get(0);
        assertEquals(2, match.activeContracts().size());
        assertTrue(match.activeContracts().stream().anyMatch(c -> "VISITING".equals(c.source()) && "Personal investigador ICREA".equals(c.employmentType())));
        assertTrue(match.activeContracts().stream().anyMatch(c -> LocalDate.of(2021, 1, 1).equals(c.startDate()) && LocalDate.of(2028, 8, 31).equals(c.endDate())));
    }

    @Test
    void lookupFallsBackToLatestHistoricalContractWhenExpired() {
        Document older = staffAssoc("Becari/ària Predoctoral", "Personal Becat", "Acadèmic", "Dept. X", "ox", "2018-01-01", "2021-06-30", false);
        Document newer = staffAssoc("Professor/a lector/a ajudant doctor/a", "Contracte laboral temporal", "Acadèmic", "Dept. X", "ox", "2022-01-01", "2025-12-31", true);
        Document person = personDoc("uuid-exp", 700L, "Oriol", "Serra", List.of(idDoc("DNI", "66554433D")), List.of(older, newer), null);
        stubPersons(List.of(person));

        ExtractedMember member = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Serra, Oriol", "Oriol", "Serra", "66554433D", "UAB", null, "");
        PersonLookupMatch match = service.lookup(member, REF_DATE);

        assertEquals(MatchConfidence.NIF_EXACTE, match.matchConfidence());
        assertNull(match.primaryContract());
        assertTrue(match.activeContracts().isEmpty());
        assertNotNull(match.latestHistoricalContract());
        assertEquals(LocalDate.of(2025, 12, 31), match.latestHistoricalContract().endDate());
        assertEquals("Professor/a lector/a ajudant doctor/a", match.latestHistoricalContract().employmentType());
    }

    @Test
    void lookupExtractsLegalIdsFromClassifiedIdField() {
        Document classifiedIdDoc = new Document("type", new Document("term", new Document("ca_ES", "DNI")))
                .append("id", "55443322Z");
        Document person = personDoc("uuid-cid", 800L, "Carla", "Soler",
                List.of(classifiedIdDoc),
                List.of(staffAssoc("Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic", "Dept. Dret", "o8", "2012-01-01", null, true)),
                null);
        stubPersons(List.of(person));

        ExtractedMember member = new ExtractedMember(MemberRole.IP, "Soler, Carla", "Carla", "Soler", "55443322Z", "UAB", null, "");
        PersonLookupMatch match = service.lookup(member, REF_DATE);

        assertEquals(MatchConfidence.NIF_EXACTE, match.matchConfidence());
        assertEquals("uuid-cid", match.matchedPersonUuid());
    }

    @Test
    void lookupMaskedNifRejectsSameLengthPositionalMismatchAndHandlesMidWildcards() {
        Document wrongPos = personDoc("uuid-wp", 810L, "Anna", "Garcia",
                List.of(idDoc("DNI", "45671234B")),
                List.of(), null);
        Document rightPos = personDoc("uuid-rp", 811L, "Pere", "Soler",
                List.of(idDoc("DNI", "12345678A")),
                List.of(), null);
        stubPersons(List.of(wrongPos, rightPos));

        // "***4567**" té longitud 9 però "45671234B" té "4567" a les posicions 0..3 -> NO ha de casar
        ExtractedMember wrongMask = new ExtractedMember(MemberRole.IP, "Garcia, Anna", "Anna", "Garcia", "***4567**", "UAB", null, "");
        assertEquals(MatchConfidence.NOM_EXACTE, service.lookup(wrongMask, REF_DATE).matchConfidence());

        // Ara sense coincidir per nom: ha de ser NO_TROBAT, no fals positiu per contains("4567")
        ExtractedMember otherNameSameMask = new ExtractedMember(MemberRole.IP, "Lopez, Maria", "Maria", "Lopez", "***4567**", "UAB", null, "");
        assertEquals(MatchConfidence.NO_TROBAT, service.lookup(otherNameSameMask, REF_DATE).matchConfidence());

        // Màscara amb asteriscs interns "123****8A" casa amb "12345678A"
        ExtractedMember midMask = new ExtractedMember(MemberRole.IP, "Soler, Pere", "Pere", "Soler", "123****8A", "UAB", null, "");
        PersonLookupMatch midMatch = service.lookup(midMask, REF_DATE);
        assertEquals(MatchConfidence.NIF_EXACTE, midMatch.matchConfidence());
        assertEquals("uuid-rp", midMatch.matchedPersonUuid());
    }

    @Test
    void lookupApproximateMatchesInitialHandlesSurnameFirstWithoutCommaAndRejectsSurnameOnly() {
        Document joan = personDoc("uuid-j", 820L, "Joan", "Garcia Lopez", List.of(), List.of(), null);
        Document joseMaria = personDoc("uuid-jm", 821L, "Jose Maria", "Garcia",
                List.of(), List.of(), null);
        Document altNamePerson = personDoc("uuid-alt", 822L, "Carles", "Roca", List.of(), List.of(), null)
                .append("names", List.of(new Document("name", new Document("firstName", "Carlos").append("lastName", "Roca"))));
        stubPersons(List.of(joan, joseMaria, altNamePerson));

        // Inicial + cognoms
        ExtractedMember withInitial = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "J. Garcia Lopez", "J.", "Garcia Lopez", null, "UAB", null, "");
        PersonLookupMatch initMatch = service.lookup(withInitial, REF_DATE);
        assertEquals(MatchConfidence.NOM_APROXIMAT, initMatch.matchConfidence());
        assertEquals("uuid-j", initMatch.matchedPersonUuid());

        // Nom sense coma en ordre "COGNOM NOM1 NOM2" -> tots els tokens exactes (NOM_EXACTE)
        ExtractedMember surnameFirstExact = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "GARCIA JOSE MARIA", "GARCIA", "JOSE MARIA", null, "UAB", null, "");
        PersonLookupMatch sfExactMatch = service.lookup(surnameFirstExact, REF_DATE);
        assertEquals(MatchConfidence.NOM_EXACTE, sfExactMatch.matchConfidence());
        assertEquals("uuid-jm", sfExactMatch.matchedPersonUuid());

        // Nom sense coma en ordre "COGNOM NOM1" amb nom compost parcial -> Level 3 NOM_APROXIMAT
        ExtractedMember surnameFirstPartial = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "GARCIA JOSE", "GARCIA", "JOSE", null, "UAB", null, "");
        PersonLookupMatch sfApproxMatch = service.lookup(surnameFirstPartial, REF_DATE);
        assertEquals(MatchConfidence.NOM_APROXIMAT, sfApproxMatch.matchConfidence());
        assertEquals("uuid-jm", sfApproxMatch.matchedPersonUuid());

        // Només cognoms sense nom propi -> no casa a cegues
        ExtractedMember surnameOnly = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Garcia Lopez", null, "Garcia Lopez", null, "UAB", null, "");
        assertEquals(MatchConfidence.NO_TROBAT, service.lookup(surnameOnly, REF_DATE).matchConfidence());

        // Variant a names[] -> NOM_EXACTE o NOM_APROXIMAT
        ExtractedMember altSearch = new ExtractedMember(MemberRole.EQUIPO_INVESTIGACION, "Carlos Roca", "Carlos", "Roca", null, "UAB", null, "");
        assertEquals(MatchConfidence.NOM_EXACTE, service.lookup(altSearch, REF_DATE).matchConfidence());
    }

    @Test
    void lookupAllReusesShortTtlCacheAcrossCalls() {
        PersonPdiLookupService cachedService = new PersonPdiLookupService(mongoTemplate, new PlanNacionalEligibilityEngine(), 60_000L);
        Document p = personDoc("uuid-c", 900L, "Marc", "Font", List.of(idDoc("DNI", "11122233H")), List.of(), null);
        stubPersons(List.of(p));

        ExtractedMember m = new ExtractedMember(MemberRole.IP, "Font, Marc", "Marc", "Font", "11122233H", "UAB", null, "");
        assertEquals(MatchConfidence.NIF_EXACTE, cachedService.lookupAll(List.of(m), REF_DATE).get(0).matchConfidence());
        assertEquals(MatchConfidence.NIF_EXACTE, cachedService.lookupAll(List.of(m), REF_DATE).get(0).matchConfidence());

        verify(mongoTemplate, times(1)).getCollection("Persons");
    }

    private static class ListMongoCursor implements MongoCursor<Document> {
        private final Iterator<Document> it;

        ListMongoCursor(List<Document> docs) {
            this.it = docs.iterator();
        }

        @Override public void close() {}
        @Override public boolean hasNext() { return it.hasNext(); }
        @Override public Document next() { return it.next(); }
        @Override public int available() { return 0; }
        @Override public Document tryNext() { return hasNext() ? next() : null; }
        @Override public ServerCursor getServerCursor() { return null; }
        @Override public ServerAddress getServerAddress() { return new ServerAddress("localhost", 27017); }
    }
}
