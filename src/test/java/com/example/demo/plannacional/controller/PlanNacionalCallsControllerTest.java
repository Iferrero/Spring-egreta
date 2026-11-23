package com.example.demo.plannacional.controller;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanNacionalCallsControllerTest {

    @Test
    void parseDateAcceptsIsoStringsTimestampsDatesAndYearMonthDayObjects() {
        assertEquals(LocalDate.of(2024, 3, 15), PlanNacionalCallsController.parseDate("2024-03-15"));
        assertEquals(LocalDate.of(2024, 3, 15), PlanNacionalCallsController.parseDate("2024-03-15T00:00:00.000+0000"));
        assertEquals(LocalDate.of(2024, 3, 15), PlanNacionalCallsController.parseDate(
                Date.from(LocalDate.of(2024, 3, 15).atStartOfDay().toInstant(java.time.ZoneOffset.UTC))));
        assertEquals(LocalDate.of(2024, 3, 15), PlanNacionalCallsController.parseDate(
                new Document("year", 2024).append("month", 3).append("day", 15)));
        assertNull(PlanNacionalCallsController.parseDate(null));
        assertNull(PlanNacionalCallsController.parseDate("no és data"));
    }

    @Test
    void resolveTitlePrefersCatalanAndFallsBackToUuid() {
        assertEquals("Títol CA", PlanNacionalCallsController.resolveTitle(
                new Document("es_ES", "Título ES").append("ca_ES", "Títol CA"), "u1"));
        assertEquals("Hola", PlanNacionalCallsController.resolveTitle(
                new Document("text", List.of(new Document("locale", "en_GB").append("value", "Hello"),
                        new Document("locale", "ca_ES").append("value", "Hola"))), "u1"));
        assertEquals("u1", PlanNacionalCallsController.resolveTitle(null, "u1"));
    }
}
