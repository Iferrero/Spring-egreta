package com.example.demo.curation.util;

import com.example.demo.curation.model.AffiliationStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Catálogo de identificadores canónicos y variantes textuales de la Universitat Autònoma de Barcelona (UAB),
 * sus hospitales universitarios vinculados y los institutos de investigación consorciados / esfera UAB.
 */
@Component
public class UabAffiliationCatalog {

    public static final String UAB_ROR_ID = "052gg0110";
    public static final String UAB_OPENALEX_ID = "I123044942";

    // Textos canónicos de la matriz UAB
    private static final List<String> UAB_CANONICAL_TEXTS = List.of(
            "autonoma de barcelona",
            "autonomous university of barcelona",
            "bellaterra",
            "08193"
    );

    // Hospitales vinculados
    private static final List<HospitalRule> AFFILIATED_HOSPITALS = List.of(
            new HospitalRule("Hospital Universitari Vall d'Hebron", List.of("vall d hebron", "vall hebron")),
            new HospitalRule("Hospital Universitari Germans Trias i Pujol", List.of("germans trias", "can ruti")),
            new HospitalRule("Hospital de la Santa Creu i Sant Pau", List.of("santa creu i sant pau", "sant pau")),
            new HospitalRule("Hospital Universitari Parc Taulí", List.of("parc tauli", "tauli"))
    );

    // Institutos consorciados / CERCA / CSIC esfera UAB
    private static final List<InstituteRule> AFFILIATED_INSTITUTES = List.of(
            new InstituteRule("ICMAB", "icmab", List.of("ciencia de materials de barcelona", "materials science institute of barcelona")),
            new InstituteRule("ICN2", "icn2", List.of("catalan institute of nanoscience", "institut catala de nanociencia")),
            new InstituteRule("IFAE", "ifae", List.of("institut de fisica d altes energies", "institute for high energy physics barcelona")),
            new InstituteRule("CRAG", "crag", List.of("centre de recerca en agrigenomica", "centre for research in agricultural genomics")),
            new InstituteRule("CVC", "cvc", List.of("visio per computador", "computer vision center")),
            new InstituteRule("ICTA", "icta", List.of("institut de ciencia i tecnologia ambientals", "icta uab")),
            new InstituteRule("IIIA", "iiia", List.of("institut d investigacio en intelligencia artificial", "artificial intelligence research institute csic", "iiia csic")),
            new InstituteRule("IBB", "ibb", List.of("institut de biotecnologia i de biomedicina"))
    );

    // Palabras institucionales indicadoras de entidades ajenas
    private static final Set<String> INSTITUTION_INDICATORS = Set.of(
            "universidad", "university", "universite", "universitat", "college", "facultad", "faculty",
            "school", "instituto", "institute", "hospital", "center", "centre", "csic", "cnrs",
            "department", "departamento", "departament", "clinica", "clinic", "polytechnic", "politecnica",
            "laboratory", "laboratorio", "fundacion", "foundation", "ciemat", "irta", "isciii", "ciber"
    );

    // Palabras geográficas o genéricas no institucionales
    private static final Set<String> GENERIC_LOCATION_WORDS = Set.of(
            "spain", "espana", "espanya", "unknown", "n a", "none", "null", "undefined",
            "catalonia", "catalunya", "madrid", "valencia", "europe", "department", "departament"
    );

    private static class HospitalRule {
        final String name;
        final List<String> patterns;
        HospitalRule(String name, List<String> patterns) {
            this.name = name;
            this.patterns = patterns;
        }
    }

    private static class InstituteRule {
        final String acronym;
        final String acronymWord;
        final List<String> namePatterns;
        InstituteRule(String acronym, String acronymWord, List<String> namePatterns) {
            this.acronym = acronym;
            this.acronymWord = acronymWord;
            this.namePatterns = namePatterns;
        }
    }

    /**
     * Resultado estructurado del cotejo de afiliaciones.
     */
    public static class AffiliationMatch {
        private final AffiliationStatus status;
        private final List<String> matchedAffiliations;
        private final String explanation;

        public AffiliationMatch(AffiliationStatus status, List<String> matchedAffiliations, String explanation) {
            this.status = status;
            this.matchedAffiliations = (matchedAffiliations != null) ? new ArrayList<>(matchedAffiliations) : Collections.emptyList();
            this.explanation = explanation;
        }

        public AffiliationStatus getStatus() {
            return status;
        }

        public List<String> getMatchedAffiliations() {
            return Collections.unmodifiableList(matchedAffiliations);
        }

        public String getExplanation() {
            return explanation;
        }

        public String getDescription() {
            return explanation;
        }

        public boolean isConfirmedUab() {
            return status == AffiliationStatus.CONFIRMED_UAB;
        }

        public boolean isOtherInstitution() {
            return status == AffiliationStatus.OTHER_INSTITUTION;
        }

        public boolean isAmbiguousOrMissing() {
            return status == AffiliationStatus.AMBIGUOUS_OR_MISSING;
        }

        @Override
        public String toString() {
            return "AffiliationMatch{" +
                    "status=" + status +
                    ", matchedAffiliations=" + matchedAffiliations +
                    ", explanation='" + explanation + '\'' +
                    '}';
        }
    }

    public AffiliationMatch matchAffiliation(String singleAffiliation) {
        if (singleAffiliation == null || singleAffiliation.isBlank()) {
            return matchAffiliation(Collections.emptyList(), Collections.emptyList());
        }
        return matchAffiliation(List.of(singleAffiliation), Collections.emptyList());
    }

    public AffiliationMatch matchAffiliation(List<String> rawAffiliationStrings) {
        return matchAffiliation(rawAffiliationStrings, Collections.emptyList());
    }

    /**
     * Analiza las firmas textuales y los identificadores institucionales para determinar
     * si la autoría pertenece a la UAB o su esfera, a otra institución ajena, o si es ambigua/ausente.
     *
     * @param rawAffiliationStrings lista de firmas textuales brutas de la obra
     * @param institutionIds lista de identificadores institucionales (ROR, OpenAlex, etc.)
     * @return resultado del cotejo con semántica AffiliationMatch
     */
    public AffiliationMatch matchAffiliation(List<String> rawAffiliationStrings, List<String> institutionIds) {
        List<String> safeRaw = (rawAffiliationStrings != null) ? rawAffiliationStrings : Collections.emptyList();
        List<String> safeIds = (institutionIds != null) ? institutionIds : Collections.emptyList();

        List<String> uabMatches = new ArrayList<>();

        // 1. Verificación por identificadores canónicos (ROR / OpenAlex)
        for (String id : safeIds) {
            if (id != null && !id.isBlank()) {
                String cleanId = id.trim().toLowerCase(Locale.ROOT);
                if (cleanId.contains(UAB_ROR_ID.toLowerCase(Locale.ROOT))) {
                    uabMatches.add("ROR:" + UAB_ROR_ID + " (Universitat Autònoma de Barcelona)");
                } else if (cleanId.contains(UAB_OPENALEX_ID.toLowerCase(Locale.ROOT))) {
                    uabMatches.add("OpenAlex:" + UAB_OPENALEX_ID + " (Universitat Autònoma de Barcelona)");
                }
            }
        }

        // 2. Verificación por firmas textuales
        for (String raw : safeRaw) {
            if (raw == null || raw.isBlank()) continue;
            String norm = CurationTextNormalizer.normalizeText(raw);
            if (norm.isBlank()) continue;

            // Cotejo matriz UAB
            boolean matchedUabDirect = false;
            for (String canonical : UAB_CANONICAL_TEXTS) {
                if (norm.contains(canonical)) {
                    uabMatches.add(raw.trim());
                    matchedUabDirect = true;
                    break;
                }
            }
            if (!matchedUabDirect && CurationTextNormalizer.containsWord(norm, "uab")) {
                uabMatches.add(raw.trim());
                matchedUabDirect = true;
            }

            if (matchedUabDirect) {
                continue;
            }

            // Cotejo hospitales vinculados
            boolean matchedHospital = false;
            for (HospitalRule hospital : AFFILIATED_HOSPITALS) {
                for (String pattern : hospital.patterns) {
                    if (norm.contains(pattern)) {
                        uabMatches.add(hospital.name);
                        matchedHospital = true;
                        break;
                    }
                }
                if (matchedHospital) break;
            }
            if (matchedHospital) {
                continue;
            }

            // Cotejo institutos de investigación consorciados / CERCA / CSIC esfera UAB
            for (InstituteRule institute : AFFILIATED_INSTITUTES) {
                if (CurationTextNormalizer.containsWord(norm, institute.acronymWord)) {
                    uabMatches.add(institute.acronym + " (Esfera UAB)");
                    break;
                }
                boolean matchedFullName = false;
                for (String namePat : institute.namePatterns) {
                    if (norm.contains(namePat)) {
                        uabMatches.add(institute.acronym + " (Esfera UAB)");
                        matchedFullName = true;
                        break;
                    }
                }
                if (matchedFullName) break;
            }
        }

        if (!uabMatches.isEmpty()) {
            return new AffiliationMatch(
                    AffiliationStatus.CONFIRMED_UAB,
                    uabMatches,
                    "Afiliación confirmada en la esfera UAB"
            );
        }

        // 3. Si no hay coincidencia con UAB, verificar si hay afiliaciones ajenas explícitas
        List<String> otherMatches = new ArrayList<>();

        for (String id : safeIds) {
            if (id != null && !id.isBlank()) {
                otherMatches.add(id.trim());
            }
        }

        for (String raw : safeRaw) {
            if (raw == null || raw.isBlank()) continue;
            String norm = CurationTextNormalizer.normalizeText(raw);
            if (norm.isBlank()) continue;

            if (isOtherInstitutionString(norm)) {
                otherMatches.add(raw.trim());
            }
        }

        if (!otherMatches.isEmpty()) {
            return new AffiliationMatch(
                    AffiliationStatus.OTHER_INSTITUTION,
                    otherMatches,
                    "Afiliación explícita a otra institución ajena a la UAB"
            );
        }

        // 4. Si no hay datos suficientes o solo cadenas genéricas/vacías
        return new AffiliationMatch(
                AffiliationStatus.AMBIGUOUS_OR_MISSING,
                Collections.emptyList(),
                "Sin afiliación institucional declarada o metadatos no concluyentes"
        );
    }

    private boolean isOtherInstitutionString(String normalizedText) {
        if (normalizedText == null || normalizedText.length() < 4) {
            return false;
        }

        if (GENERIC_LOCATION_WORDS.contains(normalizedText)) {
            return false;
        }

        for (String indicator : INSTITUTION_INDICATORS) {
            if (normalizedText.contains(indicator)) {
                return true;
            }
        }

        return false;
    }
}
