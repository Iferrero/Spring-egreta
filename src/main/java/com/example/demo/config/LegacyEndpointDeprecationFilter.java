package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LegacyEndpointDeprecationFilter extends OncePerRequestFilter {

    @Value("${app.api.legacy-endpoints-enabled:true}")
    private boolean legacyEndpointsEnabled;

    private static final String SUNSET_RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME
        .format(ZonedDateTime.parse("2026-12-31T23:59:59Z"));

    private static final Map<String, String> LEGACY_TO_CANONICAL = new LinkedHashMap<>();

    static {
        // Keep more specific suffixes first to avoid partial matches.
        LEGACY_TO_CANONICAL.put("/stats/tipos-por-anio", "/stats/types-by-year");
        LEGACY_TO_CANONICAL.put("/stats/apa-list", "/stats/apa");
        LEGACY_TO_CANONICAL.put("/stats/per-any-institut", "/stats/per-year-institute");
        LEGACY_TO_CANONICAL.put("/stats/llista-institut", "/stats/list-institute");
        LEGACY_TO_CANONICAL.put("/mismo-autor-director", "/stats/same-author-director");

        LEGACY_TO_CANONICAL.put("/informe-word-persona", "/reports/word/person");
        LEGACY_TO_CANONICAL.put("/informe-word-pais", "/reports/word/country");
        LEGACY_TO_CANONICAL.put("/informe-word", "/reports/word");
        LEGACY_TO_CANONICAL.put("/search-vigentes", "/search/active");
        LEGACY_TO_CANONICAL.put("/debug-tesis", "/debug/tesis");
        LEGACY_TO_CANONICAL.put("/stats/anios", "/stats/years");
        LEGACY_TO_CANONICAL.put("/stats/tipos", "/stats/types");
        LEGACY_TO_CANONICAL.put("/buscar", "/search");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();
        LegacyMatch legacyMatch = findLegacyMatch(uri);

        if (legacyMatch != null && !legacyEndpointsEnabled && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_GONE);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            String json = "{" +
                "\"error\":\"Legacy endpoint disabled\"," +
                "\"message\":\"Use canonical endpoint\"," +
                "\"legacy\":\"" + legacyMatch.legacyUri() + "\"," +
                "\"successor\":\"" + legacyMatch.successorUri() + "\"" +
                "}";
            response.getWriter().write(json);
            return;
        }

        if (legacyMatch != null) {
            response.setHeader("Deprecation", "true");
            response.setHeader("Sunset", SUNSET_RFC_1123);
            response.setHeader("Link", "<" + legacyMatch.successorUri() + ">; rel=\"successor-version\"");
            response.setHeader("Warning", "299 - \"Deprecated endpoint, use successor URI\"");
        }

        filterChain.doFilter(request, response);
    }

    private LegacyMatch findLegacyMatch(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return null;
        }

        for (Map.Entry<String, String> entry : LEGACY_TO_CANONICAL.entrySet()) {
            String legacySuffix = entry.getKey();
            if (!requestUri.endsWith(legacySuffix)) {
                continue;
            }
            String prefix = requestUri.substring(0, requestUri.length() - legacySuffix.length());
            String canonicalPrefix = toCanonicalPrefix(prefix);
            return new LegacyMatch(prefix + legacySuffix, canonicalPrefix + entry.getValue());
        }

        return null;
    }

    private record LegacyMatch(String legacyUri, String successorUri) {
    }

    private String toCanonicalPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/api";
        }

        if (prefix.startsWith("/api")) {
            return prefix;
        }

        if (prefix.startsWith("/otr/api")) {
            return "/api" + prefix.substring("/otr/api".length());
        }

        return "/api" + prefix;
    }
}
