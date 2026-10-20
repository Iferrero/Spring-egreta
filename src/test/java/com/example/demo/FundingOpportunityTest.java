package com.example.demo;

import com.example.demo.model.FundingOpportunity;
import com.example.demo.repository.FundingOpportunityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class FundingOpportunityTest {

    @Autowired
    private FundingOpportunityRepository repository;

    @Test
    public void testFundingOpportunityLocalizedTitleAndType() {
        FundingOpportunity fo = new FundingOpportunity();
        fo.setUuid("test-uuid-1234");
        fo.setTitle(Map.of("ca_ES", "Convocatòria Test CA", "es_ES", "Convocatoria Test ES"));
        fo.setType(Map.of("term", Map.of("ca_ES", "Tipus Test CA")));

        assertEquals("Convocatòria Test CA", fo.getFullTitle());
        assertEquals("Tipus Test CA", fo.getTypeName());
    }

    @Test
    public void testFundingOpportunityRepositorySearch() {
        Page<FundingOpportunity> results = repository.findByTitleValueContainingIgnoreCase("arqueol", PageRequest.of(0, 5));
        assertNotNull(results);
        assertTrue(results.getTotalElements() > 0, "Should find funding opportunities matching 'arqueol'");
        FundingOpportunity first = results.getContent().get(0);
        assertNotNull(first.getFullTitle());
        assertNotNull(first.getUuid());
    }
}
