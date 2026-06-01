package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class AwardsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() {
        return "awards";
    }

    @Override
    protected String getCollectionName() {
        return "Awards";
    }

    @Override
    protected void postProcess() {
        // Paso 1: nuevoUuid
        var collection = mongoTemplate.getCollection(getCollectionName());
        logger.info("[Awards] Paso 1: calculando nuevoUuid...");
        for (var doc : collection.find()) {
            String managing = null;
            try {
                var managingOrg = (org.bson.Document) doc.get("managingOrganization");
                if (managingOrg != null) managing = managingOrg.getString("uuid");
            } catch (Exception ignored) {}
            String nuevoUuid = null;
            try {
                var coManaging = (java.util.List<?>) doc.get("coManagingOrganizations");
                if (coManaging != null && !coManaging.isEmpty()) {
                    var first = (org.bson.Document) coManaging.get(0);
                    nuevoUuid = first.getString("uuid");
                } else {
                    nuevoUuid = managing;
                }
            } catch (Exception e) {
                nuevoUuid = managing;
            }
            collection.updateOne(new org.bson.Document("_id", doc.get("_id")),
                new org.bson.Document("$set", new org.bson.Document("nuevoUuid", nuevoUuid)));
        }

        // Paso 2: enriquecimiento de tipo y categoria
        logger.info("[Awards] Paso 2: enriqueciendo type.term y categoria...");
        for (var enrich : AwardTypeEnrichment.values()) {
            var filter = new org.bson.Document("type.uri", enrich.uri);
            var set = new org.bson.Document();
            if (enrich.ca != null) set.append("type.term.ca_ES", enrich.ca);
            if (enrich.es != null) set.append("type.term.es_ES", enrich.es);
            if (enrich.en != null) set.append("type.term.en_GB", enrich.en);
            if (enrich.categoria != null) set.append("categoria", enrich.categoria);
            collection.updateMany(filter, new org.bson.Document("$set", set));
        }

        // Paso 3: conversión de fechas
        logger.info("[Awards] Paso 3: convirtiendo fechas...");
        int errors = 0;
        for (var doc : collection.find()) {
            Object awardDateObj = doc.get("awardDate");
            java.util.Date awardDate = null;
            try {
                if (awardDateObj instanceof String s) {
                    awardDate = java.sql.Date.valueOf(s);
                }
            } catch (Exception e) { errors++; continue; }
            org.bson.Document update = new org.bson.Document();
            if (awardDate != null) update.append("awardDate", awardDate);
            try {
                var actualPeriod = (org.bson.Document) doc.get("actualPeriod");
                if (actualPeriod != null) {
                    String start = actualPeriod.getString("startDate");
                    String end = actualPeriod.getString("endDate");
                    if (start != null) update.append("actualPeriod.startDate", java.sql.Date.valueOf(start));
                    if (end != null) update.append("actualPeriod.endDate", java.sql.Date.valueOf(end));
                }
            } catch (Exception ignored) {}
            if (!update.isEmpty())
                collection.updateOne(new org.bson.Document("_id", doc.get("_id")), new org.bson.Document("$set", update));
        }
        if (errors > 0) logger.warn("[Awards] Paso 3: {} documentos sin awardDate válido", errors);

        // Paso 4: institutionalPart.value string → float
        logger.info("[Awards] Paso 4: convirtiendo institutionalPart.value a float...");
        int errors4 = 0;
        for (var doc : collection.find()) {
            try {
                var fundings = (java.util.List<?>) doc.get("fundings");
                if (fundings != null && !fundings.isEmpty()) {
                    var funding = (org.bson.Document) fundings.get(0);
                    var collaborators = (java.util.List<?>) funding.get("fundingCollaborators");
                    if (collaborators != null && !collaborators.isEmpty()) {
                        var collab = (org.bson.Document) collaborators.get(0);
                        var instPart = (org.bson.Document) collab.get("institutionalPart");
                        if (instPart != null) {
                            Object value = instPart.get("value");
                            if (value instanceof String s) {
                                double f = Double.parseDouble(s.replace(",", "."));
                                collection.updateOne(
                                    new org.bson.Document("_id", doc.get("_id")),
                                    new org.bson.Document("$set", new org.bson.Document("fundings.0.fundingCollaborators.0.institutionalPart.value", f))
                                );
                            }
                        }
                    }
                }
            } catch (Exception e) { errors4++; }
        }
        if (errors4 > 0) logger.warn("[Awards] Paso 4: {} documentos no convertidos", errors4);
        logger.info("[Awards] Post-procesado completado");
    }
}
