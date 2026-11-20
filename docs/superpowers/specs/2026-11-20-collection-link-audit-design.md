# Diseño: Auditoría HTTP de Enlaces Rotos por Colección (`documents-coleccion.html`)

**Fecha:** 2026-11-20  
**Estado:** Pendiente de revisión de la especificación  
**Idioma de la interfaz:** Català (`ca`)  

---

## 1. Objetivo y Alcance

Ampliar el panel [documents-coleccion.html](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/src/main/webapp/documents-coleccion.html) y su backend Spring Boot + MongoDB (`kraken`) para detectar, cuantificar y consultar **enlaces rotos verificados por HTTP** en todas las colecciones de la base de datos.

### Enfoque arquitectónico aprobado (Enfoque A)
Se adopta una arquitectura de **dos colecciones ligeras en MongoDB (`CollectionLinkAuditSummary` y `CollectionLinkAuditIssues`) con deduplicación en memoria por escaneo e intercambio atómico lógico por `scanId`**. No se persisten los resultados `200 OK` en MongoDB; únicamente se almacenan el resumen agregado por colección y los registros con incidencias (`BROKEN` o `BLOCKED`), evitando así acercarse al límite BSON de 16 MB y manteniendo consultas, filtros y exportaciones rápidas.

### Alcance de datos
- **Colecciones analizadas:** Todas las colecciones de negocio de MongoDB `kraken`, excluyendo las colecciones internas de auditoría (`CollectionLinkAuditSummary`, `CollectionLinkAuditIssues`) y colecciones del sistema (`system.*`).
- **Campos inspeccionados:**
  - `documents` y `links` en **todas** las colecciones que contengan alguno de estos arrays no vacíos.
  - `electronicVersions` adicionalmente en la colección `Researchoutputs` (únicamente URLs explícitas en `link`, `file.url` y `file.fileURL`; no se construyen ni resuelven URLs a partir de identificadores `doi` sueltos).

### Criterio de clasificación de enlaces
1. **Rotos confirmados (`BROKEN`):**
   - Códigos HTTP `404`, `410`, `5xx`, y cualquier otro `4xx` distinto de `401`, `403` y `429`.
   - Respuesta `3xx` sin cabecera `Location` válida o superación del límite máximo de redirecciones (`5`).
   - Errores de red y protocolo: error de resolución DNS, timeout de conexión o lectura, error SSL/TLS, rechazo/corte de conexión.
   - Sintaxis o esquema de URL inválido (cadena con espacios/caracteres de control, URI mal formada, esquema distinto de `http`/`https` o sin host).
2. **Bloqueados / dudosos (`BLOCKED`):**
   - Códigos HTTP `401`, `403` y `429` (frecuentes por medidas antibot o paywalls en editoriales científicas).
3. **Válidos (`OK`):**
   - Respuesta final `2xx` tras seguir redirecciones válidas.
4. **URLs internas de Egreta:**
   - Las URLs cuyo host coincide exactamente con los servidores Egreta configurados (`egreta.uab.cat`, `egretat.uab.cat`) se verifican enviando la cabecera `api-key` correspondiente. Esta cabecera nunca se envía a hosts externos ni tras una redirección que salga del host Egreta.

### Acciones soportadas en la interfaz
Solo diagnóstico y consulta:
- Visualización de KPIs globales y estado por colección en la tabla principal.
- Ejecución asíncrona en segundo plano (global o por colección individual) con seguimiento de progreso en vivo y cancelación limpia.
- Modal de detalle paginado y filtrable con listado de enlaces rotos/bloqueados (registro afectado, campo origen, URL, código HTTP/motivo), enlace directo al registro en Egreta (`https://egreta.uab.cat/admin/workspace.xhtml?uuid=<uuid>`) y exportación CSV completa.

### Fuera de alcance (YAGNI)
- Caché persistente de URLs `200 OK` en MongoDB entre ejecuciones independientes.
- Resolución de DOIs sueltos vía `https://doi.org/<doi>`.
- Edición, corrección o borrado de enlaces directamente sobre la API de Egreta.
- Marcado de incidencias como «revisado/ignorado» o flujo de notas manuales.
- Tareas programadas automáticas (`@Scheduled` / cron).
- Ejecución simultánea de múltiples trabajos de auditoría.

---

## 2. Arquitectura Backend (`com.example.demo.linkaudit`)

No se añaden nuevas dependencias en `pom.xml`. Se utilizan `java.net.http.HttpClient`, Virtual Threads de Java 21, `MongoTemplate` (ya presente) y `com.sun.net.httpserver.HttpServer` (incluido en el JDK) para las pruebas HTTP embebidas.

### 2.1 Propiedades de configuración (`application.properties`)
Valores configurables con valores por defecto seguros:
- `app.link-audit.global-concurrency=24`
- `app.link-audit.per-host-concurrency=3`
- `app.link-audit.connect-timeout-ms=5000`
- `app.link-audit.request-timeout-ms=10000`
- `app.link-audit.max-redirects=5`
- `app.link-audit.insert-batch-size=500`
- `app.link-audit.user-agent=Mozilla/5.0 (compatible; UAB-Egreta-LinkAudit/1.0)`

**Mapa de hosts internos de Egreta (`egretaHostApiKeys`):**
Al inicializar `HttpLinkVerifier`, se extraen los hosts (`URI.create(url).getHost().toLowerCase(Locale.ROOT)`) y sus respectivas claves de las propiedades ya existentes en `application.properties`:
- `app.egreta.external-org.base-url` + `app.egreta.external-org.api-key` (`egreta.uab.cat`)
- `egreta54.base.url` + `egreta54.api.key` (`egretat.uab.cat`)

Si una propiedad está vacía o no tiene URL válida, ese host simplemente no se registra en el mapa.

---

### 2.2 Modelo de Datos y DTOs (`com.example.demo.linkaudit.model`)

#### Enums inmutables
- `LinkStatus`: `OK`, `BROKEN`, `BLOCKED`
- `FailureReason`:
  - `OK`
  - `HTTP_404`, `HTTP_410`, `HTTP_4XX`, `HTTP_5XX`
  - `HTTP_401`, `HTTP_403`, `HTTP_429`
  - `INVALID_SYNTAX`, `DNS_ERROR`, `TIMEOUT`, `SSL_ERROR`, `CONNECTION_ERROR`, `TOO_MANY_REDIRECTS`
- `AuditJobStatus`: `IDLE`, `RUNNING`, `CANCELLING`, `COMPLETED`, `CANCELLED`, `FAILED`
- `LinkSourceField`:
  - `DOCUMENTS("documents")`
  - `LINKS("links")`
  - `ELECTRONIC_VERSIONS("electronicVersions")`

#### Records / DTOs
- `ExtractedLinkRef(String collection, String recordId, String recordUuid, Long recordPureId, String recordTitle, String recordType, String sourceField, String subType, String linkLabel, String rawUrl)`
- `LinkCheckResult(String rawUrl, String normalizedUrl, String finalUrl, LinkStatus status, FailureReason reason, Integer httpStatus, String errorMessage, Instant checkedAt)`
- `FieldAuditCounts(long totalLinks, long okCount, long brokenCount, long blockedCount)`
- `CollectionAuditSummary(String collection, String scanId, Instant scannedAt, long durationMs, long recordsWithLinks, long totalLinks, long uniqueUrls, long okCount, long brokenCount, long blockedCount, Map<String, FieldAuditCounts> byField, String lastError)`
- `LinkAuditIssue(String collection, String scanId, LinkStatus status, FailureReason reason, Integer httpStatus, String errorMessage, String url, String finalUrl, String sourceField, String subType, String linkLabel, String recordId, String recordUuid, Long recordPureId, String recordTitle, String recordType, String egretaUrl, Instant checkedAt)`
- `AuditJobState(String jobId, AuditJobStatus status, String scope, Instant startedAt, Instant finishedAt, String currentCollection, int collectionsTotal, int collectionsCompleted, long currentCollectionUniqueUrls, long currentCollectionCheckedUrls, long totalUrlsChecked, long brokenFound, long blockedFound, String lastError)`
- `AuditSummaryResponse(Instant generatedAt, long collectionsAudited, long totalLinksChecked, long totalBroken, long totalBlocked, Instant lastAuditedAt, AuditJobState job, List<CollectionAuditSummary> collections)`
- `LinkIssuesPageResponse(String collection, String scanId, Instant scannedAt, int page, int size, long totalItems, int totalPages, Map<String, Long> countsByStatus, Map<String, Long> countsByField, Map<String, Long> countsByReason, List<LinkAuditIssue> items)`
- `ScanRequest(String collection)`

---

### 2.3 Extracción por Streaming (`CollectionLinkExtractor`)

Extrae referencias de enlaces de cualquier colección de `kraken` sin cargar documentos completos en memoria.

1. **Filtro de consulta en MongoDB:**
   - Condición `$or` sobre:
     - `{ "documents": { "$exists": true, "$ne": [] } }`
     - `{ "links": { "$exists": true, "$ne": [] } }`
     - Solo si `"Researchoutputs".equalsIgnoreCase(collection)`: `{ "electronicVersions": { "$exists": true, "$ne": [] } }`
2. **Proyección mínima y cursor:**
   - Campos proyectados: `_id`, `uuid`, `pureId`, `title`, `name`, `type`, `typeDiscriminator`, `documents`, `links` (y `electronicVersions` si aplica en `Researchoutputs`).
   - Iteración mediante `FindIterable` con `batchSize(500)` y `noCursorTimeout(false)`.
3. **Reglas de extracción por campo origen:**
   Los strings nulos o compuestos únicamente por espacios en blanco se ignoran. Los strings no vacíos se conservan tal cual en `rawUrl` (haciendo únicamente `trim()` en los extremos al validar sintaxis) para poder detectar errores de formato reales (espacios internos, falta de esquema, etc.).
   - **`documents[]`** (`sourceField = "documents"`):
     - Por cada elemento `Document` del array `documents`, inspeccionar en orden los campos de URL: `url`, `fileURL`, `fileUrl`, `link`, `file.url`, `file.fileURL`.
     - Dentro de un mismo elemento de `documents[]`, si varios campos contienen exactamente la misma cadena URL (por ejemplo `file.url` y `file.fileURL`), se emite una sola referencia por URL distinta dentro de ese elemento; si contiene varias URLs distintas, se emiten todas.
     - `subType`: `type.term.ca_ES` → `type.term.es_ES` → `type.term.en_GB` → `"Sense especificar"`.
     - `linkLabel`: `title` (si es String no vacío, o mapa localizado `{ca_ES,es_ES,en_GB}`) → `fileName` → `file.fileName` → `""`.
   - **`links[]`** (`sourceField = "links"`):
     - Por cada elemento `Document` del array `links`, extraer `url`.
     - `subType`: `linkType.term.ca_ES` → `linkType.term.es_ES` → `linkType.term.en_GB` → `"Sense especificar"`.
     - `linkLabel`: `alias` → `description.ca_ES` → `description.es_ES` → `description.en_GB` → `""`.
   - **`electronicVersions[]`** (solo cuando la colección es `Researchoutputs`, `sourceField = "electronicVersions"`):
     - Por cada elemento `Document` del array `electronicVersions`, inspeccionar en orden: `link`, `file.url`, `file.fileURL`. Deduplicar URLs idénticas dentro del mismo elemento `electronicVersions`. **No** generar URLs desde `doi`.
     - `subType`: `versionType.term.ca_ES` → `versionType.term.es_ES` → `versionType.term.en_GB` → `typeDiscriminator` → `"Sense especificar"`.
     - `linkLabel`: `file.fileName` → `title` → `""`.
4. **Resolución de metadatos del registro padre:**
   - `recordId`: `Objects.toString(doc.get("_id"), "")`.
   - `recordUuid`: `doc.getString("uuid")` (si nulo o vacío, `null`).
   - `recordPureId`: valor numérico convertido a `Long` si `pureId instanceof Number`, sino `null`.
   - `recordTitle`: primer valor no vacío en orden:
     1. `title.value`
     2. `title.ca_ES` → `title.es_ES` → `title.en_GB`
     3. `title` (cuando es `String`)
     4. `name.ca_ES` → `name.es_ES` → `name.en_GB`
     5. `name.firstName` + `" "` + `name.lastName` (para colecciones tipo `Persons`)
     6. `name` (cuando es `String`)
     7. `recordUuid` → `recordId`
   - `recordType`: `type.term.ca_ES` → `type.term.es_ES` → `type.term.en_GB` → `typeDiscriminator` → `"Sense especificar"`.
   - `egretaUrl`: si `recordUuid` no es nulo ni vacío, `"https://egreta.uab.cat/admin/workspace.xhtml?uuid=" + URLEncoder.encode(recordUuid, UTF_8)`; en caso contrario `null`.

---

### 2.4 Verificador HTTP (`HttpLinkVerifier`)

1. **Validación sintáctica previa (sin tráfico de red):**
   - `trimmed = rawUrl.trim()`.
   - Si `trimmed.isEmpty()`, contiene espacios o caracteres de control (`\r`, `\n`, `\t`), falla `new URI(trimmed)`, el esquema (case-insensitive) no es `http` ni `https`, o `uri.getHost() == null || uri.getHost().isBlank()`: devuelve inmediatamente `LinkStatus.BROKEN` con `FailureReason.INVALID_SYNTAX` y mensaje descriptivo.
2. **Seguimiento manual de redirecciones y política de `api-key`:**
   - El `java.net.http.HttpClient` se configura con `followRedirects(HttpClient.Redirect.NEVER)` y `connectTimeout`.
   - Cada petición lleva `User-Agent` configurado y `Accept: */*`, con `requestTimeout` por salto.
   - Antes de construir cada petición (tanto la inicial como cada salto de redirección):
     - Se comprueba si `currentUri.getHost().toLowerCase(Locale.ROOT)` está en `egretaHostApiKeys`.
     - **Solo si coincide exactamente**, se añade la cabecera `api-key: <clave>`.
     - Si la respuesta es `301`, `302`, `303`, `307` o `308`:
       - Se lee la cabecera `Location`. Si falta o está vacía, se clasifica como `BROKEN` / `CONNECTION_ERROR` (mensaje `"Redirecció sense capçalera Location"`).
       - Si se alcanza el límite `maxRedirects` (`5`), devuelve `BROKEN` / `TOO_MANY_REDIRECTS`.
       - Se resuelve el nuevo destino con `currentUri.resolve(location)`. Si la URI resultante falla la validación `http`/`https` con host válido, devuelve `BROKEN` / `INVALID_SYNTAX`.
       - Se valida la política de `api-key` de nuevo sobre el host destino: si redirige desde Egreta hacia un dominio externo, el nuevo `HttpRequest` no incluye `api-key`.
3. **Estrategia `HEAD` con fallback a `GET` ligero:**
   - El primer intento sobre la URL usa método `HEAD` (`BodyHandlers.discarding()`).
   - Si el servidor responde `405` (Method Not Allowed), `501` (Not Implemented) o `400` (Bad Request) al `HEAD`, se reintenta la URL actual una sola vez con `GET`, cabecera `Range: bytes=0-0` y `BodyHandlers.discarding()` (sin descargar el cuerpo completo en memoria).
   - Nota: las redirecciones `3xx` se siguen primero; si tras seguir una redirección el destino final rechaza `HEAD` con `405`/`501`/`400`, también aplica el fallback a `GET` ligero sobre ese destino final.
4. **Tabla de clasificación exacta:**

| Respuesta / Excepción | `LinkStatus` | `FailureReason` |
|---|---|---|
| `200`–`299` | `OK` | `OK` |
| `401` | `BLOCKED` | `HTTP_401` |
| `403` | `BLOCKED` | `HTTP_403` |
| `429` | `BLOCKED` | `HTTP_429` |
| `404` | `BROKEN` | `HTTP_404` |
| `410` | `BROKEN` | `HTTP_410` |
| Otros `400`–`499` | `BROKEN` | `HTTP_4XX` |
| `500`–`599` | `BROKEN` | `HTTP_5XX` |
| `3xx` sin cabecera `Location` | `BROKEN` | `CONNECTION_ERROR` |
| Más de `maxRedirects` saltos `3xx` | `BROKEN` | `TOO_MANY_REDIRECTS` |
| URL o `Location` inválida / esquema no HTTP(S) | `BROKEN` | `INVALID_SYNTAX` |
| `UnknownHostException` | `BROKEN` | `DNS_ERROR` |
| `HttpTimeoutException`, `SocketTimeoutException` | `BROKEN` | `TIMEOUT` |
| `SSLHandshakeException`, `SSLException` | `BROKEN` | `SSL_ERROR` |
| Otras `IOException` (`ConnectException`, EOF, etc.) | `BROKEN` | `CONNECTION_ERROR` |

---

### 2.5 Persistencia en MongoDB (`LinkAuditRepository`)

Se utilizan dos colecciones dedicadas en `kraken` (constantes compartidas en un sitio único para evitar duplicación con `CollectionDocumentStatsService`):
- **`CollectionLinkAuditSummary`**:
  - `_id`: nombre de la colección auditada (p. ej. `"Awards"`, `"Researchoutputs"`).
  - Campos: `collection`, `scanId` (UUID), `scannedAt`, `durationMs`, `recordsWithLinks`, `totalLinks`, `uniqueUrls`, `okCount`, `brokenCount`, `blockedCount`, `byField` (mapa con claves `documents`, `links`, `electronicVersions`), `lastError` (`null` si finalizó bien).
- **`CollectionLinkAuditIssues`**:
  - 1 documento por cada incidencia (`BROKEN` o `BLOCKED`) detectada en la colección.
  - Campos: `collection`, `scanId`, `status`, `reason`, `httpStatus`, `errorMessage`, `url`, `finalUrl`, `sourceField`, `subType`, `linkLabel`, `recordId`, `recordUuid`, `recordPureId`, `recordTitle`, `recordType`, `egretaUrl`, `checkedAt`.
  - Índices creados de forma idempotente al arrancar (`@PostConstruct` / `ensureIndexes()`):
    - `idx_audit_issues_col_scan_status_field`: `(collection: 1, scanId: 1, status: 1, sourceField: 1)`
    - `idx_audit_issues_col_scan_reason`: `(collection: 1, scanId: 1, reason: 1)`
    - `idx_audit_issues_col_scan_http`: `(collection: 1, scanId: 1, httpStatus: 1)`

#### Intercambio atómico lógico por `scanId`
Al completar el escaneo de una colección con un `newScanId` recién generado:
1. Se insertan en `CollectionLinkAuditIssues` únicamente las incidencias (`BROKEN` y `BLOCKED`) en lotes de `insertBatchSize` (`500`).
2. Se ejecuta `replaceOne({ "_id": collection }, summaryDoc, upsert=true)` en `CollectionLinkAuditSummary` fijando `scanId = newScanId`. A partir de este instante las consultas leen exclusivamente el nuevo escaneo.
3. Se ejecuta `deleteMany({ "collection": collection, "scanId": { "$ne": newScanId } })` en `CollectionLinkAuditIssues` para purgar incidencias obsoletas.

Si el escaneo de la colección se **cancela** o **falla** antes del paso 2:
- Se ejecuta una limpieza best-effort `deleteMany({ "collection": collection, "scanId": newScanId })`.
- El resumen previo y sus incidencias en `CollectionLinkAuditSummary` / `CollectionLinkAuditIssues` permanecen intactos sin pérdida ni mezcla de datos.

---

### 2.6 Orquestación de Escaneos (`LinkAuditService`)

- **Un único trabajo activo a la vez:**
  - Controlado con `AtomicReference<ActiveJob>` / sincronización al iniciar.
  - Si el job actual está en `RUNNING` o `CANCELLING`, `startScan(...)` lanza `ScanAlreadyRunningException` (mapeada a `409 Conflict`).
- **Selección y ordenación de colecciones:**
  - En escaneo puntual (`collection != null`): valida que la colección exista en `kraken` y no sea interna (`CollectionLinkAuditSummary`, `CollectionLinkAuditIssues`, `system.*`); si no es válida lanza `IllegalArgumentException` (mapeada a `400 Bad Request`).
  - En escaneo global (`collection == null`): lista colecciones de `kraken` excluyendo internas, las ordena por `estimatedDocumentCount()` ascendente y mueve `"Researchoutputs"` explícitamente al final de la lista para que todas las colecciones pequeñas y medianas muestren resultados en los primeros instantes.
- **Deduplicación en memoria y concurrencia controlada:**
  - Durante un escaneo (global o individual), se mantiene un mapa compartido `ConcurrentHashMap<String, CompletableFuture<LinkCheckResult>>` indexado por URL (`rawUrl.trim()`), de modo que si una misma URL aparece N veces dentro de una colección o en múltiples colecciones dentro del mismo escaneo global, se ejecuta una única comprobación HTTP.
  - Se utiliza un `ExecutorService` basado en Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`), un `Semaphore(globalConcurrency)` (24 por defecto) y un `ConcurrentHashMap<String, Semaphore>` por host (`perHostConcurrency = 3`) para evitar saturar `egreta.uab.cat`, `ddd.uab.cat` ni provocar bloqueos por rate-limit.
  - Si la URL es sintácticamente inválida antes de extraer host, no consume permisos de red ni semáforos.
- **Aislamiento de errores y cancelación:**
  - En un escaneo global, cada colección completada queda persistida inmediatamente; si una colección falla por un error inesperado de lectura/red, se registra el error y el job continúa con la siguiente colección.
  - Cuando se llama a `cancelScan()`, el estado pasa a `CANCELLING`, se interrumpe la espera de futuros pendientes de la colección actual, se limpia su `newScanId` parcial, se preserva todo lo ya completado y el estado final pasa a `CANCELLED`.
  - En `@PreDestroy` se solicita cancelación y se cierra ordenadamente el executor.

### 2.7 Ajuste en `CollectionDocumentStatsService.java`
Modificar [CollectionDocumentStatsService.java](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/src/main/java/com/example/demo/service/CollectionDocumentStatsService.java) para filtrar y omitir del listado de `listCollectionNames()` las colecciones internas de auditoría (`CollectionLinkAuditSummary`, `CollectionLinkAuditIssues`) y colecciones `system.*`, referenciando las constantes compartidas del módulo `linkaudit`.

---

## 3. API REST (`CollectionLinkAuditController`)

Controlador anotado con `@RestController` y `@RequestMapping("/api/mongo/collections/link-audit")`. Hereda exactamente la misma configuración de seguridad de Spring Security ya aplicada a `/api/mongo/**` (CAS en producción, `ROLE_USER` en desarrollo, o `api-key`).

### Endpoints

1. **`GET /api/mongo/collections/link-audit/summary`**
   - **Respuesta `200 OK` (`AuditSummaryResponse`):** devuelve los KPIs globales calculados sobre todos los resúmenes persistidos (`collectionsAudited`, `totalLinksChecked`, `totalBroken`, `totalBlocked`, `lastAuditedAt`), el estado actual `job` (`AuditJobState`) y la lista `collections` de `CollectionAuditSummary`.
   - Carga de forma instantánea desde `CollectionLinkAuditSummary` al abrir la página.

2. **`POST /api/mongo/collections/link-audit/scan`**
   - **Body opcional (`ScanRequest`):** `{}` o vacío para escaneo global; `{ "collection": "Awards" }` para una colección concreta.
   - **Respuestas:**
     - `202 Accepted` con `AuditJobState` inicial si se lanza el trabajo.
     - `400 Bad Request` si se especifica una colección inexistente o interna.
     - `409 Conflict` si ya hay un escaneo en curso (`RUNNING` o `CANCELLING`).

3. **`GET /api/mongo/collections/link-audit/status`**
   - **Respuesta `200 OK` (`AuditJobState`):** estado en tiempo real del trabajo en curso o del último trabajo finalizado (`IDLE` si no se ha ejecutado ninguno desde el arranque).

4. **`POST /api/mongo/collections/link-audit/cancel`**
   - **Respuesta `200 OK` (`AuditJobState`):** solicita parada limpia si hay un trabajo activo; si no hay trabajo activo (`IDLE`/`COMPLETED`/`CANCELLED`/`FAILED`), devuelve el estado actual sin error.

5. **`GET /api/mongo/collections/link-audit/{collection}/issues`**
   - **Query params:**
     - `status`: `BROKEN` (por defecto), `BLOCKED` o `ALL`.
     - `field`: opcional (`documents`, `links`, `electronicVersions` o `ALL`).
     - `reason`: opcional (valor de `FailureReason`, p. ej. `HTTP_404`, `TIMEOUT`, o `ALL`).
     - `q`: opcional; filtro case-insensitive (regex escapada de forma segura) sobre `url`, `recordTitle` o `recordUuid`.
     - `page`: entero base 0 (por defecto `0`).
     - `size`: entero entre `1` y `200` (por defecto `50`).
   - **Lógica:** valida nombre de colección (`400` si vacío o colección interna). Consulta el `scanId` activo en `CollectionLinkAuditSummary`; si la colección no ha sido auditada aún, devuelve `200 OK` con lista `items` vacía y totales en `0`. Si existe `scanId`, ejecuta la consulta filtrada y ordenada (primero `BROKEN`, luego `sourceField`, `recordTitle`), junto con los contadores por faceta (`countsByStatus`, `countsByField`, `countsByReason`) del `scanId` activo.

6. **`GET /api/mongo/collections/link-audit/{collection}/issues/export`**
   - **Query params:** mismos filtros `status` (por defecto `ALL`; desde la UI se pasa el filtro activo), `field`, `reason`, `q`.
   - **Validación y respuestas:** devuelve `400 Bad Request` si el nombre de colección es vacío o interno. Si la colección aún no tiene auditoría persistida, devuelve `200 OK` con un CSV que contiene únicamente la fila de cabeceras (con BOM UTF-8).
   - **Respuesta `200 OK` (`text/csv; charset=UTF-8`):**
     - `Content-Disposition: attachment; filename="enllacos_incorrectes_<collection>_<YYYY-MM-DD>.csv"`
     - Escrito en streaming directamente sobre el `OutputStream` / `Writer` HTTP con BOM UTF-8 (`\uFEFF`) usando cursor de MongoDB sobre el `scanId` activo.
     - **Columnas del CSV:** `Coleccio`, `Estat`, `Motiu`, `Codi_HTTP`, `Camp`, `Subtipus`, `Etiqueta_Enllac`, `URL`, `URL_Final`, `Error`, `ID_Registre`, `UUID_Registre`, `PureID_Registre`, `Titol_Registre`, `Tipus_Registre`, `Enllac_Egreta`, `Data_Comprovacio`.
     - Escapado RFC 4180 (comillas dobles escapadas como `""`) y mitigación de *CSV Formula Injection* en campos de texto libre (`Titol_Registre`, `Etiqueta_Enllac`, `Error`) que comiencen por `=`, `+`, `-`, `@`, `\t` o `\r` (anteponiendo `'`), preservando intactas las URLs `http://` / `https://`.

---

## 4. Interfaz de Usuario (`src/main/webapp/documents-coleccion.html`)

Todos los elementos nuevos mantienen la estética Tailwind CSS + FontAwesome 6 de [documents-coleccion.html](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/src/main/webapp/documents-coleccion.html), utilizan `window.apiFetch(...)` y están redactados íntegramente en **catalán**.

### 4.1 Barra superior y KPIs
- **Barra de botones superior:** añadir botón **«Comprovar enllaços (tot)»** junto a *Recalcular dades* y *Exportar CSV* (deshabilitado mientras haya un escaneo activo).
- **Banner de progreso en vivo** (oculto cuando `job.status` no es `RUNNING` ni `CANCELLING`):
  - Muestra estado actual, nombre de la col·lecció en procés, barra de progrés de col·leccions (`collectionsCompleted / collectionsTotal`), barra/comptador d'URLs úniques de la col·lecció actual (`currentCollectionCheckedUrls / currentCollectionUniqueUrls`), comptadors acumulats de **Rots** i **Bloquejats**, i botó **«Cancel·lar»**.
  - Hace polling a `GET /mongo/collections/link-audit/status` cada **2 segundos**. Cada vez que aumenta `collectionsCompleted` o el estado pasa a `COMPLETED`/`CANCELLED`/`FAILED`, recarga `GET /mongo/collections/link-audit/summary` para refrescar la tabla y KPIs en vivo sin esperar al final de `Researchoutputs`.
- **Fila de KPIs (5 tarjetas):** ampliar el grid (`lg:grid-cols-5`) añadiendo la tarjeta **«Enllaços Rots»**:
  - Valor principal en rojo (`totalBroken` formateado).
  - Subtítol amb `totalBlocked` bloquejats/dubtosos i `totalLinksChecked` enllaços comprovats (o *"Encara no auditat"* si `collectionsAudited == 0`).

### 4.2 Tabla principal y filtros
- **Filtro «Només amb contingut»:** ajustado a:
  `item.recordsWithDocuments > 0 || (audit && audit.totalLinks > 0)`
  para que colecciones que tienen enlaces en `links` aunque tengan `0` elementos en `documents` (como `StudentTheses` o `ExternalOrganizations` tras ser auditadas) sean visibles con el filtro por defecto.
- **Nuevo checkbox rápido:** **«Només amb enllaços rots»** junto a «Només amb contingut».
- **Nueva columna ordenable «Enllaços Rots»:**
  - Si la colección no tiene resumen de auditoría: muestra `"Sense auditar"` + botón de acción compacto (icono lupa/play) para auditar esa colección.
  - Si está auditada con `0` rotos y `0` bloqueados: badge verde `"0 rots"` (con tooltip de total comprobados y fecha) + botón de re-escaneo individual.
  - Si tiene rotos o bloqueados: badges clicables (`X rots` en rojo y, si `> 0`, `Y bloq.` en ámbar) que abren `#linkIssuesModal` + botón de re-escaneo individual.
- **Exportación CSV general (`exportToCsv()`):** añadir las columnas `Enllacos_Comprovats`, `Enllacos_Rots`, `Enllacos_Bloquejats` y `Data_Auditoria`.

### 4.3 Modal dedicado de incidencias (`#linkIssuesModal`)
Al hacer clic sobre el badge de rotos/bloqueados de una colección:
- **Cabecera:** nombre de la colección, fecha de última auditoría, resumen (`X rots · Y bloquejats · Z comprovats`), botón **«Re-escanejar col·lecció»** y botón **«Exportar CSV»** (descarga completa vía `window.apiUrl('/mongo/collections/link-audit/' + encodeURIComponent(collection) + '/issues/export?...')` aplicando los filtros activos).
- **Barra de filtros:**
  - Pestañas de estado con contador: **Rots confirmats** (activa por defecto), **Bloquejats / dubtosos** y **Tots**.
  - Selector de **Camp**: *Tots els camps*, `documents`, `links`, `electronicVersions` (con sus contadores).
  - Selector de **Motiu**: generado dinámicamente desde `countsByReason` (`HTTP_404`, `TIMEOUT`, `DNS_ERROR`, etc.).
  - Buscador por texto libre (`q`, debounce 300 ms): filtra por URL, título del registro o UUID.
- **Tabla paginada (50 filas por página):**
  - Columnas:
    1. **Registre:** título del registro (`recordTitle`), tipo (`recordType`) y `recordUuid` en fuente monoespaciada.
    2. **Camp / Tipus:** badge del campo (`documents`, `links`, `electronicVersions`) + `subType` + `linkLabel`.
    3. **URL:** enlace clicable (`target="_blank" rel="noopener noreferrer"`) truncado visualmente con URL completa en `title` (y `finalUrl` si hubo redirección distinta).
    4. **Estat / Motiu:** badge rojo (`BROKEN`) o ámbar (`BLOCKED`), código HTTP y etiqueta legible del motivo / mensaje de error.
    5. **Egreta:** botón/enlace **«Obrir a Egreta»** hacia `egretaUrl` (`https://egreta.uab.cat/admin/workspace.xhtml?uuid=<uuid>`, `target="_blank" rel="noopener noreferrer"`) si `egretaUrl` está presente.
  - **Pie del modal:** paginación (*Anterior / Pàgina X de Y / Següent*) y botón *Tancar*.

---

## 5. Estrategia de Pruebas

Todas las pruebas se ejecutan sin dependencias externas de red (usando `com.sun.net.httpserver.HttpServer` embebido del JDK 21 y mocks o repositorios aislados):

1. **`CollectionLinkExtractorTest`:**
   - Verifica extracción en `documents[]` (`url`, `fileURL`, `file.url`, `file.fileURL` sin duplicar valores idénticos en el mismo elemento), `links[]` (`url`) y `electronicVersions[]` (solo en `Researchoutputs`, verificando que NO se resuelven `doi` sin URL explícita).
   - Verifica resolución de `recordTitle` en sus distintas variantes (`title.value`, mapa localizado, `name.firstName + lastName`, fallback a `uuid`), `subType`, `linkLabel` y `egretaUrl`.
2. **`HttpLinkVerifierTest`** *(con `HttpServer` local)*:
   - `HEAD` `200` → `OK`.
   - Fallback `HEAD 405` / `501` / `400` → `GET` `Range: bytes=0-0` → `OK`.
   - Clasificación exacta de `404`, `410`, `500` → `BROKEN`; `401`, `403`, `429` → `BLOCKED`.
   - Validación sintáctica (`espacios`, esquema `ftp://` o `javascript:`, sin host) → `BROKEN` / `INVALID_SYNTAX` sin tráfico de red.
   - Redirecciones `301`/`302` válidas hasta 5 saltos y fallo `TOO_MANY_REDIRECTS` al 6º salto.
   - **Test de seguridad `api-key`:** comprueba que la cabecera `api-key` sí se envía al host Egreta configurado en pruebas (por ejemplo `localhost`), y que al recibir un `302` desde ese host Egreta hacia un segundo `HttpServer` en otro host distinto (`127.0.0.1`), **no** se envía la cabecera `api-key` al destino externo.
3. **`LinkAuditServiceTest`:**
   - Deduplicación de URLs repetidas en múltiples documentos/colecciones dentro del mismo escaneo (1 única llamada HTTP al verificador).
   - Bloqueo de concurrencia (`ScanAlreadyRunningException` mientras un job está activo).
   - Ordenación global de colecciones (menor a mayor tamaño estimado y `Researchoutputs` al final).
   - Cancelación limpia e intercambio atómico por `scanId` (el último resumen válido permanece intacto cuando se cancela o falla un nuevo escaneo).
4. **`CollectionLinkAuditControllerTest`** (`MockMvc`):
   - Códigos de respuesta `200`, `202`, `400` (colección inexistente o interna) y `409` (escaneo ya en curso).
   - Paginación y filtrado por `status`, `field`, `reason` y `q` en `GET /{collection}/issues`.
   - Exportación CSV con cabecera `UTF-8` + BOM, `Content-Disposition` correcto y escapado seguro de comillas y fórmulas.
5. **`CollectionDocumentStatsServiceExclusionTest`:**
   - Verifica que `CollectionDocumentStatsService` excluye `CollectionLinkAuditSummary` y `CollectionLinkAuditIssues` al calcular las estadísticas de colecciones.

---

## 6. Criterios de Aceptación y Mitigación de Riesgos

### Criterios de aceptación
1. Desde `documents-coleccion.html` se puede lanzar la auditoría global o de una colección concreta y ver el progreso en tiempo real sin bloquear la interfaz.
2. Las colecciones finalizadas persisten en MongoDB (`CollectionLinkAuditSummary` y `CollectionLinkAuditIssues`) y se cargan al instante al reabrir la página.
3. Los enlaces rotos confirmados (`BROKEN`) aparecen separados de los bloqueados/dudosos (`BLOCKED` `401`/`403`/`429`) tanto en los totales como en el modal de incidencias.
4. El modal permite filtrar por estado, campo (`documents`, `links`, `electronicVersions`), motivo y texto, abrir cada registro directamente en Egreta y exportar a CSV todas las incidencias filtradas.
5. Todas las pruebas automatizadas descritas en la sección 5 pasan sin errores.

### Riesgos y mitigaciones
- **Volumen elevado de `Researchoutputs`:** se ordena explícitamente al final en el escaneo global, se deduplican URLs únicas en memoria, se usa cursor streaming con proyección mínima y se limita la concurrencia global (24) y por host (3).
- **Falsos negativos por cabeceras o `HEAD` bloqueado:** se envía un `User-Agent` identificable y se aplica fallback automático a `GET` con `Range: bytes=0-0` ante `405`/`501`/`400`.
- **Exposición accidental de `api-key`:** comprobación estricta por host exacto configurado y seguimiento manual de redirecciones re-evaluando la política de host en cada salto.
- **Interrupción a mitad de escaneo:** las incidencias se etiquetan con `scanId` y solo sustituyen al escaneo anterior en la UI tras completar y reemplazar el documento en `CollectionLinkAuditSummary`.
