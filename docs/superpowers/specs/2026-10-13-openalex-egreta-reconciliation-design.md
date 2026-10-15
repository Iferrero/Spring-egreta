# Especificación de Diseño: Motor de Conciliación Bibliométrica OpenAlex - EGRETA

**Fecha:** 2026-10-13  
**Estado:** Validado con el usuario / Pendiente de plan de implementación  
**Rol de Usuario:** Analista de Bibliometría (UAB)  

---

## 1. Visión General y Objetivos

### 1.1 Contexto y Necesidad
En el ámbito de la gestión y análisis bibliométrico de la Universitat Autònoma de Barcelona (UAB), existe la necesidad de comparar la producción científica registrada en el sistema CRIS institucional (EGRETA) con fuentes abiertas globales, concretamente **OpenAlex**, para identificar publicaciones faltantes que deban incorporarse a EGRETA.

### 1.2 El Problema Bibliométrico
Los algoritmos de desambiguación de autores en OpenAlex presentan dos fuentes frecuentes de error:
1. **Falsos positivos por homonimia:** Publicaciones asignadas erróneamente a un investigador de la UAB cuando en realidad pertenecen a otra persona física homónima que trabaja en otra institución nacional o internacional.
2. **Afiliación institucional no atribuible:** Publicaciones que sí pertenecen a la persona pero fueron realizadas en periodos anteriores o estancias en instituciones ajenas a la UAB, sin firma institucional de la UAB o de sus centros e institutos vinculados.

### 1.3 Objetivo del Sistema
Proporcionar un mecanismo asistido, ágil y transparente que:
- Identifique obras en OpenAlex atribuidas a un investigador que no consten en EGRETA.
- Triangule automáticamente la autoría y la afiliación institucional mediante fuentes secundarias (Crossref, PubMed, ROR UAB, red histórica de coautores y áreas científicas).
- Clasifique los resultados mediante un semáforo de confianza explicable (🟢 Alta certeza UAB, 🟡 Dudas / Revisión requerida, 🔴 Falsos positivos claros).
- Permita la curación tanto en **modo individual en tiempo real** (en la ficha del autor) como en **modo por lotes / batch** (para departamentos completos con acciones masivas en un clic).
- Registre de forma permanente las decisiones (aprobadas o descartadas) para evitar duplicar trabajo en revisiones futuras.

---

## 2. Modelo de Datos y Ciclo de Vida

### 2.1 Entidad `PersonPublicationCandidate` (Colección MongoDB)
Se crea una colección dedicada en MongoDB (`PersonPublicationCandidate`) para almacenar las publicaciones detectadas y el estado de la auditoría.

#### Atributos Principales:
* **`id` / `uuid`**: Identificador único del registro candidato.
* **`personUuid`**: UUID del investigador en EGRETA (`Persona.uuid`).
* **`personName`**: Nombre completo del autor en el momento del escaneo.
* **`departmentId`** / **`departmentName`**: Unidad organizativa o departamento del autor.
* **`doi`**: Identificador de objeto digital normalizado (minúsculas, sin protocolo ni prefijo `https://doi.org/`).
* **`title`**: Título de la publicación.
* **`year`**: Año de publicación (entero).
* **`venue`**: Nombre de la revista, congreso o libro.
* **`openAlexWorkId`**: URI o ID del trabajo en OpenAlex (ej. `https://openalex.org/W...`).
* **`affiliationStatus`**: Estado inferido de la afiliación (`CONFIRMED_UAB`, `OTHER_INSTITUTION`, `AMBIGUOUS_OR_MISSING`).
* **`matchedAffiliations`**: Lista de cadenas textuales o RORs vinculados a la UAB detectados.
* **`coauthorSignals`**: Resumen de coautores, indicando si existen coautores habituales del autor en EGRETA.
* **`verdict`**: Clasificación del semáforo:
  * `HIGH_CONFIDENCE_UAB` (🟢)
  * `REQUIRES_REVIEW` (🟡)
  * `LIKELY_FALSE_POSITIVE` (🔴)
* **`confidenceScore`**: Valor numérico de 0 a 100.
* **`reasons`**: Lista de explicaciones textuales legibles generadas por el motor de reglas.
* **`status`**: Estado del flujo de curación:
  * `PENDING`: Pendiente de revisión por el analista.
  * `APPROVED_IMPORT`: Aprobado para importación a EGRETA.
  * `REJECTED_HOMONYM`: Descartado (persona distinta / homónimo).
  * `REJECTED_NOT_UAB`: Descartado (misma persona pero no atribuible a la UAB).
  * `DISMISSED`: Descarte genérico.
* **`reviewedBy`**: Usuario / analista que tomó la decisión.
* **`reviewedAt`**: Marca temporal de la resolución.

#### Índices de Rendimiento y Unicidad:
* Índice compuesto único: `{"personUuid": 1, "doi": 1}` — impide duplicados y garantiza la memoria permanente de descartes por persona.
* Índice de consulta rápida: `{"personUuid": 1, "status": 1, "verdict": 1}`.
* Índice departamental: `{"departmentId": 1, "status": 1}`.

---

## 3. Motor de Triangulación y Reglas de Scoring

### 3.1 Diccionario de Afiliaciones de la Esfera UAB
El motor verifica si la cadena de afiliación o los identificadores institucionales corresponden a la UAB o a sus instituciones participadas/consorciadas:
* **Entidad Matriz:** ROR `https://ror.org/052gg0110`, OpenAlex `I123044942`. Textos: `"Universitat Autònoma de Barcelona"`, `"Universitat Autonoma de Barcelona"`, `"Autonomous University of Barcelona"`, `"UAB"`, `"Campus de Bellaterra"`, CP `"08193"`.
* **Hospitales Universitarios Vinculados:** Hospital Universitari Vall d'Hebron, Hospital de la Santa Creu i Sant Pau, Hospital Universitari Germans Trias i Pujol, Parc Taulí de Sabadell.
* **Institutos de Investigación Esfera UAB / CSIC / CERCA:** ICMAB, ICN2, IFAE, CRAG, CVC, ICTA-UAB, IIIA-CSIC, IBB, etc.

### 3.2 Proceso de Triangulación Multi-Fuente
1. **Extracción en OpenAlex:** Inspección del objeto `authorships` correspondiente al autor en el trabajo (comprobando instituciones vinculadas y `raw_affiliation_strings`).
2. **Triangulación en Crossref:** Si la afiliación en OpenAlex no es concluyente o carece de texto literal, se consulta `/works/{doi}` en Crossref para leer el array de afiliaciones del publisher.
3. **Triangulación en PubMed:** En publicaciones con PMID, se analiza la afiliación indexada por NLM.
4. **Coautoría Histórica:** Se comprueba si alguno de los coautores del paper coincide con la lista histórica de colaboradores del investigador en EGRETA (`Researchoutputs`).
5. **Consistencia Temática:** Comparación de `topics`/`concepts` del paper frente al histórico del investigador.

### 3.3 Algoritmo de Puntuación
* **Puntuación Base:**
  * `+40 puntos`: Afiliación institucional explícita a la UAB o centros de su esfera.
  * `+25 puntos`: Al menos un coautor coincidente con historial previo en EGRETA.
  * `+20 puntos`: ORCID verificado en la publicación o coincidente.
  * `+10 puntos`: Coincidencia temática en subdisciplina científica.
* **Penalizaciones:**
  * `-50 puntos`: Afiliación explícita ajena a otra universidad/centro sin vínculo con la UAB.
  * `-20 puntos`: Disciplina científica totalmente disjunta sin coautores coincidentes.

### 3.4 Umbrales del Semáforo
* **🟢 Alta Certeza UAB (`score >= 80`):** Firma UAB verificada con respaldo de ORCID o coautoría.
* **🟡 Dudas / Revisión Requerida (`40 <= score < 80`):** Posible etapa externa (postdoc, contrato previo), o firma ambigua/ausente en metadatos.
* **🔴 Falso Positivo Claro (`score < 40`):** Firma explícita en otra entidad, sin coautores UAB ni relación temática.

---

## 4. Arquitectura de Servicios y Procesamiento

### 4.1 Componentes del Sistema
* **`OpenAlexWorksClient`**: Cliente HTTP con límite de tasa (máx. 10 req/s), cabeceras de cortesía (*polite pool* mailto) y soporte de paginación por cursor.
* **`PublicationDeduplicationService`**:
  * Compara obras recuperadas contra las publicaciones existentes del autor en EGRETA (`Researchoutputs` con match exacto de DOI normalizado o similitud de título Jaccard > 0.85).
  * Excluye publicaciones que ya existan en `PersonPublicationCandidate` (garantizando memoria de descartes).
* **`AuthorAffiliationValidatorService`**: Motor de scoring y triangulación multi-fuente.
* **`ReconciliationJobRegistry`**: Coordinador de trabajos asíncronos en segundo plano para escaneos por lotes, controlando porcentaje de avance, tiempos y errores no bloqueantes.

### 4.2 Modos de Ejecución

#### Modo Individual (Fast-Path en Tiempo Real)
* **Endpoint:** `POST /api/curation/authors/{personUuid}/scan`
* **Latencia estimada:** 2 a 4 segundos.
* **Flujo:** Recupera publicaciones del autor en OpenAlex, descarta las ya presentes en EGRETA o en la base de candidatos, evalúa las nuevas mediante el validador, las persiste como `PENDING` y devuelve la lista completa al frontend.

#### Modo Lotes (Batch Asíncrono Departamental)
* **Endpoint:** `POST /api/curation/batch-scan`
* **Parámetros:** `departmentId`, `facultyId` o lista de `personUuids`.
* **Monitoreo:** `GET /api/curation/batch-scan/{jobId}/progress`.
* **Flujo:** Procesa investigador por investigador secuencialmente con pausas de cortesía; cada error puntual se anota en el registro del trabajo y se prosigue con el siguiente sin abortar el lote.

---

## 5. Experiencia del Analista y Operativa

### 5.1 Ficha Individual del Investigador
* Cabecera con datos del investigador, ORCID y botón `[🔄 Escanear OpenAlex ahora]`.
* Indicadores visuales de resumen (`🟢 X Listas | 🟡 Y Dudas | 🔴 Z Falsos positivos`).
* Tres pestañas de gestión:
  1. **Candidatas Claras (🟢):** Botón `[✅ Incorporar todas a EGRETA]` o aceptación individual.
  2. **Dudas / Revisión (🟡):** Fichas con explicación de la duda y 3 botones rápidos:
     - `[Aceptar como mérito]`
     - `[Descartar como homónimo]`
     - `[Descartar como no atribuible a UAB]`
  3. **Falsos Positivos (🔴):** Visualización compacta con botón `[🗑️ Descartar todas en bloque]`.

### 5.2 Bandeja Departamental (Curation Inbox)
* Selector de departamento y botón `[🚀 Iniciar Escaneo Departamental]`.
* Tabla consolidada con filtros por investigador, año, semáforo y estado.
* Acciones masivas:
  * `[Aprobar seleccionadas para importación]`.
  * `[Descartar seleccionadas permanentemente]`.
* Exportación de informe bibliométrico a Excel / CSV con todas las evidencias (DOI, autor, firma textual detectada, fuente, puntuación y veredicto).

### 5.3 Modo Estricto por ORCID
* En casos de investigadores con nombres muy frecuentes cuyos perfiles en OpenAlex estén severamente contaminados por homónimos globales, el analista puede activar la casilla *"Solo obras con ORCID verificado"*, evitando la búsqueda laxa por variantes de nombre.

---

## 6. Verificación y Casos de Prueba

1. **Prueba de deduplicación:** Si un DOI ya consta en EGRETA para esa persona, no debe registrarse como candidata.
2. **Prueba de memoria permanente:** Si una publicación fue marcada previamente como `REJECTED_HOMONYM`, futuros escaneos individuales o departamentales no deben volver a sugerirla.
3. **Prueba de detección de firma UAB:** Un paper con firma de "Universitat Autònoma de Barcelona" o "Hospital Universitari Vall d'Hebron" debe recibir la bonificación de afiliación UAB.
4. **Prueba de falso positivo claro:** Un paper con firma exclusiva en "Universidad de Salamanca" y sin coautores de la UAB debe clasificarse como 🔴 (`LIKELY_FALSE_POSITIVE`).
5. **Prueba de tolerancia a fallos en lotes:** Si un autor produce un error de timeout en la API externa durante un lote de 50 investigadores, los 49 restantes deben procesarse con éxito y el informe de progreso debe reflejar 1 incidencia.
