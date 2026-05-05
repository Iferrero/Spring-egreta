package com.example.demo.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class JcrMongoConfig {

    @Bean(name = "mongoClient")
    @Primary
    public MongoClient mongoClient(@Value("${spring.mongodb.uri}") String uri) {
        return MongoClients.create(uri);
    }

    @Bean(name = "mongoTemplate")
    @Primary
    public MongoTemplate mongoTemplate(@Qualifier("mongoClient") MongoClient mongoClient) {
        return new MongoTemplate(mongoClient, "kraken");
    }

    @Bean(name = "jcrMongoClient")
    public MongoClient jcrMongoClient(@Value("${app.jcr.mongodb.uri}") String uri) {
        return MongoClients.create(uri);
    }

    @Bean(name = "jcrMongoTemplate")
    public MongoTemplate jcrMongoTemplate(@Qualifier("jcrMongoClient") MongoClient mongoClient) {
        return new MongoTemplate(mongoClient, "JCR");
    }
}