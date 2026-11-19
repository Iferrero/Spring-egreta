# Disseny: Validador d'Equips PDI per a Sol·licituds del Pla Nacional (AEI / MICIU)

**Data:** 2026-11-19  
**Estat:** Pendent de revisió de l'especificació  
**Idioma de la interfície:** Català (`ca`)  

---

## 1. Objectiu i Abast

Construir una eina web amb càrrega *drag & drop* per lots que permeti al personal de gestió de la recerca validar l'elegibilitat de l'equip de recerca de sol·licituds oficials del Pla Nacional (AEI / MICIU — Projectes de Generació de Coneixement / PID) creuant les dades extretes del PDF amb la situació contractual actual del personal a la col·lecció `Persons` d'Egreta (MongoDB `kraken`).

### Principis i abast aprovat
- **Processament 100% en memòria (Enfocament 1):** Els PDFs, els NIFs i els resultats de la comprovació es processen exclusivament en memòria durant la petició HTTP i a la sessió del navegador. No es desa cap fitxer PDF, cap dada personal ni cap històric de validacions a disc ni a MongoDB.
- **Format d'entrada:** Formularis oficials de sol·licitud generats per la seu electrònica de l'AEI / MICIU (1 o múltiples PDFs arrossegats en lot).
- **Regles incloses (criteris 1–4):**
  1. Categoria laboral no elegible segons el rol (`IP`, `Equip d'Investigació`, `Equip de Treball`).
  2. Professorat emèrit en el darrer any de contracte (≤ 12 mesos) o amb venciment anterior a la fi del projecte, i contractes temporals que finalitzen abans de la fi prevista del projecte.
  3. Manca de contracte vigent a Egreta a la data de referència.
  4. Diferenciació entre personal extern (entitat al PDF diferent de la UAB) i personal UAB no trobat o amb coincidència ambigua.
- **Fora d'abast (YAGNI):** Comprovació d'incompatibilitat / participació simultània en projectes actius a `Awards`, control de dedicació a temps parcial (FTE), comprovació d'edat de jubilació (`dateOfBirth`), edició manual de membres a la UI i persistència d'històrics de lots a MongoDB.

---

## 2. Context de Dades Verificat a `kraken.Persons`

L'esquema real de la col·lecció `Persons` a MongoDB (`kraken`, 25.990 documents totals, 6.384 persones amb vinculació activa) presenta les característiques següents que condicionen el disseny:
- **Cobertura d'identificadors legals:** 6.358 de les 6.384 persones actives (99,6%) disposen de document identificatiu dins `identifiers[]` amb tipus `DNI`, `NIE`, `NIF/NIE/Passaport` o `Passaport`.
- **Format de dates:** `period.startDate` i `period.endDate` es desen principalment com a `String` ISO `YYYY-MM-DD` (120.068 `String` i 5.336 `null` verificats) i puntualment poden aparèixer com a tipus `Date`.
- **Associacions secundàries d'institut:** Molts investigadors tenen una associació vigent amb `employmentType.term.ca_ES = "Adscripció a recerca"` (2.028 registres actius) a més del seu contracte laboral real al departament. Aquesta adscripció s'ha de filtrar/relegar en seleccionar el contracte principal.
- **Personal investigador extern vinculat (ICREA / CIBER / Vinculats):** Es registra dins l'array `visitingScholarOrganizationAssociations` amb `jobTitle.term.ca_ES` (`Personal investigador ICREA`, `Personal investigador CIBER`, `Personal investigador vinculat`).
- **Professorat emèrit:** Tots els 52 emèrits actius figuren amb `employmentType.term.ca_ES = "Professorat emèrit laboral"`, `contractType.term.ca_ES = "Emèrit/a"` i `period.endDate` informat (contractes trianuals).

---

## 3. Arquitectura Backend (`com.example.demo.plannacional`)

### 3.1 Dependències i Propietats
- **Nova dependència Maven (`pom.xml`):** `org.apache.pdfbox:pdfbox:3.0.4` (compatible amb Java 21 i Spring Boot 4.1.2).
- **Propietats a `application.properties`:**
  - `app.plannacional.vllm-fallback.enabled=true` (reutilitza `app.vllm.url` i `app.vllm.model` ja configurats per al servidor local vLLM).
  - Els límits multipart existents (`spring.servlet.multipart.max-file-size=50MB` i `max-request-size=100MB`) cobreixen la càrrega de PDFs.

### 3.2 Model de Dades i DTOs (`com.example.demo.plannacional.model`)

#### Enums immutables
- `MemberRole`: `IP`, `EQUIPO_INVESTIGACION`, `EQUIPO_TRABAJO`
- `EligibilityStatus`: `OK`, `AVIS`, `NO_OK`, `EXTERN`
- `MatchConfidence`: `NIF_EXACTE`, `NOM_EXACTE`, `NOM_APROXIMAT`, `AMBIGU`, `NO_TROBAT`
- `ExtractionMethod`: `AEI_PARSER`, `VLLM_FALLBACK`
- `EmploymentCategory`: `PDI_ESTABLE`, `PDI_TEMPORAL`, `EMERIT`, `ASSOCIAT`, `SUBSTITUT`, `PREDOCTORAL`, `PTGAS`, `HONORARI_JUBILAT`, `ADSCRIPCIO_RECERCA`, `DESCONEGUDA`

#### Records / DTOs de resposta i petició
- `ExtractedMember(MemberRole role, String fullName, String firstName, String lastName, String nif, String entity, String declaredLinkage, String rawLine)`
- `ExtractedProposal(String fileName, String projectCode, String acronym, String title, Integer durationYears, LocalDate expectedStartDate, LocalDate expectedEndDate, String applicantEntity, ExtractionMethod extractionMethod, List<String> warnings, String rawTextPreview, List<ExtractedMember> members)` — on `rawTextPreview` s'acota als primers 4.000 caràcters del text extret.
- `ContractSnapshot(String source, String organizationName, String organizationUuid, String employmentType, String contractType, String staffType, LocalDate startDate, LocalDate endDate, boolean primaryAssociation, boolean researchAffiliationOnly)` — on `source` pren el valor `"STAFF"` o `"VISITING"`.
- `PersonCandidateSummary(String uuid, Long pureId, String fullName, String activeEmploymentType, String department)`
- `MemberValidationResult(ExtractedMember extractedMember, MatchConfidence matchConfidence, String matchedPersonUuid, Long matchedPureId, String matchedFullName, String egretaEditorUrl, ContractSnapshot primaryContract, List<ContractSnapshot> activeContracts, ContractSnapshot latestHistoricalContract, List<PersonCandidateSummary> ambiguousCandidates, EmploymentCategory employmentCategory, EligibilityStatus status, String reasonCode, String reasonMessage)`
- `SummaryCounts(int total, int ok, int avis, int noOk, int extern)`
- `ProposalValidationResponse(String fileName, String projectCode, String acronym, String title, Integer durationYears, LocalDate expectedStartDate, LocalDate expectedEndDate, String applicantEntity, ExtractionMethod extractionMethod, LocalDate referenceDate, int effectiveDurationYears, LocalDate effectiveProjectEndDate, EligibilityStatus overallStatus, SummaryCounts summaryCounts, List<MemberValidationResult> members, List<String> extractionWarnings, String rawTextPreview)`
- `ReevaluateTeamRequest(ExtractedProposal proposal, LocalDate referenceDate, Integer durationYears)`
- `RuleDescription(String code, String title, String ipRule, String eiRule, String etRule, String notes)`
- `CategoryDefinition(EmploymentCategory category, String label, String description, List<String> examples)`
- `RuleCatalogResponse(int defaultDurationYears, int emeritWarningMonths, List<RuleDescription> rules, List<CategoryDefinition> categoryDefinitions)`

### 3.3 Extracció i Parseig de PDFs (`com.example.demo.plannacional.parser`)

- **`PdfTextExtractor` (interfície) i `PdfBoxTextExtractor` (implementació):**
  - Llegeix els bytes directament en memòria amb `Loader.loadPDF(byte[])` d'Apache PDFBox.
  - Valida que el fitxer no sigui buit i comenci amb la signatura `%PDF-`. Llança `InvalidPdfException` si el document és invàlid, corrupte o protegit amb contrasenya sense permisos d'extracció.
  - Extreu el text pàgina per pàgina amb `PDFTextStripper` (`setSortByPosition(true)`).
  - Si un PDF té pàgines però el text extret conté menys de 80 caràcters alfanumèrics útils, llança `ScannedPdfException` indicant que el PDF és una imatge escanejada sense capa de text.

- **`AeiSolicitudParser`:**
  - Parser determinista basat en patrons estructurals per a formularis oficials de la seu electrònica de l'AEI / MICIU.
  - **Metadades de capçalera:** extreu codi/referència del projecte (p. ex. `PID\d{4}-[A-Z0-9-]+`, `CNS...` o etiquetes `Referencia` / `Código de solicitud`), acrònim, títol, entitat sol·licitant, durada en anys (ex. `3 años` / `4 años`) i dates d'inici/fi previstes si consten al formulari.
  - **Detecció de blocs d'equip:** normalitza salts de pàgina, capçaleres/peus repetits, accents i majúscules/minúscules per identificar els canvis de secció:
    - `MemberRole.IP`: `INVESTIGADOR/A PRINCIPAL`, `IP1`, `IP2`, `CO-INVESTIGADOR/A PRINCIPAL`.
    - `MemberRole.EQUIPO_INVESTIGACION`: `EQUIPO DE INVESTIGACIÓN`, `EQUIP D'INVESTIGACIÓ`.
    - `MemberRole.EQUIPO_TRABAJO`: `EQUIPO DE TRABAJO`, `EQUIP DE TREBALL`.
  - **Extracció de membres:** reconeix files tabulars o blocs per persona amb nom i cognoms (`"Cognoms, Nom"` o `"Nom Cognoms"`), NIF/NIE/passaport (incloent emmascarament parcial de la seu electrònica com `***1234**`), entitat d'adscripció i vinculació declarada. Conserva `rawLine` per al diagnòstic.

- **`VllmProposalFallbackParser`:**
  - Només s'executa quan el PDF conté text vàlid però `AeiSolicitudParser` obté 0 membres i `app.plannacional.vllm-fallback.enabled=true`.
  - Crida l'endpoint local compatible amb OpenAI (`app.vllm.url` / `app.vllm.model`) sol·licitant JSON estructurat de metadades i membres.
  - Si té èxit, marca `extractionMethod = ExtractionMethod.VLLM_FALLBACK` i afegeix l'avís `"Extret amb IA (format no estàndard) — reviseu el llistat"`. Si el servidor no respon o retorna JSON invàlid, degrada netament retornant 0 membres amb un avís explícit sense fallar la petició.

### 3.4 Cerca a Egreta i Selecció de Contracte (`com.example.demo.plannacional.lookup.PersonPdiLookupService`)

Consulta la col·lecció `Persons` de `kraken` mitjançant `MongoTemplate` amb projecció lleugera (`uuid`, `pureId`, `name`, `names`, `identifiers`, `retirementDate`, `staffOrganizationAssociations`, `visitingScholarOrganizationAssociations`).

#### Normalització i cascada d'emparellament (3 nivells)
1. **`NIF_EXACTE`:** Normalitza el document del PDF (majúscules, sense espais, guions ni punts) i cerca coincidència exacta dins `identifiers[]` (tipus `DNI`, `NIE`, `NIF/NIE/Passaport`, `Passaport`). Si el NIF del PDF ve parcialment emmascarat (p. ex. `***1234**` o `****1234*`), compara la subcadena visible en la mateixa posició sobre el NIF d'Egreta i confirma que almenys un cognom coincideixi.
2. **`NOM_EXACTE`:** Si no hi ha NIF o no s'ha trobat per NIF, normalitza nom i cognoms (descomposició NFKD sense accents, minúscules, sense puntuació, tolerant l'ordre `"Cognoms, Nom"` vs `"Nom Cognoms"` i partícules `de`, `del`, `la`, `las`, `los`, `i`, `y`, `d'`, `l'`).
3. **`NOM_APROXIMAT`:** Cerca candidats compartint els tokens principals de cognom i coincidència en nom o inicial.
   - Si existeix un únic candidat clar per sobre del llindar de similitud → `NOM_APROXIMAT`.
   - Si hi ha múltiples candidats sense desempat únic (tant en nom exacte amb homònims com en nom aproximat) → `MatchConfidence.AMBIGU` retornant fins a 5 `PersonCandidateSummary` dins `ambiguousCandidates`. Mai es tria un homònim a l'atzar.
   - Si no hi ha cap coincidència → `MatchConfidence.NO_TROBAT`.

#### Selecció del contracte vigent real a `referenceDate`
- Es considera vigent a `referenceDate` qualsevol associació dins `staffOrganizationAssociations` (`source = "STAFF"`) o `visitingScholarOrganizationAssociations` (`source = "VISITING"`) que compleixi:
  - `startDate == null || !startDate.isAfter(referenceDate)`
  - `endDate == null || !endDate.isBefore(referenceDate)`
- El parser de dates accepta tant `String` (`YYYY-MM-DD` o ISO-8601) com `java.util.Date`.
- **Filtrat d'`"Adscripció a recerca"`:** Les associacions amb `employmentType = "Adscripció a recerca"` i les associacions sense categoria laboral es releguen sempre que existeixi un contracte laboral o una vinculació `VISITING` vigent. Només si la persona únicament té `"Adscripció a recerca"` vigent s'assigna com a `primaryContract` amb `researchAffiliationOnly = true`.
- **Desempat entre múltiples contractes vigents:** Prioritza `primaryAssociation == true` i, a igualtat, la categoria més favorable per a elegibilitat; totes les associacions vigents s'inclouen a `activeContracts` per a transparència a la UI.
- **Sense contracte vigent:** Si cap associació és vigent a `referenceDate`, s'obté `latestHistoricalContract` ordenant totes les associacions per `endDate` descendent (i `startDate` descendent).
- **URL d'edició a Egreta:** Quan `pureId != null`, es genera:  
  `https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=<pureId>`

### 3.5 Motor de Regles d'Elegibilitat (`com.example.demo.plannacional.engine.PlanNacionalEligibilityEngine`)

Mòdul pur Java desacoblat de MongoDB i de Spring Web.

#### Càlcul de la data prevista de fi del projecte (`effectiveProjectEndDate`)
- `effectiveDurationYears`: valor explicitat pel gestor (`durationYears` de la petició) si està present; en defecte, `proposal.durationYears()` si consta al PDF; i si no, **`3` anys** per defecte.
- `effectiveProjectEndDate`: si el PDF aporta `expectedEndDate` i el gestor no ha forçat una durada a la petició, s'empra `expectedEndDate`; altrament `(expectedStartDate != null ? expectedStartDate : referenceDate).plusYears(effectiveDurationYears)`.

#### Classificació de categories laborals (`EmploymentCategory`)
Mapeig determinista sobre `employmentType`, `contractType` i `staffType` de `primaryContract` (normalitzat sense accents ni majúscules):

| Categoria (`EmploymentCategory`) | Patrons reals verificats a `kraken.Persons` |
|---|---|
| `PDI_ESTABLE` | `Catedràtic/a*`, `Catedratics*`, `Professorat titular*`, `Titular d'escoles universitàries`, `Agregat/da contractat doctor/a`, `Professor/a col·laborador/a permanent`, `Professor/a distingit/da`, `Investigador/a doctor distingit`, `Director/a investigació`, `Investigador/a ordinari/a`, `Investigador/a postdoctoral indefinit/da`, i vinculacions `VISITING`: `Personal investigador ICREA`, `Personal investigador CIBER`, `Personal investigador vinculat` |
| `PDI_TEMPORAL` | `Professor/a lector/a*`, `Ramón Y Cajal`, `Beatriz de Galindo`, `Beatriu de Pinós`, `Juan de La Cierva`, `M.S. Skłodowska-Curie`, `Margarita Salas`, `Maria Zambrano`, `Sara Borrell`, `Personal investigador postdoctoral projectes`, `Professor/a visitant*`, `Professorat laboral estranger`, `Agregat/da interí/na`, `Titular d'universitat interí/na`, i altres `postdoctoral*` no indefinits |
| `EMERIT` | `contractType == "Emèrit/a"` o `employmentType` contenint `emèrit` / `emerit` (`Professorat emèrit laboral`, `Professor/a emèrit/a*`) |
| `ASSOCIAT` | Qualsevol terme contenint `associat` (`Professor/a associat/da laboral`, `Associat/da mèdic/a laboral`, infermeria, fisioteràpia, psicologia, veterinari, logopeda, `Professor associat indefinit`, `Professors Associats TC`) |
| `SUBSTITUT` | `Professor/a substitut/a` |
| `PREDOCTORAL` | `Estudiant de doctorat`, `predoctoral`, `en formació` / `en formaci`, `FPI`, `FPU`, `FI-JOAN ORÓ`, `FI-SDUR`, `novell Catalunya`, `La Caixa`, `Govern Basc`, `Becari/ària`, `PIF` |
| `PTGAS` | `Tècnic/a*`, `TSR*`, `Administratiu/va*`, `Auxiliar*`, `Cos de gestió*`, `Cos tècnic*`, `Director/a de servei`, `Director/a d'oficina`, `Cap d'unitat`, `Bibliotecari*`, o `staffType == "No acadèmic"` sense altra categoria investigadora |
| `HONORARI_JUBILAT` | `Professor/a Honorari/a`, `Personal acadèmic jubilat` |
| `ADSCRIPCIO_RECERCA` | Només quan `researchAffiliationOnly == true` |
| `DESCONEGUDA` | Qualsevol altre valor no reconegut (mai s'assumeix `OK` en silenci) |

#### Detecció de personal extern vs UAB
- Si `extractedMember.entity` és present i no correspon a la UAB (`UAB`, `Universitat Autònoma de Barcelona`, `Universidad Autónoma de Barcelona`), i la persona **no** té contracte vigent a `kraken.Persons` a `referenceDate` (encara que tingui contractes històrics previs o no aparegui a `Persons`) → `EligibilityStatus.EXTERN` amb `reasonCode = "EXTERNAL_ENTITY"` i `reasonMessage = "Personal extern (<Entitat>) — no aplica control PDI UAB"`.
- Si al PDF consta una entitat externa però la persona **sí** té contracte vigent a `kraken.Persons` → s'avalua el seu contracte i, si el resultat base fos `OK`, s'eleva com a mínim a `EligibilityStatus.AVIS` (`"Consta com a extern al PDF (<Entitat>) però té contracte vigent a la UAB — reviseu afiliació"`); si el resultat base ja és `NO_OK`, es manté `NO_OK`.

#### Matriu de regles per rol (`IP`, `EQUIPO_INVESTIGACION`, `EQUIPO_TRABAJO`)

| Situació / Categoria | Com a `IP` | Com a `EQUIPO_INVESTIGACION` | Com a `EQUIPO_TRABAJO` | `reasonCode` / Motiu tipus (en català) |
|---|---|---|---|---|
| `PDI_ESTABLE` sense `endDate` o `endDate >= effectiveProjectEndDate` | `OK` | `OK` | `OK` | `ELIGIBLE_STABLE` — *"Contracte estable vigent (<cat>)"* *(si `matchConfidence == NOM_APROXIMAT`, passa a `AVIS` per confirmar identitat)* |
| `PDI_ESTABLE` amb `endDate < effectiveProjectEndDate` | `AVIS` | `AVIS` | `OK` | `STABLE_EXPIRES_BEFORE_END` — *"Contracte estable (<cat>) amb data de fi YYYY-MM-DD anterior a la fi del projecte"* |
| `PDI_TEMPORAL` amb `endDate >= effectiveProjectEndDate` (o sense `endDate`) | `OK` | `OK` | `OK` | `ELIGIBLE_TEMPORARY` — *"Contracte temporal vigent (<cat>) cobreix tot el projecte (fi: YYYY-MM-DD)"* |
| `PDI_TEMPORAL` amb `endDate < effectiveProjectEndDate` | `NO_OK` | `AVIS` | `OK` | `TEMPORARY_EXPIRES_BEFORE_END` — *"Contracte temporal (<cat>) finalitza el YYYY-MM-DD, abans de la fi prevista del projecte (YYYY-MM-DD)"* |
| `EMERIT` vigent amb `endDate != null` i `referenceDate <= endDate <= referenceDate.plusMonths(12)` | `NO_OK` | `NO_OK` | `NO_OK` | `EMERIT_LAST_YEAR` — *"Emèrit/a en darrer any de contracte (fi: YYYY-MM-DD)"* |
| `EMERIT` vigent amb `endDate > referenceDate.plusMonths(12)` però `endDate < effectiveProjectEndDate` | `NO_OK` | `AVIS` | `OK` | `EMERIT_EXPIRES_BEFORE_END` — *"Emèrit/a amb contracte fins YYYY-MM-DD, anterior a la fi prevista del projecte"* |
| `EMERIT` amb cobertura total (`endDate == null` o `endDate >= effectiveProjectEndDate`) | `AVIS` | `OK` | `OK` | `EMERIT_COVERS_PROJECT` — *"Emèrit/a amb contracte fins YYYY-MM-DD — verifiqueu requisit de co-IP"* (per IP) / *"Emèrit/a amb contracte vigent fins YYYY-MM-DD"* (EI/ET) |
| `ASSOCIAT` | `NO_OK` | `NO_OK` | `OK` | `INELIGIBLE_ASSOCIAT` — *"Professorat associat (<cat>) — no elegible com a IP / Equip d'Investigació"* |
| `SUBSTITUT` | `NO_OK` | `NO_OK` | `OK` | `INELIGIBLE_SUBSTITUT` — *"Professorat substitut — no elegible com a IP / Equip d'Investigació"* |
| `PREDOCTORAL` | `NO_OK` | `NO_OK` | `OK` | `INELIGIBLE_PREDOCTORAL` — *"Personal predoctoral / en formació (<cat>) — només pot participar a l'Equip de Treball"* |
| `PTGAS` | `NO_OK` | `NO_OK` | `OK` | `INELIGIBLE_PTGAS` — *"Personal tècnic / PTGAS (<cat>) — no elegible com a IP / Equip d'Investigació"* |
| `HONORARI_JUBILAT` | `NO_OK` | `NO_OK` | `AVIS` | `INELIGIBLE_HONORARY_RETIRED` — *"Professorat honorari / jubilat — sense contracte elegible per a IP / Equip d'Investigació"* |
| `ADSCRIPCIO_RECERCA` | `AVIS` | `AVIS` | `AVIS` | `ONLY_RESEARCH_AFFILIATION` — *"Només consta 'Adscripció a recerca'; reviseu el contracte principal a Egreta"* |
| Sense contracte vigent a `referenceDate` (entitat UAB o no especificada) | `NO_OK` | `NO_OK` | `AVIS` | `NO_ACTIVE_CONTRACT` — *"Sense contracte vigent a Egreta a data YYYY-MM-DD (darrer: <cat>, fi YYYY-MM-DD)"* |
| `MatchConfidence.NO_TROBAT` (entitat UAB o sense entitat) | `AVIS` | `AVIS` | `AVIS` | `PERSON_NOT_FOUND` — *"Persona no trobada a la col·lecció Persons d'Egreta"* |
| `MatchConfidence.AMBIGU` | `AVIS` | `AVIS` | `AVIS` | `AMBIGUOUS_PERSON_MATCH` — *"Múltiples coincidències a Egreta — seleccioneu o reviseu manualment"* |
| `DESCONEGUDA` | `AVIS` | `AVIS` | `AVIS` | `UNKNOWN_CATEGORY` — *"Categoria laboral no classificada: '<cat>' — reviseu manualment"* |

#### Semàfor global del projecte (`overallStatus`)
- `NO_OK` si hi ha ≥ 1 membre amb `status == NO_OK`.
- `AVIS` si no hi ha cap `NO_OK`, però hi ha ≥ 1 membre amb `status == AVIS` (o si la llista de membres extrets és buida).
- `OK` quan tots els membres avaluats tenen `status == OK` o `status == EXTERN`.

### 3.6 API REST (`com.example.demo.plannacional.controller.PlanNacionalController`)

Controlador anotat amb `@RestController` sota `@RequestMapping("/api/plan-nacional")`:

1. **`POST /api/plan-nacional/validar`** (`consumes = MediaType.MULTIPART_FORM_DATA_VALUE`)
   - Paràmetres:
     - `file` (`@RequestParam("file") MultipartFile`, obligatori)
     - `referenceDate` (`@RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate`, defecte `LocalDate.now()`)
     - `durationYears` (`@RequestParam(required = false) Integer`, opcional; si s'informa, validat entre `1` i `6`)
     - `useAiFallback` (`@RequestParam(defaultValue = "true") boolean`)
   - Respostes:
     - `200 OK`: `ProposalValidationResponse`
     - `400 Bad Request`: fitxer buit, tipus/capçalera no PDF, `durationYears` fora de rang o PDF corrupte/protegit (`InvalidPdfException`)
     - `422 Unprocessable Entity`: PDF escanejat sense capa de text (`ScannedPdfException`), amb JSON descriptiu en català.

2. **`POST /api/plan-nacional/validar-equipo`** (`consumes = MediaType.APPLICATION_JSON_VALUE`)
   - Cos: `ReevaluateTeamRequest(proposal, referenceDate, durationYears)`
   - Retorna `200 OK` amb `ProposalValidationResponse` recalculat en memòria contra `Persons` sense tornar a pujar ni re-parsejar el PDF.

3. **`GET /api/plan-nacional/reglas`**
   - Retorna `200 OK` amb `RuleCatalogResponse` perquè el modal de la interfície mostri les regles i categories actives de forma transparent.

#### Seguretat
- Tots els endpoints de `/api/plan-nacional/**` i la pàgina `/validacio-plan-nacional.html` queden protegits per la configuració existent del portal (`ROLE_USER` a `DevSecurityConfig` i autenticació CAS a `SecurityConfig`), sense afegir excepcions públiques.

---

## 4. Arquitectura Frontend (`src/main/webapp/validacio-plan-nacional.html` + `.js`)

### 4.1 Integració i Estil Institucional
- Fitxers: `src/main/webapp/validacio-plan-nacional.html` i `src/main/webapp/validacio-plan-nacional.js`.
- Reutilitza les convencions del portal: `config.js` (`apiUrl`/`apiFetch`), `uab-brand.js`, `uab-theme.css`, Tailwind CSS, FontAwesome 6 i SheetJS (`xlsx`).
- Idioma de tota la interfície: **català (`lang="ca"`)**.
- Es dona visibilitat mitjançant una nova targeta d'accés directe a `src/main/webapp/index.html`.

### 4.2 Components de la Interfície
1. **Capçalera i Barra de Paràmetres de Convocatòria:**
   - Selector de **Data de referència** (per defecte la data actual).
   - Selector de **Durada per defecte** (`3 anys` / `4 anys`, emprat quan el PDF no especifica durada ni data de fi).
   - Botó **Recalcular lot** que invoca `POST /api/plan-nacional/validar-equipo` per a cada projecte carregat sense tornar a pujar els PDFs.
   - Botó **Criteris de validació** que obre un modal alimentat dinàmicament des de `GET /api/plan-nacional/reglas`.
   - Botó **Exportar Excel (.xlsx)** deshabilitat fins que hi hagi almenys 1 projecte analitzat.

2. **Zona *Drag & Drop* per Lots:**
   - Àrea interactiva per arrossegar o seleccionar 1 o múltiples fitxers `.pdf` (amb opció d'afegir nous PDFs al lot existent sense perdre els anteriors).
   - Cua concurrent controlada al client (màxim 3 peticions `POST /api/plan-nacional/validar` en paral·lel) amb barra de progrés (`Validant X de Y PDFs...`).
   - Gestió aïllada d'errors per fitxer: si un PDF retorna `400` o `422`, es mostra com a targeta d'error en la llista de projectes sense interrompre la resta del lot.
   - Accions per treure un projecte concret del lot o buidar tot el lot.

3. **KPIs Globals Clicables:**
   - 5 targetes clicables que actuen com a filtre ràpid sobre la llista de projectes: `Projectes`, `OK`, `Avisos`, `NO OK` i `Membres revisats`.

4. **Vista Maestro–Detall:**
   - **Panell esquerre (`Llista de projectes`):** cercador per nom de fitxer, codi o acrònim; filtre per semàfor (`Tots`, `NO OK`, `AVÍS`, `OK`, `ERROR`); targetes ordenades amb badge de semàfor global, recomptes (`OK` / `AVÍS` / `NO OK` / `EXTERN`) i botó per eliminar del lot.
   - **Panell dret (`Detall de l'equip`):**
     - **Fitxa del projecte:** codi/referència, acrònim, títol, entitat sol·licitant, badge del mètode d'extracció (`Parser AEI` o `IA fallback`), avisos d'extracció i selector local de durada editable (`3 anys` / `4 anys` / valor PDF) amb recàlcul instantani via `/validar-equipo`.
     - **Filtres de taula:** per rol (`Tots`, `IP`, `Equip d'Investigació`, `Equip de Treball`) i per estat (`Tots`, `NO OK`, `AVÍS`, `OK`, `EXTERN`).
     - **Taula d'equip** ordenada per rol (`IP` → `EQUIPO_INVESTIGACION` → `EQUIPO_TRABAJO`) i severitat (`NO_OK` → `AVIS` → `OK` → `EXTERN`) amb les columnes:
       - `Rol` (`IP`, `EI`, `ET`)
       - `Membre al PDF` (nom complet, NIF, entitat declarada, badge de `MatchConfidence`)
       - `Situació a Egreta` (nom Egreta, categoria principal, `contractType`, departament, dates `startDate → endDate`, i desplegable/tooltip quan té múltiples `activeContracts` o llista de `ambiguousCandidates`)
       - `Check` (badge semàntic: verd `OK`, ambre `AVÍS`, vermell `NO OK`, blau/gris `EXTERN`)
       - `Motiu` (missatge detallat en català)
       - `Accions`: botó **Editor Egreta** (`https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=<pureId>`) obert en finestra emergent (`window.open`, seguint el patró de `contratos-validacion.html`).
     - **Panell plegable `"Diagnòstic d'extracció"`:** mostra `extractionWarnings` i `rawTextPreview` per inspeccionar ràpidament qualsevol PDF amb format anòmal.

### 4.3 Exportació Excel (`.xlsx`) en Client (SheetJS)
Genera un llibre Excel amb 2 fulls:
- **Full 1 — `Resum_Projectes`:** `Fitxer PDF`, `Codi projecte`, `Acrònim`, `Títol`, `Durada efectiva (anys)`, `Data referència`, `Fi prevista projecte`, `Semàfor global`, `Total membres`, `OK`, `Avisos`, `NO OK`, `Externs`, `Mètode extracció`, `Incidències principals`.
- **Full 2 — `Detall_Equips`:** `Fitxer PDF`, `Codi projecte`, `Acrònim`, `Rol PDF`, `Nom complet PDF`, `NIF PDF`, `Entitat PDF`, `Concordança`, `Nom Egreta`, `UUID Egreta`, `PureID`, `Categoria classificada`, `Categoria laboral Egreta`, `Tipus contracte`, `Departament`, `Data inici`, `Data fi`, `Estat`, `Codi motiu`, `Motiu detallat`.

---

## 5. Estratègia de Proves (TDD)

1. **`PlanNacionalEligibilityEngineTest` (unitari pur):**
   - Cobreix tota la matriu de regles: `PDI_ESTABLE` (sense `endDate`, amb `endDate` posterior i anterior al projecte), `PDI_TEMPORAL` (cobreix vs venç abans en IP/EI/ET), `EMERIT` (≤ 12 mesos → `NO_OK` a tots els rols; > 12 mesos però < fi projecte → `NO_OK` IP / `AVIS` EI / `OK` ET; cobertura completa → `AVIS` IP / `OK` EI i ET), `ASSOCIAT`, `SUBSTITUT`, `PREDOCTORAL`, `PTGAS`, `HONORARI_JUBILAT`, `VISITING` (`ICREA`, `CIBER`, `vinculat`), `ADSCRIPCIO_RECERCA` única vs descartada, sense contracte vigent amb fallback històric, externs vs discrepància d'entitat, no trobat, `AMBIGU`, categoria `DESCONEGUDA`, `NOM_APROXIMAT` i càlcul del semàfor global.
2. **`AeiSolicitudParserTest` (unitari):**
   - Fixtures de text representatius de formularis oficials AEI / MICIU (codi/acrònim/títol/durada, blocs IP1/IP2, Equip d'Investigació, Equip de Treball, NIF/NIE/passaports complets i emmascarats, entitats externes, salts de pàgina, cas sense membres i detecció de `ScannedPdfException` en `PdfBoxTextExtractor`).
3. **`PersonPdiLookupServiceTest` (unitari amb `MongoTemplate` mockejat):**
   - Cascada `NIF_EXACTE` (incloent NIF emmascarat) → `NOM_EXACTE` (ordre `"Cognoms, Nom"` vs `"Nom Cognoms"` i accents) → `NOM_APROXIMAT` → `AMBIGU` (múltiples candidats) → `NO_TROBAT`; selecció de contracte actiu excloent `"Adscripció a recerca"`, priorització de `visitingScholarOrganizationAssociations`, parseig de dates `String` i `Date`, i recuperació de `latestHistoricalContract`.
4. **`PlanNacionalControllerTest` (`@WebMvcTest` / MockMvc):**
   - Prova `POST /api/plan-nacional/validar` (PDF vàlid → `200`, fitxer no PDF / buit → `400`, PDF escanejat → `422`), `POST /api/plan-nacional/validar-equipo` (`200`) i `GET /api/plan-nacional/reglas` (`200`).
5. **Comanda de verificació prevista:**
   `.\mvnw.cmd test -Dtest=PlanNacional*Test,AeiSolicitudParserTest,PersonPdiLookupServiceTest`
