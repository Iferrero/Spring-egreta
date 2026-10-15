package com.example.demo.service;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servei per al calcul d'estadistiques i agregacions complexes de publicacions a MongoDB
 * (tipus per any, persona resum, llistat APA).
 */
@Service
public class PublicacionStatsService {

    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;

    // Cache per apa-list, tipos-by-year i persona-resumen: key -> (result, timestamp)
    public record ApaListCacheEntry(List<Map<String, Object>> data, long timestamp) {}
    public record StatsCacheEntry(List<Map> data, long timestamp) {}

    private final Map<String, ApaListCacheEntry> apaListCache = new ConcurrentHashMap<>();
    private final Map<String, StatsCacheEntry> tiposPorAnioCache = new ConcurrentHashMap<>();
    private final Map<String, StatsCacheEntry> personaResumenCache = new ConcurrentHashMap<>();
    private static final long APA_LIST_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final long STATS_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    @Autowired
    public PublicacionStatsService(
            MongoTemplate mongoTemplate,
            ResearchOutputJournalLinkService researchOutputJournalLinkService) {
        this.mongoTemplate = mongoTemplate;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
    }

    public String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid) {
        return buildStatsCacheKey(desde, hasta, deptUuid, null, false);
    }

    public String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid, String filtrePersonal) {
        return buildStatsCacheKey(desde, hasta, deptUuid, filtrePersonal, false);
    }

    public String buildStatsCacheKey(Integer desde, Integer hasta, List<String> deptUuid, String filtrePersonal, boolean managedByDepartment) {
        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();
        String fp = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal;
        return String.valueOf(desde) + "_" + String.valueOf(hasta) + "_" + String.join(",", deptFilter) + "_" + fp + "_" + managedByDepartment;
    }

    public Document buildPersonAssocCriteria(String filtrePersonal, Integer desde, Integer hasta) {
        boolean usePeriode = "periode".equalsIgnoreCase(filtrePersonal);
        if (usePeriode && (desde != null || hasta != null)) {
            List<Document> conditions = new ArrayList<>();
            if (desde != null) {
                String desdeIso = desde + "-01-01";
                Date desdeDate = Date.from(LocalDate.of(desde, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant());
                conditions.add(new Document("$or", List.of(
                    new Document("period.endDate", null),
                    new Document("period.endDate", new Document("$exists", false)),
                    new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 9)),
                        new Document("period.endDate", new Document("$gte", desdeDate))
                    )),
                    new Document("$and", List.of(
                        new Document("period.endDate", new Document("$type", 2)),
                        new Document("period.endDate", new Document("$gte", desdeIso))
                    ))
                )));
            }
            if (hasta != null) {
                String hastaIso = hasta + "-12-31";
                Date hastaDate = Date.from(LocalDate.of(hasta, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant());
                conditions.add(new Document("$or", List.of(
                    new Document("period.startDate", null),
                    new Document("period.startDate", new Document("$exists", false)),
                    new Document("$and", List.of(
                        new Document("period.startDate", new Document("$type", 9)),
                        new Document("period.startDate", new Document("$lte", hastaDate))
                    )),
                    new Document("$and", List.of(
                        new Document("period.startDate", new Document("$type", 2)),
                        new Document("period.startDate", new Document("$lte", hastaIso))
                    ))
                )));
            }
            return conditions.size() == 1 ? conditions.get(0) : new Document("$and", conditions);
        } else {
            LocalDate today = LocalDate.now();
            Date todayDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
            String todayIso = today.toString();
            return new Document("$or", Arrays.asList(
                new Document("period.endDate", null),
                new Document("period.endDate", new Document("$exists", false)),
                new Document("$and", Arrays.asList(
                    new Document("period.endDate", new Document("$type", 9)),
                    new Document("period.endDate", new Document("$gt", todayDate))
                )),
                new Document("$and", Arrays.asList(
                    new Document("period.endDate", new Document("$type", 2)),
                    new Document("period.endDate", new Document("$gt", todayIso))
                ))
            ));
        }
    }

    public List<Map> getStatsTiposPorAnio(
            Integer desde,
            Integer hasta,
            List<String> deptUuid,
            String filtrePersonal,
            boolean managedByDepartment) {

        String cacheKey = buildStatsCacheKey(desde, hasta, deptUuid, filtrePersonal, managedByDepartment);
        StatsCacheEntry cached = tiposPorAnioCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < STATS_CACHE_TTL_MS) {
            return cached.data();
        }

        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();

        List<Document> pipeline = new ArrayList<>();

        if (managedByDepartment && !deptFilter.isEmpty()) {
            List<Document> andFilters = new ArrayList<>();
            andFilters.add(new Document("workflow.step", "approved"));
            andFilters.add(new Document("managingOrganization.uuid", new Document("$in", deptFilter)));

            pipeline.add(new Document("$match", new Document("$and", andFilters)));

            pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList(
                    new Document("$arrayElemAt", Arrays.asList("$publicationStatuses.publicationDate.year", 0)),
                    new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear"))
                )))
                .append("tipoPublicacion", new Document("$ifNull", Arrays.asList(
                    "$type.term.ca_ES",
                    new Document("$ifNull", Arrays.asList("$type.term.ca_ES", "Sense tipus"))
                )))));

            List<Document> yearFilters = new ArrayList<>();
            yearFilters.add(new Document("$expr", new Document("$gt", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$publicationYear", 0)), 0
            ))));
            if (desde != null) {
                yearFilters.add(new Document("publicationYear", new Document("$gte", desde)));
            }
            if (hasta != null) {
                yearFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
            }
            pipeline.add(new Document("$match", new Document("$and", yearFilters)));

            pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationYear", "$publicationYear")
                .append("tipoPublicacion", "$tipoPublicacion")
                .append("publicationUuid", "$publicationUuid"))));

            pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

            pipeline.add(new Document("$project", new Document("_id", 0)
                .append("anio", "$_id.publicationYear")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("num_publicaciones", "$totalPublicaciones")
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

            pipeline.add(new Document("$sort", new Document("anio", -1)
                .append("tipo_publicacion", 1)));

            List<Map> result = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

            tiposPorAnioCache.put(cacheKey, new StatsCacheEntry(result, System.currentTimeMillis()));
            return result;
        }

        pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

        if (managedByDepartment && !deptFilter.isEmpty()) {
            pipeline.add(new Document("$match", new Document("managingOrganization.uuid", new Document("$in", deptFilter))));
        }

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList(
                    new Document("$arrayElemAt", Arrays.asList("$publicationStatuses.publicationDate.year", 0)),
                    new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear"))
                )))
                .append("tipoPublicacion", new Document("$ifNull", Arrays.asList(
                    "$type.term.ca_ES",
                    new Document("$ifNull", Arrays.asList("$type.term.ca_ES", "Sense tipus"))
                )))
                .append("managingOrgUuid", "$managingOrganization.uuid")
                .append("contributors", new Document("$ifNull", Arrays.asList("$contributors", List.of())))));

        List<Document> andFilters = new ArrayList<>();
        if (desde != null) {
            andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
        }
        if (hasta != null) {
            andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
        }
        if (!andFilters.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", andFilters)));
        }

        pipeline.add(new Document("$unwind", "$contributors"));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", 1)
                .append("publicationYear", 1)
                .append("tipoPublicacion", 1)
                .append("managingOrgUuid", 1)
                .append("personUuid", new Document("$trim", new Document("input",
                        new Document("$ifNull", Arrays.asList(
                                "$contributors.person.uuid",
                                new Document("$ifNull", Arrays.asList("$contributors.externalPerson.uuid", "")))))))));

        pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(
                new Document("$strLenCP", "$personUuid"), 0
        )))));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Persons")
                .append("localField", "personUuid")
                .append("foreignField", "uuid")
                .append("as", "persona_info")));

        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$persona_info")
                .append("preserveNullAndEmptyArrays", false)));

        Document assocCriteria = buildPersonAssocCriteria(filtrePersonal, desde, hasta);

        if (!deptFilter.isEmpty()) {
            Document assocMatch = new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", new Document("$and", Arrays.asList(
                    new Document("organization.uuid", new Document("$in", deptFilter)),
                    assocCriteria
                )))
            );
            Document managingMatch = new Document("managingOrgUuid", new Document("$in", deptFilter));
            pipeline.add(new Document("$match", new Document("$or", Arrays.asList(assocMatch, managingMatch))));
        } else {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", assocCriteria)
            )));
        }

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationUuid", "$publicationUuid")
                .append("publicationYear", "$publicationYear")
                .append("tipoPublicacion", "$tipoPublicacion"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

        pipeline.add(new Document("$project", new Document("_id", 0)
                .append("anio", "$_id.publicationYear")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("num_publicaciones", "$totalPublicaciones")
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

        pipeline.add(new Document("$sort", new Document("anio", 1)
                .append("tipo_publicacion", 1)));

        List<Map> result = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        tiposPorAnioCache.put(cacheKey, new StatsCacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    public List<Map> getStatsPersonaResumen(
            Integer desde,
            Integer hasta,
            List<String> deptUuid) {

        String cacheKey = buildStatsCacheKey(desde, hasta, deptUuid);
        StatsCacheEntry cached = personaResumenCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < STATS_CACHE_TTL_MS) {
            return cached.data();
        }

        List<Document> pipeline = new ArrayList<>();

        pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", "$uuid")
                .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))
                .append("tipoPublicacion", new Document("$ifNull", Arrays.asList("$type.term.ca_ES", "Sense tipus")))
                .append("contributors", new Document("$ifNull", Arrays.asList("$contributors", List.of())))));

        List<Document> andFilters = new ArrayList<>();
        if (desde != null) {
            andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
        }
        if (hasta != null) {
            andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
        }
        if (!andFilters.isEmpty()) {
            pipeline.add(new Document("$match", new Document("$and", andFilters)));
        }

        pipeline.add(new Document("$unwind", "$contributors"));

        pipeline.add(new Document("$project", new Document()
                .append("publicationUuid", 1)
                .append("tipoPublicacion", 1)
                .append("personUuid", new Document("$trim", new Document("input",
                        new Document("$ifNull", Arrays.asList(
                                "$contributors.person.uuid",
                                new Document("$ifNull", Arrays.asList("$contributors.externalPerson.uuid", "")))))))
                .append("personaContributor", new Document("$trim", new Document("input",
                        new Document("$concat", Arrays.asList(
                                new Document("$ifNull", Arrays.asList("$contributors.name.lastName", "")),
                                new Document("$cond", Arrays.asList(
                                    new Document("$gt", Arrays.asList(new Document("$strLenCP", new Document("$ifNull", Arrays.asList("$contributors.name.firstName", ""))), 0)),
                                    ", ",
                                    ""
                                )),
                                new Document("$ifNull", Arrays.asList("$contributors.name.firstName", ""))
                        ))).append("chars", " ")))));

        pipeline.add(new Document("$match", new Document("$expr", new Document("$or", Arrays.asList(
                new Document("$gt", Arrays.asList(new Document("$strLenCP", "$personUuid"), 0)),
                new Document("$gt", Arrays.asList(new Document("$strLenCP", "$personaContributor"), 0))
        )))));

        pipeline.add(new Document("$lookup", new Document()
                .append("from", "Persons")
                .append("localField", "personUuid")
                .append("foreignField", "uuid")
                .append("as", "persona_info")));

        pipeline.add(new Document("$unwind", new Document()
                .append("path", "$persona_info")
                .append("preserveNullAndEmptyArrays", true)));

        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
                .filter(v -> v != null && !v.isBlank())
                .toList();

        LocalDate hoy = LocalDate.now();
        String hoyIso = hoy.toString();
        Date hoyDate = Date.from(hoy.atStartOfDay(ZoneId.systemDefault()).toInstant());

        Document activeAssociationCriteria = new Document("$or", Arrays.asList(
            new Document("period.endDate", null),
            new Document("period.endDate", new Document("$exists", false)),
            new Document("$and", Arrays.asList(
                new Document("period.endDate", new Document("$type", 9)),
                new Document("period.endDate", new Document("$gt", hoyDate))
            )),
            new Document("$and", Arrays.asList(
                new Document("period.endDate", new Document("$type", 2)),
                new Document("period.endDate", new Document("$gt", hoyIso))
            ))
        ));

        if (!deptFilter.isEmpty()) {
            Document assocCriteria = new Document("$and", Arrays.asList(
                new Document("organization.uuid", new Document("$in", deptFilter)),
                activeAssociationCriteria
            ));

            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", assocCriteria)
            )));
        } else {
            pipeline.add(new Document("$match", new Document(
                "persona_info.staffOrganizationAssociations",
                new Document("$elemMatch", activeAssociationCriteria)
            )));
        }

        Document nomPersona = new Document("$trim", new Document("input",
            new Document("$concat", Arrays.asList(
                new Document("$ifNull", Arrays.asList("$persona_info.name.lastName", "")),
                new Document("$cond", Arrays.asList(
                    new Document("$gt", Arrays.asList(new Document("$strLenCP", new Document("$ifNull", Arrays.asList("$persona_info.name.firstName", ""))), 0)),
                    ", ",
                    ""
                )),
                new Document("$ifNull", Arrays.asList("$persona_info.name.firstName", ""))
            ))).append("chars", " "));

        Document nomContributor = new Document("$trim", new Document("input",
            new Document("$ifNull", Arrays.asList("$personaContributor", ""))).append("chars", " "));

        Document personaExpr = new Document("$cond", Arrays.asList(
            new Document("$gt", Arrays.asList(new Document("$strLenCP", nomPersona), 0)),
            nomPersona,
            new Document("$cond", Arrays.asList(
                new Document("$gt", Arrays.asList(new Document("$strLenCP", nomContributor), 0)),
                nomContributor,
                "$personUuid"
            ))
        ));

        pipeline.add(new Document("$project", new Document()
            .append("publicationUuid", 1)
            .append("publicationYear", 1)
            .append("personUuid", 1)
            .append("tipoPublicacion", 1)
            .append("persona", personaExpr)));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("publicationUuid", "$publicationUuid")
                .append("publicationYear", "$publicationYear")
                .append("personUuid", "$personUuid")
                .append("tipoPublicacion", "$tipoPublicacion")
                .append("persona", "$persona"))));

        pipeline.add(new Document("$group", new Document("_id", new Document()
                .append("personUuid", "$_id.personUuid")
                .append("persona", "$_id.persona")
                .append("publicationYear", "$_id.publicationYear")
                .append("tipoPublicacion", "$_id.tipoPublicacion"))
                .append("totalPublicaciones", new Document("$sum", 1))));

        pipeline.add(new Document("$project", new Document("_id", 0)
                .append("personUuid", "$_id.personUuid")
                .append("nombre", "$_id.persona")
                .append("persona", "$_id.persona")
                .append("anio", "$_id.publicationYear")
                .append("publicationYear", "$_id.publicationYear")
                .append("tipo_publicacion", "$_id.tipoPublicacion")
                .append("tipoPublicacion", "$_id.tipoPublicacion")
                .append("totalPublicaciones", "$totalPublicaciones")));

        pipeline.add(new Document("$sort", new Document("nombre", 1)
                .append("tipo_publicacion", 1)));

        List<Map> result = mongoTemplate
                .getCollection("Researchoutputs")
                .aggregate(pipeline)
                .into(new ArrayList<>());

        personaResumenCache.put(cacheKey, new StatsCacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    public List<Map<String, Object>> getStatsApaList(
            Integer desde,
            Integer hasta,
            List<String> deptUuid,
            String filtrePersonal,
            boolean managedByDepartment) {

        String fp = (filtrePersonal == null || filtrePersonal.isBlank()) ? "vigent" : filtrePersonal;
        List<String> deptFilter = (deptUuid == null ? List.<String>of() : deptUuid).stream()
            .filter(v -> v != null && !v.isBlank())
            .toList();
        String cacheKey = desde + "_" + hasta + "_" + String.join(",", deptFilter) + "_" + fp + "_" + managedByDepartment;
        ApaListCacheEntry cached = apaListCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < APA_LIST_CACHE_TTL_MS) {
            return cached.data();
        }

        List<Document> rows;

        if (managedByDepartment && !deptFilter.isEmpty()) {
            List<Document> pipeline = new ArrayList<>();
            pipeline.add(new Document("$match", new Document("$and", Arrays.asList(
                    new Document("workflow.step", "approved"),
                    new Document("managingOrganization.uuid", new Document("$in", deptFilter))
            ))));

            pipeline.add(new Document("$project", new Document()
                    .append("publicationUuid", "$uuid")
                    .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))));

            List<Document> yearFilters = new ArrayList<>();
            yearFilters.add(new Document("$expr", new Document("$gt", Arrays.asList(
                    new Document("$ifNull", Arrays.asList("$publicationYear", 0)), 0
            ))));
            if (desde != null) {
                yearFilters.add(new Document("publicationYear", new Document("$gte", desde)));
            }
            if (hasta != null) {
                yearFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
            }
            pipeline.add(new Document("$match", new Document("$and", yearFilters)));

            pipeline.add(new Document("$group", new Document("_id", "$publicationUuid")
                    .append("publicationYear", new Document("$first", "$publicationYear"))));

            rows = mongoTemplate
                    .getCollection("Researchoutputs")
                    .aggregate(pipeline)
                    .into(new ArrayList<>());
        } else {
            List<Document> pipeline = new ArrayList<>();

            pipeline.add(new Document("$match", new Document("workflow.step", "approved")));

            pipeline.add(new Document("$project", new Document()
                    .append("publicationUuid", "$uuid")
                    .append("publicationYear", new Document("$ifNull", Arrays.asList("$publicationDate.year", "$submissionYear")))
                    .append("managingOrgUuid", "$managingOrganization.uuid")
                    .append("contributors", new Document("$ifNull", Arrays.asList("$contributors", List.of())))));

            List<Document> andFilters = new ArrayList<>();
            if (desde != null) {
                andFilters.add(new Document("publicationYear", new Document("$gte", desde)));
            }
            if (hasta != null) {
                andFilters.add(new Document("publicationYear", new Document("$lte", hasta)));
            }
            if (!andFilters.isEmpty()) {
                pipeline.add(new Document("$match", new Document("$and", andFilters)));
            }

            pipeline.add(new Document("$unwind", "$contributors"));

            pipeline.add(new Document("$project", new Document()
                    .append("publicationUuid", 1)
                    .append("publicationYear", 1)
                    .append("managingOrgUuid", 1)
                    .append("personUuid", new Document("$trim", new Document("input",
                            new Document("$ifNull", Arrays.asList(
                                    "$contributors.person.uuid",
                                    new Document("$ifNull", Arrays.asList("$contributors.externalPerson.uuid", "")))))))));

            pipeline.add(new Document("$match", new Document("$expr", new Document("$gt", Arrays.asList(
                    new Document("$strLenCP", "$personUuid"), 0
            )))));

            pipeline.add(new Document("$lookup", new Document()
                    .append("from", "Persons")
                    .append("localField", "personUuid")
                    .append("foreignField", "uuid")
                    .append("as", "persona_info")));

            pipeline.add(new Document("$unwind", new Document()
                    .append("path", "$persona_info")
                    .append("preserveNullAndEmptyArrays", false)));

            Document assocCriteriaApa = buildPersonAssocCriteria(filtrePersonal, desde, hasta);

            if (!deptFilter.isEmpty()) {
                Document assocMatchApa = new Document(
                    "persona_info.staffOrganizationAssociations",
                    new Document("$elemMatch", new Document("$and", Arrays.asList(
                        new Document("organization.uuid", new Document("$in", deptFilter)),
                        assocCriteriaApa
                    )))
                );
                Document managingMatchApa = new Document("managingOrgUuid", new Document("$in", deptFilter));
                pipeline.add(new Document("$match", new Document("$or", Arrays.asList(assocMatchApa, managingMatchApa))));
            } else {
                pipeline.add(new Document("$match", new Document(
                    "persona_info.staffOrganizationAssociations",
                    new Document("$elemMatch", assocCriteriaApa)
                )));
            }

            pipeline.add(new Document("$group", new Document("_id", "$publicationUuid")
                    .append("publicationYear", new Document("$first", "$publicationYear"))));

            rows = mongoTemplate
                    .getCollection("Researchoutputs")
                    .aggregate(pipeline)
                    .into(new ArrayList<>());
        }

        if (rows.isEmpty()) return List.of();

        List<String> uuids = rows.stream()
                .map(r -> r.getString("_id"))
                .filter(u -> u != null && !u.isBlank())
                .toList();

        Map<String, Integer> yearByUuid = new LinkedHashMap<>();
        for (Document r : rows) {
            String uid = r.getString("_id");
            if (uid != null) yearByUuid.put(uid, r.getInteger("publicationYear", 0));
        }

        List<Document> fullDocs = mongoTemplate
                .getCollection("Researchoutputs")
                .find(new Document("uuid", new Document("$in", uuids)))
                .into(new ArrayList<>());

        fullDocs.sort((a, b) -> {
            int ya = yearByUuid.getOrDefault(a.getString("uuid"), 0);
            int yb = yearByUuid.getOrDefault(b.getString("uuid"), 0);
            if (yb != ya) return Integer.compare(yb, ya);
            Document titleDocA = (Document) a.get("title");
            Document titleDocB = (Document) b.get("title");
            String ta = titleDocA != null ? titleDocA.getString("value") : "";
            String tb = titleDocB != null ? titleDocB.getString("value") : "";
            if (ta == null) ta = "";
            if (tb == null) tb = "";
            return ta.compareToIgnoreCase(tb);
        });

        List<Map<String, Object>> apaResult = fullDocs.stream().map(pub -> {
            String uuid = pub.getString("uuid");
            int year = yearByUuid.getOrDefault(uuid, 0);

            Document typeDoc = (Document) pub.get("type");
            Document termDoc = typeDoc != null ? (Document) typeDoc.get("term") : null;
            String tipo = termDoc != null ? termDoc.getString("ca_ES") : null;
            if (tipo == null || tipo.isBlank()) tipo = "Sense tipus";

            Document titleDocPub = (Document) pub.get("title");
            String titulo = titleDocPub != null ? titleDocPub.getString("value") : "";
            if (titulo == null) titulo = "";

            String apa = researchOutputJournalLinkService.formatApaForDocument(pub);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uuid", uuid);
            result.put("year", year);
            result.put("tipo", tipo);
            result.put("titulo", titulo);
            result.put("apa", apa);
            return result;
        }).toList();

        apaListCache.put(cacheKey, new ApaListCacheEntry(apaResult, System.currentTimeMillis()));
        return apaResult;
    }
}
