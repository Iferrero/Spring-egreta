window.APP_CONFIG = window.APP_CONFIG || {
    apiBaseUrl: '/otr/api',
    apiBaseCandidates: [
        '/otr/api',
        '/api'
    ]
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
