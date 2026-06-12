package com.example.demo.service;

import com.example.demo.util.MongoPipelineBuilder;
import com.mongodb.client.MongoCollection;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AwardService {

    private final MongoTemplate mongoTemplate;

    // Cache: orgUuid -> year -> (contractId -> importReal)
    // Populated once at startup from the Excel file.
    private Map<String, Map<Integer, Map<String, Double>>> excelCache = Map.of();

    // Cache: orgUuid -> year -> (contractId -> tipus)
    private Map<String, Map<Integer, Map<String, String>>> excelTipusCache = Map.of();

    // Cache: orgUuid -> year -> (contractId -> titol col17)
    private Map<String, Map<Integer, Map<String, String>>> excelTitolCache = Map.of();

    // Cache: orgUuid -> year -> (contractId -> personaUuid col10)
    private Map<String, Map<Integer, Map<String, String>>> excelPersonaCache = Map.of();

    @Autowired
    public AwardService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    private void loadExcelCache() {
        try {
            ClassPathResource resource = new ClassPathResource(EXCEL_PRESTACIO_PATH);
            if (!resource.exists()) return;
            try (InputStream is = resource.getInputStream();
                 Workbook workbook = WorkbookFactory.create(is)) {
                Sheet sheet = workbook.getSheetAt(0);
                Map<String, Map<Integer, Map<String, Double>>> cache = new LinkedHashMap<>();
                Map<String, Map<Integer, Map<String, String>>> tipusCache = new LinkedHashMap<>();
                Map<String, Map<Integer, Map<String, String>>> titolCache = new LinkedHashMap<>();
                Map<String, Map<Integer, Map<String, String>>> personaCache = new LinkedHashMap<>();
                for (int rowIdx = EXCEL_DATA_START_ROW; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                    Row row = sheet.getRow(rowIdx);
                    if (row == null) continue;
                    String tipus = getCellStringValue(row.getCell(5));
                    if (!"Prestació de Serveis".equals(tipus)) continue;
                    String unitUuid = getCellStringValue(row.getCell(3));
                    if (unitUuid == null || unitUuid.isBlank()) continue;
                    Integer year = getCellIntValue(row.getCell(1));
                    if (year == null) continue;
                    String id = getCellStringValue(row.getCell(18));
                    if (id == null || id.isBlank()) id = "row_" + rowIdx;
                    double importReal = getCellDoubleValue(row.getCell(21));
                    String tipusLabel = tipus != null ? tipus : "Prestació de Serveis";
                    String titol = getCellStringValue(row.getCell(17));
                    if (titol == null) titol = "";
                    String personaUuid = getCellStringValue(row.getCell(10));
                    if (personaUuid == null) personaUuid = "";
                    cache.computeIfAbsent(unitUuid, k -> new LinkedHashMap<>())
                         .computeIfAbsent(year, k -> new LinkedHashMap<>())
                         .merge(id, importReal, Double::max);
                    tipusCache.computeIfAbsent(unitUuid, k -> new LinkedHashMap<>())
                              .computeIfAbsent(year, k -> new LinkedHashMap<>())
                              .putIfAbsent(id, tipusLabel);
                    titolCache.computeIfAbsent(unitUuid, k -> new LinkedHashMap<>())
                              .computeIfAbsent(year, k -> new LinkedHashMap<>())
                              .putIfAbsent(id, titol);
                                    personaCache.computeIfAbsent(unitUuid, k -> new LinkedHashMap<>())
                                                .computeIfAbsent(year, k -> new LinkedHashMap<>())
                                                .putIfAbsent(id, personaUuid);
                }
                excelCache = Collections.unmodifiableMap(cache);
                excelTipusCache = Collections.unmodifiableMap(tipusCache);
                excelTitolCache = Collections.unmodifiableMap(titolCache);
                            excelPersonaCache = Collections.unmodifiableMap(personaCache);
            }
        } catch (Exception e) {
            System.err.println("[AwardService] Could not load Excel cache: " + e.getMessage());
        }
    }

    /*
    ===============================
    CATEGORÍAS
    ===============================
    */

    public List<String> getCategorias() {

        return runPipeline(
                "mongodb/awards/categories.json",
                null
        ).stream()

        .map(doc -> doc.getString("categoria"))

        .filter(c -> c != null && !c.isBlank())

        .toList();
    }

    /*
    ===============================
    TIPUS
    ===============================
    */

    public List<String> getTipus() {

        return runPipeline(
                "mongodb/awards/tipus.json",
                null
        ).stream()

                .map(doc -> doc.getString("categoria"))

        .filter(t -> t != null && !t.isBlank())

        .toList();
    }

        public List<Document> getTipusPerCategoria() {
                return runPipeline(
                                "mongodb/awards/tipus-per-categoria.json",
                                null
                );
        }

    /*
    ===============================
    TOTAL STATS
    ===============================
    */

    public Map<String, Object> getTotalStats() {

        List<Document> pipeline = List.of(

                new Document("$match",
                        new Document("workflow.step", "validated")),

                new Document("$unwind", "$fundings"),

                new Document("$unwind",
                        "$fundings.fundingCollaborators"),

                new Document("$group",
                        new Document("_id", null)
                                .append("totalDinero",
                                        new Document("$sum",
                                                "$fundings.fundingCollaborators.institutionalPart.value"))
                                .append("totalProyectos",
                                        new Document("$sum", 1)))
        );

        List<Document> result = mongoTemplate
                .getCollection("Awards")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        if (result.isEmpty()) {

            return Map.of(
                    "totalDinero", 0,
                    "totalProyectos", 0);
        }

        Document doc = result.get(0);

        return Map.of(
                "totalDinero", doc.get("totalDinero"),
                "totalProyectos", doc.get("totalProyectos"));
    }

    /*
    ===============================
    POWER TABLE
    ===============================
    */

    public List<Document> getAwardsLlistaInstitut(String collaboratorUuid,
                                                   Integer desde,
                                                   Integer hasta) {
        if (collaboratorUuid == null || collaboratorUuid.isBlank()) {
            return List.of();
        }
        List<Document> mongoResult = runPipeline(
                "mongodb/awards/managing-llista-awards.json",
                builder -> {
                    builder.replaceManagingUuid(collaboratorUuid);
                    builder.awardDateBetween(desde, hasta);
                });
        List<Document> excelResult = getExcelPrestacioLlistaRows(collaboratorUuid, desde, hasta);
        List<Document> combined = new ArrayList<>(mongoResult);
        combined.addAll(excelResult);
        return combined;
    }

    private List<Document> getExcelPrestacioLlistaRows(String orgUuid, Integer desde, Integer hasta) {
        Map<Integer, Map<String, Double>> byYear = excelCache.get(orgUuid);
        if (byYear == null || byYear.isEmpty()) return List.of();
        Map<Integer, Map<String, String>> tipusByYear = excelTipusCache.getOrDefault(orgUuid, Map.of());
        Map<String, Map<Integer, Map<String, String>>> titolCacheAll = excelTitolCache;
        Map<Integer, Map<String, String>> titolByYear = excelTitolCache.getOrDefault(orgUuid, Map.of());
        Map<Integer, Map<String, String>> personaByYear = excelPersonaCache.getOrDefault(orgUuid, Map.of());
        List<Document> result = new ArrayList<>();
        byYear.forEach((year, ids) -> {
            if (desde != null && year < desde) return;
            if (hasta != null && year > hasta) return;
            Map<String, String> tipusMap = tipusByYear.getOrDefault(year, Map.of());
            Map<String, String> titolMap = titolByYear.getOrDefault(year, Map.of());
                        Map<String, String> personaMap = personaByYear.getOrDefault(year, Map.of());
            ids.forEach((id, importVal) -> {
                String tipus = tipusMap.getOrDefault(id, "Prestació de Serveis");
                String titol = titolMap.getOrDefault(id, "");
                String personaUuid = personaMap.getOrDefault(id, "");
                List<String> holdersUuids = personaUuid.isBlank() ? List.of() : List.of(personaUuid);
                result.add(new Document()
                        .append("anyo", year)
                        .append("titulo", titol)
                        .append("tipoAward", "Prestació de Serveis")
                        .append("categoria", "Prestació de Serveis")
                        .append("awardHoldersUuids", holdersUuids)
                        .append("institutionalPart", Math.round(importVal * 100.0) / 100.0));
            });
        });
        return result;
    }

    public List<Document> getIpsInstitut(String collaboratorUuid,
                                          Integer desde,
                                          Integer hasta) {
        if (collaboratorUuid == null || collaboratorUuid.isBlank()) {
            return List.of();
        }

        List<String> IP_TERMS_CA = Arrays.asList("Investigador/a Principal");
        List<String> IP_TERMS_ES = Arrays.asList("Investigador/a principal", "Investigador/a Principal");
        List<String> IP_TERMS_EN = Arrays.asList("Principal Investigator");
        List<String> COIP_TERMS_CA = Arrays.asList("Co-Investigador/a Principal");
        List<String> COIP_TERMS_ES = Arrays.asList("Co-Investigador/a Principal", "Co-Investigador/a principal");
        List<String> COIP_TERMS_EN = Arrays.asList("Co-Principal Investigator");

        List<Document> pipeline = new ArrayList<>();

        // 1. Filtre workflow
        pipeline.add(new Document("$match",
            new Document("workflow.step", new Document("$in", Arrays.asList("validated", "closed")))));

        // 2. Filtre per organització gestora
        pipeline.add(new Document("$match",
            new Document("$or", Arrays.asList(
                new Document("managingOrganization.uuid", collaboratorUuid),
                new Document("coManagingOrganizations.uuid", collaboratorUuid)
            ))));

        // 3. Filtre per rang d'anys (awardDate)
        if (desde != null || hasta != null) {
            Document range = new Document();
            if (desde != null) range.put("$gte",
                Date.from(java.time.LocalDate.of(desde, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)));
            if (hasta != null) range.put("$lte",
                Date.from(java.time.LocalDate.of(hasta, 12, 31).atTime(23,59,59).toInstant(java.time.ZoneOffset.UTC)));
            pipeline.add(new Document("$match", new Document("awardDate", range)));
        }

        // 4. Desplegar holders i filtrar per rol IP/Co-IP
        pipeline.add(new Document("$unwind", "$awardHolders"));
        pipeline.add(new Document("$match", new Document("$or", Arrays.asList(
            new Document("awardHolders.role.term.ca_ES", new Document("$in", IP_TERMS_CA)),
            new Document("awardHolders.role.term.es_ES", new Document("$in", IP_TERMS_ES)),
            new Document("awardHolders.role.term.en_GB", new Document("$in", IP_TERMS_EN)),
            new Document("awardHolders.role.term.ca_ES", new Document("$in", COIP_TERMS_CA)),
            new Document("awardHolders.role.term.es_ES", new Document("$in", COIP_TERMS_ES)),
            new Document("awardHolders.role.term.en_GB", new Document("$in", COIP_TERMS_EN))
        ))));

        // 5. Classificar IP vs Co-IP
        Document esIPExpr = new Document("$or", Arrays.asList(
            new Document("$in", Arrays.asList("$awardHolders.role.term.ca_ES", IP_TERMS_CA)),
            new Document("$in", Arrays.asList("$awardHolders.role.term.es_ES", IP_TERMS_ES)),
            new Document("$in", Arrays.asList("$awardHolders.role.term.en_GB", IP_TERMS_EN))
        ));
        Document esCoIPExpr = new Document("$or", Arrays.asList(
            new Document("$in", Arrays.asList("$awardHolders.role.term.ca_ES", COIP_TERMS_CA)),
            new Document("$in", Arrays.asList("$awardHolders.role.term.es_ES", COIP_TERMS_ES)),
            new Document("$in", Arrays.asList("$awardHolders.role.term.en_GB", COIP_TERMS_EN))
        ));
        pipeline.add(new Document("$addFields", new Document()
            .append("esIP",   esIPExpr)
            .append("esCoIP", esCoIPExpr)
            .append("holderUuid",    "$awardHolders.person.uuid")
            .append("holderFirst",   new Document("$ifNull", Arrays.asList("$awardHolders.name.firstName", "")))
            .append("holderLast",    new Document("$ifNull", Arrays.asList("$awardHolders.name.lastName",  "")))
        ));

        // 6. Agrupar per persona
        pipeline.add(new Document("$group", new Document()
            .append("_id", "$holderUuid")
            .append("nombre", new Document("$first", new Document("$cond", Arrays.asList(
                new Document("$and", Arrays.asList(
                    new Document("$ne", Arrays.asList("$holderLast",  "")),
                    new Document("$ne", Arrays.asList("$holderFirst", ""))
                )),
                new Document("$concat", Arrays.asList("$holderLast", ", ", "$holderFirst")),
                new Document("$cond", Arrays.asList(
                    new Document("$ne", Arrays.asList("$holderLast", "")),
                    "$holderLast",
                    "$holderFirst"
                ))
            ))))
            .append("nAwardsIP", new Document("$sum", new Document("$cond", Arrays.asList(
                new Document("$and", Arrays.asList(
                    new Document("$eq", Arrays.asList("$esIP",   true)),
                    new Document("$eq", Arrays.asList("$esCoIP", false))
                )), 1, 0
            ))))
            .append("nAwardsCoIP", new Document("$sum", new Document("$cond", Arrays.asList(
                "$esCoIP", 1, 0
            ))))
        ));

        // 7. Ordenar
        pipeline.add(new Document("$sort", new Document()
            .append("nAwardsIP",   -1)
            .append("nAwardsCoIP", -1)
            .append("nombre",       1)
        ));

        // 8. Projectar
        pipeline.add(new Document("$project", new Document()
            .append("_id",        0)
            .append("personUuid", "$_id")
            .append("nombre",     1)
            .append("nAwardsIP",  1)
            .append("nAwardsCoIP", 1)
        ));

        List<Document> results = new ArrayList<>();
        mongoTemplate.getCollection("Awards").aggregate(pipeline).forEach(results::add);
        return results;
    }

    public List<Document> getPowerTable(Integer desde,
                                        Integer hasta,
                                        String modoAnio,
                                        String collaboratorUuid) {

        List<Document> mongoResult;
        if (collaboratorUuid != null && !collaboratorUuid.isBlank() && !"all".equalsIgnoreCase(collaboratorUuid)) {
            mongoResult = runPipeline(
                    "mongodb/awards/managing-powertable.json",
                    builder -> {
                        builder.replaceManagingUuid(collaboratorUuid);

                        if ("vigencia".equalsIgnoreCase(modoAnio)) {
                            builder.vigenciaYears(desde, hasta);
                        } else {
                            builder.awardDateBetween(desde, hasta);
                        }
                    });
        } else {
            mongoResult = runPipeline(
                    "mongodb/awards/powertable.json",
                    builder -> {
                        if ("vigencia".equalsIgnoreCase(modoAnio)) {
                            builder.vigenciaYears(desde, hasta);
                        } else {
                            builder.awardDateBetween(desde, hasta);
                        }
                    });
        }

        List<Document> excelResult = getExcelPrestacioRows(collaboratorUuid, desde, hasta);
        List<Document> combined = new ArrayList<>(mongoResult);
        combined.addAll(excelResult);
        return combined;
    }

    public List<Document> getMapConvenis(Integer desde, Integer hasta, String collaboratorUuid) {
        return runPipeline(
                "mongodb/awards/map-convenis.json",
                builder -> {
                    if (collaboratorUuid != null && !collaboratorUuid.isBlank() && !"all".equalsIgnoreCase(collaboratorUuid)) {
                        builder.replaceManagingUuid(collaboratorUuid);
                    } else {
                        List<Document> pipeline = builder.build();
                        pipeline.removeIf(stage -> {
                            Document match = stage.get("$match", Document.class);
                            if (match == null) return false;
                            List<?> orList = (List<?>) match.get("$or");
                            if (orList == null) return false;
                            for (Object condition : orList) {
                                if (!(condition instanceof Document cond)) continue;
                                if (cond.containsKey("managingOrganization.uuid")) {
                                    return true;
                                }
                            }
                            return false;
                        });
                    }
                    builder.awardDateBetween(desde, hasta);
                });
    }

    public int getXarxesPlataformesCount(Integer desde, Integer hasta, String collaboratorUuid) {
        List<Document> result = runPipeline(
                "mongodb/awards/xarxes-plataformes.json",
                builder -> {
                    if (collaboratorUuid != null && !collaboratorUuid.isBlank() && !"all".equalsIgnoreCase(collaboratorUuid)) {
                        builder.replaceManagingUuid(collaboratorUuid);
                    } else {
                        List<Document> pipeline = builder.build();
                        pipeline.removeIf(stage -> {
                            Document match = stage.get("$match", Document.class);
                            if (match == null) return false;
                            List<?> orList = (List<?>) match.get("$or");
                            if (orList == null) return false;
                            for (Object condition : orList) {
                                if (!(condition instanceof Document cond)) continue;
                                if (cond.containsKey("managingOrganization.uuid")) {
                                    return true;
                                }
                            }
                            return false;
                        });
                    }
                    builder.awardDateBetween(desde, hasta);
                });
        if (result == null || result.isEmpty()) {
            return 0;
        }
        Document doc = result.get(0);
        Object totalVal = doc.get("total");
        if (totalVal instanceof Number num) {
            return num.intValue();
        }
        return 0;
    }


    private static final String EXCEL_PRESTACIO_PATH = "TABLON-Balanç de recursos - detallat (RC0025R).xlsx";
    private static final int EXCEL_DATA_START_ROW = 6; // rows 0-5 are metadata/header

    private List<Document> getExcelPrestacioRows(String orgUuid, Integer desde, Integer hasta) {
        Map<Integer, Map<String, Double>> byYear;
        if (orgUuid == null || orgUuid.isBlank() || "all".equalsIgnoreCase(orgUuid)) {
            // Aggregate all organizations
            byYear = new LinkedHashMap<>();
            for (Map<Integer, Map<String, Double>> orgCache : excelCache.values()) {
                orgCache.forEach((year, ids) -> {
                    Map<String, Double> aggregatedIds = byYear.computeIfAbsent(year, k -> new LinkedHashMap<>());
                    ids.forEach((id, val) -> {
                        aggregatedIds.merge(id, val, Double::sum);
                    });
                });
            }
        } else {
            byYear = excelCache.get(orgUuid);
        }

        if (byYear == null || byYear.isEmpty()) return List.of();

        List<Document> result = new ArrayList<>();
        byYear.forEach((year, ids) -> {
            if (desde != null && year < desde) return;
            if (hasta != null && year > hasta) return;
            double totalImport = ids.values().stream().mapToDouble(Double::doubleValue).sum();
            result.add(new Document()
                    .append("anio", year)
                    .append("categoria", "Prestació de Serveis")
                    .append("tipo", "Prestació de Serveis")
                    .append("ajuts", ids.size())
                    .append("import", Math.round(totalImport * 100.0) / 100.0)
                    .append("esLider", true));
        });
        return result;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private Integer getCellIntValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case NUMERIC -> (int) cell.getNumericCellValue();
            case STRING -> {
                try { yield Integer.parseInt(cell.getStringCellValue().trim()); }
                catch (NumberFormatException e) { yield null; }
            }
            default -> null;
        };
    }

    private double getCellDoubleValue(Cell cell) {
        if (cell == null) return 0.0;
        return switch (cell.getCellType()) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING -> {
                try { yield Double.parseDouble(cell.getStringCellValue().trim()); }
                catch (NumberFormatException e) { yield 0.0; }
            }
            default -> 0.0;
        };
    }

    /*
    ===============================
    POWER TABLE DEBUG
    ===============================
    */

    public List<Map> getPowerTableCategoryDebug(int limit) {

        int safeLimit = Math.max(1, Math.min(limit, 500));

        List<Document> pipeline = List.of(

                new Document("$match",
                        new Document("workflow.step", "validated")),

                new Document("$project",
                        new Document("_id", 0)
                                .append("uuid", 1)
                                .append("type_ca", "$type.term.ca_ES")
                                .append("categoria", "$categoria")),

                new Document("$limit", safeLimit)
        );

        return mongoTemplate
                .getCollection("Awards")
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }

    /*
    ===============================
    PERSONA RESUMEN
    ===============================
    */

    public List<Document> getPersonaResumen(String collaboratorUuid,
                                            String deptUuid,
                                            String persona,
                                            Integer desde,
                                            Integer hasta,
                                            String modoAnio,
                                            String gestionadosPorDept,
                                            List<String> categoria,
                                            List<String> tipus) {

        List<Document> rows = runPipeline(
                "mongodb/awards/persona-resumen.json",
                builder -> {

                    builder.replaceMatch(
                            "fundings.fundingCollaborators.collaborator.uuid",
                            collaboratorUuid);

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                    if (deptUuid != null && !deptUuid.isBlank()) {
                        if ("managed".equalsIgnoreCase(gestionadosPorDept)) {
                            builder.matchManagingOrg(deptUuid);
                        }
                        Set<String> deptPersons = getPersonUuidsByFilters(deptUuid, persona);
                        if (!deptPersons.isEmpty()) {
                            builder.filterArrayBeforeFirstGroup("awardHolders", "person.uuid", deptPersons);
                        }
                    } else if (persona != null && !persona.isBlank()) {
                        Set<String> namedPersons = getPersonUuidsByFilters(null, persona);
                        if (!namedPersons.isEmpty()) {
                            builder.filterArrayBeforeFirstGroup("awardHolders", "person.uuid", namedPersons);
                        }
                    }

                    if (categoria != null && !categoria.isEmpty()) {
                        builder.matchInBeforeFirstGroup("categoria", categoria);
                    }
                    if (tipus != null && !tipus.isEmpty()) {
                        builder.matchInBeforeFirstGroup("type.term.ca_ES", tipus);
                    }
                });

                // 1. Obtener los UUIDs de personas académicas activas filtradas por deptUuid/persona
                Set<String> filteredUuids;
                if ((deptUuid == null || deptUuid.isBlank()) && (persona == null || persona.isBlank())) {
                        filteredUuids = getAcademicActivePersonUuids();
                } else {
                        filteredUuids = getPersonUuidsByFilters(deptUuid, persona);
                }

                // 2. Mapear los rows existentes por PersonaUuid
                Map<String, Document> resumenByUuid = new HashMap<>();
                for (Document r : rows) {
                        String uuid = r.getString("PersonaUuid");
                        if (uuid != null) {
                                resumenByUuid.put(uuid, r);
                        }
                }

                // 3. Obtener nombres y birthdates de todos los posibles (con y sin awards)
                Map<String, String> names = fetchPersonNames(filteredUuids);
                Map<String, Object> birthdates = fetchPersonBirthdates(filteredUuids);
                
                // 4. Construir la lista: primero los que tienen awards (comportamiento original)
                List<Document> result = rows.stream()
                        .filter(r -> filteredUuids.contains(r.getString("PersonaUuid")))
                        .peek(r -> {
                                String uuid = r.getString("PersonaUuid");
                                r.put("Persona", names.getOrDefault(uuid, ""));
                                r.put("birthdate", birthdates.getOrDefault(uuid, null));
                        })
                        .collect(Collectors.toList());

                // 5. Añadir personas filtradas que no tienen awards, con totales en 0
                for (String uuid : filteredUuids) {
                        if (!resumenByUuid.containsKey(uuid)) {
                                Document resumen = new Document();
                                resumen.put("PersonaUuid", uuid);
                                resumen.put("Persona", names.getOrDefault(uuid, ""));
                                resumen.put("birthdate", birthdates.getOrDefault(uuid, null));
                                resumen.put("totalProyectos", 0);
                                resumen.put("totalDinero", 0);
                                // Agrega aquí otros campos de totales si los necesitas
                                result.add(resumen);
                        }
                }
                return result;
    }

    private Map<String, Object> fetchPersonBirthdates(Set<String> uuids) {
        if (uuids == null || uuids.isEmpty()) return Collections.emptyMap();

        MongoCollection<Document> col = mongoTemplate.getCollection("Persons");
        Map<String, Object> result = new HashMap<>();

        col.find(new Document("uuid", new Document("$in", new ArrayList<>(uuids))))
        .projection(new Document("uuid", 1).append("dateOfBirth", 1).append("_id", 0))
        .forEach(doc -> {
                String uuid = doc.getString("uuid");
                if (uuid != null) {
                result.put(uuid, doc.get("dateOfBirth"));
                }
        });

        return result;
}
    /*
    ===============================
    AWARDS POR PERSONA
    ===============================
    */

    public List<Document> getAwardsByPersona(String personUuid,
                                            Integer desde,
                                            Integer hasta,
                                            String collaboratorUuid,
                                            String deptUuid,
                                            String modoAnio,   
                                            List<String> categoria,
                                            List<String> tipus) {

        List<Document> awards = runPipeline(
                "mongodb/awards/persona-awards.json",
                builder -> {

                    builder.replaceMatch(
                            "awardHolders.person.uuid",
                            personUuid);

                    builder.replaceMatch(
                            "fundings.fundingCollaborators.collaborator.uuid",

                            collaboratorUuid);

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                    if (categoria != null && !categoria.isEmpty()) {
                        builder.matchInBeforeFirstProject("categoria", categoria);  
                    }

                                        if (tipus != null && !tipus.isEmpty()) {
                                                builder.matchInBeforeFirstProject("type.term.ca_ES", tipus);
                                        }

                });

                enrichCoManagingOrganization(awards);
                return awards;
    }

        private void enrichCoManagingOrganization(List<Document> awards) {
                if (awards == null || awards.isEmpty()) {
                        return;
                }

                Set<String> awardUuids = awards.stream()
                                .map(doc -> doc.getString("awardUuid"))
                                .filter(uuid -> uuid != null && !uuid.isBlank())
                                .collect(Collectors.toSet());

                if (awardUuids.isEmpty()) {
                        return;
                }

                List<Document> awardOrgPipeline = List.of(
                                new Document("$match", new Document("uuid", new Document("$in", awardUuids))),
                                new Document("$project", new Document("_id", 0)
                                                .append("uuid", 1)
                                                .append("managingUuid", "$managingOrganization.uuid")
                                                .append("coManagingUuids", new Document("$map",
                                                                new Document("input", new Document("$ifNull", List.of("$coManagingOrganizations", List.of())))
                                                                                .append("as", "org")
                                                                                .append("in", "$$org.uuid"))))
                );

                List<Document> awardOrgDocs = mongoTemplate.getCollection("Awards")
                                .aggregate(awardOrgPipeline)
                                .into(new ArrayList<>());

                Map<String, List<String>> coManagingByAward = new HashMap<>();
                Set<String> allOrgUuids = new HashSet<>();

                for (Document d : awardOrgDocs) {
                        String awardUuid = d.getString("uuid");
                        String managingUuid = d.getString("managingUuid");
                        List<String> uuids = d.getList("coManagingUuids", String.class);
                        if (awardUuid == null) {
                                continue;
                        }
                        List<String> safe = uuids == null ? new ArrayList<>() : uuids.stream()
                                        .filter(u -> u != null && !u.isBlank())
                                        .filter(u -> managingUuid == null || !managingUuid.equals(u))
                                        .distinct()
                                        .collect(Collectors.toList());
                        coManagingByAward.put(awardUuid, safe);
                        allOrgUuids.addAll(safe);
                }

                Map<String, String> orgNameByUuid = new HashMap<>();
                if (!allOrgUuids.isEmpty()) {
                        List<Document> orgs = mongoTemplate.getCollection("Organizations")
                                        .aggregate(List.of(
                                                        new Document("$match", new Document("uuid", new Document("$in", allOrgUuids))),
                                                        new Document("$project", new Document("_id", 0).append("uuid", 1).append("name", 1))
                                        ))
                                        .into(new ArrayList<>());

                        for (Document org : orgs) {
                                String uuid = org.getString("uuid");
                                Document name = org.get("name", Document.class);
                                if (uuid == null || name == null) {
                                        continue;
                                }
                                String display = firstNonBlank(
                                                name.getString("ca_ES"),
                                                name.getString("es_ES"),
                                                name.getString("en_GB")
                                );
                                if (display != null) {
                                        orgNameByUuid.put(uuid, display);
                                }
                        }
                }

                for (Document award : awards) {
                        String current = asString(award.get("comanagingOrganization"));
                        String managingName = asString(award.get("managingOrganization"));
                        String awardUuid = award.getString("awardUuid");

                        if ((isBlankOrDash(current) || (managingName != null && managingName.equals(current))) && awardUuid != null) {
                                List<String> coUuids = coManagingByAward.getOrDefault(awardUuid, List.of());
                                String resolved = coUuids.stream()
                                                .map(uuid -> orgNameByUuid.getOrDefault(uuid, uuid))
                                                .filter(name -> managingName == null || !managingName.equals(name))
                                                .filter(v -> v != null && !v.isBlank())
                                                .distinct()
                                                .collect(Collectors.joining(", "));
                                if (!resolved.isBlank()) {
                                        current = resolved;
                                }
                        }

                        if (current == null || current.isBlank()) {
                                current = "-";
                        }

                        award.put("comanagingOrganization", current);
                        award.put("coManagingOrganization", current);
                }
        }

        private String asString(Object value) {
                if (value == null) {
                        return null;
                }
                if (value instanceof String s) {
                        return s;
                }
                return String.valueOf(value);
        }

        private boolean isBlankOrDash(String value) {
                return value == null || value.isBlank() || "-".equals(value);
        }

        private String firstNonBlank(String... values) {
                for (String value : values) {
                        if (value != null && !value.isBlank()) {
                                return value;
                        }
                }
                return null;
        }

    /**
     * Returns UUIDs of all Persons who have at least one staffOrganizationAssociation
     * with staffType = "Académico", endDate null or in the future, and
     * employmentType NOT matching "Asociado" (case-insensitive).
     */
        private Set<String> getAcademicActivePersonUuids() {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

        // Paso 1: académicos activos (cualquier tipo)
        Document matchAcademic = new Document("$match", new Document("staffOrganizationAssociations",
                new Document("$elemMatch",
                new Document("staffType.term.ca_ES", "Acadèmic")
                        .append("$or", List.of(
                        new Document("period.endDate", null),
                        new Document("period.endDate", new Document("$gte", today))
                        ))
                )
        ));

        // Paso 2: excluir los que tengan alguna asociación activa con tipo excluido
        Document excludeAssociats = new Document("$match", new Document("staffOrganizationAssociations",
                new Document("$not", new Document("$elemMatch",
                new Document("$or", List.of(
                        new Document("period.endDate", null),
                        new Document("period.endDate", new Document("$gte", today))
                ))
                .append("employmentType.term.ca_ES",
                        new Document("$regex", "ssociat|ormació|Tècnic|Estudiant").append("$options", "i"))
                ))
        ));

        Document project = new Document("$project", new Document("_id", 0).append("uuid", 1));

        List<Document> pipeline = List.of(matchAcademic, excludeAssociats, project);

        return mongoTemplate.getCollection("Persons")
                .aggregate(pipeline)
                .into(new ArrayList<>())
                .stream()
                .map(d -> d.getString("uuid"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }

    /**
     * Fetches firstName + lastName for the given person UUIDs in a single query.
     * Returns a map of uuid -> "FirstName LastName".
     */
    private Map<String, String> fetchPersonNames(Collection<String> uuids) {
        if (uuids == null || uuids.isEmpty()) return Map.of();
        List<Document> persons = mongoTemplate.getCollection("Persons")
            .find(new Document("uuid", new Document("$in", new ArrayList<>(uuids))))
            .projection(new Document("_id", 0).append("uuid", 1)
                .append("name.firstName", 1).append("name.lastName", 1))
            .into(new ArrayList<>());
        Map<String, String> names = new HashMap<>();
        for (Document p : persons) {
            String uuid = p.getString("uuid");
            Document name = p.get("name", Document.class);
            if (uuid != null && name != null) {
                names.put(uuid, name.getString("firstName") + " " + name.getString("lastName"));
            }
        }
        return names;
    }

    private Set<String> getPersonUuidsByFilters(String deptUuid, String persona) {

        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        List<Document> personsPipeline = new ArrayList<>();

        if (deptUuid != null && !deptUuid.isBlank()) {
                personsPipeline.add(
                new Document("$match",
                        new Document("staffOrganizationAssociations",
                        new Document("$elemMatch",
                                new Document("organization.uuid", deptUuid)
                                .append("$or", List.of(
                                        new Document("period.endDate", null),
                                        new Document("period.endDate", new Document("$gte", today))
                                ))
                        )
                        )
                )
                );
        }

        // Excluir associats, formació, tècnics y estudiants activos
        personsPipeline.add(
                new Document("$match",
                new Document("staffOrganizationAssociations",
                        new Document("$not", new Document("$elemMatch",
                        new Document("$or", List.of(
                                new Document("period.endDate", null),
                                new Document("period.endDate", new Document("$gte", today))
                        ))
                        .append("employmentType.term.ca_ES",
                                new Document("$regex", "ssociat|ormació|Tècnic|Estudiant")
                                .append("$options", "i"))
                        ))
                )
                )
        );

        if (persona != null && !persona.isBlank()) {
                personsPipeline.add(
                new Document("$match",
                        new Document("$or", List.of(
                        new Document("name.firstName",
                                new Document("$regex", persona).append("$options", "i")),
                        new Document("name.lastName",
                                new Document("$regex", persona).append("$options", "i"))
                        ))
                )
                );
        }

        personsPipeline.add(
                new Document("$project", new Document("_id", 0).append("uuid", 1))
        );

        return mongoTemplate.getCollection("Persons")
                .aggregate(personsPipeline)
                .into(new ArrayList<>())
                .stream()
                .map(p -> p.getString("uuid"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }              

    /*
    ===============================
    PROYECTOS POR AÑO
    ===============================
    */

    public List<Document> getProyectosPorAnio(Integer desde,
                                            Integer hasta,
                                            String collaboratorUuid,
                                            String deptUuid,
                                            String persona,
                                            String modoAnio,
                                            List<String> categoria,
                                            List<String> tipus) {

        return runPipeline(
                "mongodb/awards/proyectos-anio.json",
                builder -> {

                    builder.replaceMatch(
                            "fundings.fundingCollaborators.collaborator.uuid",
                            collaboratorUuid);

                    if ("vigencia".equalsIgnoreCase(modoAnio)) {
                        builder.vigenciaYears(desde, hasta);
                    } else {
                        builder.awardDateBetween(desde, hasta);
                    }

                    // filtro por persona
                    if (persona != null && !persona.isBlank()) {
                        builder.match("awardHolders.person.uuid", persona);
                    }

                    // filtro por categoria
                    if (categoria != null && !categoria.isEmpty()) {
                        builder.matchInBeforeFirstGroup("categoria", categoria);
                    }

                                        if (tipus != null && !tipus.isEmpty()) {
                                                builder.matchInBeforeFirstGroup("type.term.ca_ES", tipus);
                                        }

                    // filtro por departamento
                    if (deptUuid != null && !deptUuid.isBlank()) {

                        Set<String> persons =
                                getPersonUuidsByFilters(deptUuid, null);

                        if (!persons.isEmpty()) {
                            builder.matchIn("awardHolders.person.uuid", persons);
                        }
                    }

                });
    }

    /*
    ===============================
    MÉTODOS UTILIDAD
    ===============================
    */

private Document loadMongoQuery(String classpathLocation) {

        try {

            ClassPathResource resource =
                    new ClassPathResource(classpathLocation);

            String json = StreamUtils.copyToString(
                    resource.getInputStream(),
                    StandardCharsets.UTF_8);

            return Document.parse(json);

        } catch (IOException e) {

            throw new IllegalStateException(
                    "No se pudo cargar la consulta MongoDB: "
                            + classpathLocation,
                    e);
        }
    }

    private List<Document> runPipeline(String jsonPath,
                                   java.util.function.Consumer<MongoPipelineBuilder> config) {

                Document query = loadMongoQuery(jsonPath);

                List<Document> pipeline = query.getList("pipeline", Document.class);

                MongoPipelineBuilder builder = new MongoPipelineBuilder(pipeline);

                if (config != null) {
                        config.accept(builder);
                }

                List<Document> finalPipeline = builder.build();
                //System.out.println("[Mongo Pipeline] " + jsonPath + ":\n" + finalPipeline);
                //finalPipeline.forEach(stage -> System.out.println(stage.toJson()));

                return mongoTemplate
                                .getCollection(query.getString("collection"))
                                .aggregate(finalPipeline)
                                .into(new ArrayList<>());
    }

    
}

