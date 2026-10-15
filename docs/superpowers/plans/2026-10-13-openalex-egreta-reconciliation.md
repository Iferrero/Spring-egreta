# Plan de Implementación: Motor de Conciliación Bibliométrica OpenAlex - EGRETA

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar un motor de conciliación y curación bibliométrica que compare las publicaciones de investigadores de la UAB entre EGRETA y OpenAlex, triangule afiliaciones y autorías mediante Crossref/PubMed y coautoría histórica, y clasifique las candidatas mediante un semáforo explicable para su gestión individual o por lotes.

**Architecture:** Arquitectura hexagonal/por capas en Spring Boot y MongoDB. Un cliente OpenAlex con rate-limiting extrae obras no presentes en EGRETA; un motor de scoring heurístico evalúa afiliaciones institucionales de la esfera UAB y señales de desambiguación para persistir candidatos en `PersonPublicationCandidate`; un servicio asíncrono gestiona escaneos departamentales mediante `ReconciliationJobRegistry`; y controladores REST exponen operaciones de curación individual y masiva.

**Tech Stack:** Java 17+, Spring Boot 3.x, Spring Data MongoDB, HttpClient / WebClient, Jackson, JUnit 5, Mockito.

**Spec:** [docs/superpowers/specs/2026-10-13-openalex-egreta-reconciliation-design.md](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/docs/superpowers/specs/2026-10-13-openalex-egreta-reconciliation-design.md)

## Global Constraints

- Modelo de persistencia en MongoDB respetando las colecciones existentes (`Persons`, `Researchoutputs`).
- Índice único compuesto en `(personUuid: 1, doi: 1)` para evitar duplicidad y garantizar memoria permanente de decisiones.
- Rate-limiting y polite pool activo en todas las llamadas a OpenAlex (cabecera User-Agent / mailto, máx. 10 req/s).
- Umbrales de confianza fijados en la especificación: `HIGH_CONFIDENCE_UAB` (>= 80), `REQUIRES_REVIEW` (40-79), `LIKELY_FALSE_POSITIVE` (< 40).
- Compatibilidad con el ejecutor asíncrono existente `ReconciliationJobRegistry`.

## Review Focus

1. **DOI con formatos inconsistentes (mayúsculas, prefijos `https://doi.org/`, espacios):** La normalización debe garantizar coincidencia exacta sin falsos duplicados.
2. **Autor con homónimos globales masivos sin ORCID:** El sistema debe degradar con gracia a puntuación baja (🔴) en ausencia de coautores y firma UAB sin lanzar excepciones.
3. **Afiliaciones UAB escritas en múltiples variantes o a través de hospitales/institutos consorciados:** El catálogo de patrones debe reconocer firmas de Vall d'Hebron, Germans Trias, ICMAB, IFAE, etc.
4. **Fallo o timeout en API externa durante un trabajo batch:** La excepción en un investigador individual debe capturarse, registrar la incidencia en el job y continuar procesando los restantes autores del lote.
5. **Rechazo previo permanente:** Si una publicación ya fue rechazada para un autor, un escaneo posterior no debe sobreescribirla a estado `PENDING`.

---

### Task 1: Modelo de Datos `PersonPublicationCandidate` y Repositorio MongoDB

**Files:**
- Create: `src/main/java/com/example/demo/curation/model/PersonPublicationCandidate.java`
- Create: `src/main/java/com/example/demo/curation/model/AffiliationStatus.java`
- Create: `src/main/java/com/example/demo/curation/model/CandidateVerdict.java`
- Create: `src/main/java/com/example/demo/curation/model/CandidateStatus.java`
- Create: `src/main/java/com/example/demo/curation/repository/PersonPublicationCandidateRepository.java`
- Test: `src/test/java/com/example/demo/curation/model/PersonPublicationCandidateRepositoryTest.java`

**Interfaces:**
- Consumes: `Persona.uuid`
- Produces: `PersonPublicationCandidateRepository` con consultas:
  - `Optional<PersonPublicationCandidate> findByPersonUuidAndDoi(String personUuid, String doi)`
  - `List<PersonPublicationCandidate> findByPersonUuid(String personUuid)`
  - `List<PersonPublicationCandidate> findByPersonUuidAndStatus(String personUuid, CandidateStatus status)`
  - `List<PersonPublicationCandidate> findByDepartmentIdAndStatus(String departmentId, CandidateStatus status)`

- [ ] **Step 1: Write the failing test**
Crear prueba unitaria `PersonPublicationCandidateRepositoryTest` que verifique la persistencia de un candidato, la búsqueda por `(personUuid, doi)` y que no permita duplicados en el mismo par `(personUuid, doi)`.

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=PersonPublicationCandidateRepositoryTest`  
Expected: FAIL con símbolos no encontrados.

- [ ] **Step 3: Implement enums y entidad `PersonPublicationCandidate`**
Crear los enums (`AffiliationStatus`, `CandidateVerdict`, `CandidateStatus`) y la entidad `@Document(collection = "PersonPublicationCandidates")` con los campos especificados: `id`, `personUuid`, `personName`, `departmentId`, `departmentName`, `doi`, `title`, `year`, `venue`, `openAlexWorkId`, `affiliationStatus`, `matchedAffiliations`, `coauthorSignals`, `verdict`, `confidenceScore`, `reasons`, `status`, `reviewedBy`, `reviewedAt`. Configurar `@CompoundIndex(def = "{'personUuid': 1, 'doi': 1}", unique = true)`.

- [ ] **Step 4: Implement repository interface**
Definir `PersonPublicationCandidateRepository extends MongoRepository<PersonPublicationCandidate, String>`.

- [ ] **Step 5: Run test to verify it passes**
Run: `./mvnw test -Dtest=PersonPublicationCandidateRepositoryTest`  
Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/example/demo/curation/model/ src/main/java/com/example/demo/curation/repository/ src/test/java/com/example/demo/curation/model/
git commit -m "feat(curation): add PersonPublicationCandidate entity and repository"
```

---

### Task 2: Servicio de Normalización y Deduplicación (`PublicationDeduplicationService`)

**Files:**
- Create: `src/main/java/com/example/demo/curation/service/PublicationDeduplicationService.java`
- Test: `src/test/java/com/example/demo/curation/service/PublicationDeduplicationServiceTest.java`

**Interfaces:**
- Consumes: `Publicacion` (`Researchoutputs`), `PersonPublicationCandidateRepository`
- Produces: `boolean isAlreadyKnown(String personUuid, String rawDoi, String rawTitle, List<Publicacion> existingEgretaWorks)`
- Produces: `String normalizeDoi(String rawDoi)`
- Produces: `double calculateTitleSimilarity(String title1, String title2)`

- [ ] **Step 1: Write the failing test**
Crear `PublicationDeduplicationServiceTest` con casos para:
  - Normalización de DOIs (`" 10.1016/j.cell.2020 "`, `"https://doi.org/10.1016/J.CELL.2020"` -> `"10.1016/j.cell.2020"`).
  - Coincidencia exacta por DOI frente a publicaciones de EGRETA.
  - Coincidencia difusa por título con Jaccard > 0.85 cuando falta DOI.
  - Exclusión si ya existe en `PersonPublicationCandidateRepository` (sea pendiente o descartada).

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=PublicationDeduplicationServiceTest`  
Expected: FAIL con clase no encontrada.

- [ ] **Step 3: Implement `PublicationDeduplicationService`**
Implementar normalización de DOI (limpieza de espacios, lowercase, eliminación de prefijos `https://doi.org/`, `http://dx.doi.org/`, `doi:`). Implementar tokenización y cálculo de similitud Jaccard para títulos. Implementar `isAlreadyKnown` integrando la consulta al repositorio de candidatos.

- [ ] **Step 4: Run test to verify it passes**
Run: `./mvnw test -Dtest=PublicationDeduplicationServiceTest`  
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/example/demo/curation/service/PublicationDeduplicationService.java src/test/java/com/example/demo/curation/service/PublicationDeduplicationServiceTest.java
git commit -m "feat(curation): implement PublicationDeduplicationService with DOI and title normalization"
```

---

### Task 3: Catálogo de Afiliaciones UAB y Motor de Scoring (`AuthorAffiliationValidatorService`)

**Files:**
- Create: `src/main/java/com/example/demo/curation/util/UabAffiliationCatalog.java`
- Create: `src/main/java/com/example/demo/curation/service/AuthorAffiliationValidatorService.java`
- Test: `src/test/java/com/example/demo/curation/service/AuthorAffiliationValidatorServiceTest.java`

**Interfaces:**
- Consumes: Metadatos extraídos de la obra (OpenAlex / Crossref), autor en EGRETA, red de coautores habituales de EGRETA.
- Produces: `PersonPublicationCandidate evaluateCandidate(...)` con puntuación calculada, razones detalladas y veredicto del semáforo (🟢/🟡/🔴).

- [ ] **Step 1: Write the failing test**
Crear `AuthorAffiliationValidatorServiceTest` verificando:
  - Obra con firma "Universitat Autònoma de Barcelona" y coautor conocido de EGRETA -> `score >= 80`, `HIGH_CONFIDENCE_UAB` (🟢).
  - Obra de hospital universitario vinculado ("Hospital Universitari Vall d'Hebron") -> Detecta afiliación UAB esfera.
  - Obra con firma explícita en otra institución ("Universidad Complutense de Madrid") sin coautores UAB -> `score < 40`, `LIKELY_FALSE_POSITIVE` (🔴).
  - Obra sin afiliación declarada pero con ORCID coincidente -> `REQUIRES_REVIEW` (🟡).

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=AuthorAffiliationValidatorServiceTest`  
Expected: FAIL con clases no encontradas.

- [ ] **Step 3: Implement `UabAffiliationCatalog`**
Crear catálogo constante / configurable con:
  - ROR institucional: `052gg0110`, OpenAlex: `I123044942`.
  - Expresiones canónicas UAB: `"autonoma de barcelona"`, `"autonomous university of barcelona"`, `"uab"`, `"bellaterra"`, `"08193"`.
  - Hospitales: `"vall d'hebron"`, `"germans trias"`, `"santa creu i sant pau"`, `"parc tauli"`.
  - Centros consorciados / CERCA: `"icmab"`, `"icn2"`, `"ifae"`, `"crag"`, `"cvc"`, `"icta"`, `"iiia"`.
  - Método `AffiliationMatch matchAffiliation(List<String> rawAffiliationStrings, List<String> institutionIds)`.

- [ ] **Step 4: Implement `AuthorAffiliationValidatorService`**
Implementar el algoritmo de puntuación (+40 afiliación UAB, +25 coautor conocido, +20 ORCID, +10 área coincidente; -50 afiliación ajena, -20 disciplina ajena). Asignar semáforo `HIGH_CONFIDENCE_UAB` (>= 80), `REQUIRES_REVIEW` (40-79), `LIKELY_FALSE_POSITIVE` (< 40) y redactar las explicaciones textuales en el campo `reasons`.

- [ ] **Step 5: Run test to verify it passes**
Run: `./mvnw test -Dtest=AuthorAffiliationValidatorServiceTest`  
Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/example/demo/curation/util/UabAffiliationCatalog.java src/main/java/com/example/demo/curation/service/AuthorAffiliationValidatorService.java src/test/java/com/example/demo/curation/service/AuthorAffiliationValidatorServiceTest.java
git commit -m "feat(curation): implement UabAffiliationCatalog and AuthorAffiliationValidatorService"
```

---

### Task 4: Cliente OpenAlex y Triangulación Crossref (`OpenAlexWorksClient`)

**Files:**
- Create: `src/main/java/com/example/demo/curation/adapter/OpenAlexWorksClient.java`
- Create: `src/main/java/com/example/demo/curation/adapter/CrossrefAffiliationEnricher.java`
- Create: `src/main/java/com/example/demo/curation/model/OpenAlexWorkDto.java`
- Test: `src/test/java/com/example/demo/curation/adapter/OpenAlexWorksClientTest.java`

**Interfaces:**
- Consumes: Parámetros del autor (ORCID, nombre, variantes)
- Produces: `List<OpenAlexWorkDto> fetchWorksByAuthor(String orcid, String authorName, boolean strictOrcid)`
- Produces: `Optional<List<String>> enrichAffiliationsFromCrossref(String doi)`

- [ ] **Step 1: Write the failing test**
Crear `OpenAlexWorksClientTest` usando mocks HTTP o WireMock para simular respuesta JSON de OpenAlex `/works?filter=author.orcid:...` y verificar extracción de DOIs, títulos, años, autores, conceptos y afiliaciones textuales.

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=OpenAlexWorksClientTest`  
Expected: FAIL.

- [ ] **Step 3: Implement `OpenAlexWorksClient` y `CrossrefAffiliationEnricher`**
Implementar llamadas HTTP GET a `https://api.openalex.org/works` con cabeceras de cortesía (`User-Agent: Spring-EGRETA-Curation/1.0 (mailto:bibliometria@uab.cat)`), soporte de paginación por cursor y deserialización a `OpenAlexWorkDto`. Implementar consulta de enriquecimiento a Crossref (`https://api.crossref.org/works/{doi}`) cuando la afiliación en OpenAlex no esté especificada.

- [ ] **Step 4: Run test to verify it passes**
Run: `./mvnw test -Dtest=OpenAlexWorksClientTest`  
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/example/demo/curation/adapter/ src/main/java/com/example/demo/curation/model/OpenAlexWorkDto.java src/test/java/com/example/demo/curation/adapter/
git commit -m "feat(curation): add OpenAlexWorksClient and CrossrefAffiliationEnricher"
```

---

### Task 5: Orquestador de Conciliación Individual (`OpenAlexReconciliationService`)

**Files:**
- Create: `src/main/java/com/example/demo/curation/service/OpenAlexReconciliationService.java`
- Test: `src/test/java/com/example/demo/curation/service/OpenAlexReconciliationServiceTest.java`

**Interfaces:**
- Consumes: `PersonaRepository`, `PublicacionRepository`, `OpenAlexWorksClient`, `PublicationDeduplicationService`, `AuthorAffiliationValidatorService`, `PersonPublicationCandidateRepository`
- Produces: `List<PersonPublicationCandidate> scanPerson(String personUuid, boolean strictOrcidOnly)`

- [ ] **Step 1: Write the failing test**
Crear `OpenAlexReconciliationServiceTest` simulando una persona de EGRETA con 2 publicaciones previas, recuperando 3 publicaciones de OpenAlex (1 ya existente, 1 nueva con firma UAB, 1 de un homónimo en otra universidad). Verificar que se descarte la existente y se creen 2 candidatas en MongoDB con sus respectivos semáforos (🟢 y 🔴).

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=OpenAlexReconciliationServiceTest`  
Expected: FAIL.

- [ ] **Step 3: Implement `OpenAlexReconciliationService`**
Coordinar la recuperación de la persona, extracción de coautores previos desde `PublicacionRepository`, llamada a `OpenAlexWorksClient`, filtrado por `PublicationDeduplicationService`, evaluación en `AuthorAffiliationValidatorService` y persistencia en `PersonPublicationCandidateRepository`. Devolver la lista completa de candidatas activas para esa persona.

- [ ] **Step 4: Run test to verify it passes**
Run: `./mvnw test -Dtest=OpenAlexReconciliationServiceTest`  
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/example/demo/curation/service/OpenAlexReconciliationService.java src/test/java/com/example/demo/curation/service/OpenAlexReconciliationServiceTest.java
git commit -m "feat(curation): implement OpenAlexReconciliationService for individual author scanning"
```

---

### Task 6: Coordinador Asíncrono por Lotes (`BatchReconciliationService`)

**Files:**
- Create: `src/main/java/com/example/demo/curation/service/BatchReconciliationService.java`
- Create: `src/main/java/com/example/demo/curation/model/BatchScanRequest.java`
- Create: `src/main/java/com/example/demo/curation/model/BatchScanProgressDto.java`
- Test: `src/test/java/com/example/demo/curation/service/BatchReconciliationServiceTest.java`

**Interfaces:**
- Consumes: `OpenAlexReconciliationService`, `ReconciliationJobRegistry`, `PersonaRepository`
- Produces: `String launchBatchScan(BatchScanRequest request)`
- Produces: `BatchScanProgressDto getBatchProgress(String jobId)`

- [ ] **Step 1: Write the failing test**
Crear `BatchReconciliationServiceTest` simulando un lote para un departamento con 3 investigadores. Verificar que se genere un `jobId`, que el progreso se actualice (`processedCount`, `greenCount`, `yellowCount`, `redCount`) y que si un autor lanza excepción, los otros dos terminen satisfactoriamente con la incidencia registrada.

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=BatchReconciliationServiceTest`  
Expected: FAIL.

- [ ] **Step 3: Implement `BatchReconciliationService`**
Implementar registro de trabajo asíncrono con `ReconciliationJobRegistry`, ejecución en hilo de background mediante `ExecutorService`, pausa de cortesía entre autores (ej. 200ms) para respetar rate-limits, control de contadores (🟢/🟡/🔴) y captura de errores individuales por autor.

- [ ] **Step 4: Run test to verify it passes**
Run: `./mvnw test -Dtest=BatchReconciliationServiceTest`  
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/example/demo/curation/service/BatchReconciliationService.java src/main/java/com/example/demo/curation/model/BatchScan* src/test/java/com/example/demo/curation/service/BatchReconciliationServiceTest.java
git commit -m "feat(curation): implement BatchReconciliationService with async progress tracking"
```

---

### Task 7: Controladores REST y Operativa de Curación Masiva e Individual

**Files:**
- Create: `src/main/java/com/example/demo/curation/controller/CurationReconciliationController.java`
- Create: `src/main/java/com/example/demo/curation/model/CandidateStatusUpdateRequest.java`
- Create: `src/main/java/com/example/demo/curation/model/BulkCandidateActionRequest.java`
- Test: `src/test/java/com/example/demo/curation/controller/CurationReconciliationControllerTest.java`

**Interfaces:**
- Endpoints:
  - `POST /api/curation/authors/{personUuid}/scan` -> Escaneo individual en tiempo real.
  - `GET /api/curation/authors/{personUuid}/candidates` -> Listado de candidatas filtrado por estado y semáforo.
  - `PUT /api/curation/candidates/{candidateId}/status` -> Actualizar decisión (`APPROVED_IMPORT`, `REJECTED_HOMONYM`, etc.).
  - `POST /api/curation/candidates/bulk-action` -> Acción masiva (aprobar o descartar lista de IDs).
  - `POST /api/curation/batch-scan` -> Iniciar trabajo departamental en segundo plano.
  - `GET /api/curation/batch-scan/{jobId}/progress` -> Estado del trabajo por lotes.
  - `GET /api/curation/candidates/export` -> Exportación CSV de candidatos y evidencias.

- [ ] **Step 1: Write the failing test**
Crear `CurationReconciliationControllerTest` con `@WebMvcTest` verificando respuestas HTTP 200 para escaneo individual, actualización de estado de candidato, acción por lotes y consulta de progreso de job.

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=CurationReconciliationControllerTest`  
Expected: FAIL.

- [ ] **Step 3: Implement `CurationReconciliationController`**
Implementar los endpoints REST con validación de entradas, manejo de respuestas DTO e integración con los servicios `OpenAlexReconciliationService`, `BatchReconciliationService` y `PersonPublicationCandidateRepository`. Implementar endpoint de exportación CSV con cabeceras `(personName, doi, title, year, affiliationStatus, matchedAffiliations, verdict, status)`.

- [ ] **Step 4: Run test to verify it passes**
Run: `./mvnw test -Dtest=CurationReconciliationControllerTest`  
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/example/demo/curation/controller/ src/main/java/com/example/demo/curation/model/ src/test/java/com/example/demo/curation/controller/
git commit -m "feat(curation): implement REST endpoints for individual and batch reconciliation"
```
