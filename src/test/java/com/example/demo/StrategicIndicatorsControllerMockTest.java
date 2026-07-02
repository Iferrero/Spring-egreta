package com.example.demo;

import com.example.demo.controller.StrategicIndicatorsController;
import com.example.demo.service.AwardService;
import com.example.demo.service.ResearchOutputJournalLinkService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.aggregation.Aggregation;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class StrategicIndicatorsControllerMockTest {

    @Test
    public void testEuropeanProjectsParticipationAlwaysGreaterThanOrEqualLeadership() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AwardService awardService = mock(AwardService.class);
        ResearchOutputJournalLinkService journalLinkService = mock(ResearchOutputJournalLinkService.class);

        // Mock aggregation results to avoid NPE
        org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> aggResults = mock(org.springframework.data.mongodb.core.aggregation.AggregationResults.class);
        when(aggResults.getMappedResults()).thenReturn(Collections.emptyList());
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("Persons"), eq(Document.class))).thenReturn(aggResults);

        StrategicIndicatorsController controller = new StrategicIndicatorsController(
                mongoTemplate, awardService, journalLinkService
        );

        List<Document> powerTableRows = new ArrayList<>();
        
        // Add a led project with 5 ajuts
        Document ledProject = new Document()
                .append("tipo", "Programa Marc Europeu")
                .append("esLider", true)
                .append("ajuts", 5);
        powerTableRows.add(ledProject);

        when(awardService.getPowerTable(eq(2024), eq(2024), eq("awardDate"), any())).thenReturn(powerTableRows);

        Map<String, Object> stats = controller.getStats(2024, "all");
        assertNotNull(stats);
        
        long lead = ((Number) stats.get("europeanProjectsLead")).longValue();
        long part = ((Number) stats.get("europeanProjectsPart")).longValue();
        
        assertEquals(5L, lead);
        assertEquals(5L, part);

        // Case 2: An anomalous non-led project with negative ajuts to simulate part < lead
        List<Document> anomalousRows = new ArrayList<>();
        anomalousRows.add(new Document()
                .append("tipo", "Programa Marc Europeu")
                .append("esLider", true)
                .append("ajuts", 5));
        anomalousRows.add(new Document()
                .append("tipo", "Programa Marc Europeu")
                .append("esLider", false)
                .append("ajuts", -2));

        when(awardService.getPowerTable(eq(2024), eq(2024), eq("awardDate"), any())).thenReturn(anomalousRows);

        stats = controller.getStats(2024, "all");
        lead = ((Number) stats.get("europeanProjectsLead")).longValue();
        part = ((Number) stats.get("europeanProjectsPart")).longValue();

        assertEquals(5L, lead);
        assertEquals(5L, part); // Enforced to equal lead (5) rather than 3
    }

    @Test
    public void testSgrDoctorsQueryCriteria() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AwardService awardService = mock(AwardService.class);
        ResearchOutputJournalLinkService journalLinkService = mock(ResearchOutputJournalLinkService.class);

        StrategicIndicatorsController controller = new StrategicIndicatorsController(
                mongoTemplate, awardService, journalLinkService
        );

        when(awardService.getPowerTable(anyInt(), anyInt(), anyString(), any())).thenReturn(new ArrayList<>());

        // Mock aggregation results
        org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> aggResults = mock(org.springframework.data.mongodb.core.aggregation.AggregationResults.class);
        when(aggResults.getMappedResults()).thenReturn(List.of(new Document("total", 10L)));
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("Persons"), eq(Document.class))).thenReturn(aggResults);

        // Call getStats
        Map<String, Object> stats = controller.getStats(2024, "all");
        assertNotNull(stats);
        assertEquals(10L, stats.get("sgrDoctors"));

        // Capture the Aggregation sent to aggregate on "Persons"
        org.mockito.ArgumentCaptor<Aggregation> aggCaptor = org.mockito.ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(aggCaptor.capture(), eq("Persons"), eq(Document.class));

        Aggregation capturedAggregation = aggCaptor.getValue();
        assertNotNull(capturedAggregation);

        // Verify that the pipeline stages contain the doctor URIs and SGR adscripcio_recerca uri
        String aggString = capturedAggregation.toString();
        assertTrue(aggString.contains("/dk/atira/pure/person/employmenttypes/agregat_contractat"));
        assertTrue(aggString.contains("/dk/atira/pure/person/employmenttypes/catedratics"));
        assertTrue(aggString.contains("/dk/atira/pure/person/employmenttypes/adscripcio_recerca"));
        assertTrue(aggString.contains("sgr"));
    }
}
