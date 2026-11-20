package com.example.demo.linkaudit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for collection link audit HTTP verification and concurrency.
 */
@Component
@ConfigurationProperties(prefix = "app.link-audit")
public class LinkAuditProperties {

    private int globalConcurrency = 24;
    private int perHostConcurrency = 3;
    private int connectTimeoutMs = 5000;
    private int requestTimeoutMs = 10000;
    private int maxRedirects = 5;
    private int insertBatchSize = 500;
    private String userAgent = "Mozilla/5.0 (compatible; UAB-Egreta-LinkAudit/1.0)";

    public int getGlobalConcurrency() {
        return globalConcurrency;
    }

    public void setGlobalConcurrency(int globalConcurrency) {
        this.globalConcurrency = globalConcurrency;
    }

    public int getPerHostConcurrency() {
        return perHostConcurrency;
    }

    public void setPerHostConcurrency(int perHostConcurrency) {
        this.perHostConcurrency = perHostConcurrency;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public int getInsertBatchSize() {
        return insertBatchSize;
    }

    public void setInsertBatchSize(int insertBatchSize) {
        this.insertBatchSize = insertBatchSize;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
