package com.example.demo.service;

import com.example.demo.model.Jcr;
import com.example.demo.model.Journal;
import com.example.demo.repository.JournalRepository;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PersonaCvService {

    private final MongoTemplate mongoTemplate;
    private final AwardService awardService;
    private final ResearchOutputJournalLinkService researchOutputJournalLinkService;
    private final JournalJcrService journalJcrService;
    private final JournalRepository journalRepository;

    private static final String UAB_COLLABORATOR_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";

    @Autowired
    public PersonaCvService(MongoTemplate mongoTemplate,
                            AwardService awardService,
                            ResearchOutputJournalLinkService researchOutputJournalLinkService,
                            JournalJcrService journalJcrService,
                            JournalRepository journalRepository) {
        this.mongoTemplate = mongoTemplate;
        this.awardService = awardService;
        this.researchOutputJournalLinkService = researchOutputJournalLinkService;
        this.journalJcrService = journalJcrService;
        this.journalRepository = journalRepository;
    }

    /**
     * Retorna el conjunt consolidat d'informació curricular per al CV Visual d'una persona.
     */
    public Map<String, Object> getCvData(String personUuid, Integer desde, Integer hasta) {
        if (personUuid == null || personUuid.isBlank()) {
            return Map.of("error", "UUID de persona no proporcionat");
        }

        int yearDesde = (desde != null && desde > 1900) ? desde : 1970;
        int yearHasta = (hasta != null && hasta > 1900) ? hasta : LocalDate.now().getYear() + 10;

        // 1. Perfil de la persona
        Map<String, Object> profile = getPersonProfile(personUuid.trim());
        if (profile == null || profile.isEmpty()) {
            return Map.of("error", "No s'ha trobat la persona amb UUID: " + personUuid);
        }

        // 2. Publicacions i mètriques JCR
        Map<String, Object> pubData = getPublicationsData(personUuid.trim(), yearDesde, yearHasta);

        // 3. Projectes i Ajudes (Awards)
        Map<String, Object> awardsData = getAwardsData(personUuid.trim(), yearDesde, yearHasta);

        // 4. Tesis Doctorals dirigides
        List<Map<String, Object>> theses = getStudentTheses(personUuid.trim(), yearDesde, yearHasta);

        // 5. Activitats i reconeixements
        List<Map<String, Object>> activities = getActivities(personUuid.trim());

        // 6. Consolidació de KPIs i Gràfics
        Map<String, Object> kpis = calculateKpis(profile, pubData, awardsData, theses, activities);
        Map<String, Object> charts = buildChartsData(pubData, awardsData, yearDesde, yearHasta);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profile", profile);
        response.put("kpis", kpis);
        response.put("charts", charts);
        response.put("publications", pubData.get("list"));
        response.put("awards", awardsData.get("list"));
        response.put("theses", theses);
        response.put("activities", activities);

        return response;
    }

    /**
     * Obté i estructura el perfil de la persona des de la col·lecció Persons.
     */
    private Map<String, Object> getPersonProfile(String personUuid) {
        List<Document> docs = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Persons")
                .find(new Document("uuid", personUuid))
                .limit(1)
                .into(docs);

        if (docs.isEmpty()) {
            return null;
        }

        Document doc = docs.get(0);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("uuid", doc.getString("uuid"));
        profile.put("pureId", doc.get("pureId"));
        profile.put("orcid", doc.getString("orcid"));

        Document nameDoc = (Document) doc.get("name");
        String firstName = nameDoc != null ? nameDoc.getString("firstName") : "";
        String lastName = nameDoc != null ? nameDoc.getString("lastName") : "";
        profile.put("firstName", firstName != null ? firstName : "");
        profile.put("lastName", lastName != null ? lastName : "");
        profile.put("fullName", ((firstName != null && !firstName.isBlank()) ? firstName + " " : "") + (lastName != null ? lastName : ""));

        // Identificadors addicionals (ORCID, Scopus Author ID)
        List<String> scopusIds = extractScopusIds(doc, personUuid);
        profile.put("scopusIds", scopusIds);

        // Associacions organitzatives
        List<Document> assocs = getListDocument(doc, "staffOrganizationAssociations");
        List<Map<String, Object>> affiliations = new ArrayList<>();
        boolean isActive = false;
        LocalDate hoy = LocalDate.now();

        for (Document assoc : assocs) {
            Map<String, Object> aff = new LinkedHashMap<>();
            Document org = (Document) assoc.get("organization");
            String orgName = "";
            String orgUuid = "";
            if (org != null) {
                orgUuid = org.getString("uuid");
                Document orgNameDoc = (Document) org.get("name");
                if (orgNameDoc != null) {
                    orgName = getSafeLocalized(orgNameDoc);
                }
            }
            aff.put("orgName", (orgName != null && !orgName.isBlank()) ? orgName : "UAB");
            aff.put("orgUuid", orgUuid != null ? orgUuid : "");

            String empLabel = extractCategoryFromAssoc(assoc);
            aff.put("employmentType", empLabel != null ? empLabel : "");

            Document staffType = (Document) assoc.get("staffType");
            String staffLabel = "";
            if (staffType != null) {
                Document term = (Document) staffType.get("term");
                if (term != null) {
                    staffLabel = getSafeLocalized(term);
                }
            }
            aff.put("staffType", staffLabel != null ? staffLabel : "");

            Document period = (Document) assoc.get("period");
            String startDate = "";
            String endDate = "";
            boolean assocActive = true;
            if (period != null) {
                Object sObj = period.get("startDate");
                Object eObj = period.get("endDate");
                if (sObj != null) startDate = sObj.toString().substring(0, Math.min(10, sObj.toString().length()));
                if (eObj != null) {
                    endDate = eObj.toString().substring(0, Math.min(10, eObj.toString().length()));
                    try {
                        if (eObj instanceof Date d) {
                            if (d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(hoy)) {
                                assocActive = false;
                            }
                        } else if (eObj instanceof String s && !s.isBlank()) {
                            if (LocalDate.parse(s.substring(0, 10)).isBefore(hoy)) {
                                assocActive = false;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            aff.put("startDate", startDate);
            aff.put("endDate", endDate);
            aff.put("isActive", assocActive);
            if (assocActive) isActive = true;

            Boolean isPrimary = assoc.getBoolean("primaryAssociation", false);
            aff.put("primary", isPrimary);

            affiliations.add(aff);
        }

        // Determinem la categoria professional i departament MÉS RECENT (ÚLTIMA)
        // 1. Ordenem de més recent a més antiga per data d'inici
        List<Document> sortedAssocs = new ArrayList<>(assocs);
        sortedAssocs.sort((a, b) -> {
            String dateA = getAssocDateStr(a, "startDate");
            String dateB = getAssocDateStr(b, "startDate");
            return dateB.compareTo(dateA); // Més recent primer
        });

        // 2. Busquem la més recent contractual (excloent 'Adscripció a recerca')
        // 2a. Primer entre les actives contractuals
        Document latestAssoc = null;
        for (Document a : sortedAssocs) {
            if (!isAdscripcioRecerca(a) && isAssocActive(a, hoy)) {
                latestAssoc = a;
                break;
            }
        }
        // 2b. Si no n'hi ha cap activa contractual, busquem entre totes les contractuals històriques
        if (latestAssoc == null) {
            for (Document a : sortedAssocs) {
                if (!isAdscripcioRecerca(a)) {
                    latestAssoc = a;
                    break;
                }
            }
        }
        // 2c. Fallback
        if (latestAssoc == null && !sortedAssocs.isEmpty()) {
            latestAssoc = sortedAssocs.get(0);
        }

        String primaryDept = "";
        String primaryDeptUuid = "";
        String latestCategory = "";

        if (latestAssoc != null) {
            latestCategory = extractCategoryFromAssoc(latestAssoc);
            Document org = (Document) latestAssoc.get("organization");
            if (org != null) {
                primaryDeptUuid = org.getString("uuid");
                Document orgNameDoc = (Document) org.get("name");
                if (orgNameDoc != null) {
                    primaryDept = getSafeLocalized(orgNameDoc);
                }
            }
        }

        profile.put("primaryDepartment", (primaryDept != null && !primaryDept.isBlank()) ? primaryDept : "Universitat Autònoma de Barcelona");
        profile.put("primaryDepartmentUuid", primaryDeptUuid != null ? primaryDeptUuid : "");
        profile.put("category", (latestCategory != null && !latestCategory.isBlank()) ? latestCategory : "Investigador/a");
        profile.put("isActive", isActive);
        profile.put("affiliations", affiliations);

        return profile;
    }

    private List<String> extractScopusIds(Document doc, String personUuid) {
        Set<String> scopusIds = new LinkedHashSet<>();

        if (doc != null) {
            // 1. Direct fields
            String directScopus = getSafeString(doc, "scopusId");
            if (directScopus == null) directScopus = getSafeString(doc, "scopusAuthorId");
            if (directScopus == null) directScopus = getSafeString(doc, "scopus");
            if (directScopus != null && !directScopus.isBlank()) {
                scopusIds.add(directScopus.trim());
            }

            // 2. identifiers array
            List<Document> ids = getListDocument(doc, "identifiers");
            for (Document idItem : ids) {
                String idValue = getSafeString(idItem, "id");
                if (idValue == null) idValue = getSafeString(idItem, "value");

                String typeUri = getSafeString(idItem, "typeUri");
                if (typeUri == null) typeUri = getSafeString(idItem, "uri");
                if (typeUri == null) typeUri = getSafeString(idItem, "typeDiscriminator");

                Object typeObj = idItem.get("type");
                String typeStr = "";
                if (typeObj instanceof Document td) {
                    String u = td.getString("uri");
                    String t = getSafeLocalized(td);
                    typeStr = (u != null ? u : "") + " " + (t != null ? t : "");
                } else if (typeObj instanceof String s) {
                    typeStr = s;
                }

                String combinedType = ((typeUri != null ? typeUri : "") + " " + typeStr).toLowerCase();
                if (idValue != null && !idValue.isBlank()) {
                    if (combinedType.contains("scopus") ||
                        combinedType.contains("authorid") ||
                        combinedType.contains("author_id") ||
                        combinedType.contains("elsevier")) {
                        scopusIds.add(idValue.trim());
                    }
                }
            }

            // 3. customDefinedFields
            Object cdf = doc.get("customDefinedFields");
            if (cdf instanceof Document cdfDoc) {
                Object sc = cdfDoc.get("scopus");
                if (sc == null) sc = cdfDoc.get("scopusId");
                if (sc == null) sc = cdfDoc.get("scopusAuthorId");
                if (sc instanceof String s && !s.isBlank()) scopusIds.add(s.trim());
                else if (sc instanceof Document sd && sd.getString("value") != null) scopusIds.add(sd.getString("value").trim());
            }
        }

        // 4. Fallback: Search in Researchoutputs if not found in Person doc
        if (scopusIds.isEmpty() && personUuid != null) {
            try {
                Document matchDoc = new Document("contributors.person.uuid", personUuid);
                mongoTemplate.getDb().getCollection("Researchoutputs")
                        .find(matchDoc)
                        .limit(20)
                        .forEach(ro -> {
                            List<Document> contribs = getListDocument(ro, "contributors");
                            for (Document c : contribs) {
                                Document pRef = (Document) c.get("person");
                                if (pRef != null && personUuid.equals(pRef.getString("uuid"))) {
                                    List<Document> cIds = getListDocument(c, "identifiers");
                                    for (Document cid : cIds) {
                                        String val = getSafeString(cid, "value");
                                        if (val == null) val = getSafeString(cid, "id");
                                        String turi = getSafeString(cid, "typeUri");
                                        if (val != null && turi != null && turi.toLowerCase().contains("scopus")) {
                                            scopusIds.add(val.trim());
                                        }
                                    }
                                }
                            }
                        });
            } catch (Exception ignored) {}
        }

        return new ArrayList<>(scopusIds);
    }

    private boolean isAdscripcioRecerca(Document assoc) {
        if (assoc == null) return false;
        Object empType = assoc.get("employmentType");
        if (empType instanceof Document etDoc) {
            String uri = etDoc.getString("uri");
            if (uri != null && (uri.contains("adscripcio_recerca") || uri.contains("adscripcio"))) return true;
            Object term = etDoc.get("term");
            if (term instanceof Document td) {
                String s = getSafeLocalized(td);
                if (s != null && (s.toLowerCase().contains("adscripci") || s.toLowerCase().contains("adscripcion"))) return true;
            } else if (term instanceof String s) {
                if (s.toLowerCase().contains("adscripci") || s.toLowerCase().contains("adscripcion")) return true;
            }
        }
        return false;
    }

    private boolean isAssocActive(Document assoc, LocalDate hoy) {
        if (assoc == null) return false;
        Document period = (Document) assoc.get("period");
        if (period == null) return true;
        Object eObj = period.get("endDate");
        if (eObj == null) return true;
        try {
            if (eObj instanceof Date d) {
                return !d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(hoy);
            } else if (eObj instanceof String s && !s.isBlank()) {
                return !LocalDate.parse(s.substring(0, 10)).isBefore(hoy);
            }
        } catch (Exception ignored) {}
        return true;
    }

    private String getAssocDateStr(Document assoc, String dateField) {
        if (assoc == null) return "";
        Document period = (Document) assoc.get("period");
        if (period == null) return "";
        Object obj = period.get(dateField);
        if (obj == null) return "";
        return obj.toString();
    }

    private String extractCategoryFromAssoc(Document assoc) {
        if (assoc == null) return "";
        // 1. employmentType (terme més precís: Catedràtic, Titular, Agregat, etc.)
        Object empType = assoc.get("employmentType");
        if (empType instanceof Document etDoc) {
            Object term = etDoc.get("term");
            if (term instanceof Document td) {
                String s = getSafeLocalized(td);
                if (s != null && !s.isBlank()) return s;
            } else if (term instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        // 2. jobDescription
        Object jd = assoc.get("jobDescription");
        if (jd instanceof Document jdDoc) {
            Object term = jdDoc.get("term");
            if (term instanceof Document td) {
                String s = getSafeLocalized(td);
                if (s != null && !s.isBlank()) return s;
            }
        }
        // 3. jobTitle
        Object jt = assoc.get("jobTitle");
        if (jt instanceof Document jtDoc) {
            Object term = jtDoc.get("term");
            if (term instanceof Document td) {
                String s = getSafeLocalized(td);
                if (s != null && !s.isBlank()) return s;
            }
        }
        // 4. staffType
        Object staffType = assoc.get("staffType");
        if (staffType instanceof Document stDoc) {
            Object term = stDoc.get("term");
            if (term instanceof Document td) {
                String s = getSafeLocalized(td);
                if (s != null && !s.isBlank()) return s;
            }
        }
        return "";
    }

    /**
     * Obté les publicacions de l'investigador, enriquides amb quartil JCR i desglossament.
     */
    private Map<String, Object> getPublicationsData(String personUuid, int yearDesde, int yearHasta) {
        Map<String, Object> res = new LinkedHashMap<>();

        // Consulta de Researchoutputs amb matching per personUuid
        Document matchDoc = new Document("workflow.step", "approved")
                .append("$or", Arrays.asList(
                        new Document("contributors.person.uuid", personUuid),
                        new Document("contributors.externalPerson.uuid", personUuid)
                ));

        List<Document> docs = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Researchoutputs")
                .find(matchDoc)
                .sort(new Document("publicationStatuses.publicationDate.year", -1).append("submissionYear", -1))
                .into(docs);

        // 1. Recollim tots els ISSNs per pre-carregar en bloc la cache JCR
        Map<String, Optional<Journal>> journalCache = new HashMap<>();
        Map<String, List<Jcr>> jcrCache = new HashMap<>();
        Set<String> allIssns = new LinkedHashSet<>();

        for (Document doc : docs) {
            allIssns.addAll(extractIssnsFromPubDoc(doc, journalCache));
        }
        if (!allIssns.isEmpty()) {
            journalJcrService.warmJcrCacheForIssns(allIssns);
        }

        List<Map<String, Object>> pubList = new ArrayList<>();
        Map<Integer, Integer> pubsPerYear = new TreeMap<>();
        Map<String, Integer> pubsByType = new HashMap<>();
        Map<String, Integer> quartilesCount = new LinkedHashMap<>();
        quartilesCount.put("Q1", 0);
        quartilesCount.put("Q2", 0);
        quartilesCount.put("Q3", 0);
        quartilesCount.put("Q4", 0);
        quartilesCount.put("Sense Quartil", 0);

        int countArticles = 0;
        int countQ1 = 0;
        int countQ2 = 0;
        int countQ3 = 0;
        int countQ4 = 0;
        int countD1 = 0;
        int countIndexedJcr = 0;

        double sumJif = 0.0;
        int countJif = 0;
        Double maxJif = null;
        String maxJifJournal = "";
        String maxJifArticle = "";
        Integer maxJifYear = null;

        Map<String, Integer> categoryCounts = new LinkedHashMap<>();

        for (Document doc : docs) {
            String pUuid = doc.getString("uuid");
            Integer year = extractYear(doc);
            if (year != null && (year < yearDesde || year > yearHasta)) {
                continue;
            }

            String title = extractTitle(doc);
            String type = extractType(doc);
            String journalTitle = extractJournalTitle(doc);
            String doi = extractDoi(doc);
            String authors = extractAuthors(doc);
            String volume = extractVolume(doc);
            String issue = extractIssue(doc);
            String pages = extractPages(doc);
            boolean openAccess = isOpenAccess(doc);

            // Resolució detallada de mètriques JCR
            JcrPublicationMetrics jcr = resolvePublicationJcrMetrics(doc, year, journalCache, jcrCache);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", pUuid);
            item.put("title", title);
            item.put("year", year != null ? year : 0);
            item.put("type", type);
            item.put("journal", journalTitle);
            item.put("volume", volume);
            item.put("issue", issue);
            item.put("pages", pages);
            item.put("quartile", jcr.quartile);
            item.put("isD1", jcr.isD1);
            item.put("jif", jcr.jif);
            item.put("jifFormatted", jcr.jifFormatted != null ? jcr.jifFormatted : "");
            item.put("jif5Years", jcr.jif5YearsFormatted != null ? jcr.jif5YearsFormatted : "");
            item.put("jci", jcr.jci != null ? Math.round(jcr.jci * 100.0) / 100.0 : null);
            item.put("percentile", jcr.percentile);
            item.put("jcrRank", jcr.jcrRank != null ? jcr.jcrRank : "");
            item.put("jcrCategory", jcr.jcrCategory != null ? jcr.jcrCategory : "");
            item.put("jcrEdition", jcr.jcrEdition != null ? jcr.jcrEdition : "");
            item.put("jcrYear", jcr.jcrYear != null ? jcr.jcrYear : 0);
            item.put("isIndexedJcr", jcr.isIndexed);
            item.put("doi", doi);
            item.put("authors", authors);
            item.put("openAccess", openAccess);

            pubList.add(item);

            // Recompte temporal
            if (year != null && year > 1900) {
                pubsPerYear.put(year, pubsPerYear.getOrDefault(year, 0) + 1);
            }
            pubsByType.put(type, pubsByType.getOrDefault(type, 0) + 1);

            boolean isJournalArticle = (type != null && (type.toLowerCase().contains("article") || type.toLowerCase().contains("artículo"))) || !journalTitle.isBlank();
            if (isJournalArticle) {
                countArticles++;

                if (jcr.isIndexed || !"Sense Quartil".equalsIgnoreCase(jcr.quartile) || jcr.jif != null) {
                    countIndexedJcr++;
                }

                if ("Q1".equalsIgnoreCase(jcr.quartile)) {
                    countQ1++;
                } else if ("Q2".equalsIgnoreCase(jcr.quartile)) {
                    countQ2++;
                } else if ("Q3".equalsIgnoreCase(jcr.quartile)) {
                    countQ3++;
                } else if ("Q4".equalsIgnoreCase(jcr.quartile)) {
                    countQ4++;
                }

                if (quartilesCount.containsKey(jcr.quartile)) {
                    quartilesCount.put(jcr.quartile, quartilesCount.get(jcr.quartile) + 1);
                } else {
                    quartilesCount.put("Sense Quartil", quartilesCount.get("Sense Quartil") + 1);
                }

                if (jcr.isD1) {
                    countD1++;
                }

                if (jcr.jif != null && jcr.jif > 0) {
                    sumJif += jcr.jif;
                    countJif++;
                    if (maxJif == null || jcr.jif > maxJif) {
                        maxJif = jcr.jif;
                        maxJifJournal = !journalTitle.isBlank() ? journalTitle : "";
                        maxJifArticle = title;
                        maxJifYear = year;
                    }
                }

                if (jcr.jcrCategory != null && !jcr.jcrCategory.isBlank()) {
                    categoryCounts.put(jcr.jcrCategory, categoryCounts.getOrDefault(jcr.jcrCategory, 0) + 1);
                }
            }
        }

        List<Map<String, Object>> topCategories = categoryCounts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(8)
                .map(e -> {
                    Map<String, Object> catMap = new LinkedHashMap<>();
                    catMap.put("category", e.getKey());
                    catMap.put("count", e.getValue());
                    return catMap;
                })
                .toList();

        res.put("list", pubList);
        res.put("total", pubList.size());
        res.put("totalArticles", countArticles);
        res.put("countIndexedJcr", countIndexedJcr);
        res.put("countQ1", countQ1);
        res.put("countQ2", countQ2);
        res.put("countQ3", countQ3);
        res.put("countQ4", countQ4);
        res.put("countD1", countD1);
        res.put("sumJif", sumJif);
        res.put("countJif", countJif);
        res.put("avgJif", countJif > 0 ? Math.round((sumJif / countJif) * 1000.0) / 1000.0 : 0.0);
        res.put("maxJif", maxJif != null ? Math.round(maxJif * 1000.0) / 1000.0 : 0.0);
        res.put("maxJifJournal", maxJifJournal);
        res.put("maxJifArticle", maxJifArticle);
        res.put("maxJifYear", maxJifYear);
        res.put("topCategories", topCategories);
        res.put("byYear", pubsPerYear);
        res.put("byType", pubsByType);
        res.put("quartilesCount", quartilesCount);

        return res;
    }

    /**
     * Obté els projectes i ajuts (Awards) de l'investigador amb rol, import i tipologia.
     */
    private Map<String, Object> getAwardsData(String personUuid, int yearDesde, int yearHasta) {
        Map<String, Object> res = new LinkedHashMap<>();

        List<Document> rawAwards = new ArrayList<>();
        try {
            rawAwards = awardService.getAwardsByPersona(
                    personUuid,
                    yearDesde,
                    yearHasta,
                    UAB_COLLABORATOR_UUID,
                    null,
                    "awardDate",
                    null,
                    null
            );
        } catch (Exception e) {
            System.err.println("[PersonaCvService] Error getting awards: " + e.getMessage());
        }

        // Carreguem documents originals de la col·lecció Awards per enriquir qualsevol dada que falti
        List<String> awardUuids = rawAwards.stream()
                .map(d -> getSafeString(d, "awardUuid"))
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();

        Map<String, Document> rawAwardsFromDb = new HashMap<>();
        if (!awardUuids.isEmpty()) {
            mongoTemplate.getDb().getCollection("Awards")
                    .find(new Document("uuid", new Document("$in", awardUuids)))
                    .forEach(d -> {
                        String u = d.getString("uuid");
                        if (u != null) rawAwardsFromDb.put(u, d);
                    });
        }

        List<Map<String, Object>> awardsList = new ArrayList<>();
        Map<Integer, Double> amountPerYear = new TreeMap<>();
        Map<Integer, Integer> countPerYear = new TreeMap<>();
        Map<String, Double> amountByType = new LinkedHashMap<>();

        double totalAmount = 0.0;
        int totalAwardsAsIp = 0;

        for (Document doc : rawAwards) {
            String awardUuid = getSafeString(doc, "awardUuid");
            Document dbAward = (awardUuid != null) ? rawAwardsFromDb.get(awardUuid) : null;

            // Títol
            String title = getSafeString(doc, "titulo");
            if (title == null || title.isBlank()) title = getSafeString(doc, "title");
            if ((title == null || title.isBlank()) && dbAward != null) {
                Object tObj = dbAward.get("title");
                if (tObj instanceof Document tDoc) {
                    title = tDoc.getString("ca_ES");
                    if (title == null) title = tDoc.getString("es_ES");
                    if (title == null) title = tDoc.getString("en_GB");
                } else if (tObj instanceof String s) {
                    title = s;
                }
            }
            if (title == null || title.isBlank()) title = awardUuid != null ? awardUuid : "Ajut de recerca";

            // Codi / Referència
            String awardId = getSafeString(doc, "awardId");
            if ((awardId == null || awardId.isBlank()) && dbAward != null) {
                awardId = getSafeString(dbAward, "applicationReferenceNumber");
            }

            // Tipus
            String tipus = getSafeString(doc, "tipoAward");
            if (tipus == null || tipus.isBlank()) tipus = getSafeString(doc, "tipus");
            if (tipus == null || tipus.isBlank()) tipus = getSafeString(doc, "categoria");
            if ((tipus == null || tipus.isBlank()) && dbAward != null) {
                Object typeObj = dbAward.get("type");
                if (typeObj instanceof Document typeDoc) {
                    Object termObj = typeDoc.get("term");
                    if (termObj instanceof Document termDoc) {
                        tipus = termDoc.getString("ca_ES");
                        if (tipus == null) tipus = termDoc.getString("es_ES");
                    }
                }
            }
            if (tipus == null || tipus.isBlank()) tipus = "Projectes de recerca";

            // Rol
            String role = getSafeString(doc, "rol");
            if (role == null || role.isBlank()) role = getSafeString(doc, "personRole");
            if ((role == null || role.isBlank()) && dbAward != null) {
                List<Document> holders = getListDocument(dbAward, "awardHolders");
                for (Document h : holders) {
                    Document pRef = (Document) h.get("person");
                    if (pRef != null && personUuid.equals(pRef.getString("uuid"))) {
                        Document rDoc = (Document) h.get("role");
                        if (rDoc != null) {
                            Document rTerm = (Document) rDoc.get("term");
                            if (rTerm != null) {
                                role = rTerm.getString("ca_ES");
                                if (role == null) role = rTerm.getString("es_ES");
                            }
                        }
                        break;
                    }
                }
            }
            boolean isIp = (role != null && (role.toLowerCase().contains("principal") || role.toLowerCase().contains("holder") || role.toLowerCase().contains("ip")));
            if (isIp) totalAwardsAsIp++;

            // Import (€)
            double amount = 0.0;
            Object impObj = doc.get("institutionalPart");
            if (impObj == null) impObj = doc.get("totalImport");
            if (impObj == null) impObj = doc.get("importe_partida");
            if (impObj instanceof Number n) {
                amount = n.doubleValue();
            } else if (impObj instanceof String s && !s.isBlank()) {
                try { amount = Double.parseDouble(s.trim()); } catch (Exception ignored) {}
            }

            // Si ve a 0 de la pipeline, cerquem a dbAward
            if (amount <= 0 && dbAward != null) {
                List<Document> fundings = getListDocument(dbAward, "fundings");
                for (Document f : fundings) {
                    List<Document> collabs = getListDocument(f, "fundingCollaborators");
                    for (Document c : collabs) {
                        Object ipObj = c.get("institutionalPart");
                        if (ipObj instanceof Document ipDoc) {
                            Object val = ipDoc.get("value");
                            if (val instanceof Number n) amount += n.doubleValue();
                            else if (val instanceof String s && !s.isBlank()) {
                                try { amount += Double.parseDouble(s.trim()); } catch (Exception ignored) {}
                            }
                        }
                    }
                    if (amount <= 0) {
                        Object amtObj = f.get("amount");
                        if (amtObj instanceof Document amtDoc) {
                            Object val = amtDoc.get("value");
                            if (val instanceof Number n) amount += n.doubleValue();
                            else if (val instanceof String s && !s.isBlank()) {
                                try { amount += Double.parseDouble(s.trim()); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
            amount = Math.round(amount * 100.0) / 100.0;
            totalAmount += amount;

            // Dates i Any
            Integer year = null;
            Object anyoObj = doc.get("anyo");
            if (anyoObj instanceof Number n) {
                year = n.intValue();
            } else if (doc.get("awardDate") != null) {
                String dStr = doc.get("awardDate").toString();
                if (dStr.length() >= 4) {
                    try { year = Integer.parseInt(dStr.substring(0, 4)); } catch (Exception ignored) {}
                }
            } else if (dbAward != null && dbAward.get("awardDate") != null) {
                String dStr = dbAward.get("awardDate").toString();
                if (dStr.length() >= 4) {
                    try { year = Integer.parseInt(dStr.substring(0, 4)); } catch (Exception ignored) {}
                }
            }

            String startDate = getSafeString(doc, "vigenciaInicio");
            if (startDate == null) startDate = getSafeString(doc, "startDate");
            String endDate = getSafeString(doc, "vigenciaFin");
            if (endDate == null) endDate = getSafeString(doc, "endDate");

            if (startDate == null && dbAward != null) {
                Document period = (Document) dbAward.get("actualPeriod");
                if (period != null) {
                    startDate = getSafeString(period, "startDate");
                    endDate = getSafeString(period, "endDate");
                }
            }

            String funder = getSafeString(doc, "managingOrganization");
            if (funder == null) funder = getSafeString(doc, "fundingOrganisation");
            if (funder == null) funder = "Universitat Autònoma de Barcelona";

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", awardUuid);
            item.put("title", title);
            item.put("awardId", awardId != null ? awardId : "");
            item.put("type", tipus);
            item.put("role", role != null ? role : (isIp ? "Investigador/a Principal" : "Col·laborador/a"));
            item.put("isIp", isIp);
            item.put("amount", amount);
            item.put("year", year != null ? year : 0);
            item.put("startDate", startDate != null ? startDate.substring(0, Math.min(10, startDate.length())) : "");
            item.put("endDate", endDate != null ? endDate.substring(0, Math.min(10, endDate.length())) : "");
            item.put("funder", funder);

            awardsList.add(item);

            if (year != null && year > 1900) {
                amountPerYear.put(year, amountPerYear.getOrDefault(year, 0.0) + amount);
                countPerYear.put(year, countPerYear.getOrDefault(year, 0) + 1);
            }
            amountByType.put(tipus, amountByType.getOrDefault(tipus, 0.0) + amount);
        }

        totalAmount = Math.round(totalAmount * 100.0) / 100.0;
        res.put("list", awardsList);
        res.put("total", awardsList.size());
        res.put("totalAsIp", totalAwardsAsIp);
        res.put("totalAmount", totalAmount);
        res.put("amountPerYear", amountPerYear);
        res.put("countPerYear", countPerYear);
        res.put("amountByType", amountByType);

        return res;
    }

    /**
     * Obté les tesis doctorals dirigides per l'investigador.
     */
    private List<Map<String, Object>> getStudentTheses(String personUuid, int yearDesde, int yearHasta) {
        List<Map<String, Object>> list = new ArrayList<>();

        Document filter = new Document("supervisors.person.uuid", personUuid);
        List<Document> docs = new ArrayList<>();
        mongoTemplate.getDb().getCollection("StudentTheses")
                .find(filter)
                .sort(new Document("awardDate.year", -1).append("publicationStatuses.publicationDate.year", -1))
                .into(docs);

        for (Document doc : docs) {
            String uuid = doc.getString("uuid");
            String title = "Sense títol";
            Document titleDoc = (Document) doc.get("title");
            if (titleDoc != null && titleDoc.getString("value") != null) {
                title = titleDoc.getString("value");
            }

            Integer year = null;
            Document awardDateDoc = (Document) doc.get("awardDate");
            if (awardDateDoc != null && awardDateDoc.get("year") instanceof Number n) {
                year = n.intValue();
            }

            if (year != null && (year < yearDesde || year > yearHasta)) {
                continue;
            }

            // Nom de l'estudiant (primer autor/contributor)
            String studentName = "";
            List<Document> contribs = getListDocument(doc, "contributors");
            if (!contribs.isEmpty()) {
                Document c = contribs.get(0);
                Document nameDoc = (Document) c.get("name");
                if (nameDoc != null) {
                    String fn = nameDoc.getString("firstName");
                    String ln = nameDoc.getString("lastName");
                    studentName = (ln != null ? ln : "") + (fn != null && !fn.isBlank() ? ", " + fn : "");
                }
            }

            // Rol de supervisor
            String supervisorRole = "Director/a";
            List<Document> supervisors = getListDocument(doc, "supervisors");
            for (Document sup : supervisors) {
                Document personRef = (Document) sup.get("person");
                if (personRef != null && personUuid.equals(personRef.getString("uuid"))) {
                    Document roleDoc = (Document) sup.get("role");
                    if (roleDoc != null) {
                        Document term = (Document) roleDoc.get("term");
                        if (term != null && term.getString("ca_ES") != null) {
                            supervisorRole = term.getString("ca_ES");
                        }
                    }
                    break;
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", uuid);
            item.put("title", title);
            item.put("year", year != null ? year : 0);
            item.put("studentName", studentName.isBlank() ? "Doctorand/a" : studentName);
            item.put("role", supervisorRole);

            list.add(item);
        }

        return list;
    }

    /**
     * Obté activitats i premis vinculats a la persona (amb títol, lloc/entitat, data i reconeixement).
     */
    private List<Map<String, Object>> getActivities(String personUuid) {
        List<Map<String, Object>> list = new ArrayList<>();

        Document filter = new Document("persons.person.uuid", personUuid);
        List<Document> docs = new ArrayList<>();
        mongoTemplate.getDb().getCollection("Activities")
                .find(filter)
                .sort(new Document("period.startDate.year", -1).append("period.startDate", -1))
                .into(docs);

        // Recollim UUIDs d'events, organitzacions externes i internes per resoldre noms en bloc
        Set<String> eventUuids = new HashSet<>();
        Set<String> extOrgUuids = new HashSet<>();
        Set<String> intOrgUuids = new HashSet<>();

        for (Document doc : docs) {
            Object ev = doc.get("event");
            if (ev == null) ev = doc.get("relatedEvent");
            if (ev == null) ev = doc.get("eventRef");
            if (ev == null) ev = doc.get("conference");
            if (ev instanceof Document evDoc && evDoc.getString("uuid") != null) {
                eventUuids.add(evDoc.getString("uuid"));
            } else if (ev instanceof String s && !s.isBlank()) {
                eventUuids.add(s);
            }
            List<Document> evList = getListDocument(doc, "events");
            for (Document ed : evList) {
                if (ed.getString("uuid") != null) eventUuids.add(ed.getString("uuid"));
            }
            List<Document> relList = getListDocument(doc, "relatedEvents");
            for (Document ed : relList) {
                if (ed.getString("uuid") != null) eventUuids.add(ed.getString("uuid"));
            }

            Object memberOf = doc.get("memberOf");
            if (memberOf instanceof Document mDoc) {
                Object eo = mDoc.get("externalOrganization");
                if (eo instanceof Document eoDoc && eoDoc.getString("uuid") != null) {
                    extOrgUuids.add(eoDoc.getString("uuid"));
                }
            }
            List<Document> extList = getListDocument(doc, "externalOrganizations");
            for (Document eo : extList) {
                if (eo.getString("uuid") != null) extOrgUuids.add(eo.getString("uuid"));
            }
            List<Document> orgList = getListDocument(doc, "organizations");
            for (Document o : orgList) {
                if (o.getString("uuid") != null) intOrgUuids.add(o.getString("uuid"));
            }
        }

        Map<String, Document> eventsByUuid = new HashMap<>();
        if (!eventUuids.isEmpty()) {
            mongoTemplate.getDb().getCollection("Events")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(eventUuids))))
                    .forEach(ev -> {
                        String id = ev.getString("uuid");
                        if (id != null) eventsByUuid.put(id, ev);
                    });
        }

        Map<String, String> extOrgNames = new HashMap<>();
        if (!extOrgUuids.isEmpty()) {
            mongoTemplate.getDb().getCollection("ExternalOrganizations")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(extOrgUuids))))
                    .forEach(eo -> {
                        String id = eo.getString("uuid");
                        Object nObj = eo.get("name");
                        if (nObj instanceof Document nd) {
                            String s = getSafeLocalized(nd);
                            if (s != null) extOrgNames.put(id, s);
                        } else if (nObj instanceof String s) {
                            extOrgNames.put(id, s);
                        }
                    });
        }

        Map<String, String> intOrgNames = new HashMap<>();
        if (!intOrgUuids.isEmpty()) {
            mongoTemplate.getDb().getCollection("Organizations")
                    .find(new Document("uuid", new Document("$in", new ArrayList<>(intOrgUuids))))
                    .forEach(o -> {
                        String id = o.getString("uuid");
                        Object nObj = o.get("name");
                        if (nObj instanceof Document nd) {
                            String s = getSafeLocalized(nd);
                            if (s != null) intOrgNames.put(id, s);
                        } else if (nObj instanceof String s) {
                            intOrgNames.put(id, s);
                        }
                    });
        }

        for (Document doc : docs) {
            String uuid = getSafeString(doc, "uuid");

            // 1. Tipus
            String typeName = "Activitat";
            Document typeDoc = (Document) doc.get("type");
            if (typeDoc != null) {
                Document term = (Document) typeDoc.get("term");
                if (term != null) {
                    String t = getSafeLocalized(term);
                    if (t != null) typeName = t;
                }
            }

            // 2. Categoria (Investigació / Docència / Divulgació)
            String category = extractActivityCategory(doc);

            // 3. Rol de la persona en l'activitat (Ponent, Organitzador/a, etc.)
            String role = null;
            List<Document> persons = getListDocument(doc, "persons");
            for (Document p : persons) {
                Object pRefObj = p.get("person");
                if (pRefObj instanceof Document pRef && personUuid.equals(pRef.getString("uuid"))) {
                    Object rDocObj = p.get("role");
                    if (rDocObj instanceof Document rDoc) {
                        Object rTermObj = rDoc.get("term");
                        if (rTermObj instanceof Document rTerm) {
                            role = getSafeLocalized(rTerm);
                        }
                    }
                    break;
                }
            }

            // 4. Títol / Nombre
            String title = extractActivityTitle(doc);
            if (title == null || title.isBlank()) {
                title = typeName;
            }

            // 5. Esdeveniment relacionat (Congrés, Simposi, Jornades...)
            String eventName = extractActivityEventName(doc, eventsByUuid);
            String eventDate = extractActivityEventDate(doc, eventsByUuid);
            String eventLocation = extractActivityEventLocation(doc, eventsByUuid);

            // 6. Lloc / Entitat / Seu (global)
            String place = extractActivityPlace(doc, eventsByUuid, extOrgNames, intOrgNames);

            // 7. Data / Període
            String dateStr = extractActivityDate(doc);
            Integer year = extractActivityYear(doc);

            // 8. Descripció
            String description = extractActivityDescription(doc);

            // 9. Grau de reconeixement (Internacional, Nacional, etc.)
            String recognition = null;
            Document degDoc = (Document) doc.get("degreeOfRecognition");
            if (degDoc != null) {
                Document degTerm = (Document) degDoc.get("term");
                if (degTerm != null) {
                    recognition = getSafeLocalized(degTerm);
                }
            }

            // 10. Tipus d'inscripció (per invitació / lliure)
            String invitation = null;
            Object invObj = doc.get("invitation");
            if (invObj == null) invObj = doc.get("talkType");
            if (invObj == null) invObj = doc.get("participationType");
            if (invObj instanceof Document invDoc) {
                Document invTerm = (Document) invDoc.get("term");
                if (invTerm != null) invitation = getSafeLocalized(invTerm);
            } else if (invObj instanceof String s && !s.isBlank()) {
                invitation = s.trim();
            }

            boolean isCongress = (eventName != null && !eventName.isBlank()) ||
                    (typeName != null && (typeName.toLowerCase().contains("conferència") ||
                                          typeName.toLowerCase().contains("conferencia") ||
                                          typeName.toLowerCase().contains("presentació") ||
                                          typeName.toLowerCase().contains("presentacion") ||
                                          typeName.toLowerCase().contains("ponència") ||
                                          typeName.toLowerCase().contains("ponencia") ||
                                          typeName.toLowerCase().contains("comunicació") ||
                                          typeName.toLowerCase().contains("comunicacion") ||
                                          typeName.toLowerCase().contains("poster") ||
                                          typeName.toLowerCase().contains("pòster") ||
                                          typeName.toLowerCase().contains("congrés") ||
                                          typeName.toLowerCase().contains("congreso")));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", uuid);
            item.put("type", typeName);
            item.put("category", category != null ? category : "");
            item.put("role", role != null ? role : "");
            item.put("title", title);
            item.put("eventName", eventName != null ? eventName : "");
            item.put("congress", eventName != null ? eventName : "");
            item.put("eventDate", eventDate != null ? eventDate : "");
            item.put("eventLocation", eventLocation != null ? eventLocation : "");
            item.put("place", place != null ? place : "");
            item.put("date", dateStr != null ? dateStr : "");
            item.put("year", year != null ? year : 0);
            item.put("description", description != null ? description : "");
            item.put("recognition", recognition != null ? recognition : "");
            item.put("invitation", invitation != null ? invitation : "");
            item.put("hasEvent", eventName != null && !eventName.isBlank());
            item.put("isCongress", isCongress);

            list.add(item);
        }

        return list;
    }

    /**
     * Consolida els indicadors clau (Scorecards).
     */
    private Map<String, Object> calculateKpis(Map<String, Object> profile,
                                              Map<String, Object> pubData,
                                              Map<String, Object> awardsData,
                                              List<Map<String, Object>> theses,
                                              List<Map<String, Object>> activities) {
        Map<String, Object> kpis = new LinkedHashMap<>();

        int totalPubs = (int) pubData.getOrDefault("total", 0);
        int totalArticles = (int) pubData.getOrDefault("totalArticles", 0);
        int countIndexedJcr = (int) pubData.getOrDefault("countIndexedJcr", 0);
        int countQ1 = (int) pubData.getOrDefault("countQ1", 0);
        int countQ2 = (int) pubData.getOrDefault("countQ2", 0);
        int countQ3 = (int) pubData.getOrDefault("countQ3", 0);
        int countQ4 = (int) pubData.getOrDefault("countQ4", 0);
        int countD1 = (int) pubData.getOrDefault("countD1", 0);

        double pctQ1 = totalArticles > 0 ? ((double) countQ1 / totalArticles) * 100.0 : 0.0;
        double pctQ2 = totalArticles > 0 ? ((double) countQ2 / totalArticles) * 100.0 : 0.0;
        double pctD1 = totalArticles > 0 ? ((double) countD1 / totalArticles) * 100.0 : 0.0;
        double pctIndexed = totalArticles > 0 ? ((double) countIndexedJcr / totalArticles) * 100.0 : 0.0;

        int totalAwards = (int) awardsData.getOrDefault("total", 0);
        int totalAwardsIp = (int) awardsData.getOrDefault("totalAsIp", 0);
        double totalAmount = (double) awardsData.getOrDefault("totalAmount", 0.0);

        kpis.put("totalPublications", totalPubs);
        kpis.put("totalArticles", totalArticles);
        kpis.put("totalIndexedJcr", countIndexedJcr);
        kpis.put("percentageIndexedJcr", Math.round(pctIndexed * 10.0) / 10.0);
        kpis.put("countQ1", countQ1);
        kpis.put("percentageQ1", Math.round(pctQ1 * 10.0) / 10.0);
        kpis.put("countQ2", countQ2);
        kpis.put("percentageQ2", Math.round(pctQ2 * 10.0) / 10.0);
        kpis.put("countQ3", countQ3);
        kpis.put("countQ4", countQ4);
        kpis.put("countD1", countD1);
        kpis.put("percentageD1", Math.round(pctD1 * 10.0) / 10.0);

        kpis.put("avgJif", pubData.getOrDefault("avgJif", 0.0));
        kpis.put("maxJif", pubData.getOrDefault("maxJif", 0.0));
        kpis.put("maxJifJournal", pubData.getOrDefault("maxJifJournal", ""));
        kpis.put("maxJifArticle", pubData.getOrDefault("maxJifArticle", ""));
        kpis.put("maxJifYear", pubData.getOrDefault("maxJifYear", null));
        kpis.put("topJcrCategories", pubData.getOrDefault("topCategories", Collections.emptyList()));

        kpis.put("totalAwards", totalAwards);
        kpis.put("totalAwardsAsIp", totalAwardsIp);
        kpis.put("totalFundingAmount", totalAmount);
        kpis.put("totalTheses", theses.size());
        kpis.put("totalActivities", activities.size());

        return kpis;
    }

    /**
     * Estructura les dades per als gràfics d'ECharts.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildChartsData(Map<String, Object> pubData,
                                                Map<String, Object> awardsData,
                                                int yearDesde,
                                                int yearHasta) {
        Map<String, Object> charts = new LinkedHashMap<>();

        Map<Integer, Integer> pubsPerYear = (Map<Integer, Integer>) pubData.getOrDefault("byYear", Collections.emptyMap());
        Map<Integer, Integer> awardsCountPerYear = (Map<Integer, Integer>) awardsData.getOrDefault("countPerYear", Collections.emptyMap());
        Map<Integer, Double> awardsAmountPerYear = (Map<Integer, Double>) awardsData.getOrDefault("amountPerYear", Collections.emptyMap());

        Set<Integer> allYears = new TreeSet<>();
        allYears.addAll(pubsPerYear.keySet());
        allYears.addAll(awardsCountPerYear.keySet());
        allYears.addAll(awardsAmountPerYear.keySet());

        // Filtrar anys vàlids
        List<Integer> years = allYears.stream()
                .filter(y -> y >= yearDesde && y <= yearHasta)
                .sorted()
                .toList();

        List<Integer> pubSeries = new ArrayList<>();
        List<Integer> awardSeries = new ArrayList<>();
        List<Double> amountSeries = new ArrayList<>();

        for (Integer y : years) {
            pubSeries.add(pubsPerYear.getOrDefault(y, 0));
            awardSeries.add(awardsCountPerYear.getOrDefault(y, 0));
            amountSeries.add(awardsAmountPerYear.getOrDefault(y, 0.0));
        }

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("years", years);
        timeline.put("publications", pubSeries);
        timeline.put("awards", awardSeries);
        timeline.put("funding", amountSeries);
        charts.put("timeline", timeline);

        // Quartils
        charts.put("quartiles", pubData.get("quartilesCount"));

        // Tipus de publicacions
        charts.put("publicationTypes", pubData.get("byType"));

        // Tipus de projectes / finançament
        charts.put("fundingByTypes", awardsData.get("amountByType"));

        return charts;
    }

    // -------------------------------------------------------------------------
    // Helper parsers
    // -------------------------------------------------------------------------

    private Integer extractYear(Document doc) {
        if (doc == null) return null;

        // 1. Direct publicationDate (Document with year)
        Object pubDateObj = doc.get("publicationDate");
        if (pubDateObj instanceof Document dateDoc) {
            if (dateDoc.get("year") instanceof Number n) {
                return n.intValue();
            }
        }

        // 2. publicationStatuses (Array of statuses or single Document)
        Object pubStatusesObj = doc.get("publicationStatuses");
        if (pubStatusesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Document itemDoc) {
                    Object pDate = itemDoc.get("publicationDate");
                    if (pDate instanceof Document dateDoc) {
                        if (dateDoc.get("year") instanceof Number n) {
                            return n.intValue();
                        }
                    }
                }
            }
        } else if (pubStatusesObj instanceof Document itemDoc) {
            Object pDate = itemDoc.get("publicationDate");
            if (pDate instanceof Document dateDoc) {
                if (dateDoc.get("year") instanceof Number n) {
                    return n.intValue();
                }
            }
        }

        // 3. submissionYear
        Object subYear = doc.get("submissionYear");
        if (subYear instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private String extractTitle(Document doc) {
        if (doc == null) return "Sense títol";
        Object titleObj = doc.get("title");
        if (titleObj instanceof Document titleDoc && titleDoc.getString("value") != null) {
            return titleDoc.getString("value");
        } else if (titleObj instanceof String s && !s.isBlank()) {
            return s;
        }
        return "Sense títol";
    }

    private String extractType(Document doc) {
        if (doc == null) return "Publicació";
        Object typeObj = doc.get("type");
        if (typeObj instanceof Document typeDoc) {
            Object termObj = typeDoc.get("term");
            if (termObj instanceof Document term) {
                String ca = term.getString("ca_ES");
                if (ca != null && !ca.isBlank()) return ca;
                String es = term.getString("es_ES");
                if (es != null && !es.isBlank()) return es;
                String en = term.getString("en_GB");
                if (en != null && !en.isBlank()) return en;
            } else if (termObj instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return "Publicació";
    }

    private String extractJournalTitle(Document doc) {
        if (doc == null) return "";
        Object jaObj = doc.get("journalAssociation");
        if (jaObj instanceof Document ja) {
            Object titleObj = ja.get("title");
            if (titleObj instanceof Document titleDoc && titleDoc.getString("value") != null) {
                return titleDoc.getString("value");
            } else if (titleObj instanceof String s && !s.isBlank()) {
                return s;
            }
            Object jObj = ja.get("journal");
            if (jObj instanceof Document jDoc) {
                Object jtObj = jDoc.get("title");
                if (jtObj instanceof Document jtDoc && jtDoc.getString("value") != null) {
                    return jtDoc.getString("value");
                }
            }
        }
        return "";
    }

    private String getSafeString(Document doc, String key) {
        if (doc == null) return null;
        Object val = doc.get(key);
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isBlank() ? null : s;
    }

    private String extractVolume(Document doc) {
        if (doc == null) return null;
        Object jaObj = doc.get("journalAssociation");
        if (jaObj instanceof Document ja) {
            String v = getSafeString(ja, "volume");
            if (v != null) return v;
        }
        return getSafeString(doc, "volume");
    }

    private String extractIssue(Document doc) {
        if (doc == null) return null;
        Object jaObj = doc.get("journalAssociation");
        if (jaObj instanceof Document ja) {
            String num = getSafeString(ja, "journalNumber");
            if (num != null) return num;
            String issue = getSafeString(ja, "issue");
            if (issue != null) return issue;
        }
        return getSafeString(doc, "issue");
    }

    private String extractPages(Document doc) {
        if (doc == null) return null;
        Object jaObj = doc.get("journalAssociation");
        if (jaObj instanceof Document ja) {
            String pages = getSafeString(ja, "pages");
            if (pages != null) return pages;
        }
        String pages = getSafeString(doc, "pages");
        if (pages != null) return pages;
        String numPages = getSafeString(doc, "numberOfPages");
        if (numPages != null) return numPages + " p.";
        return null;
    }

    private String extractDoi(Document doc) {
        if (doc == null) return "";
        List<Document> evList = getListDocument(doc, "electronicVersions");
        for (Document ev : evList) {
            String doi = getSafeString(ev, "doi");
            if (doi != null) return doi;
            String link = getSafeString(ev, "link");
            if (link != null && link.contains("doi.org")) return link;
        }
        return "";
    }

    private String extractAuthors(Document doc) {
        List<Document> contrs = getListDocument(doc, "contributors");
        if (contrs.isEmpty()) return "Sense autors";
        return contrs.stream().map(c -> {
            Document nd = (Document) c.get("name");
            if (nd != null) {
                String ln = nd.getString("lastName");
                String fn = nd.getString("firstName");
                return (ln != null ? ln : "") + (fn != null && !fn.isBlank() ? ", " + fn : "");
            }
            return "";
        }).filter(s -> !s.isBlank()).collect(Collectors.joining("; "));
    }

    private boolean isOpenAccess(Document doc) {
        List<Document> evList = getListDocument(doc, "electronicVersions");
        for (Document ev : evList) {
            Document oap = (Document) ev.get("openAccessPermission");
            if (oap != null) {
                Document term = (Document) oap.get("term");
                if (term != null) {
                    String en = term.getString("en_GB");
                    if (en != null && en.toLowerCase().contains("open")) return true;
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers per a Mètriques i Índexs JCR
    // -------------------------------------------------------------------------

    public static class JcrPublicationMetrics {
        public String quartile = "Sense Quartil";
        public boolean isD1 = false;
        public Double jif = null;
        public String jifFormatted = null;
        public Double jif5Years = null;
        public String jif5YearsFormatted = null;
        public Double jci = null;
        public Double percentile = null;
        public String jcrRank = null;
        public String jcrCategory = null;
        public String jcrEdition = null;
        public boolean isIndexed = false;
        public Integer jcrYear = null;
    }

    private JcrPublicationMetrics resolvePublicationJcrMetrics(
            Document doc,
            Integer pubYear,
            Map<String, Optional<Journal>> journalCache,
            Map<String, List<Jcr>> jcrCache) {

        JcrPublicationMetrics metrics = new JcrPublicationMetrics();
        if (doc == null) return metrics;

        Set<String> issns = extractIssnsFromPubDoc(doc, journalCache);
        if (issns.isEmpty()) return metrics;

        List<Jcr> matchedJcrs = new ArrayList<>();
        for (String issn : issns) {
            String norm = normalizeIssn(issn);
            if (norm != null) {
                List<Jcr> list = jcrCache.computeIfAbsent(norm, k -> journalJcrService.findJcrByIssn(issn));
                if (list != null && !list.isEmpty()) {
                    matchedJcrs.addAll(list);
                }
            }
        }

        if (matchedJcrs.isEmpty()) return metrics;

        List<Jcr> uniqueJcrs = dedupeJcrList(matchedJcrs);

        Jcr.JournalCitationReport bestReport = null;
        int minYearDiff = Integer.MAX_VALUE;

        for (Jcr jcr : uniqueJcrs) {
            if (jcr.getJournalCitationReports() == null) continue;
            for (Jcr.JournalCitationReport rep : jcr.getJournalCitationReports()) {
                if (rep == null || rep.getYear() == null) continue;
                if (pubYear != null && rep.getYear().equals(pubYear)) {
                    bestReport = rep;
                    minYearDiff = 0;
                    break;
                }
                int diff = pubYear != null ? Math.abs(rep.getYear() - pubYear) : 0;
                if (bestReport == null || diff < minYearDiff) {
                    bestReport = rep;
                    minYearDiff = diff;
                }
            }
            if (minYearDiff == 0) break;
        }

        if (bestReport == null) return metrics;

        metrics.isIndexed = true;
        metrics.jcrYear = bestReport.getYear();

        // 1. Impact Metrics (JIF, 5-Year JIF, JCI)
        if (bestReport.getMetrics() != null) {
            Map<String, Object> im = bestReport.getMetrics().getImpactMetrics();
            if (im != null) {
                metrics.jif = parseDoubleMetric(im.get("jif"));
                metrics.jif5Years = parseDoubleMetric(im.get("jif5Years"));
            }
            Map<String, Object> sm = bestReport.getMetrics().getSourceMetrics();
            if (sm != null) {
                metrics.jci = parseDoubleMetric(sm.get("jci"));
            }
        }

        if (metrics.jif != null) {
            metrics.jifFormatted = String.format(Locale.US, "%.3f", metrics.jif);
        }
        if (metrics.jif5Years != null) {
            metrics.jif5YearsFormatted = String.format(Locale.US, "%.3f", metrics.jif5Years);
        }

        // 2. Ranks & Categories (Quartil, Decil, Percentil, Rank, Categoria)
        if (bestReport.getRanks() != null) {
            Jcr.Ranks ranks = bestReport.getRanks();
            Jcr.RankItem bestItem = null;

            if (ranks.getJif() != null && !ranks.getJif().isEmpty()) {
                for (Jcr.RankItem item : ranks.getJif()) {
                    if (item == null) continue;
                    if (bestItem == null) {
                        bestItem = item;
                    } else if (isQuartileBetter(item.getQuartile(), bestItem.getQuartile())) {
                        bestItem = item;
                    } else if (Objects.equals(item.getQuartile(), bestItem.getQuartile()) &&
                            item.getJifPercentile() != null &&
                            (bestItem.getJifPercentile() == null || item.getJifPercentile() > bestItem.getJifPercentile())) {
                        bestItem = item;
                    }
                }
            }

            if (bestItem == null && ranks.getJci() != null && !ranks.getJci().isEmpty()) {
                bestItem = ranks.getJci().get(0);
            }
            if (bestItem == null && ranks.getArticleInfluence() != null && !ranks.getArticleInfluence().isEmpty()) {
                bestItem = ranks.getArticleInfluence().get(0);
            }

            if (bestItem != null) {
                if (bestItem.getQuartile() != null && !bestItem.getQuartile().isBlank()) {
                    metrics.quartile = bestItem.getQuartile().trim().toUpperCase(Locale.ROOT);
                }
                metrics.percentile = bestItem.getJifPercentile() != null ? bestItem.getJifPercentile() : bestItem.getJciPercentile();
                if (metrics.percentile != null) {
                    metrics.percentile = Math.round(metrics.percentile * 10.0) / 10.0;
                }
                metrics.jcrRank = bestItem.getRank();
                metrics.jcrCategory = bestItem.getCategory();
                metrics.jcrEdition = bestItem.getEdition();

                if ((metrics.percentile != null && metrics.percentile >= 90.0) ||
                    (metrics.jcrRank != null && isRankInTopDecile(metrics.jcrRank))) {
                    metrics.isD1 = true;
                }
            }
        }

        return metrics;
    }

    private Set<String> extractIssnsFromPubDoc(Document doc, Map<String, Optional<Journal>> journalCache) {
        Set<String> issns = new LinkedHashSet<>();
        if (doc == null) return issns;

        addIfValidIssn(issns, getSafeString(doc, "issn"));
        addIfValidIssn(issns, getSafeString(doc, "eissn"));

        Object jAssocObj = doc.get("journalAssociation");
        if (jAssocObj instanceof Document ja) {
            Object iObj = ja.get("issn");
            if (iObj instanceof Document id) addIfValidIssn(issns, id.getString("value"));
            else if (iObj instanceof String s) addIfValidIssn(issns, s);

            Object eiObj = ja.get("electronicIssn");
            if (eiObj instanceof Document eid) addIfValidIssn(issns, eid.getString("value"));
            else if (eiObj instanceof String s) addIfValidIssn(issns, s);

            Object jRef = ja.get("journal");
            if (jRef instanceof Document jd && jd.getString("uuid") != null) {
                String jUuid = jd.getString("uuid");
                Optional<Journal> jOpt = journalCache.computeIfAbsent(jUuid, journalRepository::findByUuid);
                jOpt.ifPresent(j -> issns.addAll(j.getAllIssnsForJoin()));
            }
        }

        Object jObj = doc.get("journal");
        if (jObj instanceof Document jd && jd.getString("uuid") != null) {
            String jUuid = jd.getString("uuid");
            Optional<Journal> jOpt = journalCache.computeIfAbsent(jUuid, journalRepository::findByUuid);
            jOpt.ifPresent(j -> issns.addAll(j.getAllIssnsForJoin()));
        }

        Object pcObj = doc.get("publicationChannel");
        if (pcObj instanceof Document pc) {
            Object jRef = pc.get("journal");
            if (jRef instanceof Document jd && jd.getString("uuid") != null) {
                String jUuid = jd.getString("uuid");
                Optional<Journal> jOpt = journalCache.computeIfAbsent(jUuid, journalRepository::findByUuid);
                jOpt.ifPresent(j -> issns.addAll(j.getAllIssnsForJoin()));
            }
        }

        return issns;
    }

    private void addIfValidIssn(Set<String> set, String issn) {
        if (issn != null && !issn.isBlank()) {
            set.add(issn.trim());
        }
    }

    private static String normalizeIssn(String value) {
        if (value == null) return null;
        String normalized = value.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private List<Jcr> dedupeJcrList(List<Jcr> list) {
        Map<String, Jcr> map = new LinkedHashMap<>();
        for (Jcr j : list) {
            if (j == null) continue;
            String key = j.getId() != null ? j.getId() : j.getBestIssnForJoin();
            if (key != null) map.put(key, j);
        }
        return new ArrayList<>(map.values());
    }

    private Double parseDoubleMetric(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean isQuartileBetter(String q1, String q2) {
        if (q1 == null) return false;
        if (q2 == null) return true;
        Map<String, Integer> score = Map.of("Q1", 4, "Q2", 3, "Q3", 2, "Q4", 1);
        return score.getOrDefault(q1.toUpperCase(), 0) > score.getOrDefault(q2.toUpperCase(), 0);
    }

    private boolean isRankInTopDecile(String rankStr) {
        if (rankStr == null || !rankStr.contains("/")) return false;
        try {
            String[] parts = rankStr.trim().split("/");
            if (parts.length == 2) {
                double pos = Double.parseDouble(parts[0].trim());
                double total = Double.parseDouble(parts[1].trim());
                return total > 0 && (pos / total) <= 0.10;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers per a Activities
    // -------------------------------------------------------------------------

    private String getSafeLocalized(Document doc) {
        if (doc == null) return null;
        if (doc.getString("ca_ES") != null && !doc.getString("ca_ES").isBlank()) return doc.getString("ca_ES").trim();
        if (doc.getString("es_ES") != null && !doc.getString("es_ES").isBlank()) return doc.getString("es_ES").trim();
        if (doc.getString("en_GB") != null && !doc.getString("en_GB").isBlank()) return doc.getString("en_GB").trim();
        if (doc.getString("value") != null && !doc.getString("value").isBlank()) return doc.getString("value").trim();
        return null;
    }

    private String extractActivityCategory(Document doc) {
        if (doc == null) return null;
        Object catObj = doc.get("categories");
        if (catObj instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (item instanceof Document cd) {
                    Object term = cd.get("term");
                    if (term instanceof Document td) {
                        String s = getSafeLocalized(td);
                        if (s != null) return s;
                    }
                }
            }
        }
        Object cObj = doc.get("category");
        if (cObj instanceof Document cd) {
            Object term = cd.get("term");
            if (term instanceof Document td) return getSafeLocalized(td);
        } else if (cObj instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private String extractActivityTitle(Document doc) {
        if (doc == null) return null;

        // 1. Nombre / Name (el camp principal d'EGRETA que es veu a la captura)
        Object nameObj = doc.get("name");
        if (nameObj instanceof Document nDoc) {
            String s = getSafeLocalized(nDoc);
            if (s != null) return s;
        } else if (nameObj instanceof String s && !s.isBlank()) {
            return s.trim();
        }

        // 2. Title
        Object titleObj = doc.get("title");
        if (titleObj instanceof Document tDoc) {
            String s = getSafeLocalized(tDoc);
            if (s != null) return s;
        } else if (titleObj instanceof String s && !s.isBlank()) {
            return s.trim();
        }

        // 3. Si no té títol directe, cerquem a l'esdeveniment / event
        Object eventObj = doc.get("event");
        if (eventObj instanceof Document eventDoc) {
            Object evTitle = eventDoc.get("title");
            if (evTitle == null) evTitle = eventDoc.get("name");
            if (evTitle instanceof Document etDoc) {
                String s = getSafeLocalized(etDoc);
                if (s != null) return s;
            } else if (evTitle instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }

        // 4. O a la primera descripció
        List<Document> descList = getListDocument(doc, "descriptions");
        if (!descList.isEmpty()) {
            Document d = descList.get(0);
            Object vObj = d.get("value");
            if (vObj instanceof Document vd) {
                String s = getSafeLocalized(vd);
                if (s != null) return s;
            } else if (vObj instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    private Document resolveEventDoc(Document doc, Map<String, Document> eventsByUuid) {
        if (doc == null) return null;
        Object evObj = doc.get("event");
        if (evObj == null) evObj = doc.get("relatedEvent");
        if (evObj == null) evObj = doc.get("eventRef");
        if (evObj == null) evObj = doc.get("conference");
        if (evObj == null) {
            Object evList = doc.get("events");
            if (evList instanceof List<?> l && !l.isEmpty()) evObj = l.get(0);
        }
        if (evObj == null) {
            Object relList = doc.get("relatedEvents");
            if (relList instanceof List<?> l && !l.isEmpty()) evObj = l.get(0);
        }

        if (evObj instanceof Document ed) {
            String u = ed.getString("uuid");
            if (u != null && eventsByUuid != null && eventsByUuid.containsKey(u)) {
                Document dbEv = eventsByUuid.get(u);
                Document merged = new Document(dbEv);
                merged.putAll(ed);
                return merged;
            }
            return ed;
        } else if (evObj instanceof String s && eventsByUuid != null && eventsByUuid.containsKey(s)) {
            return eventsByUuid.get(s);
        }
        return null;
    }

    private String extractActivityEventName(Document doc, Map<String, Document> eventsByUuid) {
        Document eventDoc = resolveEventDoc(doc, eventsByUuid);
        if (eventDoc == null) return null;
        Object etObj = eventDoc.get("title");
        if (etObj == null) etObj = eventDoc.get("name");
        if (etObj instanceof Document etDoc) return getSafeLocalized(etDoc);
        else if (etObj instanceof String s) return s.trim();
        return null;
    }

    private String extractActivityEventDate(Document doc, Map<String, Document> eventsByUuid) {
        Document eventDoc = resolveEventDoc(doc, eventsByUuid);
        if (eventDoc == null) return null;
        Object p = eventDoc.get("period");
        if (p instanceof Document pd) {
            String s = formatPeriodPart(pd.get("startDate"));
            String e = formatPeriodPart(pd.get("endDate"));
            if (s != null && e != null && !s.equals(e)) return s + " → " + e;
            if (s != null) return s;
            if (e != null) return e;
        }
        Object lc = eventDoc.get("lifecycle");
        if (lc instanceof Document ld) {
            String s = ld.getString("startDate");
            String e = ld.getString("endDate");
            if (s != null && e != null && !s.equals(e)) return s + " → " + e;
            if (s != null) return s;
            if (e != null) return e;
        }
        return null;
    }

    private String extractActivityEventLocation(Document doc, Map<String, Document> eventsByUuid) {
        Document eventDoc = resolveEventDoc(doc, eventsByUuid);
        if (eventDoc == null) return null;
        String loc = getSafeString(eventDoc, "location");
        String place = getSafeString(eventDoc, "place");
        String city = getSafeString(eventDoc, "city");

        String country = null;
        Object cObj = eventDoc.get("country");
        if (cObj instanceof Document cDoc) {
            Object term = cDoc.get("term");
            if (term instanceof Document termDoc) country = getSafeLocalized(termDoc);
            else if (cDoc.getString("value") != null) country = cDoc.getString("value");
        } else if (cObj instanceof String s) {
            country = s;
        }

        List<String> parts = Stream.of(place, loc, city, country)
                .filter(pt -> pt != null && !pt.isBlank())
                .distinct()
                .toList();
        if (!parts.isEmpty()) return String.join(", ", parts);
        return null;
    }

    private String extractActivityPlace(Document doc, Map<String, Document> eventsByUuid, Map<String, String> extOrgNames, Map<String, String> intOrgNames) {
        if (doc == null) return null;

        List<String> placeParts = new ArrayList<>();

        // 1. Event (Congress, Conference, Symposia, Workshop, Festival...)
        Object eventObj = doc.get("event");
        Document eventDoc = (eventObj instanceof Document ed) ? ed : null;
        if (eventDoc != null && eventDoc.getString("uuid") != null && eventsByUuid != null) {
            Document dbEv = eventsByUuid.get(eventDoc.getString("uuid"));
            if (dbEv != null) eventDoc = dbEv;
        }

        if (eventDoc != null) {
            String loc = getSafeString(eventDoc, "location");
            String place = getSafeString(eventDoc, "place");
            String city = getSafeString(eventDoc, "city");

            String country = null;
            Object cObj = eventDoc.get("country");
            if (cObj instanceof Document cDoc) {
                Object term = cDoc.get("term");
                if (term instanceof Document termDoc) country = getSafeLocalized(termDoc);
                else if (cDoc.getString("value") != null) country = cDoc.getString("value");
            } else if (cObj instanceof String s) {
                country = s;
            }

            if (place != null && !place.isBlank() && !placeParts.contains(place)) placeParts.add(place);
            if (loc != null && !loc.isBlank() && !placeParts.contains(loc)) placeParts.add(loc);
            if (city != null && !city.isBlank() && !placeParts.contains(city)) placeParts.add(city);
            if (country != null && !country.isBlank() && !placeParts.contains(country)) placeParts.add(country);
        }

        // 2. MemberOf / ExternalOrganization
        Object memberOfObj = doc.get("memberOf");
        if (memberOfObj instanceof Document mDoc) {
            Object extOrgObj = mDoc.get("externalOrganization");
            if (extOrgObj instanceof Document eoDoc) {
                String eoUuid = eoDoc.getString("uuid");
                String name = (eoUuid != null && extOrgNames != null) ? extOrgNames.get(eoUuid) : null;
                if (name == null) {
                    Object nameObj = eoDoc.get("name");
                    if (nameObj instanceof Document nDoc) name = getSafeLocalized(nDoc);
                    else if (nameObj instanceof String s) name = s;
                }
                if (name != null && !name.isBlank() && !placeParts.contains(name)) {
                    placeParts.add(name);
                }
            }
        }

        // 3. External organizations list
        List<Document> extList = getListDocument(doc, "externalOrganizations");
        for (Document eo : extList) {
            String eoUuid = eo.getString("uuid");
            String name = (eoUuid != null && extOrgNames != null) ? extOrgNames.get(eoUuid) : null;
            if (name == null) {
                Object nameObj = eo.get("name");
                if (nameObj instanceof Document nDoc) name = getSafeLocalized(nDoc);
                else if (nameObj instanceof String s) name = s;
            }
            if (name != null && !name.isBlank() && !placeParts.contains(name)) {
                placeParts.add(name);
            }
        }

        // 4. Journal / Revista (per a comitès editorials / avaluació)
        Object jObj = doc.get("journal");
        if (jObj instanceof Document jDoc) {
            Object jtObj = jDoc.get("title");
            String jt = null;
            if (jtObj instanceof Document jtDoc) jt = getSafeLocalized(jtDoc);
            else if (jtObj instanceof String s) jt = s;
            if (jt != null && !jt.isBlank() && !placeParts.contains(jt)) {
                placeParts.add("Revista: " + jt);
            }
        }

        // 5. Publisher / Editorial
        Object pubObj = doc.get("publisher");
        if (pubObj instanceof Document pubDoc) {
            Object pnObj = pubDoc.get("name");
            String pn = null;
            if (pnObj instanceof Document pnDoc) pn = getSafeLocalized(pnDoc);
            else if (pnObj instanceof String s) pn = s;
            if (pn != null && !pn.isBlank() && !placeParts.contains(pn)) {
                placeParts.add("Editorial: " + pn);
            }
        }

        // 6. Direct location, place, city, country
        String directPlace = getSafeString(doc, "place");
        if (directPlace != null && !placeParts.contains(directPlace)) placeParts.add(directPlace);
        String directLoc = getSafeString(doc, "location");
        if (directLoc != null && !placeParts.contains(directLoc)) placeParts.add(directLoc);
        String directCity = getSafeString(doc, "city");
        if (directCity != null && !placeParts.contains(directCity)) placeParts.add(directCity);
        String directCountry = getSafeString(doc, "country");
        if (directCountry != null && !placeParts.contains(directCountry)) placeParts.add(directCountry);

        if (placeParts.isEmpty()) {
            return "";
        }

        return String.join(" · ", placeParts);
    }

    private String extractActivityDate(Document doc) {
        if (doc == null) return null;
        Object periodObj = doc.get("period");
        if (periodObj instanceof Document pDoc) {
            String start = formatPeriodPart(pDoc.get("startDate"));
            String end = formatPeriodPart(pDoc.get("endDate"));
            if (start != null && end != null && !start.equals(end)) {
                return start + " - " + end;
            } else if (start != null) {
                return start;
            } else if (end != null) {
                return end;
            }
        }
        return null;
    }

    private Integer extractActivityYear(Document doc) {
        if (doc == null) return null;
        Object periodObj = doc.get("period");
        if (periodObj instanceof Document pDoc) {
            Object start = pDoc.get("startDate");
            if (start instanceof Document sDoc && sDoc.get("year") instanceof Number n) {
                return n.intValue();
            } else if (start != null) {
                String s = start.toString();
                if (s.length() >= 4) {
                    try { return Integer.parseInt(s.substring(0, 4)); } catch (Exception ignored) {}
                }
            }
            Object end = pDoc.get("endDate");
            if (end instanceof Document eDoc && eDoc.get("year") instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    private String formatPeriodPart(Object datePart) {
        if (datePart == null) return null;
        if (datePart instanceof Document d) {
            Integer year = d.get("year") instanceof Number n ? n.intValue() : null;
            Integer month = d.get("month") instanceof Number n ? n.intValue() : null;
            Integer day = d.get("day") instanceof Number n ? n.intValue() : null;
            if (year != null && month != null && day != null) {
                return String.format("%02d/%02d/%04d", day, month, year);
            } else if (year != null && month != null) {
                return String.format("%02d/%04d", month, year);
            } else if (year != null) {
                return String.valueOf(year);
            }
        } else if (datePart instanceof String s && !s.isBlank()) {
            return s.length() >= 10 ? s.substring(0, 10) : s;
        }
        return null;
    }

    private String extractActivityDescription(Document doc) {
        if (doc == null) return null;
        List<Document> descList = getListDocument(doc, "descriptions");
        if (!descList.isEmpty()) {
            for (Document d : descList) {
                Object valObj = d.get("value");
                if (valObj instanceof Document vd) {
                    String s = getSafeLocalized(vd);
                    if (s != null) return s;
                } else if (valObj instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Document> getListDocument(Document doc, String key) {
        if (doc == null) return Collections.emptyList();
        Object val = doc.get(key);
        if (val instanceof List<?> l) {
            return (List<Document>) l;
        }
        return Collections.emptyList();
    }
}
