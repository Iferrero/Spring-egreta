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

    @Test
    public void testEuropeanProgramFilterAndBreakdown() {
        List<Map<String, Object>> europeanCalls = controller.statsByFundingOpportunity(false, "PJE,PEI");
        assertNotNull(europeanCalls);
        assertFalse(europeanCalls.isEmpty(), "Expected at least one PJE or PEI funding opportunity");

        boolean foundWithProgram = false;
        for (Map<String, Object> call : europeanCalls) {
            Object programCode = call.get("programCode");
            assertTrue("PJE".equals(programCode) || "PEI".equals(programCode),
                "Expected programCode to be PJE or PEI, got: " + programCode);
            assertTrue(call.containsKey("program"), "Expected program key to be present");
            assertTrue(call.containsKey("programPath"), "Expected programPath key to be present");
            if (call.get("program") != null) {
                foundWithProgram = true;
            }
            int acc = (int) call.get("accepted");
            int leader = (int) call.get("acceptedLeader");
            int partner = (int) call.get("acceptedPartner");
            assertTrue(leader >= 0 && partner >= 0 && (leader + partner) <= acc);
        }
        assertTrue(foundWithProgram, "Expected at least one call to have an extracted program keyword");

        String sampleFundingUuid = (String) europeanCalls.get(0).get("fundingUuid");
        int expectedSent = (int) europeanCalls.get(0).get("sent");

        Map<String, Object> breakdown = controller.breakdownByFundingOpportunity(sampleFundingUuid, false);
        assertNotNull(breakdown);
        assertEquals(sampleFundingUuid, breakdown.get("fundingUuid"));

        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) breakdown.get("total");
        assertNotNull(total);
        assertEquals(expectedSent, total.get("sent"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byAmbit = (List<Map<String, Object>>) breakdown.get("byAmbit");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDepartment = (List<Map<String, Object>>) breakdown.get("byDepartment");

        assertNotNull(byAmbit);
        assertNotNull(byDepartment);
        assertFalse(byAmbit.isEmpty());
        assertFalse(byDepartment.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> byGender = (Map<String, Object>) breakdown.get("byGender");
        assertNotNull(byGender, "Expected byGender map in breakdown");
        assertTrue(byGender.containsKey("female"));
        assertTrue(byGender.containsKey("male"));

        int ambitSum = byAmbit.stream().mapToInt(m -> (int) m.get("sent")).sum();
        int deptSum = byDepartment.stream().mapToInt(m -> (int) m.get("sent")).sum();
        assertEquals(expectedSent, ambitSum, "Sum of sent by ambit should match total sent");
        assertEquals(expectedSent, deptSum, "Sum of sent by department should match total sent");

        String sampleOrgUuid = (String) byDepartment.get(0).get("orgUuid");
        List<Map<String, Object>> ips = controller.departmentIpsByFundingOpportunity(sampleFundingUuid, sampleOrgUuid, false);
        assertNotNull(ips);
        assertFalse(ips.isEmpty(), "Expected at least one IP for selected department");
        for (Map<String, Object> ip : ips) {
            assertNotNull(ip.get("fullName"));
            assertNotNull(ip.get("gender"));
            assertTrue((int) ip.get("sent") >= 1);
        }
    }
}
