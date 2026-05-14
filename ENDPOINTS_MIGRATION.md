# Endpoint Migration Guide

Aquest document recull la migracio de rutes legacy a rutes canoniques.

## Estat final

- Ruta canonica unica: `/api/...`
- El frontend del repositori consumeix exclusivament rutes canoniques.
- Les rutes legacy estan desactivades per defecte (`app.api.legacy-endpoints-enabled=false`).
- Base URL per entorn:
	- Desenvolupament: `/api`
	- Produccio: `/otr/api`
	- Resolucio automatica al frontend segons context (`/otr/*` -> `/otr/api`, altrament `/api`)
	- Override opcional via `window.APP_CONFIG.apiBaseUrl` o meta `api-base-url`

## Estat actual de deprecacio

- Els endpoints legacy inclouen capcaleres HTTP de deprecacio en runtime:
	- `Deprecation: true`
	- `Sunset: Thu, 31 Dec 2026 23:59:59 GMT`
	- `Link: <...>; rel="successor-version"`
	- `Warning: 299 - "Deprecated endpoint, use successor URI"`

- Implementacio: `LegacyEndpointDeprecationFilter`.

- Interruptor de tall (feature flag):
	- Propietat: `app.api.legacy-endpoints-enabled`
	- Valor per defecte: `true`
	- Si es posa a `false`: les rutes legacy retornen `HTTP 410 Gone` i indiquen la ruta successora.

## Rutes canoniques en ús

### Awards

- Canonica: `/api/awards/reports/word/country`
- Canonica: `/api/awards/debug/tesis`

### Persons

- Canonica: `/api/persons/reports/word`
- Canonica: `/api/persons/reports/word/person`
- Canonica: `/api/persons/search/active`

### Pure (Publicacions)

- Canonica: `/api/pure/search`
- Canonica: `/api/pure/stats/years`
- Canonica: `/api/pure/stats/types`
- Canonica: `/api/pure/stats/types-by-year`
- Canonica: `/api/pure/stats/apa`

### Student Theses

- Canonica: `/api/student-theses/stats/same-author-director`
- Canonica: `/api/student-theses/stats/per-year-institute`
- Canonica: `/api/student-theses/stats/list-institute`

## Operacio

1. Si cal reobrir compatibilitat temporal, configura `app.api.legacy-endpoints-enabled=true`.
2. Amb el valor per defecte (`false`), qualsevol ruta legacy retorna `410 Gone`.
