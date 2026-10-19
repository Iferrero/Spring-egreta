package com.example.demo.controller;

import org.apache.poi.ss.usermodel.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Controller per a la càrrega massiva d'equipaments des d'Excel cap a l'API REST d'Egreta/Pure.
 */
@RestController
@RequestMapping("/api/equipment-loader")
@CrossOrigin(origins = "*")
public class EquipmentLoaderController {

    private static final Logger logger = LoggerFactory.getLogger(EquipmentLoaderController.class);

    @Value("${app.api.key}")
    private String apiKey;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    private static final String URL_TEST = "https://egretat.uab.cat/ws/api/";
    private static final String URL_PROD = "https://egreta.uab.cat/ws/api/";

    private final RestTemplate restTemplate = new RestTemplate();

    // Mapeig conegut de Serveis Cientificotècnics (SCT) de la UAB cap a UUIDs d'Egreta
    private static final Map<String, ServiceOrgMapping> SERVICE_MAPPINGS = new LinkedHashMap<>();

    public record ServiceOrgMapping(String acronym, String fullName, String uuid) {}

    static {
        registerService("SCAC", "Servei de Cultius Cel·lulars, Producció d'Anticossos i Citometria (SCAC)", "a332d180-1aea-4777-98b1-5e23ed63e1e3");
        registerService("SPTA", "Servei de Planta de Tecnologia dels Aliments (SPTA)", "7a6bcd2c-23f5-4f95-8b99-0807da569813");
        registerService("SAQ", "Servei de Anàlisi Química (SAQ)", "fa2ca6e6-26ef-4e77-829e-337087a60d11");
        registerService("SGiEB", "Servei de Genòmica i Espectroscòpia de Biomolècules (SGiEB)", "638371ef-9bfc-4242-bb79-eb5999ee269d");
        registerService("SMiDRX", "Servei de Microscopia i Difracció de Raigs X (SMiDRX)", "30d7f9d3-7ad1-48f3-86bb-066596841630");
        registerService("SRI", "Servei de Radiacions Ionitzants (SRI)", "fee3bdf3-486c-4da9-a789-817712d4eed0");
    }

    private static void registerService(String acronym, String fullName, String uuid) {
        ServiceOrgMapping mapping = new ServiceOrgMapping(acronym, fullName, uuid);
        SERVICE_MAPPINGS.put(acronym.toLowerCase(), mapping);
        SERVICE_MAPPINGS.put(fullName.toLowerCase(), mapping);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json;charset=utf-8");
        h.set("api-key", apiKey);
        h.set("Accept", "application/json");
        h.set("Accept-Charset", "UTF-8");
        return h;
    }

    private String baseUrl(String entorn) {
        return "prod".equalsIgnoreCase(entorn) ? URL_PROD : URL_TEST;
    }

    /**
     * Retorna la llista de serveis SCT mapejats predefinits.
     */
    @GetMapping("/services")
    public ResponseEntity<?> getKnownServices() {
        Map<String, ServiceOrgMapping> unique = new LinkedHashMap<>();
        for (ServiceOrgMapping m : SERVICE_MAPPINGS.values()) {
            unique.put(m.uuid(), m);
        }
        return ResponseEntity.ok(new ArrayList<>(unique.values()));
    }

    /**
     * Llegeix el fitxer per defecte inclòs al repositori.
     */
    @GetMapping("/load-default")
    public ResponseEntity<?> loadDefaultExcel() {
        try {
            Resource resource = new ClassPathResource("20260629-SCT-FULL PER CÀRREGA CONSENSUAT.xlsx");
            if (!resource.exists()) {
                java.io.File file = new java.io.File("src/main/resources/20260629-SCT-FULL PER CÀRREGA CONSENSUAT.xlsx");
                if (file.exists()) {
                    try (InputStream is = new java.io.FileInputStream(file)) {
                        return ResponseEntity.ok(parseExcelStream(is, "20260629-SCT-FULL PER CÀRREGA CONSENSUAT.xlsx"));
                    }
                }
                return ResponseEntity.status(404).body(Map.of("error", "No s'ha trobat el fitxer per defecte al servidor."));
            }
            try (InputStream is = resource.getInputStream()) {
                return ResponseEntity.ok(parseExcelStream(is, resource.getFilename()));
            }
        } catch (Exception e) {
            logger.error("Error carregant excel per defecte: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Error en carregar l'arxiu per defecte: " + e.getMessage()));
        }
    }

    /**
     * Llegeix el fitxer d'actius fixos d'equipament científic per defecte.
     */
    @GetMapping("/load-actius-default")
    public ResponseEntity<?> loadActiusDefault() {
        try {
            Resource resource = new ClassPathResource("actius-equipaments-cientifics.xlsx");
            if (!resource.exists()) {
                java.io.File file = new java.io.File("src/main/resources/actius-equipaments-cientifics.xlsx");
                if (file.exists()) {
                    try (InputStream is = new java.io.FileInputStream(file)) {
                        return ResponseEntity.ok(parseExcelStream(is, "actius-equipaments-cientifics.xlsx"));
                    }
                }
                return ResponseEntity.status(404).body(Map.of("error", "No s'ha trobat el fitxer d'actius fixos al servidor."));
            }
            try (InputStream is = resource.getInputStream()) {
                return ResponseEntity.ok(parseExcelStream(is, resource.getFilename()));
            }
        } catch (Exception e) {
            logger.error("Error carregant excel d'actius per defecte: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Error en carregar l'arxiu d'actius fixos: " + e.getMessage()));
        }
    }

    /**
     * Rep un fitxer Excel pujat des del frontend.
     */
    @PostMapping(value = "/parse-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseUploadedExcel(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "L'arxiu enviat està buit."));
            }
            try (InputStream is = file.getInputStream()) {
                return ResponseEntity.ok(parseExcelStream(is, file.getOriginalFilename()));
            }
        } catch (Exception e) {
            logger.error("Error processant Excel pujat: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Error en llegir l'arxiu Excel: " + e.getMessage()));
        }
    }

    /**
     * Helper per normalitzar strings de capçalera (sense accents, minúscules).
     */
    private String normalizeHeader(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalized.toLowerCase().trim();
    }

    private boolean isLanguageMatch(String header, String lang) {
        if (header == null) return false;
        String h = header.toLowerCase().trim();
        return switch (lang) {
            case "ca" -> (h.contains("cat") || h.contains("catal") || h.contains("(ca)") || h.contains(" ca ") || h.endsWith("(ca)") || h.endsWith(" ca"))
                         && !h.contains("cas") && !h.contains("castell");
            case "es" -> h.contains("cas") || h.contains("castell") || h.contains("esp") || h.contains("(es)") || h.contains(" es ") || h.endsWith("(es)") || h.endsWith(" es") || h.contains("spanish") || h.contains("espan");
            case "en" -> (h.contains("ang") || h.contains("eng") || h.contains("engl") || h.contains("(en)") || h.endsWith("(en)") || h.endsWith(" en") || h.contains("angles") || h.contains("ingles"))
                         && !h.contains("castell") && !h.contains("espan");
            default -> false;
        };
    }

    /**
     * Parseja un InputStream d'Excel i genera la llista d'equipaments llesta per a Egreta.
     */
    private Map<String, Object> parseExcelStream(InputStream inputStream, String fileName) throws Exception {
        List<Map<String, Object>> parsedEquipments = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int totalRows = sheet.getLastRowNum();
            if (totalRows < 1) {
                return Map.of("fileName", fileName, "totalCount", 0, "equipments", Collections.emptyList());
            }

            // Detectar índexs de columna a partir de les capçaleres de la fila 0
            Row headerRow = sheet.getRow(0);
            int colTitleCat    = 0;
            int colTitleCas    = 1;
            int colTitleAng    = 2;
            int colServei      = 3;
            int colEmail       = 4;
            int colModel       = 5;
            int colDescCat     = 6;
            int colDescCas     = 7;
            int colDescAng     = 8;
            int colPhoto       = 9;
            int colPrestacio   = 10;
            int colFinancament = 11;

            // Columnes específiques per a format Actius Fixos
            boolean isActiusFixos = false;
            int colAssetNum        = -1;
            int colAssetClass      = -1;
            int colLocation        = -1;
            int colLocationDesc    = -1;
            int colCostCenter      = -1;
            int colCostCenterDesc  = -1;
            int colProject         = -1;
            int colProjectDesc     = -1;
            int colCapYear         = -1;
            int colResponsible     = -1;
            int colCurrentVal      = -1;
            int colAccumAmort      = -1;
            int colNetVal          = -1;
            int colNiu             = -1;
            int colOrgName         = -1;
            int colOrgUuid         = -1;

            if (headerRow != null) {
                int lastCell = headerRow.getLastCellNum();
                for (int c = 0; c < lastCell; c++) {
                    String h = normalizeHeader(getCellString(headerRow, c, formatter));
                    if (h.contains("actiu fix") || h.contains("denominacio") || h.contains("uuid organitzacio") || h.contains("classe d'actiu")) {
                        isActiusFixos = true;
                    }

                    if (h.contains("classe") && h.contains("actiu")) {
                        colAssetClass = c;
                    } else if (h.contains("numero") && (h.contains("actiu") || h.contains("fix"))) {
                        colAssetNum = c;
                    } else if (h.contains("denominacio")) {
                        if (isLanguageMatch(h, "ca")) colTitleCat = c;
                        else if (isLanguageMatch(h, "es")) colTitleCas = c;
                        else if (isLanguageMatch(h, "en")) colTitleAng = c;
                    } else if (h.contains("title") || h.contains("titol") || h.contains("titulo")) {
                        if (isLanguageMatch(h, "ca")) colTitleCat = c;
                        else if (isLanguageMatch(h, "es")) colTitleCas = c;
                        else if (isLanguageMatch(h, "en")) colTitleAng = c;
                    } else if (h.contains("descrip") || h.contains("description")) {
                        if (h.contains("emplacament")) {
                            colLocationDesc = c;
                        } else if (h.contains("centre de cost") || h.contains("cost")) {
                            colCostCenterDesc = c;
                        } else if (h.contains("projecte") || h.contains("proyecto") || h.contains("project")) {
                            colProjectDesc = c;
                        } else if (h.contains("classe")) {
                            colAssetClass = c;
                        } else {
                            if (isLanguageMatch(h, "ca")) colDescCat = c;
                            else if (isLanguageMatch(h, "es")) colDescCas = c;
                            else if (isLanguageMatch(h, "en")) colDescAng = c;
                        }
                    } else if (h.contains("emplacament") || h.contains("emplazamiento") || h.contains("ubicacio")) {
                        colLocation = c;
                    } else if (h.contains("centre de cost") || h.contains("centro de coste")) {
                        colCostCenter = c;
                    } else if (h.contains("projecte") || h.contains("proyecto") || h.contains("project")) {
                        colProject = c;
                    } else if (h.contains("capitalitzaci") || h.contains("any") || h.contains("ano") || h.contains("year")) {
                        colCapYear = c;
                    } else if (h.contains("responsable")) {
                        colResponsible = c;
                    } else if (h.contains("valor net")) {
                        colNetVal = c;
                    } else if (h.contains("amortitzaci")) {
                        colAccumAmort = c;
                    } else if (h.contains("valor actual") || h.contains("valor")) {
                        colCurrentVal = c;
                    } else if (h.contains("niu")) {
                        colNiu = c;
                    } else if (h.contains("uuid organitzaci") || h.contains("uuid")) {
                        colOrgUuid = c;
                    } else if (h.contains("nom oficial") || (h.contains("organitzaci") && !h.contains("uuid"))) {
                        colOrgName = c;
                    } else if (h.contains("servei") || h.contains("servicio") || h.contains("service")) {
                        colServei = c;
                    } else if (h.contains("mail") || h.contains("correu") || h.contains("email")) {
                        colEmail = c;
                    } else if (h.contains("model") || h.contains("marca")) {
                        colModel = c;
                    } else if (h.contains("foto") || h.contains("photo") || h.contains("imatge") || h.contains("image")) {
                        colPhoto = c;
                    } else if (h.contains("prestaci") || h.contains("loan")) {
                        colPrestacio = c;
                    } else if (h.contains("subvenci") || h.contains("financ") || h.contains("ajut") || h.contains("adquirit")) {
                        colFinancament = c;
                    }
                }
            }

            logger.info("Column mapping for '{}' (isActiusFixos={}): titleCat={}, descCat={}, descCas={}, descAng={}, orgUuid={}",
                    fileName, isActiusFixos, colTitleCat, colDescCat, colDescCas, colDescAng, colOrgUuid);

            for (int r = 1; r <= totalRows; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String titleCat = getCellString(row, colTitleCat, formatter);
                String titleCas = getCellString(row, colTitleCas, formatter);
                String titleAng = getCellString(row, colTitleAng, formatter);

                // Si tots els títols estan buits, ignorem la fila
                if (titleCat.isBlank() && titleCas.isBlank() && titleAng.isBlank()) {
                    continue;
                }

                String serveiRaw      = colServei >= 0 ? getCellString(row, colServei, formatter) : "";
                String emailRaw       = colEmail >= 0 ? getCellString(row, colEmail, formatter) : "";
                String modelRaw       = colModel >= 0 ? getCellString(row, colModel, formatter) : "";
                String descCatRaw     = colDescCat >= 0 ? getCellString(row, colDescCat, formatter) : "";
                String descCasRaw     = colDescCas >= 0 ? getCellString(row, colDescCas, formatter) : "";
                String descAngRaw     = colDescAng >= 0 ? getCellString(row, colDescAng, formatter) : "";
                String fotoRaw        = colPhoto >= 0 ? getCellString(row, colPhoto, formatter) : "";
                String tipusPresRaw   = colPrestacio >= 0 ? getCellString(row, colPrestacio, formatter) : "";
                String financamentRaw = colFinancament >= 0 ? getCellString(row, colFinancament, formatter) : "";

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rowIndex", r + 1); // 1-based per visualització usuari
                item.put("titleCat", titleCat.trim());
                item.put("titleCas", titleCas.trim());
                item.put("titleAng", titleAng.trim());
                item.put("descCat", descCatRaw.trim());
                item.put("descCas", descCasRaw.trim());
                item.put("descAng", descAngRaw.trim());

                if (isActiusFixos) {
                    String assetNumber = colAssetNum >= 0 ? getCellString(row, colAssetNum, formatter).trim() : "";
                    String assetClass = colAssetClass >= 0 ? getCellString(row, colAssetClass, formatter).trim() : "";
                    String location = colLocation >= 0 ? getCellString(row, colLocation, formatter).trim() : "";
                    String locationDesc = colLocationDesc >= 0 ? getCellString(row, colLocationDesc, formatter).trim() : "";
                    String costCenter = colCostCenter >= 0 ? getCellString(row, colCostCenter, formatter).trim() : "";
                    String costCenterDesc = colCostCenterDesc >= 0 ? getCellString(row, colCostCenterDesc, formatter).trim() : "";
                    String project = colProject >= 0 ? getCellString(row, colProject, formatter).trim() : "";
                    String projectDesc = colProjectDesc >= 0 ? getCellString(row, colProjectDesc, formatter).trim() : "";
                    String capYear = colCapYear >= 0 ? getCellString(row, colCapYear, formatter).trim() : "";
                    String responsible = colResponsible >= 0 ? getCellString(row, colResponsible, formatter).trim() : "";
                    String currentVal = colCurrentVal >= 0 ? getCellString(row, colCurrentVal, formatter).trim() : "";
                    String accumAmort = colAccumAmort >= 0 ? getCellString(row, colAccumAmort, formatter).trim() : "";
                    String netVal = colNetVal >= 0 ? getCellString(row, colNetVal, formatter).trim() : "";
                    String niu = colNiu >= 0 ? getCellString(row, colNiu, formatter).trim() : "";
                    String orgName = colOrgName >= 0 ? getCellString(row, colOrgName, formatter).trim() : "";
                    String orgUuid = colOrgUuid >= 0 ? getCellString(row, colOrgUuid, formatter).trim() : "";

                    item.put("assetNumber", assetNumber);
                    item.put("assetClass", assetClass);
                    item.put("location", location);
                    item.put("locationDesc", locationDesc);
                    item.put("costCenter", costCenter);
                    item.put("costCenterDesc", costCenterDesc);
                    item.put("project", project);
                    item.put("projectDesc", projectDesc);
                    item.put("capYear", capYear);
                    item.put("responsible", responsible);
                    item.put("currentValue", currentVal);
                    item.put("accumAmort", accumAmort);
                    item.put("netValue", netVal);
                    item.put("niu", niu);
                    item.put("orgName", !orgName.isEmpty() ? orgName : (!costCenterDesc.isEmpty() ? costCenterDesc : "UAB"));
                    item.put("orgAcronym", !orgName.isEmpty() ? orgName : costCenter);
                    item.put("orgUuid", orgUuid);
                    item.put("orgMatched", !orgUuid.isBlank());
                    item.put("serviceRaw", !orgName.isEmpty() ? orgName : costCenterDesc);
                    item.put("model", assetNumber.isEmpty() ? (assetClass.isEmpty() ? "Equipament" : assetClass) : assetNumber);
                    item.put("tipusPrestacioRaw", "Sense servei");
                    item.put("tipusPrestacioUri", "/dk/atira/pure/equipment/loantypes/sense_servei");

                    String personUuid = "";
                    String personEmail = "";
                    if (mongoTemplate != null) {
                        try {
                            Document p = null;
                            if (!niu.isBlank()) {
                                p = mongoTemplate.getCollection("Persons").find(new Document("identifiers.value", niu)).first();
                                if (p == null) {
                                    p = mongoTemplate.getCollection("Persons").find(new Document("identifiers.id", niu)).first();
                                }
                            }
                            if (p == null && !responsible.isBlank()) {
                                String[] parts = responsible.split(",");
                                if (parts.length >= 2) {
                                    p = mongoTemplate.getCollection("Persons").find(new Document("name.lastName",
                                        Pattern.compile("^" + Pattern.quote(parts[0].trim()), Pattern.CASE_INSENSITIVE))).first();
                                }
                            }
                            if (p != null) {
                                personUuid = p.getString("uuid");
                                List<Document> assocs = (List<Document>) p.get("staffOrganizationAssociations");
                                if (assocs != null) {
                                    for (Document assoc : assocs) {
                                        List<Document> emails = (List<Document>) assoc.get("emails");
                                        if (emails != null) {
                                            for (Document em : emails) {
                                                String val = em.getString("value");
                                                if (val != null && val.contains("@")) {
                                                    personEmail = val.trim().toLowerCase();
                                                    break;
                                                }
                                            }
                                        }
                                        if (!personEmail.isEmpty()) break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("No s'ha pogut resoldre persona per NIU {}: {}", niu, e.getMessage());
                        }
                    }
                    item.put("personUuid", personUuid != null ? personUuid : "");
                    item.put("email", !personEmail.isEmpty() ? personEmail : cleanEmail(emailRaw));

                    if (!project.isBlank() && !project.equals("#")) {
                        item.put("financament", "Projecte " + project + (!projectDesc.isBlank() && !projectDesc.equals("#") ? ": " + projectDesc : ""));
                    } else {
                        item.put("financament", "");
                    }
                } else {
                    // Normalitzacions SCT clàssic
                    String email = cleanEmail(emailRaw);
                    String model = modelRaw.trim();
                    String tipusPrestacioUri = mapLoanTypeToUri(tipusPresRaw);
                    ServiceOrgMapping orgMapping = resolveOrganization(serveiRaw);

                    item.put("serviceRaw", serveiRaw.trim());
                    item.put("orgAcronym", orgMapping != null ? orgMapping.acronym() : extractAcronym(serveiRaw));
                    item.put("orgName", orgMapping != null ? orgMapping.fullName() : serveiRaw.trim());
                    item.put("orgUuid", orgMapping != null ? orgMapping.uuid() : "");
                    item.put("orgMatched", orgMapping != null);
                    item.put("email", email);
                    item.put("model", model);
                    item.put("financament", financamentRaw.trim());
                    item.put("photo", fotoRaw.trim());
                    item.put("tipusPrestacioRaw", tipusPresRaw.trim());
                    item.put("tipusPrestacioUri", tipusPrestacioUri);
                }

                // Generar payload Egreta per defecte
                Map<String, Object> egretaPayload = buildEgretaPayload(item, "approved", "/dk/atira/pure/equipment/equipmenttypes/equipment/equipment");
                item.put("egretaPayload", egretaPayload);
                item.put("status", "READY");

                parsedEquipments.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", fileName);
        result.put("totalCount", parsedEquipments.size());
        result.put("equipments", parsedEquipments);
        return result;
    }

    private String getCellString(Row row, int cellIndex, DataFormatter formatter) {
        if (cellIndex < 0) return "";
        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        return formatter.formatCellValue(cell);
    }

    private String cleanEmail(String raw) {
        if (raw == null) return "";
        return raw.replace("\n", "").replace("\r", "").trim();
    }

    private String extractAcronym(String text) {
        if (text == null) return "";
        int start = text.indexOf('(');
        int end = text.indexOf(')');
        if (start != -1 && end > start) {
            return text.substring(start + 1, end).trim();
        }
        return "";
    }

    private ServiceOrgMapping resolveOrganization(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.trim().toLowerCase();

        // 1. Cercar clau exacta
        if (SERVICE_MAPPINGS.containsKey(lower)) {
            return SERVICE_MAPPINGS.get(lower);
        }

        // 2. Cercar per acrònim entre parèntesis
        String acronym = extractAcronym(text).toLowerCase();
        if (!acronym.isEmpty() && SERVICE_MAPPINGS.containsKey(acronym)) {
            return SERVICE_MAPPINGS.get(acronym);
        }

        // 3. Cercar per paraules clau
        if (lower.contains("scac") || lower.contains("cultius")) return SERVICE_MAPPINGS.get("scac");
        if (lower.contains("spta") || lower.contains("aliments")) return SERVICE_MAPPINGS.get("spta");
        if (lower.contains("saq") || lower.contains("química") || lower.contains("quimica")) return SERVICE_MAPPINGS.get("saq");
        if (lower.contains("sgieb") || lower.contains("genòmica") || lower.contains("genomica")) return SERVICE_MAPPINGS.get("sgieb");
        if (lower.contains("smidrx") || lower.contains("microscopia") || lower.contains("microscòpia")) return SERVICE_MAPPINGS.get("smidrx");
        if (lower.contains("sri") || lower.contains("radiacions")) return SERVICE_MAPPINGS.get("sri");

        return null;
    }

    private String mapLoanTypeToUri(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/dk/atira/pure/equipment/loantypes/notavailable";
        }
        String lower = raw.toLowerCase().trim();
        if (lower.contains("autoservei") && (lower.contains("i autoservei") || lower.contains("y autoservicio") || lower.contains("3."))) {
            return "/dk/atira/pure/equipment/loantypes/internalexternal";
        }
        if (lower.contains("autoservei") || lower.contains("autoservicio") || lower.startsWith("2.")) {
            return "/dk/atira/pure/equipment/loantypes/internal";
        }
        return "/dk/atira/pure/equipment/loantypes/notavailable";
    }

    public record MultilingualFunding(String cat, String cas, String eng) {}

    private MultilingualFunding adaptFunding(String raw) {
        if (raw == null || raw.isBlank()) {
            return new MultilingualFunding("", "", "");
        }
        String txt = raw.trim();

        // 1. Patrons coneguts específics de convocatòries SCT UAB
        if (txt.contains("EQC2019-005636-P")) {
            return new MultilingualFunding(
                "Adquirit amb subvenció del <i>Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento</i>. Convocatòria d'adquisició d'equipament científic-tècnic de 2019 (Ref: EQC2019-005636-P)",
                "Adquirido con subvención del <i>Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento</i>. Convocatoria de adquisición de equipamiento científico-técnico del 2019 (Ref: EQC2019-005636-P)",
                "Acquired with a grant from the State Sub-programme for Scientific and Technical Infrastructure and Equipment. 2019 Funding Opportunity for the Procurement of Scientific and Technical Equipment (Ref: EQC2019-005636-P)"
            );
        }
        if (txt.contains("EQC2024-008870-P")) {
            return new MultilingualFunding(
                "Adquirit amb subvenció per a l’adquisició d’equipament científicotècnic finançat per MICIU/AEI /10.13039/501100011033 i per FEDER, UE  (actuació EQC2024-008870-P)",
                "Adquirido con subvención para la compra de equipamiento científico-técnico financiado por MICIU/AEI /10.13039/501100011033 y por FEDER, UE  (Ref. EQC2024-008870-P)",
                "Acquired with a grant for the purchase of scientific and technical equipment funded by MICIU/AEI /10.13039/501100011033 and FEDER, UE (Ref: EQC2024-008870-P)"
            );
        }
        if (txt.contains("EQC2021-007223")) {
            return new MultilingualFunding(
                "Adquirit amb subvenció per a l'adquisició d'equipament científicotècnic finançat per MCIN/AEI/10.13039/501100011033 i per la Unió Europea. Fons NextGeneration EU/PRTR (actuació EQC2021-007223)",
                "Adquirido con subvención para la adquisición de equipamiento científico-técnico financiado por MCIN/AEI/10.13039/501100011033 y por la Unión Europea. Fondos NextGeneration EU/PRTR (actuación EQC2021-007223)",
                "Acquired with a grant for the purchase of scientific and technical equipment funded by MCIN/AEI/10.13039/501100011033 and European Union NextGeneration EU/PRTR funds (call EQC2021-007223)"
            );
        }
        if (txt.contains("UNAB10-4E-485")) {
            return new MultilingualFunding(
                "Adquirit amb subvenció del Ministerio de Ciencia e Innovación. Convocatòria 2010 - Subprograma de Proyectos de Infraestructura Científico-Tecnológica cofinançats amb FEDER, Ref. UNAB10-4E-485",
                "Adquirido con subvención del Ministerio de Ciencia e Innovación. Convocatoria 2010 - Subprograma de Proyectos de Infraestructura Científico-Tecnológica cofinanciados con FEDER, Ref. UNAB10-4E-485",
                "Acquired with a grant from the Ministry of Science and Innovation. 2010 Call - Sub-programme for Scientific-Technological Infrastructure Projects co-financed with ERDF, Ref. UNAB10-4E-485"
            );
        }

        // 2. Adaptació genèrica si no coincideix amb els patrons anteriors
        String cat = txt;
        if (cat.contains("Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento")) {
            cat = cat.replace("Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento",
                    "<i>Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento</i>");
        }

        String cas = txt
            .replaceAll("(?i)Adquirit amb subvenci[oó]", "Adquirido con subvención")
            .replaceAll("(?i)per a l[’']adquisici[oó] d[’']equipament cient[ií]ficot[eè]cnic", "para la adquisición de equipamiento científico-técnico")
            .replaceAll("(?i)per a l[’']adquisici[oó] d[’']equipament", "para la adquisición de equipamiento")
            .replaceAll("(?i)finan[cç]at per", "financiado por")
            .replaceAll("(?i)\\bi per\\b", "y por")
            .replaceAll("(?i)Convocat[oò]ria", "Convocatoria")
            .replaceAll("(?i)actuaci[oó]", "actuación");

        if (cas.contains("Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento")) {
            cas = cas.replace("Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento",
                    "<i>Subprograma Estatal de infraestructuras científicas y técnicas y equipamiento</i>");
        }

        String eng = txt
            .replaceAll("(?i)Adquirit amb subvenci[oó]|Adquirido con subvenci[oó]n", "Acquired with a grant")
            .replaceAll("(?i)per a l[’']adquisici[oó] d[’']equipament cient[ií]ficot[eè]cnic|para la adquisici[oó]n de equipamiento cient[ií]fico-t[eé]cnico", "for the purchase of scientific and technical equipment")
            .replaceAll("(?i)finan[cç]at per|financiado por", "funded by")
            .replaceAll("(?i)\\bi per\\b|\\by por\\b", "and")
            .replaceAll("(?i)Convocat[oò]ria|Convocatoria", "Call for proposals")
            .replaceAll("(?i)actuaci[oó]|actuaci[oó]n", "call");

        return new MultilingualFunding(cat, cas, eng);
    }

    private String buildDescriptionWithFunding(String desc, String heading, String fundingText) {
        StringBuilder sb = new StringBuilder();
        if (desc != null && !desc.isBlank()) {
            sb.append(desc.trim());
        }
        if (fundingText != null && !fundingText.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("<br/><br/>");
            }
            sb.append("<b>").append(heading).append(":</b><br/>");
            sb.append(fundingText.trim());
        }
        return sb.toString();
    }

    /**
     * Construeix el cos JSON per a l'API REST d'Egreta (PUT /equipment).
     * 1. Descripció combinada: descripció de l'equip + <br/><br/><b>Finançament / Financiación / Funding</b><br/> + text de subvenció.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildEgretaPayload(Map<String, Object> row, String workflowStep, String equipmentTypeUri) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // 1. Títol multilingüe
        Map<String, String> title = new LinkedHashMap<>();
        String cat = (String) row.getOrDefault("titleCat", "");
        String cas = (String) row.getOrDefault("titleCas", "");
        String ang = (String) row.getOrDefault("titleAng", "");
        if (!cat.isBlank()) title.put("ca_ES", cat);
        if (!cas.isBlank()) title.put("es_ES", cas);
        if (!ang.isBlank()) title.put("en_GB", ang);
        if (title.isEmpty()) title.put("ca_ES", "Equipament sense títol");
        payload.put("title", title);

        // 2. Tipus d'equipament
        payload.put("type", Map.of("uri", equipmentTypeUri != null && !equipmentTypeUri.isBlank() 
                ? equipmentTypeUri 
                : "/dk/atira/pure/equipment/equipmenttypes/equipment/equipment"));

        // 3. Tipus de préstec / prestació (notavailable = servei, internal = autoservei, internalexternal = ambdós)
        String loanTypeUri = (String) row.getOrDefault("tipusPrestacioUri", "/dk/atira/pure/equipment/loantypes/notavailable");
        payload.put("loanType", Map.of("uri", loanTypeUri));

        // 4. Visibilitat (FREE = públic)
        payload.put("visibility", Map.of("key", "FREE"));

        // 5. Workflow (approved per defecte)
        payload.put("workflow", Map.of("step", workflowStep != null && !workflowStep.isBlank() ? workflowStep : "approved"));

        // 6. Organització responsable
        String orgUuid = (String) row.getOrDefault("orgUuid", "");
        if (!orgUuid.isBlank()) {
            payload.put("managingOrganization", Map.of("systemName", "Organization", "uuid", orgUuid));
            payload.put("organizations", List.of(Map.of("systemName", "Organization", "uuid", orgUuid)));
        }

        // 7. Email de contacte
        String email = (String) row.getOrDefault("email", "");
        if (!email.isBlank()) {
            payload.put("emails", List.of(
                Map.of(
                    "value", email,
                    "type", Map.of("uri", "/dk/atira/pure/equipment/equipmentemailtype/email")
                )
            ));
        }

        // 8. Descripcions combinades multilingües amb HTML enriquida
        List<Map<String, Object>> descriptions = new ArrayList<>();

        String dCat = (String) row.getOrDefault("descCat", "");
        String dCas = (String) row.getOrDefault("descCas", "");
        String dAng = (String) row.getOrDefault("descAng", "");
        String proj = (String) row.getOrDefault("project", "");
        String projDesc = (String) row.getOrDefault("projectDesc", "");
        String financament = (String) row.getOrDefault("financament", "");

        String fundingRaw = (projDesc != null && !projDesc.isBlank() && !projDesc.equals("#")) ? projDesc : financament;
        MultilingualFunding funding = adaptFunding(fundingRaw);
        String fundCat = !funding.cat().isBlank() ? funding.cat() : fundingRaw;
        String fundCas = !funding.cas().isBlank() ? funding.cas() : fundingRaw;
        String fundAng = !funding.eng().isBlank() ? funding.eng() : fundingRaw;

        String fullCat = buildDescriptionWithFunding(dCat, "Finançament", fundCat);
        String fullCas = buildDescriptionWithFunding(dCas, "Financiación", fundCas);
        String fullAng = buildDescriptionWithFunding(dAng, "Funding", fundAng);

        // Per a actius fixos, afegir bloc de metadades a les descripcions si existeixen
        String assetNum = (String) row.getOrDefault("assetNumber", "");
        String loc = (String) row.getOrDefault("location", "");
        String locDesc = (String) row.getOrDefault("locationDesc", "");
        String capYr = (String) row.getOrDefault("capYear", "");
        String resp = (String) row.getOrDefault("responsible", "");
        String niu = (String) row.getOrDefault("niu", "");

        if (!assetNum.isBlank() || !loc.isBlank() || !capYr.isBlank() || !resp.isBlank()) {
            StringBuilder metaCat = new StringBuilder();
            StringBuilder metaCas = new StringBuilder();
            StringBuilder metaAng = new StringBuilder();

            metaCat.append("<b>Dades de l'actiu científic:</b><br/>");
            metaCas.append("<b>Datos del activo científico:</b><br/>");
            metaAng.append("<b>Scientific asset details:</b><br/>");

            if (!assetNum.isBlank()) {
                metaCat.append("• <b>Núm. actiu fix:</b> ").append(assetNum).append("<br/>");
                metaCas.append("• <b>Núm. activo fijo:</b> ").append(assetNum).append("<br/>");
                metaAng.append("• <b>Fixed asset no.:</b> ").append(assetNum).append("<br/>");
            }
            if (!loc.isBlank()) {
                metaCat.append("• <b>Emplaçament:</b> ").append(loc).append(!locDesc.isBlank() ? " (" + locDesc + ")" : "").append("<br/>");
                metaCas.append("• <b>Emplazamiento:</b> ").append(loc).append(!locDesc.isBlank() ? " (" + locDesc + ")" : "").append("<br/>");
                metaAng.append("• <b>Location:</b> ").append(loc).append(!locDesc.isBlank() ? " (" + locDesc + ")" : "").append("<br/>");
            }
            if (!capYr.isBlank()) {
                metaCat.append("• <b>Any capitalització:</b> ").append(capYr).append("<br/>");
                metaCas.append("• <b>Año capitalización:</b> ").append(capYr).append("<br/>");
                metaAng.append("• <b>Capitalization year:</b> ").append(capYr).append("<br/>");
            }
            if (!resp.isBlank()) {
                metaCat.append("• <b>Responsable:</b> ").append(resp).append(!niu.isBlank() ? " (NIU: " + niu + ")" : "").append("<br/>");
                metaCas.append("• <b>Responsable:</b> ").append(resp).append(!niu.isBlank() ? " (NIU: " + niu + ")" : "").append("<br/>");
                metaAng.append("• <b>Responsible person:</b> ").append(resp).append(!niu.isBlank() ? " (NIU: " + niu + ")" : "").append("<br/>");
            }
            if (!proj.isBlank() && !proj.equals("#")) {
                metaCat.append("• <b>Codi projecte:</b> ").append(proj).append("<br/>");
                metaCas.append("• <b>Código proyecto:</b> ").append(proj).append("<br/>");
                metaAng.append("• <b>Project code:</b> ").append(proj).append("<br/>");
            }

            if (!fullCat.isBlank()) fullCat += "<br/><br/>" + metaCat;
            else fullCat = metaCat.toString();

            if (!fullCas.isBlank()) fullCas += "<br/><br/>" + metaCas;
            else fullCas = metaCas.toString();

            if (!fullAng.isBlank()) fullAng += "<br/><br/>" + metaAng;
            else fullAng = metaAng.toString();
        }

        // Contact persons
        String personUuid = (String) row.getOrDefault("personUuid", "");
        if (!personUuid.isBlank()) {
            payload.put("contactPersons", List.of(Map.of("systemName", "Person", "uuid", personUuid)));
        }

        if (!fullCat.isBlank() || !fullCas.isBlank() || !fullAng.isBlank()) {
            Map<String, String> descVal = new LinkedHashMap<>();
            if (!fullCat.isBlank()) descVal.put("ca_ES", fullCat);
            if (!fullCas.isBlank()) descVal.put("es_ES", fullCas);
            if (!fullAng.isBlank()) descVal.put("en_GB", fullAng);

            descriptions.add(Map.of(
                "value", descVal,
                "type", Map.of("uri", "/dk/atira/pure/equipment/descriptions/equipmentdescription")
            ));
        }
        if (!descriptions.isEmpty()) {
            payload.put("descriptions", descriptions);
        }

        // 9. Marca i model a 'details' (només si és un model real i no el número d'actiu)
        String model = (String) row.getOrDefault("model", "");
        if (!model.isBlank() && !model.equals(assetNum)) {
            payload.put("details", List.of(
                Map.of(
                    "name", Map.of(
                        "ca_ES", model,
                        "es_ES", model,
                        "en_GB", model
                    )
                )
            ));
        }

        return payload;
    }

    /**
     * Endpoint per crear un sol equipament a Egreta.
     */
    @PostMapping("/create-single")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> createSingleEquipment(@RequestBody Map<String, Object> request) {
        String entorn = (String) request.getOrDefault("entorn", "test");
        boolean dryRun = Boolean.TRUE.equals(request.get("dryRun"));
        Map<String, Object> payload = (Map<String, Object>) request.get("payload");

        if (payload == null || payload.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El payload de l'equipament és obligatori."));
        }

        if (dryRun) {
            return ResponseEntity.ok(Map.of(
                "status", "DRY_RUN",
                "message", "Validació correcta (Dry Run). No s'ha creat a Egreta.",
                "payload", payload
            ));
        }

        try {
            String uuid = (String) payload.get("uuid");
            String url = (uuid != null && !uuid.isBlank()) ? (baseUrl(entorn) + "equipment/" + uuid) : (baseUrl(entorn) + "equipment");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, buildHeaders());
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Map<String, Object> body = resp.getBody();
                String createdUuid = (String) body.get("uuid");
                Object pureId = body.get("pureId");
                return ResponseEntity.ok(Map.of(
                    "status", "CREATED",
                    "httpStatus", resp.getStatusCode().value(),
                    "uuid", createdUuid != null ? createdUuid : (uuid != null ? uuid : ""),
                    "pureId", pureId != null ? pureId : "",
                    "portalUrl", body.getOrDefault("portalUrl", ""),
                    "message", (uuid != null && !uuid.isBlank() ? "Actualitzat" : "Creat") + " correctament a Egreta (" + entorn.toUpperCase() + ")"
                ));
            } else {
                return ResponseEntity.status(resp.getStatusCode()).body(Map.of(
                    "status", "ERROR",
                    "message", "Resposta no exitosa: HTTP " + resp.getStatusCode()
                ));
            }
        } catch (HttpClientErrorException e) {
            logger.error("Error d'API Egreta: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "status", "ERROR",
                "httpStatus", e.getStatusCode().value(),
                "error", e.getResponseBodyAsString()
            ));
        } catch (Exception e) {
            logger.error("Excepció processant equipament: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                "status", "ERROR",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Endpoint per processar un lot d'equipaments (amb suport de dry-run).
     */
    @PostMapping("/process-batch")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> processBatch(@RequestBody Map<String, Object> request) {
        String entorn = (String) request.getOrDefault("entorn", "test");
        boolean dryRun = Boolean.TRUE.equals(request.get("dryRun"));
        String workflowStep = (String) request.getOrDefault("workflowStep", "approved");
        String equipmentTypeUri = (String) request.getOrDefault("equipmentTypeUri", "/dk/atira/pure/equipment/equipmenttypes/equipment/equipment");
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");

        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No s'han enviat equipaments per processar."));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;

        for (Map<String, Object> item : items) {
            Map<String, Object> rowResult = new LinkedHashMap<>();
            rowResult.put("rowIndex", item.get("rowIndex"));
            rowResult.put("titleCat", item.get("titleCat"));
            rowResult.put("model", item.get("model"));

            // Obtenir o reconstruir payload
            Map<String, Object> payload;
            if (item.containsKey("egretaPayload") && item.get("egretaPayload") instanceof Map) {
                payload = (Map<String, Object>) item.get("egretaPayload");
            } else {
                payload = buildEgretaPayload(item, workflowStep, equipmentTypeUri);
            }

            // Validació mínima
            String orgUuid = (String) item.get("orgUuid");
            if (orgUuid == null || orgUuid.isBlank()) {
                rowResult.put("status", "ERROR");
                rowResult.put("message", "No s'ha pogut assignar cap organització/servei de la UAB.");
                results.add(rowResult);
                errorCount++;
                continue;
            }

            if (dryRun) {
                rowResult.put("status", "DRY_RUN");
                rowResult.put("message", "Payload vàlid preparat per a Egreta.");
                rowResult.put("payload", payload);
                results.add(rowResult);
                successCount++;
                continue;
            }

            // Execució real PUT a Egreta
            try {
                String existingUuid = (String) payload.get("uuid");
                if (existingUuid == null || existingUuid.isBlank()) {
                    existingUuid = (String) item.get("uuid");
                }
                String url = (existingUuid != null && !existingUuid.isBlank()) ? (baseUrl(entorn) + "equipment/" + existingUuid) : (baseUrl(entorn) + "equipment");
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, buildHeaders());
                ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);

                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    Map<String, Object> body = resp.getBody();
                    rowResult.put("status", "CREATED");
                    rowResult.put("uuid", body.get("uuid"));
                    rowResult.put("pureId", body.get("pureId"));
                    rowResult.put("portalUrl", body.get("portalUrl"));
                    rowResult.put("message", "Creat/Actualitzat correctament (HTTP " + resp.getStatusCode().value() + ")");
                    successCount++;
                } else {
                    rowResult.put("status", "ERROR");
                    rowResult.put("message", "HTTP " + resp.getStatusCode().value());
                    errorCount++;
                }
            } catch (HttpClientErrorException e) {
                rowResult.put("status", "ERROR");
                rowResult.put("message", "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
                errorCount++;
            } catch (Exception e) {
                rowResult.put("status", "ERROR");
                rowResult.put("message", "Excepció: " + e.getMessage());
                errorCount++;
            }

            results.add(rowResult);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entorn", entorn);
        response.put("dryRun", dryRun);
        response.put("total", items.size());
        response.put("successCount", successCount);
        response.put("errorCount", errorCount);
        response.put("results", results);

        return ResponseEntity.ok(response);
    }

    /**
     * Puja directament una fotografia a l'API d'Egreta (/equipment/file-uploads).
     */
    @PostMapping(value = "/upload-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPhoto(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "entorn", defaultValue = "test") String entorn) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Fitxer de foto buit."));
        }
        try {
            String url = baseUrl(entorn) + "equipment/file-uploads";
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", apiKey);
            headers.set("Accept", "application/json");
            headers.setContentType(MediaType.parseMediaType(file.getContentType() != null ? file.getContentType() : "image/png"));

            HttpEntity<byte[]> entity = new HttpEntity<>(file.getBytes(), headers);
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());
        } catch (HttpClientErrorException e) {
            logger.error("Error pujant foto a Egreta: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            logger.error("Excepció pujant foto: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Adjunta una foto a un equipament existent mitjançant el seu UUID.
     */
    @PostMapping(value = "/attach-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> attachPhotoToExistingEquipment(
            @RequestParam("uuid") String uuid,
            @RequestParam(value = "entorn", defaultValue = "test") String entorn,
            @RequestParam("file") MultipartFile file) {
        if (uuid == null || uuid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "L'UUID de l'equipament és obligatori."));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El fitxer d'imatge està buit."));
        }

        try {
            // 1. Obtenir l'equipament existent
            String getUrl = baseUrl(entorn) + "equipment/" + uuid;
            HttpEntity<Void> getEntity = new HttpEntity<>(buildHeaders());
            ResponseEntity<Map> getResp = restTemplate.exchange(getUrl, HttpMethod.GET, getEntity, Map.class);

            if (!getResp.getStatusCode().is2xxSuccessful() || getResp.getBody() == null) {
                return ResponseEntity.status(getResp.getStatusCode()).body(Map.of("error", "No s'ha trobat l'equipament a Egreta."));
            }

            Map<String, Object> equipment = getResp.getBody();

            // 2. Afegir o reemplaçar imatge
            List<Map<String, Object>> images = new ArrayList<>();
            Object existingImages = equipment.get("images");
            if (existingImages instanceof List<?>) {
                images.addAll((List<Map<String, Object>>) existingImages);
            }

            String base64Data = Base64.getEncoder().encodeToString(file.getBytes());
            Map<String, Object> newImage = new LinkedHashMap<>();
            newImage.put("fileName", file.getOriginalFilename());
            newImage.put("mimeType", file.getContentType() != null ? file.getContentType() : "image/png");
            newImage.put("size", file.getSize());
            newImage.put("type", Map.of("uri", "/dk/atira/pure/equipment/equipmentfiles/photo"));
            newImage.put("fileData", base64Data);

            images.add(newImage);
            equipment.put("images", images);

            // 3. Desar canvis a Egreta (PUT /equipment/{uuid})
            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(equipment, buildHeaders());
            ResponseEntity<Map> putResp = restTemplate.exchange(getUrl, HttpMethod.PUT, putEntity, Map.class);

            return ResponseEntity.ok(Map.of(
                "status", "UPDATED",
                "uuid", uuid,
                "message", "Fotografia adjuntada correctament a l'equipament (" + entorn.toUpperCase() + ")",
                "images", putResp.getBody() != null ? putResp.getBody().get("images") : Collections.emptyList()
            ));
        } catch (HttpClientErrorException e) {
            logger.error("Error d'API Egreta attachPhoto: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            logger.error("Excepció attachPhoto: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
