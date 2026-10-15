const appConfigFromWindow = window.APP_CONFIG || {};
const metaApiBase = document.querySelector('meta[name="api-base-url"]')?.content?.trim();

function detectDefaultApiBaseUrl() {
    const path = window.location.pathname || '';
    const isOtrContext = path === '/otr' || path.startsWith('/otr/');

    if (isOtrContext) {
        return '/otr/api';
    }

    return '/api';
}

function getDefaultEnv() {
    return 'prod';
}

function initEnvironmentSelectors() {
    const defaultEnv = getDefaultEnv();
    const selectors = document.querySelectorAll('#selectEntorn, #autoApplyEnv, #autoValidateEnv, select[name="entorn"], select[name="env"]');
    selectors.forEach(sel => {
        if (sel) {
            sel.value = defaultEnv;
        }
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initEnvironmentSelectors);
} else {
    initEnvironmentSelectors();
}

const resolvedApiBase =
    appConfigFromWindow.apiBaseUrl ||
    metaApiBase ||
    detectDefaultApiBaseUrl();

const configuredCandidates = Array.isArray(appConfigFromWindow.apiBaseCandidates)
    ? appConfigFromWindow.apiBaseCandidates
    : [];

window.APP_CONFIG = {
    ...appConfigFromWindow,
    apiBaseUrl: resolvedApiBase,
    apiBaseCandidates: [
        resolvedApiBase,
        '/api',
        '/otr/api',
        ...configuredCandidates
    ].filter(Boolean)
};

window.getDefaultEnv = getDefaultEnv;

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
                window.APP_CONFIG.apiBaseUrl = base;
                return response;
            }

            lastResponse = response;
            const contentType = response.headers.get('content-type') || '';
            if (response.status !== 404 || contentType.includes('application/json')) {
                return response;
            }
        } catch (error) {
            if (error.name === 'AbortError') {
                throw error;
            }
            lastError = error;
        }
    }

    if (lastResponse) {
        return lastResponse;
    }

    throw lastError || new Error('No se pudo conectar con la API.');
};
