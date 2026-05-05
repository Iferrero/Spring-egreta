package com.example.demo.config;

import java.io.IOException;
import java.util.Arrays;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String apiKey;
    private final String apiKeyHeader;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public ApiKeyAuthFilter(
        @Value("${app.security.api-key}") String apiKey,
        @Value("${app.security.api-key-header:X-API-KEY}") String apiKeyHeader
    ) {
        this.apiKey = apiKey;
        this.apiKeyHeader = apiKeyHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        if (!isProtectedPath(request.getServletPath())) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedApiKey = request.getHeader(apiKeyHeader);

        if (apiKey != null && !apiKey.isBlank() && apiKey.equals(providedApiKey)) {
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("api-key-client", null, AuthorityUtils.NO_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid API key\"}");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    private boolean isProtectedPath(String servletPath) {
        return Arrays.stream(SecurityPaths.PROTECTED_ENDPOINT_PATTERNS)
            .anyMatch(pattern -> antPathMatcher.match(pattern, servletPath));
    }
}
