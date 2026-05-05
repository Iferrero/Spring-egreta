package com.example.demo.config;

public final class SecurityPaths {

    public static final String[] PROTECTED_ENDPOINT_PATTERNS = {
        "/api/**",
        "/otr/api/**",
        "/applications/**",
        "/awards/**",
        "/external-organizations/**",
        "/funding-opportunities/**",
        "/journals/**",
        "/pure/**",
        "/persons/**",
        "/student-theses/**"
    };

    private SecurityPaths() {
    }
}
