package com.example.demo.service;

import com.example.demo.util.PublicacionDocumentUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Servei per a la sincronitzacio amb l'API REST d'Egreta.
 * Encapsula el patro GET + modificacio + PUT que es repetia 3 vegades
 * a PublicacionController.
 */
@Service
public class EgretaApiService {

    @Value("${app.api.key}")
    private String apiKey;

    private static final String URL_PROD = "https://egreta.uab.cat/ws/api/";
    private static final String URL_TEST = "https://egretat.uab.cat/ws/api/";

    private String baseUrl(String env) {
        return "prod".equalsIgnoreCase(env) ? URL_PROD : URL_TEST;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json;charset=utf-8");
        headers.set("api-key", apiKey);
        headers.set("Accept", "application/json");
        return headers;
    }

    public boolean syncResearchOutputLanguageToEgreta(String uuid, Document language, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = buildHeaders();
            String url = baseUrl(targetEnv) + "research-outputs/" + uuid;

            ResponseEntity<Map> getResp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                System.err.println("GET failed for Egreta research-output UUID: " + uuid);
                return false;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            Map<String, Object> langMap = new LinkedHashMap<>();
            langMap.put("uri", language.getString("uri"));
            langMap.put("term", language.get("term"));
            data.put("language", langMap);

            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
            ResponseEntity<Map> putResp = restTemplate.exchange(url, HttpMethod.PUT, putEntity, Map.class);
            return putResp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            System.err.println("Error syncing research-output language to Egreta: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public String syncResearchOutputToEgreta(String uuid, Document pub, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = buildHeaders();
            String url = baseUrl(targetEnv) + "research-outputs/" + uuid;

            ResponseEntity<Map> getResp;
            try {
                getResp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                return "La publicacio no s'ha trobat a Egreta (404) en l'entorn de "
                        + ("prod".equalsIgnoreCase(targetEnv) ? "produccio" : "tests") + ".";
            } catch (Exception e) {
                return "Error al consultar la publicacio a Egreta: " + e.getMessage();
            }

            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                return "Error al consultar la publicacio a Egreta. Status: " + getResp.getStatusCode();
            }

            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());

            if (pub.containsKey("title")) data.put("title", pub.get("title"));
            if (pub.containsKey("subTitle")) data.put("subTitle", pub.get("subTitle"));
            if (pub.containsKey("translatedTitle")) data.put("translatedTitle", pub.get("translatedTitle"));
            if (pub.containsKey("electronicVersions")) data.put("electronicVersions", pub.get("electronicVersions"));
            if (pub.containsKey("printISBNs")) data.put("printISBNs", pub.get("printISBNs"));

            if (pub.containsKey("abstract")) {
                Object abObj = pub.get("abstract");
                if (abObj instanceof Map) {
                    Map<String, Object> escapedAbstract = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) abObj).entrySet()) {
                        String lang = (String) entry.getKey();
                        Object val = entry.getValue();
                        escapedAbstract.put(lang, val instanceof String text
                                ? PublicacionDocumentUtils.escapeXmlExceptEntities(text) : val);
                    }
                    data.put("abstract", escapedAbstract);
                } else {
                    data.put("abstract", abObj);
                }
            }

            if (pub.containsKey("journalAssociation")) {
                Object localJAObj = pub.get("journalAssociation");
                if (localJAObj instanceof Map localJA) {
                    Object apiJAObj = data.get("journalAssociation");
                    if (apiJAObj instanceof Map apiJA) {
                        Map<String, Object> mergedJA = new LinkedHashMap<>((Map<String, Object>) apiJA);
                        Object localIssn = localJA.get("issn");
                        if (localIssn != null) mergedJA.put("issn", localIssn);
                        Object localJournal = localJA.get("journal");
                        if (localJournal != null) mergedJA.put("journal", localJournal);
                        data.put("journalAssociation", mergedJA);
                    } else {
                        data.put("journalAssociation", localJAObj);
                    }
                } else {
                    data.put("journalAssociation", localJAObj);
                }
            }

            try {
                HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
                ResponseEntity<Map> putResp = restTemplate.exchange(url, HttpMethod.PUT, putEntity, Map.class);
                if (putResp.getStatusCode().is2xxSuccessful()) return null;
                return "Error al desar la publicacio a Egreta. Status: " + putResp.getStatusCode();
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                String body = e.getResponseBodyAsString();
                return "Error de validacio de l'API d'Egreta (" + e.getStatusCode().value() + "): "
                        + (body.length() > 500 ? body.substring(0, 500) + "..." : body);
            } catch (Exception e) {
                return "Error al enviar la publicacio actualitzada a Egreta: " + e.getMessage();
            }
        } catch (Exception e) {
            return "Error inesperat de sincronitzacio: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    public String syncScopusIdToEgreta(String uuid, String scopusId, String targetEnv) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = buildHeaders();
            String url = baseUrl(targetEnv) + "research-outputs/" + uuid;

            ResponseEntity<Map> getResp;
            try {
                getResp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                return "La publicacio no s'ha trobat a Egreta (404) en l'entorn de "
                        + ("prod".equalsIgnoreCase(targetEnv) ? "produccio" : "tests") + ".";
            } catch (Exception e) {
                return "Error al consultar la publicacio a Egreta: " + e.getMessage();
            }

            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                return "Error al consultar la publicacio a Egreta. Status: " + getResp.getStatusCode();
            }

            Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
            List<Map<String, Object>> identifiers = (List<Map<String, Object>>) data.get("identifiers");
            if (identifiers == null) {
                identifiers = new ArrayList<>();
                data.put("identifiers", identifiers);
            } else {
                identifiers = new ArrayList<>(identifiers);
                data.put("identifiers", identifiers);
            }

            boolean found = false;
            for (Map<String, Object> idObj : identifiers) {
                if ("Scopus".equalsIgnoreCase((String) idObj.get("idSource"))) {
                    idObj.put("value", scopusId);
                    found = true;
                    break;
                }
            }

            if (!found) {
                Map<String, Object> newId = new LinkedHashMap<>();
                newId.put("typeDiscriminator", "Id");
                newId.put("idSource", "Scopus");
                newId.put("value", scopusId);
                identifiers.add(newId);
            }

            try {
                HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
                ResponseEntity<Map> putResp = restTemplate.exchange(url, HttpMethod.PUT, putEntity, Map.class);
                if (putResp.getStatusCode().is2xxSuccessful()) {
                    return null; // Sincronitzacio correcta
                }
                return "Error al desar a Egreta. Status: " + putResp.getStatusCode();
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                String body = e.getResponseBodyAsString();
                return "Error de validacio de l'API d'Egreta (" + e.getStatusCode().value() + "): "
                        + (body.length() > 500 ? body.substring(0, 500) + "..." : body);
            } catch (Exception e) {
                return "Error al enviar la publicacio actualitzada a Egreta: " + e.getMessage();
            }
        } catch (Exception e) {
            return "Error inesperat de sincronitzacio amb Egreta: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    public Integer syncJournalIssnToEgreta(String journalUuid, String newIssn, String targetEnv) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = buildHeaders();
        String url = baseUrl(targetEnv) + "journals/" + journalUuid;

        ResponseEntity<Map> getResp;
        try {
            getResp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new Exception("La revista vinculada no s'ha trobat a Egreta (404) en l'entorn de "
                    + ("prod".equalsIgnoreCase(targetEnv) ? "produccio" : "tests") + ".");
        } catch (Exception e) {
            throw new Exception("Error al consultar la revista vinculada a Egreta: " + e.getMessage());
        }

        if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
            throw new Exception("Error al consultar la revista vinculada a Egreta. Status: " + getResp.getStatusCode());
        }

        Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
        List<Map<String, Object>> issns = (List<Map<String, Object>>) data.get("issns");
        if (issns == null) issns = new ArrayList<>();

        Map<String, Object> foundIssnObj = null;
        for (Map<String, Object> iObj : issns) {
            if (newIssn.equalsIgnoreCase((String) iObj.get("issn"))) { foundIssnObj = iObj; break; }
        }

        if (foundIssnObj == null) {
            Map<String, Object> newIssnEntry = new LinkedHashMap<>();
            newIssnEntry.put("issn", newIssn);
            issns.add(newIssnEntry);
            data.put("issns", issns);
            try {
                HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, headers);
                ResponseEntity<Map> putResp = restTemplate.exchange(url, HttpMethod.PUT, putEntity, Map.class);
                if (!putResp.getStatusCode().is2xxSuccessful() || putResp.getBody() == null) {
                    throw new Exception("Error al actualitzar la revista a Egreta. Status: " + putResp.getStatusCode());
                }
                Map<String, Object> updatedJournal = putResp.getBody();
                List<Map<String, Object>> updatedIssns = (List<Map<String, Object>>) updatedJournal.get("issns");
                if (updatedIssns != null) {
                    for (Map<String, Object> iObj : updatedIssns) {
                        if (newIssn.equalsIgnoreCase((String) iObj.get("issn"))) { foundIssnObj = iObj; break; }
                    }
                }
            } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
                throw new Exception("Error de validacio de la revista a Egreta (400): " + e.getResponseBodyAsString());
            } catch (Exception e) {
                throw new Exception("Error al enviar la revista actualitzada a Egreta: " + e.getMessage());
            }
        }

        if (foundIssnObj != null) {
            Object pid = foundIssnObj.get("pureId");
            if (pid instanceof Number num) return num.intValue();
        }
        return null;
    }
}