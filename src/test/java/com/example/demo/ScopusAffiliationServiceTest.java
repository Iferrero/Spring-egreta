package com.example.demo;

import com.example.demo.service.ScopusAffiliationService;
import com.example.demo.service.ScopusAffiliationService.ScopusAffiliationInfo;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ScopusAffiliationServiceTest {

    @Autowired(required = false)
    private ScopusAffiliationService scopusAffiliationService;

    @Test
    public void testServiceBeanExists() {
        assertNotNull(scopusAffiliationService, "ScopusAffiliationService bean should be loaded");
    }

    @Test
    public void testParseScopusEntry() {
        String sampleJson = """
            {
                "dc:identifier": "AFFILIATION_ID:60012345",
                "affiliation-name": "Universitat Autònoma de Barcelona",
                "city": "Bellaterra (Cerdanyola del Vallès)",
                "country": "Spain",
                "document-count": "54321",
                "prism:url": "https://api.elsevier.com/content/affiliation/affiliation_id/60012345"
            }
        """;
        Document doc = Document.parse(sampleJson);
        ScopusAffiliationInfo info = scopusAffiliationService.parseAffiliationEntry(doc);

        assertNotNull(info);
        assertEquals("60012345", info.afid());
        assertEquals("Universitat Autònoma de Barcelona", info.affiliationName());
        assertEquals("Bellaterra (Cerdanyola del Vallès)", info.city());
        assertEquals("Spain", info.country());
        assertEquals(54321L, info.documentCount());
        assertTrue(info.scopusUrl().contains("60012345"));
    }
}
