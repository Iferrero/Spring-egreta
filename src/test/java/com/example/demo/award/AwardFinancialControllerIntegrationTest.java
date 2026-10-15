package com.example.demo.award;

import com.example.demo.award.engine.AwardFinancialEngine;
import com.example.demo.award.model.ResignedAwardsQueryCriteria;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwardFinancialControllerIntegrationTest {

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
    private com.example.demo.award.port.AwardReportPort awardReportPort;

    @Mock
    private com.example.demo.award.engine.AwardClassificationEngine classificationEngine;

    private AwardController controller;

    @BeforeEach
    void setUp() {
        controller = new AwardController(repository, service, mongoTemplate, researchOutputService, financialEngine, awardReportPort, classificationEngine);
    }

    @Test
    @DisplayName("getPkAwardsStats delegates directly to AwardFinancialEngine")
    void testGetPkAwardsStatsDelegation() {
        List<Document> mockDocs = List.of(new Document("test", "pk"));
        when(financialEngine.getPkAwardsStats()).thenReturn(mockDocs);

        List<Document> result = controller.getPkAwardsStats();

        assertThat(result).isSameAs(mockDocs);
        verify(financialEngine).getPkAwardsStats();
    }

    @Test
    @DisplayName("getCostCenterConvocatorias delegates directly to AwardFinancialEngine")
    void testGetCostCenterConvocatoriasDelegation() {
        List<Map<String, Object>> mockList = List.of(Map.of("tipologia", "PK"));
        when(financialEngine.getCostCenterConvocatorias()).thenReturn(mockList);

        List<Map<String, Object>> result = controller.getCostCenterConvocatorias();

        assertThat(result).isSameAs(mockList);
        verify(financialEngine).getCostCenterConvocatorias();
    }

    @Test
    @DisplayName("getResignedAwards maps parameters to criteria and delegates to engine")
    void testGetResignedAwardsDelegation() {
        Map<String, Object> mockResp = Map.of("totalElements", 5L);
        when(financialEngine.getResignedAwards(any(ResignedAwardsQueryCriteria.class))).thenReturn(mockResp);

        Map<String, Object> result = controller.getResignedAwards(
                1, 20, "search-term", "competitives", "projectes",
                "renuncia", "terminated", "2024", "date", "desc", false
        );

        assertThat(result).isSameAs(mockResp);
        verify(financialEngine).getResignedAwards(argThat(c ->
                c.page() == 1 &&
                c.size() == 20 &&
                "search-term".equals(c.search()) &&
                "terminated".equals(c.statusType())
        ));
    }
}
