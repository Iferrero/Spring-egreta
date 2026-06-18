package com.example.demo.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;

/**
 * Proxy cap a l'API REST d'Egreta per a l'edició massiva de tipologies.
 * Equivalent Java del script Python: edicio_tipologies / readExcel.
 *
 * Endpoints:
 *   GET  /api/egreta-editor/fetch   → cerca un document per UUID a l'API d'Egreta
 *   POST /api/egreta-editor/process → processa un lot de files (GET + edita + PUT opcional)
 *
 * La API key d'Egreta queda fixa al codi (no es configura des de la UI).
 */
@RestController
@RequestMapping("/api/egreta-editor")
@CrossOrigin(origins = "*")
public class EgretaEditorController {

    private static final String API_KEY  = "9971c3cc-b3e0-48e3-9ff9-e990c795e92f";
    private static final String URL_TEST = "https://egretat.uab.cat/ws/api/";
    private static final String URL_PROD = "https://egreta.uab.cat/ws/api/";

    private final RestTemplate restTemplate = new RestTemplate();

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json;charset=utf-8");
        h.set("api-key", API_KEY);
        h.set("Accept-Charset", "UTF-8");
        return h;
    }

    private String baseUrl(String entorn) {
        return "test".equalsIgnoreCase(entorn) ? URL_TEST : URL_PROD;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/egreta-editor/fetch?entorn=&coleccio=&uuid=
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retorna el JSON d'un document d'Egreta per col·lecció + UUID.
     * S'usa per previsualitzar abans d'executar el lot.
     */
    @GetMapping("/fetch")
    public ResponseEntity<?> fetchDocument(
            @RequestParam String entorn,
            @RequestParam String coleccio,
            @RequestParam String uuid) {
        try {
            String url = baseUrl(entorn) + coleccio + "/" + uuid.trim().toLowerCase();
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders()), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", "No trobat: " + uuid,
                                 "httpStatus", e.getStatusCode().value()));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/egreta-editor/parse-excel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rep un fitxer Excel (.xlsx o .xls), llegeix la primera pestanya
     * i retorna una llista de files, on cada fila és una llista de cel·les (strings).
     */
    @PostMapping(value = "/parse-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseExcel(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "L'arxiu està buit."));
            }

            List<List<String>> rows = new ArrayList<>();
            try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = workbook.getSheetAt(0);
                DataFormatter formatter = new DataFormatter();

                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    int lastCellNum = row.getLastCellNum();
                    for (int i = 0; i < lastCellNum; i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        cells.add(formatter.formatCellValue(cell));
                    }
                    rows.add(cells);
                }
            }

            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Error en llegir l'Excel: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/egreta-editor/process
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processa un lot de files. Per cada fila:
     *  1. Fa GET a Egreta per UUID
     *  2. Valida que l'UUID coincideixi
     *  3. Aplica la lògica d'edicioDades (equivalent a edicio_dades del Python)
     *  4. Si updateEnabled=true, fa PUT a Egreta
     *
     * Request body (JSON):
     * {
     *   "entorn":        "test" | "prod",
     *   "coleccio":      "awards" | "applications" | ...,
     *   "updateEnabled": true | false,
     *   "clave":         "uri",                  // camp a editar dins l'element
     *   "dictType":      null | "dict" | "list", // tipus de contenidor
     *   "dictName":      "natureTypes",           // nom del contenidor (si dictType != null)
     *   "dictValue":     "",                      // valor actual a cercar (per a list)
     *   "files": [
     *     { "uuid": "...", "valor": "..." },
     *     ...
     *   ]
     * }
     *
     * Retorna una llista de resultats per fila.
     */
    @PostMapping("/process")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> processBatch(@RequestBody Map<String, Object> request) {

        String entorn        = (String) request.getOrDefault("entorn", "test");
        String coleccio      = (String) request.getOrDefault("coleccio", "awards");
        boolean updateEnabled= Boolean.TRUE.equals(request.get("updateEnabled"));
        String clave         = (String) request.getOrDefault("clave", "uri");
        String dictType      = (String) request.get("dictType");   // may be null
        String dictName      = (String) request.get("dictName");   // may be null
        String dictValue     = (String) request.get("dictValue");  // may be null

        List<Map<String, Object>> files = (List<Map<String, Object>>) request.get("files");
        List<Map<String, Object>> resultats = new ArrayList<>();

        if (files == null || files.isEmpty()) {
            return resultats;
        }

        for (Map<String, Object> fila : files) {
            String uuid  = String.valueOf(fila.getOrDefault("uuid", "")).trim().toLowerCase();
            String valor = String.valueOf(fila.getOrDefault("valor", "")).trim().toLowerCase();

            Map<String, Object> resultat = new LinkedHashMap<>();
            resultat.put("uuid",  uuid);
            resultat.put("valor", valor);

            if (uuid.isBlank() || uuid.equals("null")) {
                resultat.put("status",  "SKIP");
                resultat.put("message", "UUID buit, fila ignorada");
                resultats.add(resultat);
                continue;
            }

            try {
                // ── 1. GET ──────────────────────────────────────────────────
                String getUrl = baseUrl(entorn) + coleccio + "/" + uuid;
                ResponseEntity<Map> getResp = restTemplate.exchange(
                        getUrl, HttpMethod.GET, new HttpEntity<>(buildHeaders()), Map.class);

                if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                    resultat.put("status",  "NOT_FOUND");
                    resultat.put("message", "No trobat a Egreta (status " + getResp.getStatusCode() + ")");
                    resultats.add(resultat);
                    continue;
                }

                Map<String, Object> data = new LinkedHashMap<>(getResp.getBody());
                Object pureIdObj = data.get("pureId");
                if (pureIdObj != null) resultat.put("pureId", pureIdObj);

                // ── 2. Validació UUID ────────────────────────────────────────
                String uuidEgreta = String.valueOf(data.getOrDefault("uuid", "")).toLowerCase();
                if (!uuid.equals(uuidEgreta)) {
                    resultat.put("status",  "ERROR");
                    resultat.put("message", "UUID no coincideix: Egreta=" + uuidEgreta + " / Excel=" + uuid);
                    resultats.add(resultat);
                    continue;
                }

                // ── 3. Editar ────────────────────────────────────────────────
                boolean changed = edicioDades(data, clave, valor, dictType, dictName, dictValue);

                if (!changed) {
                    resultat.put("status",  "SKIP");
                    resultat.put("message", "Ja existia el valor '" + valor + "', no cal actualitzar");
                    resultats.add(resultat);
                    continue;
                }

                if (!updateEnabled) {
                    // Dry run: retornem el JSON modificat però no fem PUT
                    resultat.put("status",  "DRY_RUN");
                    resultat.put("message", "Previsualització correcta (sense actualitzar)");
                    resultat.put("preview", summarizeEdit(data, clave, dictType, dictName, valor));
                    resultats.add(resultat);
                    continue;
                }

                // ── 4. PUT ───────────────────────────────────────────────────
                String putUrl = baseUrl(entorn) + coleccio + "/" + uuid;
                HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(data, buildHeaders());
                ResponseEntity<Map> putResp = restTemplate.exchange(
                        putUrl, HttpMethod.PUT, putEntity, Map.class);

                if (putResp.getStatusCode().is2xxSuccessful()) {
                    resultat.put("status",   "OK");
                    resultat.put("message",  "Actualitzat correctament (HTTP " + putResp.getStatusCode() + ")");
                    resultat.put("updated",  true);
                } else {
                    resultat.put("status",  "ERROR");
                    resultat.put("message", "Error PUT: HTTP " + putResp.getStatusCode());
                }

            } catch (HttpClientErrorException e) {
                resultat.put("status",  "ERROR");
                resultat.put("message", "HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
            } catch (Exception e) {
                resultat.put("status",  "ERROR");
                resultat.put("message", "Excepció: " + e.getMessage());
            }

            resultats.add(resultat);
        }

        return resultats;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lògica d'edició  (equivalent Python: edicio_dades)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aplica l'edició sobre el mapa `data` in-place.
     * Retorna true si s'ha fet algun canvi, false si el valor ja existia.
     *
     * Modes (dictType):
     *   null / "" → camp simple: data[clave] = valor
     *   "dict"    → camp dins un objecte annidat: data[dictName][clave] = valor
     *   "list"    → element dins una llista: data[dictName][?][clave] = valor
     *              Si clave conté ".", navega per sub-camps (p.ex. "role.uri")
     */
    @SuppressWarnings("unchecked")
    private boolean edicioDades(Map<String, Object> data,
                                 String clave, String valor,
                                 String dictType, String dictName, String dictValue) {

        // ── Camp simple ──────────────────────────────────────────────────────
        if (dictType == null || dictType.isBlank()) {
            String actual = String.valueOf(data.getOrDefault(clave, ""));
            if (valor.equals(actual)) return false;
            data.put(clave, valor);
            return true;
        }

        // ── Dict (objecte annidat) ──────────────────────────────────────────
        if ("dict".equals(dictType)) {
            Map<String, Object> nested = (Map<String, Object>) data.computeIfAbsent(
                    dictName, k -> new LinkedHashMap<>());
            String actual = String.valueOf(nested.getOrDefault(clave, ""));
            if (dictValue != null && !dictValue.isBlank()) {
                // Comprovem contra dictValue (valor actual esperat), no contra valor nou
                if (dictValue.equals(actual)) {
                    return false; // ja correcte
                }
                nested.put(clave, valor);
                return true;
            }
            if (valor.equals(actual)) return false;
            nested.put(clave, valor);
            return true;
        }

        // ── List ─────────────────────────────────────────────────────────────
        if ("list".equals(dictType)) {
            List<Map<String, Object>> list;
            if (!data.containsKey(dictName)) {
                list = new ArrayList<>();
                data.put(dictName, list);
                Map<String, Object> nouElement = new LinkedHashMap<>();
                setNestedValue(nouElement, clave, valor);
                list.add(nouElement);
                return true;
            }
            list = (List<Map<String, Object>>) data.get(dictName);

            boolean[] dotNotation = {clave.contains(".")};
            String[] parts = dotNotation[0] ? clave.split("\\.", 2) : new String[]{clave};

            for (Map<String, Object> elem : list) {
                String elemVal;
                if (dotNotation[0]) {
                    Map<?, ?> nestedMap = (Map<?, ?>) elem.getOrDefault(parts[0], Map.of());
                    Object nestedVal = nestedMap.get(parts[1]);
                    elemVal = String.valueOf(nestedVal == null ? "" : nestedVal);
                } else {
                    elemVal = String.valueOf(elem.getOrDefault(clave, ""));
                }

                boolean matchesDictValue = dictValue != null && !dictValue.isBlank()
                        && elem.containsValue(dictValue);

                if (matchesDictValue) {
                    setNestedValue(elem, clave, valor);
                    return true;
                }
                if (valor.equals(elemVal)) {
                    return false; // ja correcte
                }
                if (list.size() == 1) {
                    setNestedValue(elem, clave, valor);
                    return true;
                }
            }
            // No trobat → afegim element nou
            Map<String, Object> nouElement = new LinkedHashMap<>();
            setNestedValue(nouElement, clave, valor);
            list.add(nouElement);
            return true;
        }

        return false;
    }

    /**
     * Assigna `valor` a la clau `clave` (pot ser "simple" o "pare.fill") dins `elem`.
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> elem, String clave, String valor) {
        if (clave.contains(".")) {
            String[] parts = clave.split("\\.", 2);
            Map<String, Object> nested = (Map<String, Object>) elem.computeIfAbsent(
                    parts[0], k -> new LinkedHashMap<>());
            nested.put(parts[1], valor);
        } else {
            elem.put(clave, valor);
        }
    }

    /**
     * Retorna un resum de l'edició per mostrar-lo en el dry-run.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> summarizeEdit(Map<String, Object> data,
                                               String clave, String dictType,
                                               String dictName, String valor) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if ("list".equals(dictType) && dictName != null && data.containsKey(dictName)) {
            summary.put(dictName, data.get(dictName));
        } else if ("dict".equals(dictType) && dictName != null && data.containsKey(dictName)) {
            summary.put(dictName, data.get(dictName));
        } else {
            summary.put(clave, valor);
        }
        return summary;
    }
}
