package com.example.demo.controller;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/api/mongo")
public class MongoSchemaController {

    private final MongoTemplate mongoTemplate;

    public MongoSchemaController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostMapping("/run")
    public Map<String, Object> runOperation(@RequestBody Map<String, Object> request) {
        String collection = Objects.toString(request.get("collection"), "").trim();
        String operation = Objects.toString(request.get("operation"), "").trim();

        if (collection.isEmpty()) {
            return Map.of("error", "collection is required");
        }

        if (!"getSchema".equalsIgnoreCase(operation)) {
            return Map.of("error", "unsupported operation", "supported", List.of("getSchema"));
        }

        return getSchema(collection);
    }

    @GetMapping("/schema")
    public Map<String, Object> getSchemaByCollection(@RequestParam String collection) {
        String collectionName = Objects.toString(collection, "").trim();
        if (collectionName.isEmpty()) {
            return Map.of("error", "collection is required");
        }
        return getSchema(collectionName);
    }

    private Map<String, Object> getSchema(String collectionName) {
        MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
        List<Document> sampleDocs = collection.find().limit(300).into(new ArrayList<>());

        SchemaNode root = new SchemaNode();
        root.types.add("object");

        for (Document doc : sampleDocs) {
            if (doc == null) continue;
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                if ("_id".equals(entry.getKey())) continue;
                SchemaNode fieldNode = root.properties.computeIfAbsent(entry.getKey(), k -> new SchemaNode());
                inferIntoNode(fieldNode, entry.getValue());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("collection", collectionName);
        response.put("operation", "getSchema");
        response.put("sampleSize", sampleDocs.size());
        response.put("schema", nodeToMap(root, true));
        return response;
    }

    private void inferIntoNode(SchemaNode node, Object value) {
        if (value == null) {
            node.types.add("null");
            return;
        }

        if (value instanceof List<?> list) {
            node.types.add("array");
            for (Object item : list) {
                inferIntoNode(node.items, item);
            }
            return;
        }

        if (value instanceof Document doc) {
            node.types.add("object");
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                SchemaNode child = node.properties.computeIfAbsent(entry.getKey(), k -> new SchemaNode());
                inferIntoNode(child, entry.getValue());
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            node.types.add("object");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                SchemaNode child = node.properties.computeIfAbsent(key, k -> new SchemaNode());
                inferIntoNode(child, entry.getValue());
            }
            return;
        }

        if (value instanceof String) {
            node.types.add("string");
        } else if (value instanceof Integer || value instanceof Long) {
            node.types.add("integer");
        } else if (value instanceof Float || value instanceof Double) {
            node.types.add("number");
        } else if (value instanceof Boolean) {
            node.types.add("boolean");
        } else if (value instanceof java.util.Date || value instanceof java.time.temporal.Temporal) {
            node.types.add("date");
        } else {
            node.types.add(value.getClass().getSimpleName().toLowerCase());
        }
    }

    private Map<String, Object> nodeToMap(SchemaNode node, boolean includePropertiesRoot) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!node.types.isEmpty()) {
            map.put("types", new ArrayList<>(node.types));
        }

        if (includePropertiesRoot || !node.properties.isEmpty()) {
            if (!node.properties.isEmpty()) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (Map.Entry<String, SchemaNode> entry : node.properties.entrySet()) {
                    props.put(entry.getKey(), nodeToMap(entry.getValue(), false));
                }
                map.put("properties", props);
            }
        }

        if (!node.items.types.isEmpty() || !node.items.properties.isEmpty()) {
            map.put("items", nodeToMap(node.items, false));
        }

        return map;
    }

    private static final class SchemaNode {
        private final Set<String> types = new LinkedHashSet<>();
        private final Map<String, SchemaNode> properties = new LinkedHashMap<>();
        private final SchemaNode items = new SchemaNode(true);

        private SchemaNode() {
        }

        private SchemaNode(boolean inner) {
        }
    }
}
