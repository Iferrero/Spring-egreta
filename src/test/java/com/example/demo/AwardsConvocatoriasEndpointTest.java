package com.example.demo;

import com.example.demo.controller.AwardController;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class AwardsConvocatoriasEndpointTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private AwardController awardController;

    @Test
    public void testIndexCreationAndEndpointPerformance() {
        System.out.println("--- Creating indexes ---");
        mongoTemplate.getCollection("Applications").createIndex(new Document("uuid", 1));
        mongoTemplate.getCollection("Awards").createIndex(new Document("uuid", 1));
        mongoTemplate.getCollection("Awards").createIndex(new Document("categoria", 1));
        mongoTemplate.getCollection("Awards").createIndex(new Document("applications.uuid", 1));
        mongoTemplate.getCollection("Awards").createIndex(new Document("awardDate", -1));
        mongoTemplate.getCollection("Awards").createIndex(new Document("workflow.step", 1));
        mongoTemplate.getCollection("Awards").createIndex(new Document("natureTypes.0", 1));

        System.out.println("Indexes created. Now testing getDetailedList...");
        long start = System.currentTimeMillis();

        Document matchCompetitive = new Document("$and", java.util.Arrays.asList(
            new Document("$or", java.util.Arrays.asList(
                new Document("type.term.ca_ES", "Conveni extern a la UAB").append("workflow.step", "approved"),
                new Document("type.term.ca_ES", new Document("$ne", "Conveni extern a la UAB")).append("workflow.step", "validated")
            )),
            new Document("categoria", new Document("$regex", "^Ajudes competitives"))
        ));
        long countFast = mongoTemplate.getCollection("Awards").countDocuments(matchCompetitive);
        System.out.println("Fast countCompetitive: " + countFast + " in " + (System.currentTimeMillis() - start) + "ms");

        start = System.currentTimeMillis();
        Map<String, Object> yesStats = awardController.getDetailedList(0, 1, null, "yes", "all", null, "all");
        System.out.println("yesStats: " + yesStats.get("totalElements") + " in " + (System.currentTimeMillis() - start) + "ms");

        long diffNoStats = countFast - ((Number) yesStats.get("totalElements")).longValue();
        System.out.println("Calculated noStats: " + diffNoStats);

        start = System.currentTimeMillis();
        Map<String, Object> mismatchStats = awardController.getDetailedList(0, 1, null, "all", "all", null, "mismatch");
        System.out.println("mismatchStats: " + mismatchStats.get("totalElements") + " in " + (System.currentTimeMillis() - start) + "ms");

        start = System.currentTimeMillis();
        // Test deferred lookup pipeline for page 0
        List<Document> deferredDataPipeline = new ArrayList<>();
        deferredDataPipeline.add(new Document("$match", matchCompetitive));
        deferredDataPipeline.add(new Document("$sort", new Document("awardDate", -1).append("uuid", 1)));
        deferredDataPipeline.add(new Document("$skip", 0));
        deferredDataPipeline.add(new Document("$limit", 15));
        deferredDataPipeline.add(new Document("$lookup", new Document()
            .append("from", "Applications")
            .append("localField", "applications.uuid")
            .append("foreignField", "uuid")
            .append("as", "appDocs")));
        deferredDataPipeline.add(new Document("$unwind", new Document("path", "$appDocs").append("preserveNullAndEmptyArrays", true)));
        deferredDataPipeline.add(new Document("$lookup", new Document()
            .append("from", "FundingOpportunities")
            .append("localField", "appDocs.fundingOpportunity.uuid")
            .append("foreignField", "uuid")
            .append("as", "fOpp")));
        deferredDataPipeline.add(new Document("$unwind", new Document("path", "$fOpp").append("preserveNullAndEmptyArrays", true)));
        deferredDataPipeline.add(new Document("$project", new Document()
            .append("uuid", 1).append("pureId", 1).append("title", 1).append("awardDate", 1)
            .append("type", 1).append("natureTypes", 1).append("identifiers", 1)
            .append("awardHolders", 1).append("managingOrganization", 1)
            .append("fOppUuid", "$fOpp.uuid").append("fOppTitle", "$fOpp.title").append("fOppType", "$fOpp.type")));
        List<Document> deferredResults = new ArrayList<>();
        mongoTemplate.getCollection("Awards").aggregate(deferredDataPipeline).into(deferredResults);
        System.out.println("Deferred page0 size: " + deferredResults.size() + " in " + (System.currentTimeMillis() - start) + "ms");

        start = System.currentTimeMillis();
        List<Map<String, Object>> proposals = awardController.getProposedRelations();
        System.out.println("proposals count: " + (proposals != null ? proposals.size() : "null") + " in " + (System.currentTimeMillis() - start) + "ms");
    }
}
