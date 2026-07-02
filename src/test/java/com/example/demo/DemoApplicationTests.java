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

    @Autowired
    private com.example.demo.controller.StudentThesisController studentThesisController;

    @Test
    void testFilteredTheses() {
        System.out.println("=== TESTING FILTERED THESES ===");
        try {
            var result = studentThesisController.listar("", "all", 2010, 2026, "all", 0, 10000);
            System.out.println("LISTAR OK, TOTAL: " + result.getTotalElements());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testFilteredThesesGenderMale() {
        System.out.println("=== TESTING FILTERED THESES GENDER MALE ===");
        try {
            var result = studentThesisController.listar("", "all", 2010, 2026, "male", 0, 10000);
            System.out.println("LISTAR MALE OK, TOTAL: " + result.getTotalElements());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testFilteredThesesGenderFemale() {
        System.out.println("=== TESTING FILTERED THESES GENDER FEMALE ===");
        try {
            var result = studentThesisController.listar("", "all", 2010, 2026, "female", 0, 10000);
            System.out.println("LISTAR FEMALE OK, TOTAL: " + result.getTotalElements());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testFilteredThesesGenderFemaleOnly() {
        System.out.println("=== TESTING FILTERED THESES GENDER FEMALE ONLY ===");
        try {
            var result = studentThesisController.listar("", "all", 2010, 2026, "femaleOnly", 0, 10000);
            System.out.println("LISTAR FEMALE ONLY OK, TOTAL: " + result.getTotalElements());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testFilteredThesesGenderMaleOnly() {
        System.out.println("=== TESTING FILTERED THESES GENDER MALE ONLY ===");
        try {
            var result = studentThesisController.listar("", "all", 2010, 2026, "maleOnly", 0, 10000);
            System.out.println("LISTAR MALE ONLY OK, TOTAL: " + result.getTotalElements());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testCorruptDocuments() {
        System.out.println("=== SCANNING FOR CORRUPT DOCUMENTS ===");
        
        // Scan StudentTheses
        int successTheses = 0;
        int failedTheses = 0;
        try (var cursor = mongoTemplate.getCollection("StudentTheses").find().iterator()) {
            while (true) {
                try {
                    if (!cursor.hasNext()) break;
                    Document doc = cursor.next();
                    successTheses++;
                } catch (Exception e) {
                    failedTheses++;
                    System.err.println("Failed reading document from StudentTheses: " + e.getMessage());
                    e.printStackTrace();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error scanning StudentTheses: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("StudentTheses scan finished. Success: " + successTheses + ", Failed: " + failedTheses);

        // Scan Awards
        int successAwards = 0;
        int failedAwards = 0;
        try (var cursor = mongoTemplate.getCollection("Awards").find().iterator()) {
            while (true) {
                try {
                    if (!cursor.hasNext()) break;
                    Document doc = cursor.next();
                    successAwards++;
                } catch (Exception e) {
                    failedAwards++;
                    System.err.println("Failed reading document from Awards: " + e.getMessage());
                    e.printStackTrace();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error scanning Awards: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Awards scan finished. Success: " + successAwards + ", Failed: " + failedAwards);

        // Scan Persons
        int successPersons = 0;
        int failedPersons = 0;
        try (var cursor = mongoTemplate.getCollection("Persons").find().iterator()) {
            while (true) {
                try {
                    if (!cursor.hasNext()) break;
                    Document doc = cursor.next();
                    successPersons++;
                } catch (Exception e) {
                    failedPersons++;
                    System.err.println("Failed reading document from Persons: " + e.getMessage());
                    e.printStackTrace();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error scanning Persons: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Persons scan finished. Success: " + successPersons + ", Failed: " + failedPersons);
    }
}













