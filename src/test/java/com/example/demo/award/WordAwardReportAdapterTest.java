package com.example.demo.award;

import com.example.demo.award.adapter.WordAwardReportAdapter;
import com.example.demo.award.model.AwardCountryReportCriteria;
import com.example.demo.award.port.AwardReportPort;
import com.example.demo.service.ResearchOutputJournalLinkService;
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

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WordAwardReportAdapterTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ResearchOutputJournalLinkService researchOutputService;

    @Mock
    @SuppressWarnings("unchecked")
    private MongoCollection<Document> mongoCollection;

    @Mock
    @SuppressWarnings("unchecked")
    private FindIterable<Document> findIterable;

    private AwardReportPort reportPort;

    @BeforeEach
    void setUp() {
        reportPort = new WordAwardReportAdapter(mongoTemplate, researchOutputService);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when countryCode is null or empty")
    void testInvalidCriteria() {
        assertThatThrownBy(() -> new AwardCountryReportCriteria(null, "2020-01-01", null, true, false, false, false, false, false, false, false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AwardCountryReportCriteria("   ", "2020-01-01", null, true, false, false, false, false, false, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should generate valid DOCX zip bytes with magic header PK (0x50, 0x4B)")
    void testGenerateCountryWordReport() throws Exception {
        when(mongoTemplate.getCollection(anyString())).thenReturn(mongoCollection);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.projection(any(Document.class))).thenReturn(findIterable);
        when(findIterable.limit(any(Integer.class))).thenReturn(findIterable);

        AwardCountryReportCriteria criteria = AwardCountryReportCriteria.ofDefault("ES");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        reportPort.generateCountryWordReport(criteria, baos);

        byte[] bytes = baos.toByteArray();
        assertThat(bytes).isNotEmpty();
        // Standard Zip / DOCX magic bytes: 0x50 0x4B (PK)
        assertThat(bytes[0]).isEqualTo((byte) 0x50);
        assertThat(bytes[1]).isEqualTo((byte) 0x4B);
    }
}
