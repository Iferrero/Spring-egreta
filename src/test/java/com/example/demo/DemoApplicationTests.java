package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import com.example.demo.controller.PersonaController;

import org.springframework.beans.factory.annotation.Autowired;
import com.example.demo.controller.AwardController;

@SpringBootTest
class DemoApplicationTests {

    @Autowired
    private AwardController awardController;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void testInspectAwardHolders() {
        System.out.println("=== INSPECTING AWARD HOLDERS ===");
        try {
            List<Document> docs = mongoTemplate.find(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("awardHolders").exists(true)
                ).limit(5), 
                Document.class, 
                "Awards"
            );
            for (Document doc : docs) {
                System.out.println("----------------------------------------");
                System.out.println("Award title: " + doc.get("title"));
                System.out.println("AwardHolders: " + doc.get("awardHolders"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testGetPowerTable() {
        System.out.println("=== TESTING POWERTABLE ===");
        try {
            var result = awardController.getPowerTable(2021, 2025, "awardDate", null);
            System.out.println("RESULT SIZE: " + result.size());
            if (!result.isEmpty()) {
                System.out.println("FIRST 5 RECORDS:");
                result.stream().limit(5).forEach(System.out::println);
                
                System.out.println("ALL UNIQUE CATEGORIES:");
                result.stream()
                      .map(d -> d.getString("categoria"))
                      .filter(Objects::nonNull)
                      .distinct()
                      .forEach(System.out::println);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}













