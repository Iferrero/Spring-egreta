# Plan de Implementación: Auditoría HTTP de Enlaces Rotos por Colección

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrar en `documents-coleccion.html` y su backend Spring Boot + MongoDB (`kraken`) la auditoría HTTP asíncrona de enlaces rotos y bloqueados sobre `documents` y `links` de todas las colecciones más `electronicVersions` en `Researchoutputs`, persistiendo resúmenes e incidencias en MongoDB con consulta paginada y exportación CSV.

**Architecture:** Nuevo paquete modular `com.example.demo.linkaudit` compuesto por modelo inmutable (`model/`), extractor por streaming con proyección mínima (`CollectionLinkExtractor`), verificador HTTP Java 21 con seguimiento manual de redirecciones y protección estricta de `api-key` (`HttpLinkVerifier`), repositorio sobre `CollectionLinkAuditSummary` y `CollectionLinkAuditIssues` con intercambio lógico por `scanId` (`MongoLinkAuditRepository` / `LinkAuditStore`), orquestador asíncrono de trabajo único con Virtual Threads y semáforos global/por host (`LinkAuditService`), y controlador REST `/api/mongo/collections/link-audit` (`CollectionLinkAuditController`), más la integración visual en catalán en `src/main/webapp/documents-coleccion.html` y exclusión de colecciones internas en `CollectionDocumentStatsService`.

**Tech Stack:** Java 21 (`java.net.http.HttpClient`, Virtual Threads, `com.sun.net.httpserver.HttpServer`), Spring Boot 4.1.2, Spring Data MongoDB (`MongoTemplate`), JUnit 5, Mockito, MockMvc (`standaloneSetup`), HTML5 / Tailwind CSS / FontAwesome 6 / Vanilla JS (`config.js`).

**Spec:** [2026-11-20-collection-link-audit-design.md](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/docs/superpowers/specs/2026-11-20-collection-link-audit-design.md)

## Global Constraints
- Java 21 y Spring Boot 4.1.2; **sin nuevas dependencias** en `pom.xml`.
- Persistencia únicamente en `CollectionLinkAuditSummary` y `CollectionLinkAuditIssues` (en `kraken`); **no** persistir resultados `200 OK` en MongoDB.
- Excluir `CollectionLinkAuditSummary`, `CollectionLinkAuditIssues` y `system.*` tanto en `CollectionDocumentStatsService` como en `LinkAuditService`.
- Alcance de campos: `documents` y `links` en todas las colecciones de negocio, más `electronicVersions` únicamente en `Researchoutputs` (solo URLs explícitas, **sin** resolver DOIs sueltos vía `doi.org`).
- Clasificación estricta: `2xx` → `OK`; `401`/`403`/`429` → `BLOCKED`; `404`/`410`/otros `4xx`/`5xx`/`3xx` sin `Location`/>5 redirecciones/DNS/timeout/SSL/conexión/sintaxis inválida → `BROKEN`.
- Seguridad `api-key`: enviar únicamente si el host destino coincide exactamente (case-insensitive) con un host Egreta configurado (`egreta.uab.cat`, `egretat.uab.cat`) y **nunca** tras redirecciones a hosts externos.
- Un único escaneo activo a la vez (`409 Conflict` si `RUNNING`/`CANCELLING`); intercambio atómico lógico por `scanId` al terminar cada colección.
- Seguridad web heredada de `/api/mongo/**` (sin excepciones públicas en `SecurityConfig` ni `DevSecurityConfig`).
- Interfaz en `documents-coleccion.html` redactada en **catalán (`ca`)**, usando `window.apiFetch(...)` y `window.apiUrl(...)` (compatibles con `/api` y `/otr/api`), y escapando con `escapeHtml` todo contenido dinámico.
- Higiene Git: el repositorio contiene cambios previos ajenos; usar siempre `git add <rutas-específicas>` y nunca `git add -A` ni `git add .`. En PowerShell ejecutar tests con `.\mvnw.cmd test "-Dtest=<TestClass>"`.

## Review Focus
- **Fuga de `api-key` en redirecciones cross-host, `Location` relativa y `3xx` sin `Location`/bucle >5 saltos:** una redirección `302` desde el host Egreta configurado (`localhost`) hacia un destino externo (`127.0.0.1`) no debe incluir `api-key`; una `Location` relativa (`/dest`) se resuelve con `currentUri.resolve`; un `3xx` sin `Location` devuelve `BROKEN/CONNECTION_ERROR` y 6 saltos devuelven `BROKEN/TOO_MANY_REDIRECTS`. *(Pinned in Task 2 — `HttpLinkVerifierTest`)*
- **Documentos heterogéneos/corruptos en `kraken` y deduplicación intra-elemento:** registros con `title: null`, `documents: [null, { url: "  " }]`, `links` no-array, `file.url` == `file.fileURL` en el mismo elemento, o `electronicVersions` con solo `doi` no deben lanzar excepciones ni emitir referencias duplicadas ni fabricar URLs `doi.org`. *(Pinned in Task 1 — `CollectionLinkExtractorTest`)*
- **Cancelación/fallo parcial sin corromper el último escaneo válido y orden de `Researchoutputs`:** si un escaneo se cancela o falla a mitad de colección, se purgan las filas del `newScanId` parcial sin tocar el `scanId` activo en `CollectionLinkAuditSummary`; en escaneo global un error aislado en una colección no detiene las siguientes y `Researchoutputs` se ejecuta al final. *(Pinned in Task 4 — `LinkAuditServiceTest`)*
- **Regex injection en `q`, *CSV Formula Injection* y nombres de colección inválidos/internos:** caracteres especiales de regex en `q` (`http://x.es/a?b=(1)+[2]`) deben escaparse literalmente con `Pattern.quote(q)`; títulos/etiquetas/errores que empiecen por `=`, `+`, `-`, `@`, `\t`, `\r` deben neutralizarse con `'` en el CSV sin tocar URLs `http://`/`https://`; nombres internos (`CollectionLinkAuditSummary`, `system.views`) o en blanco deben devolver `400 Bad Request`. *(Pinned in Task 3 — `MongoLinkAuditRepositoryTest` y Task 5 — `CollectionLinkAuditControllerTest`)*
- **XSS vía enlaces `INVALID_SYNTAX` (`javascript:...`) y contexto `/otr/api` en `documents-coleccion.html`:** en la tabla principal y `#linkIssuesModal` todo dato se escapa con `escapeHtml`, solo se renderiza `<a href="...">` clicable cuando la URL cumple `/^https?:\/\//i` (evitando `href="javascript:..."`), y la exportación CSV del modal usa `window.apiUrl(...)`. *(Pinned in Task 6 — verificación estática y checklist en `documents-coleccion.html`)*

---

### Task 1: Modelo de Dominio, `LinkAuditConstants`, `CollectionLinkExtractor` y Exclusión en `CollectionDocumentStatsService`

**Files:**
- Create: `src/main/java/com/example/demo/linkaudit/LinkAuditConstants.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/LinkStatus.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/FailureReason.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/AuditJobStatus.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/LinkSourceField.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/ExtractedLinkRef.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/LinkCheckResult.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/FieldAuditCounts.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/CollectionAuditSummary.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/LinkAuditIssue.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/AuditJobState.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/AuditSummaryResponse.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/LinkIssuesPageResponse.java`
- Create: `src/main/java/com/example/demo/linkaudit/model/ScanRequest.java`
- Create: `src/main/java/com/example/demo/linkaudit/extractor/CollectionLinkExtractor.java`
- Modify: `src/main/java/com/example/demo/service/CollectionDocumentStatsService.java:43-60`
- Test: `src/test/java/com/example/demo/linkaudit/extractor/CollectionLinkExtractorTest.java`
- Test: `src/test/java/com/example/demo/linkaudit/CollectionDocumentStatsServiceExclusionTest.java`

**Interfaces:**
- Consumes: `org.springframework.data.mongodb.core.MongoTemplate`, `org.bson.Document`
- Produces:
  - `LinkAuditConstants`: `SUMMARY_COLLECTION = "CollectionLinkAuditSummary"`, `ISSUES_COLLECTION = "CollectionLinkAuditIssues"`, `isInternalCollection(String collectionName)`, `buildEgretaWorkspaceUrl(String uuid)`
  - Enums exactos de Spec §2.2: `LinkStatus`, `FailureReason`, `AuditJobStatus`, `LinkSourceField` (con `getFieldName()` y `fromValue(String)`)
  - Records exactos de Spec §2.2: `ExtractedLinkRef`, `LinkCheckResult`, `FieldAuditCounts`, `CollectionAuditSummary`, `LinkAuditIssue`, `AuditJobState`, `AuditSummaryResponse`, `LinkIssuesPageResponse`, `ScanRequest`
  - `@Component CollectionLinkExtractor`:
    - `public List<ExtractedLinkRef> extractFromDocument(String collection, Document doc)`
    - `public ExtractionResult extractCollection(String collection, BooleanSupplier cancelled)` donde `public record ExtractionResult(long recordsWithLinks, List<ExtractedLinkRef> refs) {}`

- [ ] **Step 1: Write the failing tests (`CollectionLinkExtractorTest` y `CollectionDocumentStatsServiceExclusionTest`)**

Escribir en `CollectionLinkExtractorTest` tests JUnit 5 que verifiquen:
- `extractFromDocumentExtractsDocumentsLinksAndDeduplicatesSameElementUrls`: en `Awards`, un elemento de `documents[]` con `file.url == file.fileURL` emite una única referencia; un elemento con `url` y `link` distintas emite ambas; `links[]` extrae `url`, `linkType.term.{ca_ES,es_ES,en_GB}` y `alias`; `electronicVersions[]` en `Awards` **no** se extrae.
- `extractFromDocumentExtractsElectronicVersionsOnlyInResearchoutputsIgnoringBareDois`: en `Researchoutputs`, `electronicVersions[]` con `{ "doi": "10.1000/x", "link": null }` no emite ninguna URL (no fabrica `https://doi.org/...`), mientras otro elemento con `link` y `file.url` sí las extrae con `subType` de `versionType.term`.
- `extractFromDocumentResolvesTitleVariantsTypeAndEgretaUrl`: prueba las 7 variantes de `recordTitle` (`title.value`, `title.ca_ES`, `title` String, `name.ca_ES`, `name.firstName + lastName`, `name` String, fallback a `uuid`/`_id`), `recordType` y `buildEgretaWorkspaceUrl(uuid)`.
- `extractFromDocumentHandlesNullsAndMalformedNestedStructures`: *(Review Focus #2)* `title: null`, `documents: [null, new Document("url", "   ")]`, `links: "not-a-list"` no lanzan excepción y devuelven lista vacía.
- `extractCollectionStreamsWithMinimalProjectionAndRespectsCancellation`: mock de `MongoTemplate`/`MongoCollection`/`FindIterable`/`MongoCursor` verificando `batchSize(500)`, conteo de `recordsWithLinks` y parada temprana cuando `cancelled.getAsBoolean()` pasa a `true`.

Escribir en `CollectionDocumentStatsServiceExclusionTest` un test con `MongoTemplate`, `MongoDatabase` y `MongoIterable<String>` mockeados donde `listCollectionNames().into(...)` devuelve `["Awards", "CollectionLinkAuditSummary", "CollectionLinkAuditIssues", "system.views"]`: verificar que `mongoTemplate.getCollection(...)` nunca se invoca para las colecciones internas ni `system.*`, y solo `"Awards"` aparece en `collections`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd test "-Dtest=CollectionLinkExtractorTest,CollectionDocumentStatsServiceExclusionTest"`  
Expected: FAIL con errores de compilación por inexistencia de `com.example.demo.linkaudit.*`.

- [ ] **Step 3: Implement `LinkAuditConstants`, `model/*`, `CollectionLinkExtractor` y la exclusión en `CollectionDocumentStatsService`**

- Crear `LinkAuditConstants` con las constantes de colecciones internas, `isInternalCollection(String)` (`null`/blank/`SUMMARY_COLLECTION`/`ISSUES_COLLECTION`/`system.` case-insensitive) y `buildEgretaWorkspaceUrl(String)` (`https://egreta.uab.cat/admin/workspace.xhtml?uuid=` + `URLEncoder.encode(uuid, UTF_8)` o `null`).
- Crear los 4 enums y 9 records en `com.example.demo.linkaudit.model` respetando exactamente las firmas de Spec §2.2.
- Implementar `CollectionLinkExtractor` usando un único helper reutilizable de resolución de texto localizado (`ca_ES` → `es_ES` → `en_GB`) y extracción por cursor streaming (`batchSize(500)`, `noCursorTimeout(false)`, proyección mínima `_id`, `uuid`, `pureId`, `title`, `name`, `type`, `typeDiscriminator`, `documents`, `links`, más `electronicVersions` solo si `"Researchoutputs".equalsIgnoreCase(collection)`).
- Editar `CollectionDocumentStatsService.java` (alrededor de las líneas 51–56) para omitir `colName` cuando `LinkAuditConstants.isInternalCollection(colName)` sea `true` antes de llamar a `mongoTemplate.getCollection(colName)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd test "-Dtest=CollectionLinkExtractorTest,CollectionDocumentStatsServiceExclusionTest"`  
Expected: PASS (`BUILD SUCCESS`, 0 failures, 0 errors; al menos 6 tests ejecutados).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/linkaudit/LinkAuditConstants.java src/main/java/com/example/demo/linkaudit/model/ src/main/java/com/example/demo/linkaudit/extractor/ src/main/java/com/example/demo/service/CollectionDocumentStatsService.java src/test/java/com/example/demo/linkaudit/CollectionDocumentStatsServiceExclusionTest.java src/test/java/com/example/demo/linkaudit/extractor/
git commit -m "feat(link-audit): afegir model, extractor d'enllaços i exclusió a document-stats"
```

---

### Task 2: Propiedades (`LinkAuditProperties`) y Verificador HTTP (`LinkVerifier` / `HttpLinkVerifier`)

**Files:**
- Create: `src/main/java/com/example/demo/linkaudit/config/LinkAuditProperties.java`
- Create: `src/main/java/com/example/demo/linkaudit/verifier/LinkVerifier.java`
- Create: `src/main/java/com/example/demo/linkaudit/verifier/HttpLinkVerifier.java`
- Modify: `src/main/resources/application.properties:95-98`
- Test: `src/test/java/com/example/demo/linkaudit/verifier/HttpLinkVerifierTest.java`

**Interfaces:**
- Consumes: `LinkStatus`, `FailureReason`, `LinkCheckResult` (Task 1)
- Produces:
  - `@Component @ConfigurationProperties(prefix = "app.link-audit") LinkAuditProperties` (`globalConcurrency=24`, `perHostConcurrency=3`, `connectTimeoutMs=5000`, `requestTimeoutMs=10000`, `maxRedirects=5`, `insertBatchSize=500`, `userAgent="Mozilla/5.0 (compatible; UAB-Egreta-LinkAudit/1.0)"`)
  - `public interface LinkVerifier`:
    - `LinkCheckResult verify(String rawUrl)`
    - `Optional<String> extractValidHttpHost(String rawUrl)`
  - `@Component HttpLinkVerifier implements LinkVerifier` (constructor Spring con `@Autowired` inyectando `LinkAuditProperties` y los `@Value` de `app.egreta.external-org.base-url`, `app.egreta.external-org.api-key`, `egreta54.base.url`, `egreta54.api.key`; más constructor de test `public HttpLinkVerifier(LinkAuditProperties properties, Map<String, String> egretaHostApiKeys)`).

- [ ] **Step 1: Write the failing test (`HttpLinkVerifierTest`)**

Usando `com.sun.net.httpserver.HttpServer` embebido en memoria (sin red externa), cubrir:
- `verifyReturnsOkOnHead200`: petición `HEAD` devuelve `200` → `LinkStatus.OK`, `FailureReason.OK`, `httpStatus == 200`.
- `verifyFallsBackToLightweightGetWhenHeadReturns405Or501Or400`: cuando `HEAD` devuelve `405` (y en otro caso `501` o `400`), reintenta con `GET` llevando `Range: bytes=0-0` y devuelve `OK`.
- `verifyClassifiesBrokenAndBlockedStatuses`: `404` → `BROKEN/HTTP_404`, `410` → `BROKEN/HTTP_410`, `500` → `BROKEN/HTTP_5XX`, `418` → `BROKEN/HTTP_4XX`; `401` → `BLOCKED/HTTP_401`, `403` → `BLOCKED/HTTP_403`, `429` → `BLOCKED/HTTP_429`.
- `verifyRejectsInvalidSyntaxWithoutNetworkCalls`: URLs con espacios (`"https://uab.cat/a b"`), saltos de línea, `ftp://uab.cat`, `javascript:alert(1)`, cadena vacía → `BROKEN/INVALID_SYNTAX` y `extractValidHttpHost(...)` devuelve `Optional.empty()`.
- `verifyHandlesRedirectsRelativeChainMissingLocationAndLoop`: *(Review Focus #1)* `302` relativa (`/final`) → `OK` con `finalUrl` resuelta; 6 saltos `302` con `maxRedirects=5` → `BROKEN/TOO_MANY_REDIRECTS`; `302` sin `Location` → `BROKEN/CONNECTION_ERROR`.
- `verifySendsApiKeyOnlyToConfiguredEgretaHostAndStripsOnExternalRedirect`: *(Review Focus #1)* servidor Egreta simulado en `localhost` (configurado en `egretaHostApiKeys`) recibe `api-key` y responde `302` hacia servidor externo en `127.0.0.1` → el destino `127.0.0.1` **no** recibe la cabecera `api-key`, devolviendo `OK` al final.
- `verifyClassifiesConnectionRefusedAndTimeout`: puerto libre cerrado → `BROKEN/CONNECTION_ERROR`; handler lento > `requestTimeoutMs` → `BROKEN/TIMEOUT`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=HttpLinkVerifierTest"`  
Expected: FAIL con errores de compilación.

- [ ] **Step 3: Implement `LinkAuditProperties`, `LinkVerifier`, `HttpLinkVerifier` y propiedades en `application.properties`**

- Añadir en `application.properties` al final las 7 propiedades `app.link-audit.*` de Spec §2.1.
- Implementar `LinkAuditProperties` y `LinkVerifier`.
- Implementar `HttpLinkVerifier` con `HttpClient.Redirect.NEVER`, bucle manual hasta `maxRedirects`, comprobación por salto de `egretaHostApiKeys` (host en minúsculas exacto), `HEAD` con fallback a `GET` (`Range: bytes=0-0`, `BodyHandlers.discarding()`), y tabla exacta de mapeo de excepciones (`UnknownHostException` → `DNS_ERROR`, `HttpTimeoutException`/`SocketTimeoutException` → `TIMEOUT`, `SSLException` → `SSL_ERROR`, otras `IOException` → `CONNECTION_ERROR`).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=HttpLinkVerifierTest"`  
Expected: PASS (`BUILD SUCCESS`, 0 failures, 0 errors; al menos 7 tests ejecutados).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.properties src/main/java/com/example/demo/linkaudit/config/ src/main/java/com/example/demo/linkaudit/verifier/ src/test/java/com/example/demo/linkaudit/verifier/
git commit -m "feat(link-audit): implementar verificador HTTP amb control de redireccions i api-key"
```

---

### Task 3: Persistencia MongoDB (`LinkAuditStore` / `MongoLinkAuditRepository`)

**Files:**
- Create: `src/main/java/com/example/demo/linkaudit/repository/LinkAuditStore.java`
- Create: `src/main/java/com/example/demo/linkaudit/repository/MongoLinkAuditRepository.java`
- Test: `src/test/java/com/example/demo/linkaudit/repository/MongoLinkAuditRepositoryTest.java`

**Interfaces:**
- Consumes: `LinkAuditConstants`, `model/*` (Task 1), `LinkAuditProperties` (Task 2), `MongoTemplate`
- Produces:
  - `public interface LinkAuditStore`:
    - `void ensureIndexes()`
    - `List<String> listBusinessCollections()`
    - `boolean collectionExists(String collection)`
    - `long estimatedDocumentCount(String collection)`
    - `List<CollectionAuditSummary> findAllSummaries()`
    - `Optional<CollectionAuditSummary> findSummary(String collection)`
    - `void insertIssuesInBatches(List<LinkAuditIssue> issues)`
    - `void upsertSummary(CollectionAuditSummary summary)`
    - `long deleteIssuesOtherThanScan(String collection, String activeScanId)`
    - `long deleteIssuesForScan(String collection, String scanId)`
    - `LinkIssuesPageResponse findIssuesPage(String collection, String status, String field, String reason, String query, int page, int size)`
    - `void streamIssuesForExport(String collection, String status, String field, String reason, String query, Consumer<LinkAuditIssue> consumer)`
  - `@Repository MongoLinkAuditRepository implements LinkAuditStore` (expone método package-private `Bson buildIssuesFilter(String collection, String scanId, String status, String field, String reason, String query)` para verificación directa en tests).

- [ ] **Step 1: Write the failing test (`MongoLinkAuditRepositoryTest`)**

Con Mockito sobre `MongoTemplate`, `MongoDatabase`, `MongoCollection<Document>` y `FindIterable<Document>`:
- `listBusinessCollectionsExcludesInternalAndSystemCollections`: devuelve solo colecciones de negocio ordenadas, omitiendo `CollectionLinkAuditSummary`, `CollectionLinkAuditIssues` y `system.*`.
- `insertIssuesInBatchesSplitsByConfiguredBatchSize`: con `insertBatchSize = 2` y 5 `LinkAuditIssue`, invoca `insertMany` 3 veces (2 + 2 + 1).
- `upsertSummaryUsesCollectionAsIdAndReplaceUpsert`: verifica `_id == collection`, `ReplaceOptions.isUpsert() == true` y persistencia de `scanId` y `byField`.
- `deleteOperationsBuildExactScanIdFilters`: `deleteIssuesOtherThanScan` usa `$ne` con `activeScanId`; `deleteIssuesForScan` usa igualdad con `scanId`.
- `buildIssuesFilterEscapesRegexMetacharactersInQuery`: *(Review Focus #4)* pasando `q = "http://x.es/a?b=(1)+[2]"`, verifica en el `BsonDocument` generado que los caracteres `?.()+[]` van escapados literalmente (`Pattern.quote`) sin causar `PatternSyntaxException` ni comodines regex.
- `findIssuesPageReturnsEmptyWhenCollectionHasNoSummary`: si no hay resumen para la colección, devuelve `LinkIssuesPageResponse` con `totalItems == 0`, `items.isEmpty()` y mapas de facetas vacíos.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=MongoLinkAuditRepositoryTest"`  
Expected: FAIL con errores de compilación.

- [ ] **Step 3: Implement `LinkAuditStore` y `MongoLinkAuditRepository`**

- Crear interfaz `LinkAuditStore`.
- Implementar `MongoLinkAuditRepository`:
  - `@PostConstruct` invocando `ensureIndexes()` (capturando y registrando advertencia si MongoDB no estuviese disponible durante un arranque aislado de test) para crear los 3 índices compuestos exactos de Spec §2.5.
  - Conversión limpia `Document` ↔ `CollectionAuditSummary` y `LinkAuditIssue`.
  - Consulta paginada `findIssuesPage` con agregación o consultas sobre `scanId` activo para calcular `countsByStatus`, `countsByField`, `countsByReason`, ordenación (`status` ASC, `sourceField` ASC, `recordTitle` ASC), `skip`/`limit`, y cursor streaming en `streamIssuesForExport`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=MongoLinkAuditRepositoryTest"`  
Expected: PASS (`BUILD SUCCESS`, 0 failures, 0 errors; al menos 6 tests ejecutados).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/linkaudit/repository/ src/test/java/com/example/demo/linkaudit/repository/
git commit -m "feat(link-audit): afegir repositori MongoDB d'auditoria amb intercanvi per scanId"
```

---

### Task 4: Orquestación Asíncrona (`ScanAlreadyRunningException` y `LinkAuditService`)

**Files:**
- Create: `src/main/java/com/example/demo/linkaudit/service/ScanAlreadyRunningException.java`
- Create: `src/main/java/com/example/demo/linkaudit/service/LinkAuditService.java`
- Test: `src/test/java/com/example/demo/linkaudit/service/LinkAuditServiceTest.java`

**Interfaces:**
- Consumes: `LinkAuditConstants`, `model/*`, `CollectionLinkExtractor` (Task 1), `LinkAuditProperties`, `LinkVerifier` (Task 2), `LinkAuditStore` (Task 3)
- Produces:
  - `public class ScanAlreadyRunningException extends RuntimeException` (`getActiveJobState()`)
  - `@Service LinkAuditService`:
    - `public AuditSummaryResponse getSummary()`
    - `public AuditJobState startScan(String collection)`
    - `public AuditJobState getStatus()`
    - `public AuditJobState cancelScan()`
    - `public LinkIssuesPageResponse getIssues(String collection, String status, String field, String reason, String query, int page, int size)`
    - `public void streamIssuesForExport(String collection, String status, String field, String reason, String query, Consumer<LinkAuditIssue> consumer)`
    - `@PreDestroy public void shutdown()`

- [ ] **Step 1: Write the failing test (`LinkAuditServiceTest`)**

Con mocks de `LinkAuditStore`, `CollectionLinkExtractor` y `LinkVerifier`:
- `startScanDeduplicatesRepeatedUrlsAcrossCollectionsAndCommitsAtomically`: dos colecciones comparten una misma URL `404`; `linkVerifier.verify(...)` se invoca una sola vez para esa URL durante todo el escaneo global, se generan incidencias para cada registro afectado y se verifica el orden exacto de persistencia `InOrder`: `insertIssuesInBatches` → `upsertSummary` → `deleteIssuesOtherThanScan`.
- `startGlobalScanOrdersCollectionsByEstimatedCountWithResearchoutputsLast`: *(Review Focus #3)* dada la lista `["Researchoutputs" (200000), "Awards" (500), "Activities" (100)]`, verifica que el orden de escaneo ejecutado es `Activities`, `Awards`, `Researchoutputs`.
- `startScanThrowsScanAlreadyRunningExceptionWhenJobActive`: mientras un job bloqueado en `verify(...)` está en `RUNNING`, una segunda llamada a `startScan` lanza `ScanAlreadyRunningException` con el estado activo.
- `cancelScanCleansPartialNewScanWithoutOverwritingPreviousSummary`: *(Review Focus #3)* al cancelar a mitad de escaneo tras haber completado una colección y estar verificando la segunda, llama a `deleteIssuesForScan` para el `newScanId` de la colección interrumpida, **no** llama a `upsertSummary` sobre esa colección y termina en `AuditJobStatus.CANCELLED`.
- `globalScanIsolatesCollectionFailureAndContinuesRemainingCollections`: *(Review Focus #3)* si `extractCollection` falla en la primera colección, el escaneo continúa con la segunda colección y finaliza persistiendo la segunda.
- `startScanAndQueryMethodsRejectInvalidOrInternalCollections`: `startScan` rechaza colecciones internas o inexistentes en `kraken` con `IllegalArgumentException`; `getIssues` y `streamIssuesForExport` rechazan nombre vacío o colección interna (`CollectionLinkAuditSummary`, `CollectionLinkAuditIssues`, `system.*`) con `IllegalArgumentException`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=LinkAuditServiceTest"`  
Expected: FAIL con errores de compilación.

- [ ] **Step 3: Implement `ScanAlreadyRunningException` y `LinkAuditService`**

- Implementar `ScanAlreadyRunningException`.
- Implementar `LinkAuditService`:
  - `AtomicReference<AuditJobState>` y bloqueo sincronizado en `startScan`/`cancelScan` para garantizar exclusión mutua de trabajos activos.
  - Virtual Thread executor (`Executors.newVirtualThreadPerTaskExecutor()`), `Semaphore(globalConcurrency)`, `ConcurrentHashMap<String, Semaphore>` por host (`perHostConcurrency`), y caché por escaneo `ConcurrentHashMap<String, CompletableFuture<LinkCheckResult>>` indexada por `rawUrl.trim()`.
  - Si `linkVerifier.extractValidHttpHost(url)` está vacío (sintaxis inválida), verificar sin adquirir semáforos de red.
  - Cálculo de `CollectionAuditSummary` y `byField` (`documents`, `links`, `electronicVersions`), ordenación global (`estimatedDocumentCount` ascendente, `Researchoutputs` al final), limpieza de `newScanId` en cancelación/error y `@PreDestroy shutdown()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=LinkAuditServiceTest"`  
Expected: PASS (`BUILD SUCCESS`, 0 failures, 0 errors; al menos 6 tests ejecutados).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/linkaudit/service/ src/test/java/com/example/demo/linkaudit/service/
git commit -m "feat(link-audit): implementar servei asíncron d'auditoria amb concurrència per domini"
```

---

### Task 5: Controlador REST y Exportación CSV (`CollectionLinkAuditController`)

**Files:**
- Create: `src/main/java/com/example/demo/linkaudit/controller/CollectionLinkAuditController.java`
- Test: `src/test/java/com/example/demo/linkaudit/controller/CollectionLinkAuditControllerTest.java`

**Interfaces:**
- Consumes: `LinkAuditService`, `ScanAlreadyRunningException` (Task 4), `model/*` (Task 1)
- Produces:
  - `@RestController @RequestMapping("/api/mongo/collections/link-audit") CollectionLinkAuditController`:
    - `GET /summary` → `200 OK` (`AuditSummaryResponse`)
    - `POST /scan` (`@RequestBody(required = false) ScanRequest`) → `202 Accepted` (`AuditJobState`), `400 Bad Request`, `409 Conflict`
    - `GET /status` → `200 OK` (`AuditJobState`)
    - `POST /cancel` → `200 OK` (`AuditJobState`)
    - `GET /{collection}/issues` (params `status`, `field`, `reason`, `q`, `page`, `size`) → `200 OK` (`LinkIssuesPageResponse`), `400 Bad Request`
    - `GET /{collection}/issues/export` (params `status`, `field`, `reason`, `q`) → `200 OK` (`text/csv; charset=UTF-8`), `400 Bad Request`

- [ ] **Step 1: Write the failing test (`CollectionLinkAuditControllerTest`)**

Con `MockMvcBuilders.standaloneSetup(new CollectionLinkAuditController(linkAuditService)).build()`:
- `getSummaryStatusAndCancelReturn200WithPayloads`: verifica `GET /summary`, `GET /status` y `POST /cancel`.
- `postScanReturns202OnStart400OnInvalidAnd409OnConflict`: sin body y con `{ "collection": "Awards" }` → `202`; `IllegalArgumentException` → `400`; `ScanAlreadyRunningException` → `409` con JSON de `AuditJobState`.
- `getIssuesAppliesDefaultsClampsPageSizeAndRejectsInternalCollection`: *(Review Focus #4)* `size=999` queda acotado a `200`, `page=-2` acotado a `0`, y `CollectionLinkAuditSummary` o `system.views` propagando `IllegalArgumentException` devuelve `400`.
- `exportIssuesStreamsUtf8BomCsvEscapingQuotesAndFormulaInjection`: *(Review Focus #4)* verifica cabecera `Content-Type` `text/csv;charset=UTF-8`, `Content-Disposition` con `enllacos_incorrectes_Awards_`, presencia inicial de BOM UTF-8 (`0xEF 0xBB 0xBF`), las 17 columnas exactas de Spec §3.6, comillas dobles escapadas (`""`), neutralización con `'` de valores que empiezan por `=`, `+`, `-`, `@`, `\t`, `\r` en `Titol_Registre`/`Etiqueta_Enllac`/`Error`, y preservación intacta de `https://example.com/ok`.
- `exportIssuesForUnauditedCollectionReturnsHeaderOnlyCsv`: colección sin incidencias devuelve únicamente la fila de cabeceras tras el BOM.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=CollectionLinkAuditControllerTest"`  
Expected: FAIL con errores de compilación.

- [ ] **Step 3: Implement `CollectionLinkAuditController`**

Implementar los 6 endpoints con anotaciones Swagger (`@Tag`, `@Operation`), `@ExceptionHandler` para `IllegalArgumentException` (`400`) y `ScanAlreadyRunningException` (`409`), clamp de paginación (`page >= 0`, `1 <= size <= 200`), sanitización del nombre de colección para `Content-Disposition`, y escritura CSV en streaming directamente a `HttpServletResponse.getOutputStream()` con BOM UTF-8, escape RFC 4180 y protección contra *CSV Formula Injection* en campos de texto libre.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=CollectionLinkAuditControllerTest"`  
Expected: PASS (`BUILD SUCCESS`, 0 failures, 0 errors; al menos 5 tests ejecutados).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/linkaudit/controller/ src/test/java/com/example/demo/linkaudit/controller/
git commit -m "feat(link-audit): exposar API REST i exportació CSV d'enllaços incorrectes"
```

---

### Task 6: Integración UI en `documents-coleccion.html` y Verificación Integral

**Files:**
- Modify: `src/main/webapp/documents-coleccion.html:47-218,305-741`

**Interfaces:**
- Consumes: los 6 endpoints REST de `/mongo/collections/link-audit/**` mediante `window.apiFetch(...)` y `window.apiUrl(...)` (`config.js`).
- Produces: panel actualizado en catalán (`documents-coleccion.html`) con botones de escaneo, banner de progreso en vivo, 5ª KPI card, columna «Enllaços Rots», filtro rápido, CSV extendido y modal `#linkIssuesModal`.

- [ ] **Step 1: Actualizar cabecera, banner de progreso en vivo y fila de 5 KPIs en `documents-coleccion.html`**

- En cabecera (`lines 47-56`): añadir el botón `#btnScanAllLinks` («Comprovar enllaços (tot)») llamando a `startLinkAudit(null)`.
- Bajo `#metaBanner` (`lines 59-66`): añadir `#linkAuditBanner` (oculto inicialmente) con colección actual, avance `collectionsCompleted / collectionsTotal`, barra/contador `currentCollectionCheckedUrls / currentCollectionUniqueUrls`, badges en vivo de *Rots* / *Bloquejats* y botón «Cancel·lar» (`cancelLinkAudit()`).
- En KPIs (`lines 69-128`): cambiar `lg:grid-cols-4` a `lg:grid-cols-5` y añadir la 5ª tarjeta «Enllaços Rots» (`#kpiBrokenLinks` en rojo y `#kpiBrokenSub`).

- [ ] **Step 2: Actualizar filtros, tabla principal (incluido `colspan="11"`) y `exportToCsv()`**

- En toolbar (`lines 160-170`): añadir checkbox `#chkOnlyWithBroken` («Només amb enllaços rots») disparando `applyFilters()`.
- En `<thead>` (`lines 176-209`): añadir columna ordenable `Enllaços Rots` (`onclick="sortBy('brokenCount')"`) con `<i id="sort_brokenCount">`, y actualizar todos los `colspan="10"` de estados de carga/error/vacío (`lines 212, 356, 487`) a `colspan="11"`.
- En `applyFilters()`: ajustar «Només amb contingut» a `item.recordsWithDocuments > 0 || (audit && audit.totalLinks > 0)`, aplicar filtro `onlyWithBroken`, y soportar ordenación por `brokenCount` consultando el mapa de resúmenes de auditoría por colección (tratando colecciones sin auditar como `-1` para que ordenen por debajo de las auditadas en orden descendente).
- En `renderTable(list)`: renderizar la celda «Enllaços Rots» (`Sense auditar`, `0 rots` en verde, o badges clicables `X rots` / `Y bloq.` llamando a `openLinkIssuesModal(...)`) junto al botón compacto de re-escaneo individual (`startLinkAudit(col)`).
- En `exportToCsv()`: añadir columnas `Enllacos_Comprovats`, `Enllacos_Rots`, `Enllacos_Bloquejats` y `Data_Auditoria`.

- [ ] **Step 3: Añadir modal `#linkIssuesModal` y lógica JS (polling, filtros, paginación y seguridad XSS)**

- Añadir `#linkIssuesModal` con cabecera (título, resumen, fecha, botón «Re-escanejar col·lecció» y botón «Exportar CSV» usando `window.apiUrl(...)`), pestañas de estado (`BROKEN` activa por defecto, `BLOCKED`, `ALL` con contadores), desplegables de `Camp` y `Motiu`, buscador con debounce 300 ms, tabla de 5 columnas (`Registre`, `Camp / Tipus`, `URL`, `Estat / Motiu`, `Egreta`), paginación de 50 filas y cierre con `Escape` (tanto para `#typeModal` como para `#linkIssuesModal`).
- *(Review Focus #5)* Implementar `isSafeHttpUrl(url)` (`/^\s*https?:\/\//i.test(String(url || ''))`): toda cadena mostrada pasa por `escapeHtml(...)`; únicamente se renderiza `<a href="...">` clicable con `target="_blank" rel="noopener noreferrer"` cuando `isSafeHttpUrl(url)` es `true` (tanto en la columna `URL` como en `egretaUrl`), mostrando las URLs con sintaxis inválida (`javascript:...`, etc.) como texto plano dentro de `<code>`.
- Cargar `loadLinkAuditSummary()` en `DOMContentLoaded` junto a `loadStats(false)`; si el trabajo está `RUNNING`/`CANCELLING`, iniciar polling cada 2 s sobre `/mongo/collections/link-audit/status`, refrescando `/summary` cada vez que incremente `collectionsCompleted` o finalice el job.

- [ ] **Step 4: Verificación sintáctica JS/HTML y ejecución completa de pruebas**

- Verificar sintaxis del `<script>` embebido en `documents-coleccion.html` (comprobando que `node --check` sobre el bloque extraído no reporta errores si Node está instalado, y verificando con búsqueda que están presentes `isSafeHttpUrl`, `window.apiUrl`, `colspan="11"`, `escapeHtml` y todos los IDs referenciados).
- Ejecutar todas las pruebas unitarias e integración del módulo `linkaudit`:
  Run: `.\mvnw.cmd test "-Dtest=CollectionLinkExtractorTest,CollectionDocumentStatsServiceExclusionTest,HttpLinkVerifierTest,MongoLinkAuditRepositoryTest,LinkAuditServiceTest,CollectionLinkAuditControllerTest"`  
  Expected: `BUILD SUCCESS`, 0 failures, 0 errors.
- Verificar compilación completa:
  Run: `.\mvnw.cmd test-compile`  
  Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/webapp/documents-coleccion.html
git commit -m "feat(link-audit): integrar auditoria i visor d'enllaços rots a documents-coleccion.html"
```
