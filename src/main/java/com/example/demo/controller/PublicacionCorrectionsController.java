package com.example.demo.controller;

import com.example.demo.model.PublicacionCorreccion;
import com.example.demo.repository.PublicacionCorreccionRepository;
import com.example.demo.service.AbstractScraperService;
import com.example.demo.service.CrossrefService;
import com.example.demo.service.EgretaApiService;
import com.example.demo.service.PublicacionCorrectionsService;
import com.example.demo.util.PublicacionDocumentUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Controlador REST per a la gestió de correccions i controls de qualitat de publicacions (/api/pure/corrections/*).
 */
@RestController
@RequestMapping("/api/pure")
@CrossOrigin(origins = "*")
public class PublicacionCorrectionsController {

    private final MongoTemplate mongoTemplate;
    private final PublicacionCorreccionRepository publicacionCorreccionRepository;
    private final PublicacionCorrectionsService publicacionCorrectionsService;
    private final CrossrefService crossrefService;
    private final AbstractScraperService abstractScraperService;
    private final EgretaApiService egretaApiService;

    @Autowired
    public PublicacionCorrectionsController(
            MongoTemplate mongoTemplate,
            PublicacionCorreccionRepository publicacionCorreccionRepository,
            PublicacionCorrectionsService publicacionCorrectionsService,
            CrossrefService crossrefService,
            AbstractScraperService abstractScraperService,
            EgretaApiService egretaApiService) {
        this.mongoTemplate = mongoTemplate;
        this.publicacionCorreccionRepository = publicacionCorreccionRepository;
        this.publicacionCorrectionsService = publicacionCorrectionsService;
        this.crossrefService = crossrefService;
        this.abstractScraperService = abstractScraperService;
        this.egretaApiService = egretaApiService;
    }

    @GetMapping("/stats/corrections")
    public Map<String, Object> getPublicationCorrections(@RequestParam(required = false) Integer anio) {
        return publicacionCorrectionsService.getPublicationCorrections(anio);
    }

    @PostMapping("/corrections/{uuid}/review")
    public ResponseEntity<?> saveReview(
            @PathVariable String uuid,
            @RequestBody Map<String, Object> body) {
        try {
            boolean reviewed = (Boolean) body.getOrDefault("reviewed", false);
            String observations = (String) body.getOrDefault("observations", "");

            PublicacionCorreccion pc = publicacionCorreccionRepository.findById(uuid)
                    .orElseGet(() -> {
                        PublicacionCorreccion newPc = new PublicacionCorreccion();
                        newPc.setId(uuid);
                        return newPc;
                    });
            pc.setReviewed(reviewed);
            pc.setObservations(observations);
            pc.setModifiedDate(new Date());

            publicacionCorreccionRepository.save(pc);

            return ResponseEntity.ok(Map.of("success", true, "message", "Revisió desada correctament"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/unpaywall")
    public ResponseEntity<?> checkUnpaywall(@RequestParam String doi) {
        try {
            String cleanDoi = doi.trim();
            if (cleanDoi.startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
            if (cleanDoi.startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());

            String encodedDoi = URLEncoder.encode(cleanDoi, StandardCharsets.UTF_8);
            String url = "https://api.unpaywall.org/v2/" + encodedDoi + "?email=recerca@uab.cat";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);

                Boolean isOa = (Boolean) data.get("is_oa");
                String oaStatus = (String) data.get("oa_status");
                String bestUrl = "";
                Map<String, Object> bestLocus = (Map<String, Object>) data.get("best_oa_location");
                if (bestLocus != null) {
                    bestUrl = (String) bestLocus.get("url");
                }

                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", true);
                res.put("isOa", isOa != null ? isOa : false);
                res.put("oaStatus", oaStatus != null ? oaStatus : "unknown");
                res.put("url", bestUrl != null ? bestUrl : "");
                return ResponseEntity.ok(res);
            } else {
                return ResponseEntity.status(response.statusCode()).body(Map.of("success", false, "message", "HTTP " + response.statusCode()));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/{uuid}/suggest-doi")
    public ResponseEntity<?> suggestDoi(@PathVariable String uuid) {
        try {
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            String mainTitle = PublicacionDocumentUtils.nestedString(pub, "title", "value");
            if (mainTitle == null || mainTitle.isBlank()) mainTitle = PublicacionDocumentUtils.nestedString(pub, "title", "en_GB");
            if (mainTitle == null || mainTitle.isBlank()) mainTitle = PublicacionDocumentUtils.nestedString(pub, "title", "es_ES");
            if (mainTitle == null || mainTitle.isBlank()) mainTitle = PublicacionDocumentUtils.nestedString(pub, "title", "ca_ES");

            String subTitle = PublicacionDocumentUtils.nestedString(pub, "subTitle", "value");
            if (subTitle == null || subTitle.isBlank()) subTitle = PublicacionDocumentUtils.nestedString(pub, "subTitle", "en_GB");

            if (mainTitle == null || mainTitle.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "La publicació no té títol per cercar"));
            }

            String fullTitle = (subTitle != null && !subTitle.isBlank()) ? (mainTitle + " " + subTitle) : mainTitle;
            String cleanMainTitle = PublicacionDocumentUtils.cleanRawTitle(mainTitle);
            String cleanFullTitle = PublicacionDocumentUtils.cleanRawTitle(fullTitle);

            List<String> pubAuthors = crossrefService.extractAllAuthorLastNames(pub);
            String authorSearch = crossrefService.extractAuthorSearchString(pub);
            Integer year = crossrefService.getPublicationYear(pub);
            String journalIssn = crossrefService.extractJournalIssn(pub);
            String journalTitle = crossrefService.extractJournalTitle(pub);

            String transTitle = crossrefService.extractEnglishTitle(pub);
            String cleanTransTitle = transTitle != null ? PublicacionDocumentUtils.cleanRawTitle(transTitle) : null;

            Map<String, Map<String, Object>> suggestionsMap = new LinkedHashMap<>();

            if (journalIssn != null && !journalIssn.isBlank()) {
                crossrefService.queryCrossrefByFields(cleanFullTitle, authorSearch, null, journalIssn, suggestionsMap, pubAuthors, cleanFullTitle, cleanMainTitle, cleanTransTitle);
            }
            if (suggestionsMap.size() < 3 && journalTitle != null && !journalTitle.isBlank()) {
                crossrefService.queryCrossrefByFields(cleanFullTitle, authorSearch, journalTitle, null, suggestionsMap, pubAuthors, cleanFullTitle, cleanMainTitle, cleanTransTitle);
            }
            if (suggestionsMap.size() < 3) {
                crossrefService.queryCrossrefByFields(cleanFullTitle, authorSearch, null, null, suggestionsMap, pubAuthors, cleanFullTitle, cleanMainTitle, cleanTransTitle);
            }
            if (suggestionsMap.size() < 3 && !cleanMainTitle.equals(cleanFullTitle)) {
                crossrefService.queryCrossrefByFields(cleanMainTitle, authorSearch, null, null, suggestionsMap, pubAuthors, cleanFullTitle, cleanMainTitle, cleanTransTitle);
            }
            if (suggestionsMap.size() < 3) {
                crossrefService.queryAndCollectCrossref(cleanFullTitle, authorSearch, year, suggestionsMap, pubAuthors, cleanFullTitle, cleanMainTitle, cleanTransTitle);
            }
            if (suggestionsMap.size() < 3 && cleanTransTitle != null && !cleanTransTitle.isBlank() && !cleanTransTitle.equalsIgnoreCase(cleanMainTitle)) {
                crossrefService.queryCrossrefByFields(cleanTransTitle, authorSearch, null, null, suggestionsMap, pubAuthors, cleanFullTitle, cleanMainTitle, cleanTransTitle);
            }

            List<Map<String, Object>> suggestions = new ArrayList<>(suggestionsMap.values());

            if (year != null && year > 0) {
                suggestions.sort((a, b) -> {
                    Object yA = a.get("year");
                    Object yB = b.get("year");
                    boolean aMatchesYear = (yA instanceof Integer iA && iA.equals(year));
                    boolean bMatchesYear = (yB instanceof Integer iB && iB.equals(year));
                    if (aMatchesYear && !bMatchesYear) return -1;
                    if (!aMatchesYear && bMatchesYear) return 1;
                    return 0;
                });
            }

            if (suggestions.size() > 5) {
                suggestions = suggestions.subList(0, 5);
            }

            return ResponseEntity.ok(Map.of("success", true, "suggestions", suggestions));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/doi")
    public ResponseEntity<?> saveDoi(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String doi = body.get("doi");
            String env = body.getOrDefault("env", "test");
            if (doi == null || doi.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "DOI no vàlid"));
            }

            String cleanDoi = doi.trim();
            if (cleanDoi.toLowerCase().startsWith("http://doi.org/")) cleanDoi = cleanDoi.substring("http://doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("https://doi.org/")) cleanDoi = cleanDoi.substring("https://doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("http://dx.doi.org/")) cleanDoi = cleanDoi.substring("http://dx.doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("https://dx.doi.org/")) cleanDoi = cleanDoi.substring("https://dx.doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("doi.org/")) cleanDoi = cleanDoi.substring("doi.org/".length());
            if (cleanDoi.toLowerCase().startsWith("doi:")) cleanDoi = cleanDoi.substring("doi:".length()).trim();
            cleanDoi = cleanDoi.trim();

            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            List<Document> evs = PublicacionDocumentUtils.getListDocument(pub, "electronicVersions");
            boolean updated = false;
            for (Document ev : evs) {
                String discriminator = ev.getString("typeDiscriminator");
                if ("DoiElectronicVersion".equals(discriminator)) {
                    ev.put("doi", cleanDoi);
                    updated = true;
                    break;
                }
            }

            if (!updated) {
                Document newEv = new Document();
                newEv.put("typeDiscriminator", "DoiElectronicVersion");
                newEv.put("doi", cleanDoi);

                Document accessType = new Document();
                accessType.put("uri", "/dk/atira/pure/core/openaccesspermission/unknown");
                Document accessTerm = new Document();
                accessTerm.put("en_GB", "Unknown");
                accessTerm.put("es_ES", "Sin especificar");
                accessTerm.put("ca_ES", "Sense especificar");
                accessType.put("term", accessTerm);
                newEv.put("accessType", accessType);

                Document versionType = new Document();
                versionType.put("uri", "/dk/atira/pure/researchoutput/electronicversion/versiontype/publishersversion");
                Document versionTerm = new Document();
                versionTerm.put("en_GB", "Final published version");
                versionTerm.put("es_ES", "Versión publicada final");
                versionTerm.put("ca_ES", "Versió publicada final");
                versionType.put("term", versionTerm);
                newEv.put("versionType", versionType);

                evs.add(newEv);
            }

            pub.put("electronicVersions", evs);

            String egretaError = egretaApiService.syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }

            Update update = new Update().set("electronicVersions", evs);
            mongoTemplate.updateFirst(query, update, "Researchoutputs");
            publicacionCorrectionsService.clearDuplicatesCache();
            publicacionCorrectionsService.autoMarkAsReviewedIfNoErrorsLeft(uuid, pub);

            return ResponseEntity.ok(Map.of("success", true, "message", "DOI afegit correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/isbn")
    public ResponseEntity<?> saveIsbn(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String isbn = body.get("isbn");
            String env = body.getOrDefault("env", "test");
            if (isbn == null || isbn.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "ISBN no vàlid"));
            }

            String cleanIsbn = isbn.trim();
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            List<String> printIsbns = (List<String>) pub.get("printISBNs");
            if (printIsbns == null) {
                printIsbns = new ArrayList<>();
            }
            if (!printIsbns.contains(cleanIsbn)) {
                printIsbns.add(cleanIsbn);
            }
            pub.put("printISBNs", printIsbns);

            String egretaError = egretaApiService.syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }

            Update update = new Update().set("printISBNs", printIsbns);
            mongoTemplate.updateFirst(query, update, "Researchoutputs");
            publicacionCorrectionsService.autoMarkAsReviewedIfNoErrorsLeft(uuid, pub);
            return ResponseEntity.ok(Map.of("success", true, "message", "ISBN afegit correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/issn")
    public ResponseEntity<?> saveIssn(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String issn = body.get("issn");
            String env = body.getOrDefault("env", "test");
            if (issn == null || issn.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "ISSN no vàlid"));
            }

            String cleanIssn = issn.trim();
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            Document journalAssoc = (Document) pub.get("journalAssociation");
            if (journalAssoc == null) {
                journalAssoc = new Document();
            }

            Integer issnPureId = null;
            Document journalRef = journalAssoc.get("journal") instanceof Document jr ? jr : null;
            if (journalRef != null) {
                String journalUuid = journalRef.getString("uuid");
                if (journalUuid != null) {
                    try {
                        issnPureId = egretaApiService.syncJournalIssnToEgreta(journalUuid, cleanIssn, env);
                    } catch (Exception e) {
                        return ResponseEntity.status(500).body(Map.of(
                                "success", false,
                                "message", "Error al sincronitzar la revista vinculada amb l'API d'Egreta: " + e.getMessage()
                        ));
                    }
                }
            }

            Object existingIssn = journalAssoc.get("issn");
            Document issnDoc = null;
            if (existingIssn instanceof Document doc) {
                issnDoc = doc;
            } else {
                issnDoc = new Document();
            }
            issnDoc.remove("value");
            issnDoc.put("issn", cleanIssn);
            if (issnPureId != null) {
                issnDoc.put("pureId", issnPureId);
            } else {
                issnDoc.remove("pureId");
            }
            journalAssoc.put("issn", issnDoc);
            pub.put("journalAssociation", journalAssoc);

            String egretaError = egretaApiService.syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar la publicació amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }

            Update update = new Update().set("journalAssociation", journalAssoc);
            mongoTemplate.updateFirst(query, update, "Researchoutputs");

            journalRef = journalAssoc.get("journal") instanceof Document jr ? jr : null;
            if (journalRef != null) {
                String journalUuid = journalRef.getString("uuid");
                if (journalUuid != null) {
                    Query jQuery = new Query(Criteria.where("uuid").is(journalUuid));
                    Document journalDoc = mongoTemplate.findOne(jQuery, Document.class, "Journals");
                    if (journalDoc != null) {
                        List<Document> jIssns = PublicacionDocumentUtils.getListDocument(journalDoc, "issns");
                        if (jIssns == null) jIssns = new ArrayList<>();
                        boolean alreadyExists = jIssns.stream()
                                .anyMatch(obj -> cleanIssn.equals(obj.getString("issn")));
                        if (!alreadyExists) {
                            Document newIssnEntry = new Document("issn", cleanIssn);
                            jIssns.add(newIssnEntry);
                            journalDoc.put("issns", jIssns);
                            mongoTemplate.save(journalDoc, "Journals");
                        }
                    }
                }
            }

            publicacionCorrectionsService.autoMarkAsReviewedIfNoErrorsLeft(uuid, pub);
            return ResponseEntity.ok(Map.of("success", true, "message", "ISSN afegit correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/language")
    public ResponseEntity<?> updateLanguage(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        try {
            String langCode = body.get("language");
            if (langCode == null || langCode.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Idioma no vàlid"));
            }

            String cleanLang = langCode.trim();
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            Document languageDoc = new Document();
            languageDoc.put("uri", "/dk/atira/pure/core/languages/" + cleanLang);

            Map<String, String> term = new LinkedHashMap<>();
            Query langQuery = new Query(Criteria.where("baseUri").is("/dk/atira/pure/core/languages"));
            Document schemeDoc = mongoTemplate.findOne(langQuery, Document.class, "Classificationschemes");
            boolean resolved = false;
            if (schemeDoc != null) {
                List<Document> contained = (List<Document>) schemeDoc.get("containedClassifications");
                if (contained != null) {
                    for (Document c : contained) {
                        String uri = c.getString("uri");
                        if (uri != null && uri.endsWith("/" + cleanLang)) {
                            Document termDoc = (Document) c.get("term");
                            if (termDoc != null) {
                                List<Document> textList = (List<Document>) termDoc.get("text");
                                if (textList != null) {
                                    for (Document t : textList) {
                                        String locale = t.getString("locale");
                                        String value = t.getString("value");
                                        if (locale != null && value != null) {
                                            term.put(locale, value);
                                        }
                                    }
                                    resolved = true;
                                }
                            }
                            break;
                        }
                    }
                }
            }

            if (!resolved) {
                if ("ca_ES".equals(cleanLang)) {
                    term.put("ca_ES", "Català");
                    term.put("es_ES", "Catalán");
                    term.put("en_GB", "Catalan");
                } else if ("es_ES".equals(cleanLang)) {
                    term.put("ca_ES", "Espanyol");
                    term.put("es_ES", "Español");
                    term.put("en_GB", "Spanish");
                } else if ("en_GB".equals(cleanLang)) {
                    term.put("ca_ES", "Anglès");
                    term.put("es_ES", "Inglés");
                    term.put("en_GB", "English");
                } else {
                    term.put("ca_ES", cleanLang);
                    term.put("es_ES", cleanLang);
                    term.put("en_GB", cleanLang);
                }
            }
            languageDoc.put("term", term);
            pub.put("language", languageDoc);

            String env = body.getOrDefault("env", "test");
            boolean egretaSyncSuccess = egretaApiService.syncResearchOutputLanguageToEgreta(uuid, languageDoc, env);
            if (!egretaSyncSuccess) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb l'API d'Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + ")."
                ));
            }

            Update update = new Update().set("language", languageDoc);
            mongoTemplate.updateFirst(query, update, "Researchoutputs");
            publicacionCorrectionsService.autoMarkAsReviewedIfNoErrorsLeft(uuid, pub);
            return ResponseEntity.ok(Map.of("success", true, "message", "Idioma actualitzat correctament a Egreta i a la base de dades local."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/{uuid}/suggest-abstract")
    public ResponseEntity<?> suggestAbstract(@PathVariable String uuid) {
        try {
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            Map<String, String> suggestedMap = new LinkedHashMap<>();

            // 1. Cercar primer a Crossref API si la publicació té DOI
            String doi = null;
            List<Document> evs = PublicacionDocumentUtils.getListDocument(pub, "electronicVersions");
            if (evs != null) {
                for (Document ev : evs) {
                    String d = ev.getString("doi");
                    if (d != null && !d.isBlank()) {
                        doi = d.trim();
                        break;
                    }
                }
            }

            if (doi != null && !doi.isBlank()) {
                String crossrefAbstract = abstractScraperService.fetchAbstractFromCrossref(doi);
                if (crossrefAbstract != null && !crossrefAbstract.isBlank()) {
                    suggestedMap.put("en_GB", crossrefAbstract);
                }
            }

            // 2. Si no s'ha trobat a Crossref, cercar als enllaços directes (DDD / Dialnet)
            if (suggestedMap.isEmpty()) {
                List<Document> links = PublicacionDocumentUtils.getListDocument(pub, "links");
                if (links != null) {
                    for (Document lk : links) {
                        String url = lk.getString("url");
                        if (url != null && !url.isBlank()) {
                            String urlLower = url.toLowerCase().trim();
                            String targetUrl = url.trim();
                            if (urlLower.startsWith("http://")) {
                                targetUrl = "https://" + targetUrl.substring(7);
                            }
                            if (urlLower.contains("ddd.uab.cat")) {
                                Map<String, String> dddAbs = abstractScraperService.fetchAbstractFromDdd(targetUrl);
                                if (dddAbs != null) {
                                    suggestedMap.putAll(dddAbs);
                                }
                            } else if (urlLower.contains("dialnet.unirioja")) {
                                Map<String, String> dialnetAbs = abstractScraperService.fetchAbstractFromDialnet(targetUrl);
                                if (dialnetAbs != null) {
                                    suggestedMap.putAll(dialnetAbs);
                                }
                            }
                        }
                    }
                }
            }

            // 3. Fallback a Semantic Scholar API si encara no hi ha resum
            if (suggestedMap.isEmpty() && doi != null && !doi.isBlank()) {
                String title = PublicacionDocumentUtils.nestedString(pub, "title", "value");
                String cleanTitle = title != null ? title.replaceAll("<[^>]*>", "").replaceAll("[^\\p{L}0-9\\s]", " ").replaceAll("\\s+", " ").trim() : "";

                String abstractText = abstractScraperService.fetchAbstractFromApis(doi, cleanTitle);
                if (abstractText != null && !abstractText.isBlank()) {
                    suggestedMap.put("en_GB", abstractText);
                }
            }

            if (suggestedMap.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "No s'ha trobat cap resum per a aquesta publicació en les fonts externes o enllaços.",
                        "abstracts", Map.of()
                ));
            }

            return ResponseEntity.ok(Map.of("success", true, "abstracts", suggestedMap));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/abstract")
    public ResponseEntity<?> saveAbstract(
            @PathVariable String uuid,
            @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> abstracts = (Map<String, String>) body.get("abstracts");
            String env = (String) body.getOrDefault("env", "test");

            if (abstracts == null || abstracts.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "El resum no pot estar buit"));
            }

            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            Document abDoc = (Document) pub.get("abstract");
            if (abDoc == null) {
                abDoc = new Document();
            }
            for (Map.Entry<String, String> entry : abstracts.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    abDoc.put(entry.getKey(), PublicacionDocumentUtils.sanitizeAbstractText(entry.getValue().trim()));
                } else {
                    abDoc.remove(entry.getKey());
                }
            }
            pub.put("abstract", abDoc);

            String egretaError = egretaApiService.syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar la publicació amb l'API d'Egreta: " + egretaError
                ));
            }

            Update update = new Update().set("abstract", abDoc);
            mongoTemplate.updateFirst(query, update, "Researchoutputs");
            publicacionCorrectionsService.autoMarkAsReviewedIfNoErrorsLeft(uuid, pub);

            return ResponseEntity.ok(Map.of("success", true, "message", "Resum desat correctament"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/corrections/{uuid}/suggest-text-quality-fix")
    public ResponseEntity<?> suggestTextQualityFix(@PathVariable String uuid) {
        try {
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            String rawTitle = PublicacionDocumentUtils.nestedString(pub, "title", "value");
            String rawSubTitle = PublicacionDocumentUtils.nestedString(pub, "subTitle", "value");

            java.util.Map.Entry<String, String> splitOriginal = PublicacionDocumentUtils.splitTitleAndSubtitle(rawTitle, rawSubTitle);
            String origTitle = splitOriginal.getKey();
            String origSubTitle = splitOriginal.getValue();

            String fixedTitle = null;
            String fixedSubTitle = null;
            List<String> explanations = new ArrayList<>();

            // 1. Cercar primer a Crossref si té DOI per recuperar el títol oficial amb la capitalització exacta de l'editorial
            String doi = null;
            List<Document> evs = PublicacionDocumentUtils.getListDocument(pub, "electronicVersions");
            if (evs != null) {
                for (Document ev : evs) {
                    String d = ev.getString("doi");
                    if (d != null && !d.isBlank()) {
                        doi = d;
                        break;
                    }
                }
            }

            if (doi != null) {
                Map<String, String> crData = crossrefService.fetchTitleAndSubtitleByDoi(doi);
                if (crData != null) {
                    java.util.Map.Entry<String, String> splitCr = PublicacionDocumentUtils.splitTitleAndSubtitle(
                            crData.get("title"), crData.get("subTitle")
                    );
                    String crTitle = splitCr.getKey();
                    String crSubTitle = splitCr.getValue();

                    if (crTitle != null && !crTitle.isBlank() && !PublicacionDocumentUtils.isAllUppercase(crTitle)) {
                        fixedTitle = crTitle;
                        explanations.add("S'ha obtingut el títol oficial des de Crossref (amb les majúscules i noms propis exactes).");
                    }
                    if (crSubTitle != null && !crSubTitle.isBlank() && !PublicacionDocumentUtils.isAllUppercase(crSubTitle)) {
                        fixedSubTitle = crSubTitle;
                    }
                }
            }

            // 2. Fallback algorísmic en cas de no tenir DOI o que Crossref no tingui el títol
            List<String> authorNames = new ArrayList<>();
            List<Document> contrs = PublicacionDocumentUtils.getListDocument(pub, "contributors");
            for (Document c : contrs) {
                Document name = (Document) c.get("name");
                if (name != null) {
                    String ln = name.getString("lastName");
                    if (ln != null && !ln.isBlank()) authorNames.add(ln);
                    String fn = name.getString("firstName");
                    if (fn != null && !fn.isBlank()) authorNames.add(fn);
                }
            }

            if (fixedTitle == null) {
                fixedTitle = PublicacionDocumentUtils.cleanTextQuality(origTitle, true, authorNames);
                if (origTitle != null && !origTitle.equals(fixedTitle)) {
                    if (PublicacionDocumentUtils.containsStrangeCharacters(origTitle)) {
                        explanations.add("S'han corregit caràcters danyats per codificació (mojibake) al títol.");
                    }
                    if (PublicacionDocumentUtils.isAllUppercase(origTitle)) {
                        explanations.add("S'ha convertit el títol escrit tot en majúscules a format oració (preservant majúscules en noms propis i acrònims).");
                    }
                }
            }

            if (fixedSubTitle == null && origSubTitle != null && !origSubTitle.isBlank()) {
                fixedSubTitle = PublicacionDocumentUtils.cleanTextQuality(origSubTitle, true, authorNames);
                if (origSubTitle != null && !origSubTitle.equals(fixedSubTitle)) {
                    explanations.add("S'ha corregit el subtítol.");
                }
            }
            if (fixedSubTitle == null) {
                fixedSubTitle = "";
            }

            if (rawTitle != null && rawTitle.contains(":") && (rawSubTitle == null || rawSubTitle.isBlank()) && !fixedSubTitle.isBlank()) {
                explanations.add("S'ha separat automàticament el subtítol després dels dos punts (:).");
            }

            boolean hasTitleChanges = false;
            if (rawTitle != null && !rawTitle.equals(fixedTitle)) {
                hasTitleChanges = true;
            }
            if (!((rawSubTitle != null ? rawSubTitle : "").trim()).equals(fixedSubTitle.trim())) {
                hasTitleChanges = true;
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("hasChanges", hasTitleChanges);
            res.put("originalTitle", rawTitle != null ? rawTitle : "");
            res.put("suggestedTitle", fixedTitle != null ? fixedTitle : "");
            res.put("originalSubTitle", rawSubTitle != null ? rawSubTitle : "");
            res.put("suggestedSubTitle", fixedSubTitle);
            res.put("explanations", explanations);

            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/corrections/{uuid}/apply-text-quality-fix")
    public ResponseEntity<?> applyTextQualityFix(
            @PathVariable String uuid,
            @RequestBody Map<String, Object> body) {
        try {
            Query query = new Query(Criteria.where("uuid").is(uuid));
            Document pub = mongoTemplate.findOne(query, Document.class, "Researchoutputs");
            if (pub == null) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Publicació no trobada"));
            }

            String newTitle = (String) body.get("title");
            if (newTitle != null && !newTitle.isBlank()) {
                Document titleDoc = (Document) pub.get("title");
                if (titleDoc == null) {
                    titleDoc = new Document();
                    pub.put("title", titleDoc);
                }
                titleDoc.put("value", newTitle.trim());
            }

            if (body.containsKey("subTitle")) {
                String newSubTitle = (String) body.get("subTitle");
                if (newSubTitle != null && !newSubTitle.isBlank()) {
                    Document subTitleDoc = (Document) pub.get("subTitle");
                    if (subTitleDoc == null) {
                        subTitleDoc = new Document();
                        pub.put("subTitle", subTitleDoc);
                    }
                    subTitleDoc.put("value", newSubTitle.trim());
                } else if (newSubTitle != null) {
                    pub.remove("subTitle");
                }
            }

            String env = (String) body.getOrDefault("env", "test");
            String egretaError = egretaApiService.syncResearchOutputToEgreta(uuid, pub, env);
            if (egretaError != null) {
                return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "message", "Error al sincronitzar amb Egreta (" + ("prod".equalsIgnoreCase(env) ? "egreta.uab.cat" : "egretat.uab.cat") + "): " + egretaError
                ));
            }

            Update update = new Update();
            if (newTitle != null && !newTitle.isBlank()) {
                update.set("title.value", newTitle.trim());
            }
            if (body.containsKey("subTitle")) {
                String newSubTitle = (String) body.get("subTitle");
                if (newSubTitle != null && !newSubTitle.isBlank()) {
                    update.set("subTitle.value", newSubTitle.trim());
                } else {
                    update.unset("subTitle");
                }
            }
            mongoTemplate.updateFirst(query, update, "Researchoutputs");
            publicacionCorrectionsService.autoMarkAsReviewedIfNoErrorsLeft(uuid, pub);

            return ResponseEntity.ok(Map.of("success", true, "message", "Text corregit i sincronitzat correctament amb Egreta."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
