package com.example.demo.territori;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static com.example.demo.territori.TerritoriText.normalize;

/**
 * Diccionaris de classificació territorial (municipis, indicadors, coordenades...) carregats de
 * {@code mapa-territori/lookups.json}. L'ordre de les llistes importa (primera coincidència guanya).
 */
final class TerritoriLookups {

    record Indicator(String keyword, String provincia) {}

    record Coord(double lat, double lon) {}

    static final String UAB_ORG_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb";
    static final String ESFERA_ORG_UUID = "53d7b18a-caf7-4ded-840e-942ff50adc82";
    static final String UAB_EXTERNAL_SELF_UUID = "9c6105b0-d76c-4055-a281-0f8271544640";

    static final Set<String> CONVENI_TYPES = Set.of("Concessió conveni", "Conveni extern a la UAB");
    static final Set<String> PROJECT_TYPES = Set.of(
            "Projectes i Ajuts a la Recerca",
            "Projectes d'investigació Internacionals",
            "Projectes de Recerca i altres tipus d'ajuts Externs a la UAB",
            "Grups i Xarxes de Recerca",
            "Grups i Xarxes de Recerca externs a la UAB",
            "Projectes Educatius Internacionals",
            "Accions complementàries i altres ajuts",
            "Finançament Específic");

    final Set<String> placeholderOrgNames;
    final List<String> nonCatCities;
    final List<String> nonCatRegions;
    final Map<String, String> catMunicipisProv; // ordenat
    final List<Indicator> catNameIndicators;
    final Map<String, String> cpPrefixProvince;
    final List<String> privateUniKeywords;
    final List<String> privateLegalSuffixes;
    final List<String> publicKeywords;
    final List<String> uabKeepKeywords;
    final Map<String, Coord> municipiCoords; // ordenat
    final Map<String, Coord> cp3Coords;
    final Map<String, Coord> provCentroids;

    private TerritoriLookups(JsonNode root) {
        placeholderOrgNames = new HashSet<>(strings(root.get("placeholderOrgNames")));
        nonCatCities = strings(root.get("nonCatCities"));
        nonCatRegions = strings(root.get("nonCatRegions"));
        catMunicipisProv = new LinkedHashMap<>();
        for (JsonNode p : root.get("catMunicipisProv")) catMunicipisProv.put(p.get(0).asText(), p.get(1).asText());
        catNameIndicators = new ArrayList<>();
        for (JsonNode p : root.get("catNameIndicators")) catNameIndicators.add(new Indicator(p.get(0).asText(), p.get(1).asText()));
        cpPrefixProvince = new LinkedHashMap<>();
        root.get("cpPrefixProvince").fields().forEachRemaining(e -> cpPrefixProvince.put(e.getKey(), e.getValue().asText()));
        privateUniKeywords = strings(root.get("privateUniKeywords"));
        privateLegalSuffixes = strings(root.get("privateLegalSuffixes"));
        publicKeywords = strings(root.get("publicKeywords"));
        uabKeepKeywords = strings(root.get("uabKeepKeywords"));
        municipiCoords = coords(root.get("municipiCoords"));
        cp3Coords = coords(root.get("cp3Coords"));
        provCentroids = coords(root.get("provCentroids"));
    }

    static TerritoriLookups load() {
        try (InputStream in = TerritoriLookups.class.getResourceAsStream("/mapa-territori/lookups.json")) {
            if (in == null) throw new IllegalStateException("Falta mapa-territori/lookups.json al classpath");
            return new TerritoriLookups(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("No s'ha pogut llegir mapa-territori/lookups.json", e);
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    private static Map<String, Coord> coords(JsonNode arr) {
        Map<String, Coord> out = new LinkedHashMap<>();
        for (JsonNode p : arr) out.put(p.get(0).asText(), new Coord(p.get(1).get(0).asDouble(), p.get(1).get(1).asDouble()));
        return out;
    }

    // ---- resolució de coordenades ------------------------------------------------

    record Resolved(double lat, double lon, String origin) {}

    /** Prioritat: coordenades EGRETA → municipi → prefix CP3 → província. */
    Resolved resolveCoords(String coordsStr, String municipi, String cp, String provincia) {
        if (coordsStr != null && coordsStr.contains(",")) {
            String[] parts = coordsStr.split(",", -1);
            try {
                double lat = Double.parseDouble(parts[0].strip());
                double lon = Double.parseDouble(parts[1].strip());
                if (lat >= 40.3 && lat <= 43.0 && lon >= 0.0 && lon <= 3.5) {
                    return new Resolved(TerritoriText.round(lat, 5), TerritoriText.round(lon, 5), "egreta");
                }
            } catch (NumberFormatException ignored) {
                // cau al següent criteri
            }
        }
        String mNorm = normalize(municipi);
        if (!mNorm.isEmpty()) {
            Coord c = municipiCoords.get(mNorm);
            if (c != null) return new Resolved(c.lat(), c.lon(), "municipi");
            String padded = " " + mNorm + " ";
            for (Map.Entry<String, Coord> e : municipiCoords.entrySet()) {
                if (padded.contains(" " + e.getKey() + " ")) {
                    return new Resolved(e.getValue().lat(), e.getValue().lon(), "municipi");
                }
            }
        }
        String digits = cp == null ? "" : cp.replaceAll("[^0-9]", "");
        if (digits.length() >= 3) {
            Coord c = cp3Coords.get(digits.substring(0, 3));
            if (c != null) return new Resolved(c.lat(), c.lon(), "cp3");
        }
        Coord c = provCentroids.getOrDefault(provincia, provCentroids.get("Catalunya (general)"));
        return new Resolved(c.lat(), c.lon(), "provincia");
    }
}
