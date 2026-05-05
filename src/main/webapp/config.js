window.APP_CONFIG = window.APP_CONFIG || {
    apiBaseUrl: '/otr/api',
    apiBaseCandidates: [
        '/otr/api',
        '/api'
    ],
    apiKeyHeader: 'X-API-KEY',
    apiKey: 'sk_uab_2026_9fA7kLm2Qx8pV3tR6nY1cD4eH0jK5mZ'
};

window.apiUrl = window.apiUrl || function(path, baseOverride) {
    const baseFromConfig = baseOverride || window.APP_CONFIG.apiBaseUrl || '/api';
    if (!path) return baseFromConfig;
    if (/^https?:\/\//i.test(path)) return path;

    const base = String(baseFromConfig).replace(/\/$/, '');
    const relative = String(path).startsWith('/') ? path : `/${path}`;
    return `${base}${relative}`;
};

window.apiFetch = window.apiFetch || async function(path, options) {
    const configured = window.APP_CONFIG.apiBaseCandidates || [window.APP_CONFIG.apiBaseUrl || '/api'];
    const bases = Array.from(new Set(configured.filter(Boolean)));

    let lastError = null;
    let lastResponse = null;

    for (const base of bases) {
        const url = window.apiUrl(path, base);
        try {
            const response = await fetch(url, options);
            if (response.ok) {
                return response;
            }

            lastResponse = response;
            if (response.status !== 404) {
                return response;
            }
        } catch (error) {
            lastError = error;
        }
    }

    if (lastResponse) {
        return lastResponse;
    }

    throw lastError || new Error('No se pudo conectar con la API.');
};

(function setupApiKeyFetchInterceptor() {
    if (window.__apiKeyFetchInterceptorInstalled) {
        return;
    }

    const protectedPrefixes = [
        '/api/',
        '/otr/api/',
        '/applications/',
        '/awards/',
        '/external-organizations/',
        '/funding-opportunities/',
        '/journals/',
        '/pure/',
        '/persons/',
        '/student-theses/'
    ];

    const originalFetch = window.fetch.bind(window);

    function isProtectedApiRequest(requestUrl) {
        const normalizedPath = requestUrl.pathname.endsWith('/')
            ? requestUrl.pathname
            : requestUrl.pathname + '/';

        return protectedPrefixes.some(prefix => normalizedPath.startsWith(prefix));
    }

    function withApiKeyHeader(input, init) {
        const apiKey = window.APP_CONFIG && window.APP_CONFIG.apiKey;
        const apiKeyHeader = (window.APP_CONFIG && window.APP_CONFIG.apiKeyHeader) || 'X-API-KEY';

        if (!apiKey) {
            return { input, init };
        }

        const requestUrl = new URL(
            typeof input === 'string' ? input : input.url,
            window.location.origin
        );

        if (requestUrl.origin !== window.location.origin || !isProtectedApiRequest(requestUrl)) {
            return { input, init };
        }

        const headers = new Headers(
            init && init.headers
                ? init.headers
                : (typeof input === 'object' && input.headers ? input.headers : undefined)
        );

        if (!headers.has(apiKeyHeader)) {
            headers.set(apiKeyHeader, apiKey);
        }

        if (input instanceof Request) {
            return {
                input: new Request(input, {
                    ...init,
                    headers
                }),
                init: undefined
            };
        }

        return {
            input,
            init: {
                ...(init || {}),
                headers
            }
        };
    }

    window.fetch = function(input, init) {
        const request = withApiKeyHeader(input, init);
        return originalFetch(request.input, request.init);
    };

    window.__apiKeyFetchInterceptorInstalled = true;
})();
