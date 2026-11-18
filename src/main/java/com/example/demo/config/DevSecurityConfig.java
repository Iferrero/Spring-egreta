package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Seguridad para desarrollo local (cas.enabled=false): Basic Auth con usuario local.
 * NO usar en producción — en prod se usa CAS vía SecurityConfig + CasConfig.
 */
@Configuration
@ConditionalOnProperty(name = "cas.enabled", havingValue = "false")
public class DevSecurityConfig {

    @Value("${app.api.key}")
    private String expectedApiKey;

    @Bean
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .addFilterBefore(new ApiKeyAuthenticationFilter(expectedApiKey), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Permitir acceso público a Swagger UI, RapiDoc y OpenAPI docs
                .requestMatchers(
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/rapidoc.html"
                ).permitAll()
                // Recursos estáticos compartidos por las páginas con usuarios específicos
                .requestMatchers(
                    "/config.js",
                    "/uab-brand.js",
                    "/uab-theme.css",
                    "/egreta-modal.js",
                    "/favicon.ico"
                ).hasAnyRole("USER", "EUROPEES", "TERRITORI")
                // Acceso específico a convocatories-europees.html y sus endpoints
                .requestMatchers(
                    "/convocatories-europees.html",
                    "/api/applications/stats/by-funding-opportunity",
                    "/api/applications/stats/by-funding-opportunity/**"
                ).hasAnyRole("USER", "EUROPEES")
                // Acceso específico a mapa-interaccions-territori.html y sus consultas dinámicas
                .requestMatchers(
                    "/mapa-interaccions-territori.html",
                    "/api/mapa-territori/**"
                ).hasAnyRole("USER", "TERRITORI")
                .anyRequest().hasRole("USER"))
            .httpBasic(Customizer.withDefaults())
            .formLogin(form -> form.disable())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    UserDetailsService devUserDetailsService(
            @Value("${dev.user.name:recerca}") String username,
            @Value("${dev.user.password:UAB}") String password,
            @Value("${dev.eu.user.name:europees}") String euUsername,
            @Value("${dev.eu.user.password:UAB}") String euPassword,
            @Value("${dev.territori.user.name:territori}") String territoriUsername,
            @Value("${dev.territori.user.password:UAB}") String territoriPassword,
            PasswordEncoder passwordEncoder) {
        UserDetails mainUser = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();
        UserDetails euUser = User.withUsername(euUsername)
                .password(passwordEncoder.encode(euPassword))
                .roles("EUROPEES")
                .build();
        UserDetails territoriUser = User.withUsername(territoriUsername)
                .password(passwordEncoder.encode(territoriPassword))
                .roles("TERRITORI")
                .build();
        return new InMemoryUserDetailsManager(mainUser, euUser, territoriUser);
    }

    @Bean
    PasswordEncoder devPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}


