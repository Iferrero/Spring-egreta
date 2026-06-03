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

/**
 * Seguridad para desarrollo local (cas.enabled=false): Basic Auth con usuario local.
 * NO usar en producción — en prod se usa CAS vía SecurityConfig + CasConfig.
 */
@Configuration
@ConditionalOnProperty(name = "cas.enabled", havingValue = "false")
public class DevSecurityConfig {

    @Bean
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Permitir acceso público a Swagger UI y OpenAPI docs
                .requestMatchers(
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .formLogin(form -> form.disable())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    UserDetailsService devUserDetailsService(
            @Value("${dev.user.name:recerca}") String username,
            @Value("${dev.user.password:UAB}") String password,
            PasswordEncoder passwordEncoder) {
        UserDetails user = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    PasswordEncoder devPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}


