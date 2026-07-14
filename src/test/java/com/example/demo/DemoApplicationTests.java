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

    @Test
    void testPrintVigentesCategorias() {
        System.out.println("=== PRINTING VIGENTES CATEGORIAS ===");
        try {
            List<Document> result = mongoTemplate.aggregate(
                org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                    org.springframework.data.mongodb.core.aggregation.Aggregation.unwind("staffOrganizationAssociations"),
                    org.springframework.data.mongodb.core.aggregation.Aggregation.group("staffOrganizationAssociations.employmentType.term.ca_ES")
                        .addToSet("uuid").as("set"),
                    org.springframework.data.mongodb.core.aggregation.Aggregation.project("_id").and("set").size().as("total")
                ),
                "Persons",
                Document.class
            ).getMappedResults();
            for (Document doc : result) {
                System.out.println("Category: " + doc.get("_id") + " -> Count: " + doc.get("total"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private PersonaController personaController;

    @Test
    void testPrintIcreaControllerStats() {
        System.out.println("=== PRINTING CONTROLLER ICREA STATS ===");
        try {
            System.out.println("CONTROLLER_ACTUAL: " + personaController.getIcreaStats("all", null, null, null));
            System.out.println("CONTROLLER_2026: " + personaController.getIcreaStats("all", null, 2026, null));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testPrintIcreas2026() {
        System.out.println("=== PRINTING ICREAS 2026 ===");
        try {
            List<Document> result = mongoTemplate.aggregate(
                org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                    org.springframework.data.mongodb.core.aggregation.Aggregation.unwind("visitingScholarOrganizationAssociations"),
                    org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                            org.springframework.data.mongodb.core.query.Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.ca_ES").regex("icrea", "i"),
                            org.springframework.data.mongodb.core.query.Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.es_ES").regex("icrea", "i"),
                            org.springframework.data.mongodb.core.query.Criteria.where("visitingScholarOrganizationAssociations.jobTitle.term.en_GB").regex("icrea", "i")
                        )
                    ),
                    ctx -> new Document("$match", new Document("$and", Arrays.asList(
                        new Document("$or", Arrays.asList(
                            new Document("visitingScholarOrganizationAssociations.period.startDate", (Object) null),
                            new Document("visitingScholarOrganizationAssociations.period.startDate", new Document("$exists", false)),
                            new Document("$expr", new Document("$lte", Arrays.asList(
                                new Document("$toDate", "$visitingScholarOrganizationAssociations.period.startDate"),
                                new Document("$toDate", "2026-12-31T23:59:59Z")
                            )))
                        )),
                        new Document("$or", Arrays.asList(
                            new Document("visitingScholarOrganizationAssociations.period.endDate", (Object) null),
                            new Document("visitingScholarOrganizationAssociations.period.endDate", new Document("$exists", false)),
                            new Document("visitingScholarOrganizationAssociations.period", new Document("$exists", false)),
                            new Document("$expr", new Document("$gte", Arrays.asList(
                                new Document("$toDate", "$visitingScholarOrganizationAssociations.period.endDate"),
                                new Document("$toDate", "2026-01-01T00:00:00Z")
                            )))
                        ))
                    )))
                ),
                "Persons",
                Document.class
            ).getMappedResults();
            
            System.out.println("TOTAL ICREA 2026 RETRIEVED: " + result.size());
            for (Document doc : result) {
                Document assoc = (Document) doc.get("visitingScholarOrganizationAssociations");
                Document period = (Document) assoc.get("period");
                String start = period != null ? period.getString("startDate") : "N/A";
                String end = period != null ? period.getString("endDate") : "N/A";
                Object nameObj = doc.get("name");
                Object namesObj = doc.get("names");
                System.out.println("ICREA_INFO: Name: " + nameObj + " | Names: " + namesObj + " | UUID: " + doc.get("uuid") + " | EndDate: " + end + " | StartDate: " + start);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}













