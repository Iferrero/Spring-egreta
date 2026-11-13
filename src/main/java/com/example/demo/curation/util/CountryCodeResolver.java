package com.example.demo.curation.util;

import com.example.demo.curation.model.CountryAggregate;

import java.util.List;
import java.util.Locale;

/**
 * Resolves an ISO 3166-1 alpha-2 code (as returned by ROR / OpenAlex) to the country label used in the
 * catalog, instead of leaking the bare code ("ES") as if it were a country name.
 */
public final class CountryCodeResolver {

    private CountryCodeResolver() {}

    private static volatile java.util.Map<String, String> namesToCode;

    private static java.util.Map<String, String> namesToCode() {
        java.util.Map<String, String> m = namesToCode;
        if (m == null) {
            m = new java.util.HashMap<>();
            for (String iso : Locale.getISOCountries()) {
                Locale country = Locale.of("", iso);
                for (Locale display : List.of(Locale.ENGLISH, Locale.forLanguageTag("es"), Locale.forLanguageTag("ca"), Locale.forLanguageTag("fr"), Locale.GERMAN)) {
                    String n = CurationTextNormalizer.normalizeText(country.getDisplayCountry(display));
                    if (n.length() >= 4) {
                        m.putIfAbsent(n, iso);
                    }
                }
            }
            namesToCode = m;
        }
        return m;
    }

    /** ISO codes of the countries whose name appears as a whole word in the given text (e.g. "...of Sweden" -> SE). */
    public static java.util.Set<String> countryCodesNamedIn(String text) {
        java.util.Set<String> found = new java.util.HashSet<>();
        String norm = CurationTextNormalizer.normalizeText(text);
        if (norm.isBlank()) return found;
        for (java.util.Map.Entry<String, String> e : namesToCode().entrySet()) {
            if (CurationTextNormalizer.containsWord(norm, e.getKey())) {
                found.add(e.getValue());
            }
        }
        return found;
    }

    /** Returns the catalog label for the code, or the English country name when the catalog has no match, or "" if the code is not valid. */
    public static String resolve(String countryCode, List<CountryAggregate> catalog) {
        if (countryCode == null) return "";
        String code = countryCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 2) return "";

        Locale country = Locale.of("", code);
        String english = country.getDisplayCountry(Locale.ENGLISH);
        if (english == null || english.isBlank() || english.equalsIgnoreCase(code)) {
            return "";
        }

        if (catalog != null) {
            for (Locale display : List.of(Locale.forLanguageTag("ca"), Locale.forLanguageTag("es"), Locale.ENGLISH)) {
                String name = CurationTextNormalizer.normalizeText(country.getDisplayCountry(display));
                if (name.isBlank()) continue;
                for (CountryAggregate c : catalog) {
                    if (name.equals(c.normalizedLabel())) {
                        return c.label();
                    }
                }
            }
        }
        return english;
    }
}
