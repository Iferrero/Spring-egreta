package com.example.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import jakarta.servlet.ServletContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Autowired
    private ServletContext servletContext;

    @Value("${cas.enabled:false}")
    private boolean casEnabled;

    @Bean
    public OpenAPI customOpenAPI() {
        String basicAuthScheme = "basicAuth";
        String apiKeyScheme = "apiKey";
        String authDescription = casEnabled 
            ? "API REST protegida con CAS / API Key. En desarrollo local usa Basic Auth (usuario: recerca, contraseña: UAB) o API Key."
            : "API REST protegida con Basic Auth / API Key. Usuario por defecto: recerca, contraseña: UAB.";

        String contextVal = servletContext != null ? servletContext.getContextPath() : "";
        String serverUrl = (contextVal == null || contextVal.isEmpty() || contextVal.equals("/")) ? "/" : contextVal;

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
                                .url(serverUrl)
                                .description("Servidor local")
                ))
                .components(new Components()
                        .addSecuritySchemes(basicAuthScheme,
                                new SecurityScheme()
                                        .name(basicAuthScheme)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .description("Autenticación Basic HTTP (usuario/contraseña)"))
                        .addSecuritySchemes(apiKeyScheme,
                                new SecurityScheme()
                                        .name("api-key")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("Autenticación por API Key en la cabecera 'api-key' o 'X-API-KEY'")))
                .addSecurityItem(new SecurityRequirement().addList(basicAuthScheme))
                .addSecurityItem(new SecurityRequirement().addList(apiKeyScheme));
    }
}
