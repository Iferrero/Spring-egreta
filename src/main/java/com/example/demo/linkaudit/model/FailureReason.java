package com.example.demo.linkaudit.model;

public enum FailureReason {
    OK,
    HTTP_404,
    HTTP_410,
    HTTP_4XX,
    HTTP_5XX,
    HTTP_401,
    HTTP_403,
    HTTP_429,
    CLOUDFLARE_CHALLENGE,
    INVALID_SYNTAX,
    DNS_ERROR,
    TIMEOUT,
    SSL_ERROR,
    CONNECTION_ERROR,
    TOO_MANY_REDIRECTS
}
