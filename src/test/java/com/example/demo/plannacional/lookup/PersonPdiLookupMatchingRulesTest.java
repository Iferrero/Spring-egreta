package com.example.demo.plannacional.lookup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersonPdiLookupMatchingRulesTest {

    @Test
    void oneSharedSurnameIsNotEnough() {
        // "Ordoñez Garcia" no pot coincidir amb "García García" ni "Ucha Serrano" amb "Serrano García"
        assertFalse(PersonPdiLookupService.surnamesCompatible(List.of("ordonez", "garcia"), List.of("garcia", "garcia")));
        assertFalse(PersonPdiLookupService.surnamesCompatible(List.of("ucha", "serrano"), List.of("serrano", "garcia")));
    }

    @Test
    void sameSurnamesInAnyOrderOrSingleSurnameMatchFirstSurname() {
        assertTrue(PersonPdiLookupService.surnamesCompatible(List.of("gabas", "pallas"), List.of("pallas", "gabas")));
        assertTrue(PersonPdiLookupService.surnamesCompatible(List.of("gabas", "pallas"), List.of("gabas")));
        assertFalse(PersonPdiLookupService.surnamesCompatible(List.of("gabas", "pallas"), List.of("pallas")));
    }

    @Test
    void givenNamesMustNotConflict() {
        assertEquals(1.0, PersonPdiLookupService.givenNamesScore(List.of("jose", "luis"), List.of("jose", "luis")));
        assertEquals(0.9, PersonPdiLookupService.givenNamesScore(List.of("jose", "luis"), List.of("jose")));
        assertEquals(0.9, PersonPdiLookupService.givenNamesScore(List.of("jose"), List.of("j")));
        assertEquals(0.0, PersonPdiLookupService.givenNamesScore(List.of("jose", "luis"), List.of("jose", "carlos")));
        assertEquals(0.0, PersonPdiLookupService.givenNamesScore(List.of("jose"), List.of("javier")));
    }
}
