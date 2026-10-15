package com.example.demo.award;

import com.example.demo.award.engine.AwardClassificationEngine;
import com.example.demo.award.engine.AwardFinancialEngine;
import com.example.demo.award.port.AwardReportPort;
import com.example.demo.controller.AwardController;
import com.example.demo.repository.AwardRepository;
import com.example.demo.service.AwardService;
import com.example.demo.service.ResearchOutputJournalLinkService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwardClassificationControllerIntegrationTest {

    @Mock
    private AwardRepository repository;

    @Mock
    private AwardService service;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ResearchOutputJournalLinkService researchOutputService;

    @Mock
    private AwardFinancialEngine financialEngine;

    @Mock
    private AwardReportPort awardReportPort;

    @Mock
    private AwardClassificationEngine classificationEngine;

    private AwardController controller;

    @BeforeEach
    void setUp() {
        controller = new AwardController(
                repository,
                service,
                mongoTemplate,
                researchOutputService,
                financialEngine,
                awardReportPort,
                classificationEngine
        );
    }

    @Test
    @DisplayName("getNatureHierarchy delegates to AwardClassificationEngine")
    void testGetNatureHierarchyDelegation() {
        List<Map<String, Object>> mockHierarchy = List.of(Map.of("nature", "test"));
        when(classificationEngine.getNatureHierarchy()).thenReturn(mockHierarchy);

        List<Map<String, Object>> result = controller.getNatureHierarchy();
        assertThat(result).isSameAs(mockHierarchy);
        verify(classificationEngine).getNatureHierarchy();
    }

    @Test
    @DisplayName("getAvailableNatures delegates to AwardClassificationEngine")
    void testGetAvailableNaturesDelegation() {
        List<Document> mockNatures = List.of(new Document("term", "test"));
        when(classificationEngine.getAvailableNatures()).thenReturn(mockNatures);

        List<Document> result = controller.getAvailableNatures();
        assertThat(result).isSameAs(mockNatures);
        verify(classificationEngine).getAvailableNatures();
    }

    @Test
    @DisplayName("getNatureFundingRelation delegates to AwardClassificationEngine")
    void testGetNatureFundingRelationDelegation() {
        List<Map<String, Object>> mockRelation = List.of(Map.of("relation", "test"));
        when(classificationEngine.getNatureFundingRelation()).thenReturn(mockRelation);

        List<Map<String, Object>> result = controller.getNatureFundingRelation();
        assertThat(result).isSameAs(mockRelation);
        verify(classificationEngine).getNatureFundingRelation();
    }
}
