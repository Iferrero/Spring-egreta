package com.example.demo.egreta;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class AwardsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() {
        return "awards";
    }

    @Override
    protected int getPageSize() {
        return 500;
    }

    @Override
    protected int getMaxBufferSizeMB() {
        return 20;
    }

    @Override
    protected String getCollectionName() {
        return "Awards";
    }

    /**
     * Nº de hilos para descargar budgets en paralelo dentro de cada página.
     * Al ser peticiones ligeras (un solo award cada una) admiten más
     * concurrencia que getMaxWorkers(), pensado para descargar páginas
     * enteras del endpoint principal.
     */
    protected int getBudgetWorkers() {
        return Math.max(getMaxWorkers() * 4, 16);
    }

    private record BudgetFetchResult(String uuid, List<Map<String, Object>> budgets, String error) {}

    // ------------------------------------------------------------------
    // Paso 5 — descarga de budgets vía /awards/{uuid}/budgets
    // ------------------------------------------------------------------
    @Override
    protected List<Map<String, Object>> normalizeBatch(List<Map<String, Object>> items) {
        List<Map<String, Object>> docs = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            docs.add(new HashMap<>(normalize(item)));
        }

        WebClient client = buildClient();
        ExecutorService executor = Executors.newFixedThreadPool(getBudgetWorkers());
        Map<Future<BudgetFetchResult>, Integer> futures = new HashMap<>();

        try {
            for (int idx = 0; idx < docs.size(); idx++) {
                Object uuidObj = docs.get(idx).get("uuid");
                if (uuidObj == null) continue;
                String uuid = uuidObj.toString();
                futures.put(executor.submit(() -> fetchBudgetsForUuid(client, uuid)), idx);
            }

            for (Map.Entry<Future<BudgetFetchResult>, Integer> entry : futures.entrySet()) {
                int idx = entry.getValue();
                List<Map<String, Object>> budgets;
                try {
                    BudgetFetchResult result = entry.getKey().get();
                    if (result.error() != null) {
                        logger.error("[Awards] normalizeBatch: error budgets uuid={}: {}",
                                result.uuid(), result.error());
                        budgets = new ArrayList<>();
                    } else if (result.budgets() == null) {
                        budgets = new ArrayList<>();
                    } else {
                        budgets = result.budgets();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("[Awards] normalizeBatch: excepción esperando budgets (idx={}): {}",
                            idx, e.getMessage(), e);
                    budgets = new ArrayList<>();
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
                docs.get(idx).put("budgets", budgets);
            }
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // Awards sin uuid (no debería ocurrir, pero por seguridad)
        for (Map<String, Object> doc : docs) {
            doc.putIfAbsent("budgets", new ArrayList<>());
        }

        return docs;
    }

    /**
     * Descarga los budgets de un award concreto.
     * budgets es null si hubo error o el endpoint devolvió 404 (award sin budgets).
     */
    private BudgetFetchResult fetchBudgetsForUuid(WebClient client, String uuid) {
        String url = getApiBaseUrl() + "/awards/" + uuid + "/budgets";
        try {
            ResponseEntity<Map> response = client.get()
                    .uri(url)
                    .header("api-key", getApiKey())
                    .retrieve()
                    .toEntity(Map.class)
                    .block();

            if (response == null) {
                return new BudgetFetchResult(uuid, null, "Respuesta nula");
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                return new BudgetFetchResult(uuid, null, "HTTP " + response.getStatusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            if (body == null) {
                return new BudgetFetchResult(uuid, null, "JSON inválido o respuesta vacía");
            }

            // La respuesta sigue el formato paginado habitual de la API
            // ({"count": N, "items": [...]}). El campo 'budgets' real está
            // dentro de cada elemento de 'items', no en la raíz.
            Object itemsObj = body.containsKey(getItemsField()) ? body.get(getItemsField()) : body;
            List<?> rawItems;
            if (itemsObj instanceof List<?> list) {
                rawItems = list;
            } else {
                rawItems = List.of(itemsObj);
            }

            List<Map<String, Object>> budgets = new ArrayList<>();
            for (Object rawItem : rawItems) {
                if (rawItem instanceof Map<?, ?> map) {
                    Object b = map.get("budgets");
                    if (b instanceof List<?> budgetList) {
                        for (Object budget : budgetList) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> budgetMap = (Map<String, Object>) budget;
                            budgets.add(budgetMap);
                        }
                    }
                }
            }
            return new BudgetFetchResult(uuid, budgets, null);
        } catch (WebClientResponseException.NotFound e) {
            return new BudgetFetchResult(uuid, null, null);
        } catch (WebClientResponseException e) {
            String bodySnippet = e.getResponseBodyAsString();
            if (bodySnippet != null && bodySnippet.length() > 200) {
                bodySnippet = bodySnippet.substring(0, 200);
            }
            return new BudgetFetchResult(uuid, null, "HTTP " + e.getStatusCode() + ": " + bodySnippet);
        } catch (Exception e) {
            return new BudgetFetchResult(uuid, null, e.getMessage());
        }
    }

    @Override
    protected void postProcess() {
        var collection = mongoTemplate.getCollection(getCollectionName());
        logger.info("[Awards] Iniciando post-procesado optimizado con Bulk Writes...");

        List<com.mongodb.client.model.WriteModel<org.bson.Document>> bulkWrites = new ArrayList<>();
        int dateErrors = 0;
        int floatErrors = 0;

        for (var doc : collection.find()) {
            org.bson.Document setUpdates = new org.bson.Document();

            // --- PASO 1: nuevoUuid ---
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
            if (nuevoUuid != null) {
                setUpdates.append("nuevoUuid", nuevoUuid);
            }

            // --- PASO 3: Conversión de fechas ---
            Object awardDateObj = doc.get("awardDate");
            java.util.Date awardDate = null;
            try {
                if (awardDateObj instanceof String s) {
                    awardDate = java.sql.Date.valueOf(s);
                } else if (awardDateObj instanceof java.util.Date d) {
                    awardDate = d;
                }
            } catch (Exception e) {
                dateErrors++;
            }
            if (awardDate != null) {
                setUpdates.append("awardDate", awardDate);
            }
            try {
                var actualPeriod = (org.bson.Document) doc.get("actualPeriod");
                if (actualPeriod != null) {
                    String start = actualPeriod.getString("startDate");
                    String end = actualPeriod.getString("endDate");
                    if (start != null) setUpdates.append("actualPeriod.startDate", java.sql.Date.valueOf(start));
                    if (end != null) setUpdates.append("actualPeriod.endDate", java.sql.Date.valueOf(end));
                }
            } catch (Exception ignored) {}

            // --- PASO 4: institutionalPart.value string -> float ---
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
                                setUpdates.append("fundings.0.fundingCollaborators.0.institutionalPart.value", f);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                floatErrors++;
            }

            if (!setUpdates.isEmpty()) {
                bulkWrites.add(new com.mongodb.client.model.UpdateOneModel<>(
                    new org.bson.Document("_id", doc.get("_id")),
                    new org.bson.Document("$set", setUpdates)
                ));
            }
        }

        // Ejecutar Bulk Writes en lotes de 1000
        if (!bulkWrites.isEmpty()) {
            logger.info("[Awards] Ejecutando {} actualizaciones en lote...", bulkWrites.size());
            int batchSize = 1000;
            for (int i = 0; i < bulkWrites.size(); i += batchSize) {
                List<com.mongodb.client.model.WriteModel<org.bson.Document>> subList =
                    bulkWrites.subList(i, Math.min(i + batchSize, bulkWrites.size()));
                try {
                    collection.bulkWrite(subList, new com.mongodb.client.model.BulkWriteOptions().ordered(false));
                } catch (Exception e) {
                    logger.error("[Awards] Error ejecutando lote de actualización: {}", e.getMessage(), e);
                }
            }
        }

        if (dateErrors > 0) logger.warn("[Awards] Paso 3: {} documentos con errores de fecha", dateErrors);
        if (floatErrors > 0) logger.warn("[Awards] Paso 4: {} documentos con errores en conversión de institutionalPart", floatErrors);

        // Paso 2: enriquecimiento de tipo y categoria
        logger.info("[Awards] Paso 2: enriqueciendo type.term y categoria...");
        for (var enrich : AwardTypeEnrichment.values()) {
            var filter = new org.bson.Document("type.uri", enrich.uri);
            var set = new org.bson.Document();
            if (enrich.ca != null) set.append("type.term.ca_ES", enrich.ca);
            if (enrich.es != null) set.append("type.term.es_ES", enrich.es);
            if (enrich.en != null) set.append("type.term.en_GB", enrich.en);
            if (enrich.categoria != null) set.append("categoria", enrich.categoria);
            try {
                collection.updateMany(filter, new org.bson.Document("$set", set));
            } catch (Exception e) {
                logger.error("[Awards] Error en enriquecimiento para uri {}: {}", enrich.uri, e.getMessage());
            }
        }
        logger.info("[Awards] Post-procesado completado");
    }


    @Override
    protected void rebuildIndexes() {
        super.rebuildIndexes();
        try {
            var col = mongoTemplate.getCollection(getCollectionName());
            col.createIndex(new org.bson.Document("workflow.step", 1).append("managingOrganization.uuid", 1));
            col.createIndex(new org.bson.Document("fundings.fundingCollaborators.collaborator.uuid", 1));
            col.createIndex(new org.bson.Document("workflow.step", 1).append("awardDate.year", 1));
            logger.info("[{}] Rebuilt custom indexes", getCollectionName());
        } catch (Exception e) {
            throw new RuntimeException("Error rebuilding custom indexes for '" + getCollectionName() + "': " + e.getMessage(), e);
        }
    }
}
