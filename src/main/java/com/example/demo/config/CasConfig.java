package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apereo.cas.client.authentication.AuthenticationFilter;
import org.apereo.cas.client.session.SingleSignOutFilter;
import org.apereo.cas.client.session.SingleSignOutHttpSessionListener;
import org.apereo.cas.client.util.AbstractCasFilter;
import org.apereo.cas.client.util.HttpServletRequestWrapperFilter;
import org.apereo.cas.client.validation.Assertion;
import org.apereo.cas.client.validation.Cas30ProxyReceivingTicketValidationFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "cas.enabled", havingValue = "true", matchIfMissing = false)
public class CasConfig {

    @Value("${cas.server.url-prefix}")
    private String casServerUrlPrefix;

    @Value("${cas.service.server-name}")
    private String serverName;

    // ── 1. Single Sign-Out Filter (must run first) ──────────────────────────
    @Bean
    FilterRegistrationBean<SingleSignOutFilter> casSignOutFilter() {
        FilterRegistrationBean<SingleSignOutFilter> reg =
                new FilterRegistrationBean<>(new SingleSignOutFilter());
        reg.addInitParameter("casServerUrlPrefix", casServerUrlPrefix);
        reg.setUrlPatterns(List.of("/*"));
        reg.setOrder(1);
        return reg;
    }

    // ── 2. CAS Authentication Filter (redirects to CAS login if no session) ─
    @Bean
    FilterRegistrationBean<AuthenticationFilter> casAuthenticationFilter() {
        FilterRegistrationBean<AuthenticationFilter> reg =
                new FilterRegistrationBean<>(new AuthenticationFilter());
        reg.addInitParameter("casServerLoginUrl", casServerUrlPrefix + "/cas/login");
        reg.addInitParameter("serverName", serverName);
        reg.setUrlPatterns(List.of("/*"));
        reg.setOrder(2);
        return reg;
    }

    // ── 3. Ticket Validation Filter (validates service ticket with CAS server)
    @Bean
    FilterRegistrationBean<Cas30ProxyReceivingTicketValidationFilter> casValidationFilter() {
        FilterRegistrationBean<Cas30ProxyReceivingTicketValidationFilter> reg =
                new FilterRegistrationBean<>(new Cas30ProxyReceivingTicketValidationFilter());
        reg.addInitParameter("casServerUrlPrefix", casServerUrlPrefix);
        reg.addInitParameter("serverName", serverName);
        reg.setUrlPatterns(List.of("/*"));
        reg.setOrder(3);
        return reg;
    }

    // ── 4. HttpServletRequest Wrapper (exposes getRemoteUser() / getUserPrincipal())
    @Bean
    FilterRegistrationBean<HttpServletRequestWrapperFilter> casRequestWrapperFilter() {
        FilterRegistrationBean<HttpServletRequestWrapperFilter> reg =
                new FilterRegistrationBean<>(new HttpServletRequestWrapperFilter());
        reg.setUrlPatterns(List.of("/*"));
        reg.setOrder(4);
        return reg;
    }

    // ── 5. Bridge filter: reads CAS assertion → populates Spring Security context
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> casSecurityBridgeFilter() {
        OncePerRequestFilter bridge = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain) throws IOException, jakarta.servlet.ServletException {
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    var session = request.getSession(false);
                    Assertion assertion = session == null ? null
                            : (Assertion) session.getAttribute(AbstractCasFilter.CONST_CAS_ASSERTION);
                    if (assertion != null) {
                        String username = assertion.getPrincipal().getName();
                        var auth = new UsernamePasswordAuthenticationToken(
                                username, null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(bridge);
        reg.setUrlPatterns(List.of("/*"));
        reg.setOrder(5);
        return reg;
    }

    // ── Session listener for CAS SLO ─────────────────────────────────────────
    @Bean
    ServletListenerRegistrationBean<SingleSignOutHttpSessionListener> casSessionListener() {
        return new ServletListenerRegistrationBean<>(new SingleSignOutHttpSessionListener());
    }
}

