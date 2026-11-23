package com.example.demo.plannacional.controller;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.conversions.Bson;

/**
 * Llista de convocatòries del Pla Nacional (FundingOpportunities d'Egreta de tipus PJN) amb les seves dates, perquè el validador
 * del Pla Nacional pugui emprar-les com a data de referència.
 */
@RestController
@RequestMapping("/api/plan-nacional/convocatories")
public class PlanNacionalCallsController {

    private static final int FETCH_LIMIT = 3000;
    private static final List<String> COLLECTION_CANDIDATES = List.of(
            "FundingOpportunities", "Fundingopportunities", "fundingOpportunities", "fundingopportunities");

    public record CallOption(String uuid, String title, LocalDate openingDate, LocalDate deadline, LocalDate referenceDate) {}

    private final MongoTemplate mongoTemplate;

    @Autowired
    public PlanNacionalCallsController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public List<CallOption> list(
            @RequestParam(defaultValue = "") String buscar,
            @RequestParam(defaultValue = "60") int limit) {
        String collection = COLLECTION_CANDIDATES.stream()
                .filter(mongoTemplate::collectionExists)
                .findFirst().orElse(null);
        if (collection == null) {
            return List.of();
        }

        List<Bson> and = new ArrayList<>();
        if (buscar != null && !buscar.isBlank()) {
            String rx = Pattern.quote(buscar.trim());
            and.add(Filters.or(
                    Filters.regex("title.ca_ES", rx, "i"),
                    Filters.regex("title.es_ES", rx, "i"),
                    Filters.regex("title.en_GB", rx, "i"),
                    Filters.regex("title.value", rx, "i"),
                    Filters.regex("title.text.value", rx, "i"),
                    Filters.regex("uuid", rx, "i")));
        }
        and.add(Filters.or(Filters.exists("openingDate"), Filters.exists("deadline")));
        // Només convocatòries del Pla Nacional: tipus Egreta «PJN_Projectes Nacionals»
        and.add(Filters.or(
                Filters.regex("type.uri", "/pro/pjn$", "i"),
                Filters.regex("type.term.ca_ES", "^PJN", "i")));

        List<CallOption> out = new ArrayList<>();
        mongoTemplate.getCollection(collection)
                .find(Filters.and(and))
                .projection(Projections.include("uuid", "title", "openingDate", "deadline"))
                .limit(FETCH_LIMIT)
                .forEach(doc -> {
                    LocalDate opening = parseDate(doc.get("openingDate"));
                    LocalDate deadline = parseDate(doc.get("deadline"));
                    LocalDate reference = deadline != null ? deadline : opening;
                    if (reference == null) {
                        return;
                    }
                    out.add(new CallOption(doc.getString("uuid"), resolveTitle(doc.get("title"), doc.getString("uuid")),
                            opening, deadline, reference));
                });

        out.sort(Comparator.comparing(CallOption::referenceDate).reversed());
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return out.size() > safeLimit ? out.subList(0, safeLimit) : out;
    }

    static LocalDate parseDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof Date d) {
            return Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (raw instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (raw instanceof Document d && d.get("year") instanceof Number y) {
            Number m = d.get("month") instanceof Number mm ? mm : 1;
            Number day = d.get("day") instanceof Number dd ? dd : 1;
            try {
                return LocalDate.of(y.intValue(), m.intValue(), day.intValue());
            } catch (Exception e) {
                return null;
            }
        }
        if (raw instanceof String s && s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static String resolveTitle(Object title, String fallback) {
        String resolved = extractText(title);
        return resolved != null ? resolved : (fallback != null ? fallback : "Sense títol");
    }

    private static String extractText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s.trim();
        }
        if (value instanceof List<?> list) {
            for (String locale : List.of("ca_ES", "es_ES", "en_GB")) {
                for (Object item : list) {
                    if (item instanceof Document d && locale.equals(d.getString("locale"))) {
                        String v = extractText(d.get("value"));
                        if (v != null) {
                            return v;
                        }
                    }
                }
            }
            for (Object item : list) {
                String v = extractText(item);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
        if (value instanceof Document d) {
            for (String key : List.of("ca_ES", "es_ES", "en_GB", "value", "text")) {
                String v = extractText(d.get(key));
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }
}
