package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final String expectedApiKey;

    public ApiKeyAuthenticationFilter(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/v3/api-docs") ||
               path.startsWith("/swagger-ui") ||
               path.equals("/swagger-ui.html") ||
               path.startsWith("/rapidoc.html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestApiKey = request.getHeader("api-key");
        if (requestApiKey == null) {
            requestApiKey = request.getHeader("X-API-KEY");
        }

        if (requestApiKey != null) {
            if (expectedApiKey == null || expectedApiKey.isBlank()) {
                log.warn("API Key authentication requested but expected API Key is not configured on the server.");
                sendUnauthorized(response, "API Key authentication is not configured on the server.");
                return;
            }

            if (expectedApiKey.trim().equals(requestApiKey.trim())) {
                log.debug("Successful API Key authentication for path: {}", request.getServletPath());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        "api-client",
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                log.warn("Invalid API Key provided for path: {}", request.getServletPath());
                sendUnauthorized(response, "Invalid API Key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format("{\"error\": \"Unauthorized\", \"message\": \"%s\"}", message));
    }
}
