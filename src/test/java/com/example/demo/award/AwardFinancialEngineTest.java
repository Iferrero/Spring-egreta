package com.example.demo.award;

import com.example.demo.award.engine.AwardFinancialEngine;
import com.example.demo.award.model.CostCodePrefix;
import com.example.demo.award.model.PowerTableQueryCriteria;
import com.example.demo.award.model.ResignedAwardsQueryCriteria;
import com.example.demo.service.AwardService;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwardFinancialEngineTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private AwardService awardService;

    @Mock
    private MongoCollection<Document> awardsCollection;

    @Mock
    private AggregateIterable<Document> aggregateIterable;

    @Mock
    private FindIterable<Document> findIterable;

    private AwardFinancialEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AwardFinancialEngine(mongoTemplate, awardService);
    }

    @Test
    @DisplayName("getPrefixedAwardsStats builds aggregation pipeline with cost code prefix regex")
    void testPrefixedAwardsStatsPipeline() {
        when(mongoTemplate.getCollection("Awards")).thenReturn(awardsCollection);
        when(awardsCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.allowDiskUse(true)).thenReturn(aggregateIterable);
        doAnswer(invocation -> {
            List<Document> target = invocation.getArgument(0);
            target.add(new Document("testKey", "testVal"));
            return target;
        }).when(aggregateIterable).into(any());

        List<Document> results = engine.getPrefixedAwardsStats(CostCodePrefix.PK);

        assertThat(results).isNotEmpty();
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(awardsCollection).aggregate(captor.capture());

        List<Document> pipeline = captor.getValue();
        assertThat(pipeline).isNotEmpty();
        Document match = (Document) pipeline.get(0).get("$match");
        assertThat(match).isNotNull();
        Document budgetMatch = (Document) match.get("budgets.costCode");
        assertThat(budgetMatch.getString("$regex")).isEqualTo("^PK");
    }

    @Test
    @DisplayName("getPowerTable delegates to AwardService using PowerTableQueryCriteria")
    void testGetPowerTableDelegation() {
        PowerTableQueryCriteria criteria = new PowerTableQueryCriteria(2020, 2024, "awardDate", "uuid-test");
        List<Document> mockDocs = List.of(new Document("year", 2022));
        when(awardService.getPowerTable(2020, 2024, "awardDate", "uuid-test")).thenReturn(mockDocs);

        List<Document> result = engine.getPowerTable(criteria);

        assertThat(result).isSameAs(mockDocs);
        verify(awardService).getPowerTable(2020, 2024, "awardDate", "uuid-test");
    }

    @Test
    @DisplayName("getResignedAwards filters correctly with pagination and empty status")
    void testGetResignedAwardsFiltering() {
        when(mongoTemplate.getCollection("Awards")).thenReturn(awardsCollection);
        when(awardsCollection.find(any(Document.class))).thenReturn(findIterable);
        doAnswer(invocation -> {
            List<Document> target = invocation.getArgument(0);
            target.add(new Document("uuid", "award-1")
                    .append("status", new Document("reason", "Motiu personal").append("typeDiscriminator", "TerminatedAwardStatus")));
            return target;
        }).when(findIterable).into(any());

        ResignedAwardsQueryCriteria criteria = ResignedAwardsQueryCriteria.builder()
                .page(0)
                .size(10)
                .statusType("terminated")
                .reasonFilter("renuncia")
                .build();

        Map<String, Object> response = engine.getResignedAwards(criteria);

        assertThat(response).containsKey("content");
        assertThat(response).containsKey("totalElements");
        assertThat(response.get("totalElements")).isEqualTo(1L);
    }
}
