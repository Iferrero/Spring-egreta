package com.example.demo.linkaudit.verifier;

import com.example.demo.linkaudit.config.LinkAuditProperties;
import com.example.demo.linkaudit.model.FailureReason;
import com.example.demo.linkaudit.model.LinkCheckResult;
import com.example.demo.linkaudit.model.LinkStatus;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpLinkVerifierTest {

    private final List<HttpServer> serversToStop = new ArrayList<>();

    @AfterEach
    void stopServers() {
        for (HttpServer server : serversToStop) {
            server.stop(0);
        }
        serversToStop.clear();
    }

    private HttpServer startLoopbackServer() throws IOException {
        return startServer(InetAddress.getByName("127.0.0.1"));
    }

    private HttpServer startServer(InetAddress address) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(address, 0), 0);
        server.start();
        serversToStop.add(server);
        return server;
    }

    private static LinkAuditProperties defaultProps() {
        return new LinkAuditProperties();
    }

    private static String url(HttpServer server, String host, String path) {
        return "http://" + host + ":" + server.getAddress().getPort() + path;
    }

    @Test
    void linkAuditPropertiesExposeExpectedDefaults() {
        LinkAuditProperties props = new LinkAuditProperties();
        assertEquals(24, props.getGlobalConcurrency());
        assertEquals(3, props.getPerHostConcurrency());
        assertEquals(5000, props.getConnectTimeoutMs());
        assertEquals(10000, props.getRequestTimeoutMs());
        assertEquals(5, props.getMaxRedirects());
        assertEquals(500, props.getInsertBatchSize());
        assertEquals("Mozilla/5.0 (compatible; UAB-Egreta-LinkAudit/1.0)", props.getUserAgent());
    }

    @Test
    void verifyReturnsOkOnHead200() throws Exception {
        HttpServer server = startLoopbackServer();
        List<String> methods = new CopyOnWriteArrayList<>();
        List<String> userAgents = new CopyOnWriteArrayList<>();
        List<String> accepts = new CopyOnWriteArrayList<>();
        List<Boolean> hadApiKey = new CopyOnWriteArrayList<>();

        server.createContext("/ok", exchange -> {
            methods.add(exchange.getRequestMethod());
            Headers headers = exchange.getRequestHeaders();
            userAgents.add(headers.getFirst("User-Agent"));
            accepts.add(headers.getFirst("Accept"));
            hadApiKey.add(headers.containsKey("api-key"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpLinkVerifier verifier = new HttpLinkVerifier(defaultProps(), Map.of());
        String rawUrl = "  " + url(server, "127.0.0.1", "/ok") + "  ";
        LinkCheckResult result = verifier.verify(rawUrl);

        assertEquals(rawUrl, result.rawUrl());
        assertEquals(url(server, "127.0.0.1", "/ok"), result.normalizedUrl());
        assertEquals(url(server, "127.0.0.1", "/ok"), result.finalUrl());
        assertEquals(LinkStatus.OK, result.status());
        assertEquals(FailureReason.OK, result.reason());
        assertEquals(200, result.httpStatus());
        assertNull(result.errorMessage());
        assertNotNull(result.checkedAt());

        assertEquals(List.of("HEAD"), methods);
        assertEquals(List.of(defaultProps().getUserAgent()), userAgents);
        assertEquals(List.of("*/*"), accepts);
        assertEquals(List.of(false), hadApiKey);
    }

    @Test
    void verifyFallsBackToLightweightGetWhenHeadReturns405Or501Or400() throws Exception {
        HttpServer server = startLoopbackServer();
        List<String> recordedRanges = new CopyOnWriteArrayList<>();
        List<String> recordedMethods = new CopyOnWriteArrayList<>();

        for (int headCode : List.of(405, 501, 400)) {
            int getCode = headCode == 501 ? 206 : 200;
            server.createContext("/fallback-" + headCode, exchange -> {
                String method = exchange.getRequestMethod();
                recordedMethods.add(headCode + ":" + method);
                if ("HEAD".equals(method)) {
                    exchange.sendResponseHeaders(headCode, -1);
                } else {
                    recordedRanges.add(exchange.getRequestHeaders().getFirst("Range"));
                    exchange.sendResponseHeaders(getCode, -1);
                }
                exchange.close();
            });
        }

        server.createContext("/redirect-then-405", exchange -> {
            exchange.getResponseHeaders().add("Location", "/fallback-405");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.createContext("/always-405", exchange -> {
            recordedMethods.add("always:" + exchange.getRequestMethod());
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });

        HttpLinkVerifier verifier = new HttpLinkVerifier(defaultProps(), Map.of());

        for (int headCode : List.of(405, 501, 400)) {
            LinkCheckResult result = verifier.verify(url(server, "127.0.0.1", "/fallback-" + headCode));
            assertEquals(LinkStatus.OK, result.status(), "Failed for HEAD " + headCode);
            assertEquals(FailureReason.OK, result.reason());
            assertEquals(headCode == 501 ? 206 : 200, result.httpStatus());
        }

        LinkCheckResult redirectedResult = verifier.verify(url(server, "127.0.0.1", "/redirect-then-405"));
        assertEquals(LinkStatus.OK, redirectedResult.status());
        assertEquals(url(server, "127.0.0.1", "/fallback-405"), redirectedResult.finalUrl());

        LinkCheckResult still405 = verifier.verify(url(server, "127.0.0.1", "/always-405"));
        assertEquals(LinkStatus.BROKEN, still405.status());
        assertEquals(FailureReason.HTTP_4XX, still405.reason());
        assertEquals(405, still405.httpStatus());

        assertEquals(4, recordedRanges.size());
        assertTrue(recordedRanges.stream().allMatch("bytes=0-0"::equals));
        assertEquals(2L, recordedMethods.stream().filter(s -> s.startsWith("always:")).count());
    }

    @Test
    void verifyClassifiesBrokenAndBlockedStatuses() throws Exception {
        HttpServer server = startLoopbackServer();
        List<Integer> codes = List.of(404, 410, 500, 503, 418, 401, 403, 429);
        for (int code : codes) {
            server.createContext("/status-" + code, exchange -> {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
            });
        }

        HttpLinkVerifier verifier = new HttpLinkVerifier(defaultProps(), Map.of());

        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-404")), LinkStatus.BROKEN, FailureReason.HTTP_404, 404);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-410")), LinkStatus.BROKEN, FailureReason.HTTP_410, 410);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-500")), LinkStatus.BROKEN, FailureReason.HTTP_5XX, 500);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-503")), LinkStatus.BROKEN, FailureReason.HTTP_5XX, 503);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-418")), LinkStatus.BROKEN, FailureReason.HTTP_4XX, 418);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-401")), LinkStatus.BLOCKED, FailureReason.HTTP_401, 401);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-403")), LinkStatus.BLOCKED, FailureReason.HTTP_403, 403);
        assertStatus(verifier.verify(url(server, "127.0.0.1", "/status-429")), LinkStatus.BLOCKED, FailureReason.HTTP_429, 429);
    }

    @Test
    void verifyRejectsInvalidSyntaxWithoutNetworkCalls() {
        HttpLinkVerifier verifier = new HttpLinkVerifier(defaultProps(), Map.of());

        List<String> invalidUrls = new ArrayList<>();
        Collections.addAll(invalidUrls,
                null,
                "",
                "   ",
                "https://uab.cat/a b",
                "https://uab.cat/\npath",
                "https://uab.cat/\tpath",
                "ftp://uab.cat/file",
                "javascript:alert(1)",
                "https:///missing-host",
                "https://foo_bar.cat/x"
        );

        for (String badUrl : invalidUrls) {
            LinkCheckResult result = verifier.verify(badUrl);
            assertEquals(LinkStatus.BROKEN, result.status(), "Expected BROKEN for: " + badUrl);
            assertEquals(FailureReason.INVALID_SYNTAX, result.reason(), "Expected INVALID_SYNTAX for: " + badUrl);
            assertNull(result.httpStatus());
            assertNull(result.finalUrl());
            assertNotNull(result.errorMessage());
            assertFalse(result.errorMessage().isBlank());
            assertEquals(Optional.empty(), verifier.extractValidHttpHost(badUrl));
        }

        assertEquals(
                Optional.of("egreta.uab.cat"),
                verifier.extractValidHttpHost("  HTTPS://Egreta.UAB.CAT:8443/ws/api  ")
        );
    }

    @Test
    void verifyHandlesRedirectsRelativeChainMissingLocationAndLoop() throws Exception {
        HttpServer server = startLoopbackServer();

        server.createContext("/rel-start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        for (int i = 0; i < 6; i++) {
            final int hop = i;
            server.createContext("/chain/" + hop, exchange -> {
                if (hop < 5) {
                    exchange.getResponseHeaders().add("Location", "/chain/" + (hop + 1));
                    exchange.sendResponseHeaders(hop % 2 == 0 ? 301 : 302, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1);
                }
                exchange.close();
            });
        }

        for (int i = 0; i <= 6; i++) {
            final int hop = i;
            server.createContext("/loop/" + hop, exchange -> {
                exchange.getResponseHeaders().add("Location", "/loop/" + ((hop + 1) % 7));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
        }

        server.createContext("/missing-location", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.createContext("/invalid-location", exchange -> {
            exchange.getResponseHeaders().add("Location", "javascript:alert(1)");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        HttpLinkVerifier verifier = new HttpLinkVerifier(defaultProps(), Map.of());

        LinkCheckResult relResult = verifier.verify(url(server, "127.0.0.1", "/rel-start"));
        assertEquals(LinkStatus.OK, relResult.status());
        assertEquals(url(server, "127.0.0.1", "/final"), relResult.finalUrl());

        LinkCheckResult fiveHopResult = verifier.verify(url(server, "127.0.0.1", "/chain/0"));
        assertEquals(LinkStatus.OK, fiveHopResult.status());
        assertEquals(url(server, "127.0.0.1", "/chain/5"), fiveHopResult.finalUrl());

        LinkCheckResult sixHopLoop = verifier.verify(url(server, "127.0.0.1", "/loop/0"));
        assertEquals(LinkStatus.BROKEN, sixHopLoop.status());
        assertEquals(FailureReason.TOO_MANY_REDIRECTS, sixHopLoop.reason());

        LinkCheckResult missingLoc = verifier.verify(url(server, "127.0.0.1", "/missing-location"));
        assertEquals(LinkStatus.BROKEN, missingLoc.status());
        assertEquals(FailureReason.CONNECTION_ERROR, missingLoc.reason());

        LinkCheckResult invalidLoc = verifier.verify(url(server, "127.0.0.1", "/invalid-location"));
        assertEquals(LinkStatus.BROKEN, invalidLoc.status());
        assertEquals(FailureReason.INVALID_SYNTAX, invalidLoc.reason());
    }

    @Test
    void verifySendsApiKeyOnlyToConfiguredEgretaHostAndStripsOnExternalRedirect() throws Exception {
        HttpServer egretaServer = startServer(InetAddress.getByName("localhost"));
        HttpServer externalServer = startLoopbackServer();

        List<String> egretaReceivedKeys = new CopyOnWriteArrayList<>();
        List<String> egretaMethods = new CopyOnWriteArrayList<>();
        List<Boolean> externalSawApiKeyHeader = new CopyOnWriteArrayList<>();
        List<String> externalMethods = new CopyOnWriteArrayList<>();

        String externalTargetUrl = url(externalServer, "127.0.0.1", "/external-resource");

        egretaServer.createContext("/egreta-redirect", exchange -> {
            egretaMethods.add(exchange.getRequestMethod());
            egretaReceivedKeys.add(exchange.getRequestHeaders().getFirst("api-key"));
            exchange.getResponseHeaders().add("Location", externalTargetUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        externalServer.createContext("/external-resource", exchange -> {
            externalMethods.add(exchange.getRequestMethod());
            externalSawApiKeyHeader.add(exchange.getRequestHeaders().containsKey("api-key"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        String egretaBaseUrl = url(egretaServer, "localhost", "/ws/api");
        HttpLinkVerifier springStyleVerifier = new HttpLinkVerifier(
                defaultProps(),
                egretaBaseUrl,
                "secret-egreta-key",
                "not-a-valid-url://bad host",
                "ignored-key"
        );

        LinkCheckResult result = springStyleVerifier.verify(url(egretaServer, "localhost", "/egreta-redirect"));

        assertEquals(LinkStatus.OK, result.status());
        assertEquals(FailureReason.OK, result.reason());
        assertEquals(externalTargetUrl, result.finalUrl());
        assertEquals(List.of("secret-egreta-key"), egretaReceivedKeys);
        assertEquals(List.of("GET"), egretaMethods);
        assertEquals(List.of(false), externalSawApiKeyHeader);
        assertEquals(List.of("HEAD"), externalMethods);

        // Also verify direct map constructor normalizes host keys case-insensitively and strips on redirect
        HttpLinkVerifier mapVerifier = new HttpLinkVerifier(defaultProps(), Map.of("LOCALHOST", "map-key"));
        LinkCheckResult mapResult = mapVerifier.verify(url(egretaServer, "localhost", "/egreta-redirect"));
        assertEquals(LinkStatus.OK, mapResult.status());
        assertEquals(List.of("secret-egreta-key", "map-key"), egretaReceivedKeys);
        assertEquals(List.of("GET", "GET"), egretaMethods);
        assertEquals(List.of(false, false), externalSawApiKeyHeader);
        assertEquals(List.of("HEAD", "HEAD"), externalMethods);
    }

    @Test
    void verifyUsesDirectGetWithoutHeadForConfiguredEgretaHost() throws Exception {
        HttpServer egretaServer = startServer(InetAddress.getByName("localhost"));
        List<String> recordedMethods = new CopyOnWriteArrayList<>();
        List<String> recordedKeys = new CopyOnWriteArrayList<>();

        egretaServer.createContext("/ws/api/impacts/123/files/abc/doc.pdf", exchange -> {
            String method = exchange.getRequestMethod();
            recordedMethods.add(method);
            recordedKeys.add(exchange.getRequestHeaders().getFirst("api-key"));
            if ("HEAD".equals(method)) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    exchange.sendResponseHeaders(200, -1);
                } catch (IOException ignored) {
                    // Client timed out if HEAD was wrongly attempted
                } finally {
                    exchange.close();
                }
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            }
            exchange.close();
        });

        LinkAuditProperties props = defaultProps();
        props.setConnectTimeoutMs(500);
        props.setRequestTimeoutMs(400);
        HttpLinkVerifier verifier = new HttpLinkVerifier(props, Map.of("localhost", "egreta-secret"));

        LinkCheckResult result = verifier.verify(url(egretaServer, "localhost", "/ws/api/impacts/123/files/abc/doc.pdf"));

        assertEquals(LinkStatus.OK, result.status());
        assertEquals(FailureReason.OK, result.reason());
        assertEquals(200, result.httpStatus());
        assertEquals(List.of("GET"), recordedMethods);
        assertEquals(List.of("egreta-secret"), recordedKeys);
    }

    @Test
    void verifyClosesChunkedGetStreamWithoutDrainingFullBody() throws Exception {
        HttpServer server = startLoopbackServer();
        CountDownLatch bodyRelease = new CountDownLatch(1);

        server.createContext("/chunked-stream", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                try {
                    bodyRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException ignored) {
                // Client closed stream early after reading response headers
            } finally {
                exchange.close();
            }
        });

        LinkAuditProperties props = defaultProps();
        props.setRequestTimeoutMs(2000);
        HttpLinkVerifier verifier = new HttpLinkVerifier(props, Map.of());

        long startNanos = System.nanoTime();
        LinkCheckResult result;
        try {
            result = verifier.verify(url(server, "127.0.0.1", "/chunked-stream"));
        } finally {
            bodyRelease.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(LinkStatus.OK, result.status());
        assertEquals(FailureReason.OK, result.reason());
        assertEquals(200, result.httpStatus());
        assertTrue(elapsedMs < 1500, "Expected early return without draining body, took " + elapsedMs + " ms");
    }

    @Test
    void verifyClassifiesConnectionRefusedAndTimeout() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }

        HttpServer slowServer = startLoopbackServer();
        CountDownLatch slowHandlerEntered = new CountDownLatch(1);
        slowServer.createContext("/slow", exchange -> {
            slowHandlerEntered.countDown();
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException ignored) {
                // Client already closed on timeout
            } finally {
                exchange.close();
            }
        });

        LinkAuditProperties fastTimeoutProps = new LinkAuditProperties();
        fastTimeoutProps.setConnectTimeoutMs(500);
        fastTimeoutProps.setRequestTimeoutMs(150);

        HttpLinkVerifier verifier = new HttpLinkVerifier(fastTimeoutProps, Map.of());

        LinkCheckResult refusedResult = verifier.verify("http://127.0.0.1:" + closedPort + "/unreachable");
        assertEquals(LinkStatus.BROKEN, refusedResult.status());
        assertEquals(FailureReason.CONNECTION_ERROR, refusedResult.reason());
        assertNull(refusedResult.httpStatus());

        LinkCheckResult timeoutResult = verifier.verify(url(slowServer, "127.0.0.1", "/slow"));
        assertTrue(slowHandlerEntered.await(2, TimeUnit.SECONDS));
        assertEquals(LinkStatus.BROKEN, timeoutResult.status());
        assertEquals(FailureReason.TIMEOUT, timeoutResult.reason());
        assertNull(timeoutResult.httpStatus());
    }

    @Test
    void classifyExceptionMapsDnsTimeoutSslAndConnectionErrors() {
        HttpLinkVerifier verifier = new HttpLinkVerifier(defaultProps(), Map.of());

        assertEquals(FailureReason.DNS_ERROR, verifier.classifyException(new UnknownHostException("nope.invalid")));
        ConnectException wrappedUnresolved = new ConnectException("Failed");
        wrappedUnresolved.initCause(new UnresolvedAddressException());
        assertEquals(FailureReason.DNS_ERROR, verifier.classifyException(wrappedUnresolved));

        assertEquals(FailureReason.TIMEOUT, verifier.classifyException(new HttpTimeoutException("request timed out")));
        assertEquals(FailureReason.TIMEOUT, verifier.classifyException(new SocketTimeoutException("read timed out")));

        assertEquals(FailureReason.SSL_ERROR, verifier.classifyException(new SSLHandshakeException("bad cert")));
        assertEquals(FailureReason.SSL_ERROR, verifier.classifyException(new SSLException("tls error")));

        assertEquals(FailureReason.CONNECTION_ERROR, verifier.classifyException(new ConnectException("Connection refused")));
    }

    private static void assertStatus(LinkCheckResult result, LinkStatus status, FailureReason reason, int httpStatus) {
        assertEquals(status, result.status());
        assertEquals(reason, result.reason());
        assertEquals(httpStatus, result.httpStatus());
    }
}
