package com.example.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${cas.enabled:false}")
    private boolean casEnabled;

    @Bean
    public OpenAPI customOpenAPI() {
        String securitySchemeName = "basicAuth";
        String authDescription = casEnabled 
            ? "API REST protegida con CAS. En desarrollo local usa Basic Auth (usuario: recerca, contraseña: UAB)"
            : "API REST protegida con Basic Auth. Usuario por defecto: recerca, contraseña: UAB";

        return new OpenAPI()
                .info(new Info()
                        .title("Spring Egreta API")
                        .version("1.0.0")
                        .description(authDescription)
                        .contact(new Contact()
                                .name("Equipo de Desarrollo")
                                .email("recerca@uab.cat"))
                        .license(new License()
                                .name("Licencia UAB")
                                .url("https://www.uab.cat")))
                .servers(List.of(
                        new Server()
                                .url(contextPath.isEmpty() ? "/" : contextPath)
                                .description("Servidor local")
                ))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .description("Autenticación Basic HTTP (usuario/contraseña)")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName));
    }
}
