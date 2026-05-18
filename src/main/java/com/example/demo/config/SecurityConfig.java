package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

@Configuration
@ConditionalOnProperty(name = "cas.enabled", havingValue = "true", matchIfMissing = false)
public class SecurityConfig {

    @Value("${cas.server.url-prefix}")
    private String casServerUrlPrefix;

    @Value("${cas.service.server-name}")
    private String serverName;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CAS servlet filters (registered in CasConfig) intercept requests BEFORE
        // Spring Security and redirect unauthenticated users to CAS login.
        // The CasBridgeFilter populates the Spring Security context from the CAS session.
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                // Redirect to CAS login if Spring Security requires auth and no session exists
                .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(
                        casServerUrlPrefix + "/cas/login?service=https://" + serverName + "/"))
            )
            .logout(logout -> logout
                .logoutSuccessUrl(casServerUrlPrefix + "/cas/logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            )
            .csrf(csrf -> csrf.disable()); // REST API — CSRF not needed with session+CAS

        return http.build();
    }
}

