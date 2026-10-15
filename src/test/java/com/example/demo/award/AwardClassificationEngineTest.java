package com.example.demo.award;

import com.example.demo.award.engine.AwardClassificationEngine;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwardClassificationEngineTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    @SuppressWarnings("unchecked")
    private MongoCollection<Document> mongoCollection;

    @Mock
    @SuppressWarnings("unchecked")
    private FindIterable<Document> findIterable;

    @Mock
    @SuppressWarnings("unchecked")
    private AggregateIterable<Document> aggregateIterable;

    private AwardClassificationEngine classificationEngine;

    @BeforeEach
    void setUp() {
        classificationEngine = new AwardClassificationEngine(mongoTemplate, "dummy-test-key");
    }

    @Test
    @DisplayName("findNatureByUriSimilarity matches known award nature URIs")
    void testFindNatureByUriSimilarity() {
        // When uri is null or empty, should return null
        assertThat(classificationEngine.findNatureByUriSimilarity(null)).isNull();
        assertThat(classificationEngine.findNatureByUriSimilarity("   ")).isNull();

        when(mongoTemplate.getCollection("Classificationschemes")).thenReturn(mongoCollection);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        Document schemeDoc = new Document("containedClassifications", List.of(
                new Document("uri", "/dk/atira/pure/upm/nature/ajuts_pre_i_postdoctorals")
                        .append("term", new Document("ca_ES", "Ajuts pre i postdoctorals"))
        ));
        when(findIterable.first()).thenReturn(schemeDoc);

        // When uri matches fellowship pattern
        Document match = classificationEngine.findNatureByUriSimilarity("https://example.com/dk/atira/pure/award/awardnature/ajuts_pre_i_postdoctorals");
        assertThat(match).isNotNull();
        assertThat(match.getString("uri")).contains("ajuts_pre_i_postdoctorals");
    }

    @Test
    @DisplayName("getNatureHierarchy handles empty aggregation results gracefully")
    void testGetNatureHierarchyEmpty() {
        when(mongoTemplate.getCollection("Awards")).thenReturn(mongoCollection);
        when(mongoCollection.aggregate(any())).thenReturn(aggregateIterable);

        List<Map<String, Object>> hierarchy = classificationEngine.getNatureHierarchy();
        assertThat(hierarchy).isNotNull();
    }
}
