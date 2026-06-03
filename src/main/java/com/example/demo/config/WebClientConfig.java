package com.example.demo.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Value("${app.webclient.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.webclient.response-timeout-ms:12000}")
    private int responseTimeoutMs;

    @Value("${app.webclient.read-timeout-seconds:15}")
    private int readTimeoutSeconds;

    @Value("${app.webclient.write-timeout-seconds:15}")
    private int writeTimeoutSeconds;

    @Value("${app.webclient.pool.max-connections:100}")
    private int maxConnections;

    @Value("${app.webclient.pool.max-idle-seconds:20}")
    private int maxIdleSeconds;

    @Bean
    public WebClient.Builder webClientBuilder() {
        ConnectionProvider provider = ConnectionProvider.builder("egreta-webclient-pool")
            .maxConnections(Math.max(20, maxConnections))
            .maxIdleTime(Duration.ofSeconds(Math.max(5, maxIdleSeconds)))
            .evictInBackground(Duration.ofSeconds(30))
            .build();

        HttpClient httpClient = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(1000, connectTimeoutMs))
            .responseTimeout(Duration.ofMillis(Math.max(1000, responseTimeoutMs)))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(Math.max(1, readTimeoutSeconds), TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(Math.max(1, writeTimeoutSeconds), TimeUnit.SECONDS))
            );

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
