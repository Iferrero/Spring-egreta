package com.example.demo.linkaudit.verifier;

import com.example.demo.linkaudit.config.LinkAuditProperties;
import com.example.demo.linkaudit.model.FailureReason;
import com.example.demo.linkaudit.model.LinkCheckResult;
import com.example.demo.linkaudit.model.LinkStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * HTTP(S) link verifier using Java 21 {@link HttpClient} with manual redirect
 * tracking, {@code HEAD} with lightweight {@code GET} fallback, and per-hop
 * host matching so internal {@code api-key} headers are never leaked externally.
 */
@Component
public class HttpLinkVerifier implements LinkVerifier {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; UAB-Egreta-LinkAudit/1.0)";
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> HEAD_FALLBACK_STATUSES = Set.of(400, 405, 501);

    private final HttpClient httpClient;
    private final Map<String, String> egretaHostApiKeys;
    private final Duration requestTimeout;
    private final int maxRedirects;
    private final String userAgent;

    @Autowired
    public HttpLinkVerifier(
            LinkAuditProperties properties,
            @Value("${app.egreta.external-org.base-url:}") String externalOrgBaseUrl,
            @Value("${app.egreta.external-org.api-key:}") String externalOrgApiKey,
            @Value("${egreta54.base.url:}") String egreta54BaseUrl,
            @Value("${egreta54.api.key:}") String egreta54ApiKey
    ) {
        this(properties, buildEgretaHostApiKeys(externalOrgBaseUrl, externalOrgApiKey, egreta54BaseUrl, egreta54ApiKey));
    }

    public HttpLinkVerifier(LinkAuditProperties properties, Map<String, String> egretaHostApiKeys) {
        Objects.requireNonNull(properties, "properties must not be null");
        int connectTimeoutMs = Math.max(1, properties.getConnectTimeoutMs());
        int requestTimeoutMs = Math.max(1, properties.getRequestTimeoutMs());
        this.maxRedirects = Math.max(0, properties.getMaxRedirects());
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        String configuredAgent = properties.getUserAgent();
        this.userAgent = (configuredAgent != null && !configuredAgent.isBlank())
                ? configuredAgent.trim()
                : DEFAULT_USER_AGENT;
        this.egretaHostApiKeys = normalizeHostApiKeys(egretaHostApiKeys);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public Optional<String> extractValidHttpHost(String rawUrl) {
        UriValidation validation = validateRawUrl(rawUrl);
        if (!validation.isValid()) {
            return Optional.empty();
        }
        return Optional.of(validation.uri().getHost().toLowerCase(Locale.ROOT));
    }

    @Override
    public LinkCheckResult verify(String rawUrl) {
        String normalizedUrl = rawUrl == null ? null : rawUrl.trim();
        UriValidation initialValidation = validateRawUrl(rawUrl);
        if (!initialValidation.isValid()) {
            return result(rawUrl, normalizedUrl, null,
                    LinkStatus.BROKEN, FailureReason.INVALID_SYNTAX, null, initialValidation.errorMessage());
        }

        URI currentUri = initialValidation.uri();
        int redirects = 0;

        while (true) {
            HttpResponse<Void> response;
            try {
                response = sendHeadWithGetFallback(currentUri);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return result(rawUrl, normalizedUrl, currentUri.toString(),
                        LinkStatus.BROKEN, FailureReason.CONNECTION_ERROR, null, "Verificació interrompuda");
            } catch (IllegalArgumentException ex) {
                return result(rawUrl, normalizedUrl, currentUri.toString(),
                        LinkStatus.BROKEN, FailureReason.INVALID_SYNTAX, null, formatExceptionMessage(ex));
            } catch (IOException ex) {
                FailureReason reason = classifyException(ex);
                return result(rawUrl, normalizedUrl, currentUri.toString(),
                        LinkStatus.BROKEN, reason, null, formatExceptionMessage(ex));
            }

            int statusCode = response.statusCode();
            if (REDIRECT_STATUSES.contains(statusCode)) {
                if (redirects >= maxRedirects) {
                    return result(rawUrl, normalizedUrl, currentUri.toString(),
                            LinkStatus.BROKEN, FailureReason.TOO_MANY_REDIRECTS,
                            statusCode, "Massa redireccions (> " + maxRedirects + ")");
                }

                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    return result(rawUrl, normalizedUrl, currentUri.toString(),
                            LinkStatus.BROKEN, FailureReason.CONNECTION_ERROR,
                            statusCode, "Redirecció sense capçalera Location");
                }

                UriValidation nextValidation = resolveAndValidateRedirect(currentUri, location);
                if (!nextValidation.isValid()) {
                    return result(rawUrl, normalizedUrl, currentUri.toString(),
                            LinkStatus.BROKEN, FailureReason.INVALID_SYNTAX,
                            statusCode, nextValidation.errorMessage());
                }

                currentUri = nextValidation.uri();
                redirects++;
                continue;
            }

            return classifyHttpResponse(rawUrl, normalizedUrl, currentUri.toString(), statusCode);
        }
    }

    private HttpResponse<Void> sendHeadWithGetFallback(URI uri) throws IOException, InterruptedException {
        HttpRequest headRequest = buildRequest(uri, "HEAD", false);
        HttpResponse<Void> headResponse = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
        if (HEAD_FALLBACK_STATUSES.contains(headResponse.statusCode())) {
            HttpRequest getRequest = buildRequest(uri, "GET", true);
            return httpClient.send(getRequest, HttpResponse.BodyHandlers.discarding());
        }
        return headResponse;
    }

    private HttpRequest buildRequest(URI uri, String method, boolean rangeFirstByte) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*");
        if (rangeFirstByte) {
            builder.header("Range", "bytes=0-0");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String apiKey = egretaHostApiKeys.get(host);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("api-key", apiKey);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private LinkCheckResult classifyHttpResponse(String rawUrl, String normalizedUrl, String finalUrl, int statusCode) {
        if (statusCode >= 200 && statusCode <= 299) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.OK, FailureReason.OK, statusCode, null);
        }
        if (statusCode == 401) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BLOCKED, FailureReason.HTTP_401, statusCode, "HTTP 401");
        }
        if (statusCode == 403) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BLOCKED, FailureReason.HTTP_403, statusCode, "HTTP 403");
        }
        if (statusCode == 429) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BLOCKED, FailureReason.HTTP_429, statusCode, "HTTP 429");
        }
        if (statusCode == 404) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BROKEN, FailureReason.HTTP_404, statusCode, "HTTP 404");
        }
        if (statusCode == 410) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BROKEN, FailureReason.HTTP_410, statusCode, "HTTP 410");
        }
        if (statusCode >= 400 && statusCode <= 499) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BROKEN, FailureReason.HTTP_4XX, statusCode, "HTTP " + statusCode);
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return result(rawUrl, normalizedUrl, finalUrl, LinkStatus.BROKEN, FailureReason.HTTP_5XX, statusCode, "HTTP " + statusCode);
        }
        return result(rawUrl, normalizedUrl, finalUrl,
                LinkStatus.BROKEN, FailureReason.CONNECTION_ERROR, statusCode, "HTTP " + statusCode);
    }

    FailureReason classifyException(Throwable ex) {
        if (hasCause(ex, HttpTimeoutException.class) || hasCause(ex, SocketTimeoutException.class)) {
            return FailureReason.TIMEOUT;
        }
        if (hasCause(ex, UnknownHostException.class) || hasCause(ex, UnresolvedAddressException.class)) {
            return FailureReason.DNS_ERROR;
        }
        if (hasCause(ex, SSLException.class)) {
            return FailureReason.SSL_ERROR;
        }
        return FailureReason.CONNECTION_ERROR;
    }

    private static boolean hasCause(Throwable root, Class<? extends Throwable> targetType) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = root;
        while (current != null && visited.add(current)) {
            if (targetType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static UriValidation validateRawUrl(String rawUrl) {
        if (rawUrl == null) {
            return UriValidation.invalid("URL nul·la");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            return UriValidation.invalid("URL buida");
        }
        if (containsWhitespaceOrControl(trimmed)) {
            return UriValidation.invalid("La URL conté espais o caràcters de control");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            return UriValidation.invalid("Sintaxi d'URI invàlida: " + ex.getReason());
        }
        return validateHttpUriComponents(uri);
    }

    private static UriValidation resolveAndValidateRedirect(URI currentUri, String rawLocation) {
        String trimmedLocation = rawLocation.trim();
        if (containsWhitespaceOrControl(trimmedLocation)) {
            return UriValidation.invalid("Capçalera Location amb espais o caràcters de control");
        }
        URI resolved;
        try {
            resolved = currentUri.resolve(trimmedLocation);
        } catch (IllegalArgumentException ex) {
            return UriValidation.invalid("Capçalera Location invàlida: " + ex.getMessage());
        }
        return validateHttpUriComponents(resolved);
    }

    private static UriValidation validateHttpUriComponents(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return UriValidation.invalid("Esquema no HTTP/HTTPS: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return UriValidation.invalid("La URL no conté un host vàlid");
        }
        if (uri.getPort() > 65535) {
            return UriValidation.invalid("Port fora de rang: " + uri.getPort());
        }
        return UriValidation.valid(uri);
    }

    private static boolean containsWhitespaceOrControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> buildEgretaHostApiKeys(
            String externalOrgBaseUrl,
            String externalOrgApiKey,
            String egreta54BaseUrl,
            String egreta54ApiKey
    ) {
        Map<String, String> keys = new LinkedHashMap<>();
        registerEgretaHost(keys, externalOrgBaseUrl, externalOrgApiKey);
        registerEgretaHost(keys, egreta54BaseUrl, egreta54ApiKey);
        return keys;
    }

    private static void registerEgretaHost(Map<String, String> keys, String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return;
        }
        UriValidation validation = validateRawUrl(baseUrl);
        if (validation.isValid()) {
            keys.put(validation.uri().getHost().toLowerCase(Locale.ROOT), apiKey.trim());
        }
    }

    private static Map<String, String> normalizeHostApiKeys(Map<String, String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawKeys.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                normalized.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue().trim());
            }
        }
        return Map.copyOf(normalized);
    }

    private static String formatExceptionMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            Throwable cause = ex.getCause();
            message = cause != null ? cause.getMessage() : null;
        }
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return ex.getClass().getSimpleName() + ": " + message;
    }

    private static LinkCheckResult result(
            String rawUrl,
            String normalizedUrl,
            String finalUrl,
            LinkStatus status,
            FailureReason reason,
            Integer httpStatus,
            String errorMessage
    ) {
        return new LinkCheckResult(rawUrl, normalizedUrl, finalUrl, status, reason, httpStatus, errorMessage, Instant.now());
    }

    private record UriValidation(URI uri, String errorMessage) {
        static UriValidation valid(URI uri) {
            return new UriValidation(uri, null);
        }

        static UriValidation invalid(String errorMessage) {
            return new UriValidation(null, errorMessage);
        }

        boolean isValid() {
            return uri != null;
        }
    }
}
