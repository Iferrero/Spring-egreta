package com.example.demo;

import com.example.demo.controller.ApplicationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ApplicationControllerTest {

    @Autowired
    private ApplicationController controller;

    @Test
    public void testStatsByFundingOpportunityFiltersResigned() {
        // Fetch stats without excluding resigned awards
        List<Map<String, Object>> statsNormal = controller.statsByFundingOpportunity(false);
        assertNotNull(statsNormal);
        
        // Fetch stats excluding resigned awards
        List<Map<String, Object>> statsExcluded = controller.statsByFundingOpportunity(true);
        assertNotNull(statsExcluded);

        int totalSentNormal = statsNormal.stream().mapToInt(m -> (int) m.get("sent")).sum();
        int totalSentExcluded = statsExcluded.stream().mapToInt(m -> (int) m.get("sent")).sum();
        int totalResignedNormal = statsNormal.stream().mapToInt(m -> (int) m.get("resigned")).sum();

        System.out.println("TOTAL SENT NORMAL: " + totalSentNormal);
        System.out.println("TOTAL SENT EXCLUDED: " + totalSentExcluded);
        System.out.println("TOTAL RESIGNED NORMAL: " + totalResignedNormal);
        System.out.println("DIFFERENCE: " + (totalSentNormal - totalSentExcluded));

        // The excluded count must be strictly less than the normal count if there are resigned awards
        assertTrue(totalSentExcluded < totalSentNormal, "Excluded count should be less than normal count due to resigned awards");
        assertEquals(totalSentNormal - totalSentExcluded, totalResignedNormal, "Total resigned count should match difference between normal and excluded counts");
    }
}
