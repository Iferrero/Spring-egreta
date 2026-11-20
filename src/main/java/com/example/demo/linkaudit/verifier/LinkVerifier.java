package com.example.demo.linkaudit.verifier;

import com.example.demo.linkaudit.model.LinkCheckResult;

import java.util.Optional;

/**
 * Verifies HTTP/HTTPS link availability and extracts normalized hostnames for rate-limiting.
 */
public interface LinkVerifier {

    LinkCheckResult verify(String rawUrl);

    Optional<String> extractValidHttpHost(String rawUrl);
}
