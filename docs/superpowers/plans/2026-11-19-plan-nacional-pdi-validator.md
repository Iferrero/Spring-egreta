# Pla d'Implementació: Validador d'Equips PDI per a Sol·licituds del Pla Nacional (AEI / MICIU)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir un visor web *drag & drop* en català i la seva API REST a `Spring-egreta` per validar en lot l'elegibilitat dels equips de recerca de PDFs oficials de sol·licitud del Pla Nacional (AEI / MICIU) contra la col·lecció `Persons` d'Egreta (`kraken`).

**Architecture:** Un nou mòdul desacoblat `com.example.demo.plannacional` processa cada PDF 100% en memòria amb Apache PDFBox (`PdfBoxTextExtractor`), extreu metadades i membres per rol (`AeiSolicitudParser`, amb fallback opcional a vLLM via `VllmProposalFallbackParser`), cerca el personal a `kraken.Persons` (`PersonPdiLookupService`) en cascada NIF → nom exacte → nom aproximat seleccionant el contracte real (relegant `Adscripció a recerca`), i avalua la matriu de regles en un motor pur Java (`PlanNacionalEligibilityEngine`). La interfície (`validacio-plan-nacional.html` + `.js`) permet càrrega concurrent per lots, recàlcul dinàmic via `/validar-equipo` i exportació Excel en 2 fulls amb SheetJS.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Data MongoDB, Apache PDFBox 3.0.4, JUnit 5, Mockito, MockMvc (`standaloneSetup`), HTML5/Tailwind CSS/Vanilla JS, SheetJS (`xlsx`).

**Spec:** [2026-11-19-plan-nacional-pdi-validator-design.md](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/docs/superpowers/specs/2026-11-19-plan-nacional-pdi-validator-design.md)

## Global Constraints
- Processament **100% en memòria**: no persistir fitxers PDF, NIFs ni resultats a disc ni a MongoDB.
- Dependència única nova a `pom.xml`: `org.apache.pdfbox:pdfbox:3.0.4` (compatible amb Java 21 i Spring Boot 4.1.2).
- Tota la interfície web i els missatges de motius/errors cap a l'usuari han d'estar en **català (`ca`)**, reutilitzant `config.js`, `uab-brand.js`, `uab-theme.css`, Tailwind CSS, FontAwesome 6 i SheetJS (`xlsx`).
- Seguretat: `/api/plan-nacional/**` i `/validacio-plan-nacional.html` queden protegits per la configuració existent (`ROLE_USER` a `DevSecurityConfig` i CAS a `SecurityConfig`); cap obertura pública ni excepció.
- Projecció lleugera sobre `kraken.Persons` (`uuid`, `pureId`, `name`, `names`, `identifiers`, `retirementDate`, `staffOrganizationAssociations`, `visitingScholarOrganizationAssociations`), acceptant dates tant en `String` ISO (`YYYY-MM-DD` / ISO-8601) com en `java.util.Date`.
- Fora d'abast (YAGNI): participació simultània a `Awards`, dedicació FTE, edat de jubilació (`dateOfBirth`), edició manual de membres i persistència d'històrics a MongoDB.
- Higiene Git: el repositori té canvis previs no confirmats de `publication-duplicates` (incloent `src/main/webapp/index.html`); fer sempre `git add <fitxers-especifics>` i **mai** `git add -A` ni `git add .`.

## Review Focus
- **Salts de pàgina, capçaleres repetides i membres duplicats entre rols (IP + EI) al PDF AEI:** El parser no ha de perdre membres partits per un salt de pàgina ni duplicar la mateixa persona dins el mateix rol; si un NIF apareix a IP i a EI, preval el rol superior (`IP > EQUIPO_INVESTIGACION > EQUIPO_TRABAJO`). *(Pinned in Task 2 — `AeiSolicitudParserTest`)*
- **NIF emmascarat (`***1234**`), formats irregulars (`12.345.678-a`, `X-1234567-L`) i homònims compostos:** La normalització ha de casar sense falsos positius i retornar `MatchConfidence.AMBIGU` (màx. 5 candidats, mai endevinar) si un NIF emmascarat o un nom coincideix amb més d'una persona. *(Pinned in Task 3 — `PersonPdiLookupServiceTest`)*
- **Doble associació activa (`Adscripció a recerca` amb `primaryAssociation=true` i `endDate=null` + contracte `Associat/da` o `Emèrit/a`) i dates mixtes (`String` vs `Date`):** El selector ha de relegar sempre `Adscripció a recerca` encara que sigui primària quan existeix un contracte laboral/visiting vigent. *(Pinned in Task 3 — `PersonPdiLookupServiceTest`)*
- **Fronteres exactes de data (`endDate == referenceDate`, `endDate == referenceDate.plusMonths(12)`, `endDate == referenceDate.plusMonths(12).plusDays(1)`, `endDate == effectiveProjectEndDate`) i discrepància extern+contracte UAB:** `endDate == referenceDate.plusMonths(12)` és darrer any (`NO_OK`); `+1 dia` surt del darrer any; `endDate == effectiveProjectEndDate` cobreix el projecte; entitat externa al PDF amb contracte vigent UAB eleva `OK` a `AVIS` i manté `NO_OK`. *(Pinned in Task 1 — `PlanNacionalEligibilityEngineTest`)*
- **Injecció HTML/script procedent d'un PDF malformat (XSS) i errors aïllats (`400`/`422`) en lot multiproyecte:** Tot text del PDF/API s'ha d'escapar amb `escapeHtml` abans d'inserir-se al DOM, i un PDF corrupte o escanejat dins d'un lot ha de mostrar targeta d'error sense interrompre la cua ni l'exportació Excel dels projectes vàlids. *(Pinned in Task 4 — `PlanNacionalControllerTest` i Task 5 — verificació frontend)*

---

### Tasca 1: Model de Domini (`model/`) i Motor d'Elegibilitat (`PlanNacionalEligibilityEngine`)

**Files:**
- Create: `src/main/java/com/example/demo/plannacional/model/MemberRole.java`
- Create: `src/main/java/com/example/demo/plannacional/model/EligibilityStatus.java`
- Create: `src/main/java/com/example/demo/plannacional/model/MatchConfidence.java`
- Create: `src/main/java/com/example/demo/plannacional/model/ExtractionMethod.java`
- Create: `src/main/java/com/example/demo/plannacional/model/EmploymentCategory.java`
- Create: `src/main/java/com/example/demo/plannacional/model/ExtractedMember.java`
- Create: `src/main/java/com/example/demo/plannacional/model/ExtractedProposal.java`
- Create: `src/main/java/com/example/demo/plannacional/model/ContractSnapshot.java`
- Create: `src/main/java/com/example/demo/plannacional/model/PersonCandidateSummary.java`
- Create: `src/main/java/com/example/demo/plannacional/model/PersonLookupMatch.java`
- Create: `src/main/java/com/example/demo/plannacional/model/MemberValidationResult.java`
- Create: `src/main/java/com/example/demo/plannacional/model/SummaryCounts.java`
- Create: `src/main/java/com/example/demo/plannacional/model/ProposalValidationResponse.java`
- Create: `src/main/java/com/example/demo/plannacional/model/ReevaluateTeamRequest.java`
- Create: `src/main/java/com/example/demo/plannacional/model/RuleDescription.java`
- Create: `src/main/java/com/example/demo/plannacional/model/CategoryDefinition.java`
- Create: `src/main/java/com/example/demo/plannacional/model/RuleCatalogResponse.java`
- Create: `src/main/java/com/example/demo/plannacional/engine/PlanNacionalEligibilityEngine.java`
- Test: `src/test/java/com/example/demo/plannacional/engine/PlanNacionalEligibilityEngineTest.java`

**Interfaces:**
- Consumes: cap (mòdul base)
- Produces:
  - Enums exactes de l'Spec §3.2 (`MemberRole`, `EligibilityStatus`, `MatchConfidence`, `ExtractionMethod`, `EmploymentCategory`)
  - Records exactes de l'Spec §3.2 + `PersonLookupMatch(MatchConfidence matchConfidence, String matchedPersonUuid, Long matchedPureId, String matchedFullName, String egretaEditorUrl, ContractSnapshot primaryContract, List<ContractSnapshot> activeContracts, ContractSnapshot latestHistoricalContract, List<PersonCandidateSummary> ambiguousCandidates)`
  - `@Service PlanNacionalEligibilityEngine`:
    - `public EmploymentCategory classifyCategory(ContractSnapshot contract)`
    - `public boolean isExternalEntity(String entity)`
    - `public int resolveEffectiveDurationYears(ExtractedProposal proposal, Integer durationYearsOverride)`
    - `public LocalDate resolveEffectiveProjectEndDate(ExtractedProposal proposal, LocalDate referenceDate, Integer durationYearsOverride)`
    - `public MemberValidationResult evaluateMember(ExtractedMember member, PersonLookupMatch match, LocalDate referenceDate, LocalDate effectiveProjectEndDate)`
    - `public ProposalValidationResponse evaluateProposal(ExtractedProposal proposal, List<PersonLookupMatch> matches, LocalDate referenceDate, Integer durationYearsOverride)` — on `matches` té la mateixa mida i ordre que `proposal.members()`
    - `public RuleCatalogResponse buildRuleCatalog()`

- [ ] **Step 1: Write the failing test (`PlanNacionalEligibilityEngineTest`)**

Incloure tests JUnit 5 que verifiquin:
- `classifyCategoryMapsRealEgretaTerms`: comprova tots els patrons de l'Spec §3.5 (`Catedràtic/a d'universitat`, `Personal investigador ICREA`, `Personal investigador CIBER`, `Personal investigador vinculat` → `PDI_ESTABLE`; `Professor/a lector/a ajudant doctor/a`, `Investigadors/es programa Ramón Y Cajal`, `Personal investigador postdoctoral projectes` → `PDI_TEMPORAL`; `Professorat emèrit laboral` / `contractType="Emèrit/a"` → `EMERIT`; `Professor/a associat/da laboral`, `Associat/da mèdic/a laboral`, `Professor associat indefinit` → `ASSOCIAT`; `Professor/a substitut/a` → `SUBSTITUT`; `Estudiant de doctorat`, `Investigador en Formació FI-JOAN ORÓ`, `FPI` → `PREDOCTORAL`; `Tècnic/a superior suport a la recerca`, `staffType="No acadèmic"` → `PTGAS`; `Professor/a Honorari/a`, `Personal acadèmic jubilat` → `HONORARI_JUBILAT`; `researchAffiliationOnly=true` → `ADSCRIPCIO_RECERCA`; desconegut → `DESCONEGUDA`).
- `evaluateMemberCoversFullRoleMatrix`: verifica cada fila de la matriu (Spec §3.5):
  - `PDI_ESTABLE` sense `endDate` o amb `endDate >= projectEnd` → `OK`/`OK`/`OK` (`ELIGIBLE_STABLE`), però amb `NOM_APROXIMAT` → `AVIS`.
  - `PDI_ESTABLE` amb `endDate < projectEnd` → `AVIS`/`AVIS`/`OK` (`STABLE_EXPIRES_BEFORE_END`).
  - `PDI_TEMPORAL` amb `endDate >= projectEnd` vs `endDate < projectEnd` (`NO_OK` IP / `AVIS` EI / `OK` ET, `TEMPORARY_EXPIRES_BEFORE_END`).
  - `ASSOCIAT`, `SUBSTITUT`, `PREDOCTORAL`, `PTGAS` → `NO_OK` a IP i EI, `OK` a ET.
  - `HONORARI_JUBILAT` → `NO_OK` a IP i EI, `AVIS` a ET.
  - `ADSCRIPCIO_RECERCA` → `AVIS` a tots els rols (`ONLY_RESEARCH_AFFILIATION`).
  - Sense contracte actiu (`primaryContract == null`, UAB) → `NO_OK` IP/EI, `AVIS` ET (`NO_ACTIVE_CONTRACT`, citant el darrer contracte històric).
  - `NO_TROBAT`, `AMBIGU`, `DESCONEGUDA` → `AVIS` a tots els rols.
- `evaluateMemberEmeritAndDateBoundaries`: *(Review Focus #4)*
  - `endDate == referenceDate.plusMonths(12)` → `NO_OK`/`NO_OK`/`NO_OK` (`EMERIT_LAST_YEAR`).
  - `endDate == referenceDate.plusMonths(12).plusDays(1)` però `< effectiveProjectEndDate` → `NO_OK` IP / `AVIS` EI / `OK` ET (`EMERIT_EXPIRES_BEFORE_END`).
  - `endDate == effectiveProjectEndDate` (i `> referenceDate.plusMonths(12)`) → `AVIS` IP (*verifiqueu requisit de co-IP*) / `OK` EI / `OK` ET (`EMERIT_COVERS_PROJECT`).
  - Temporal amb `endDate == effectiveProjectEndDate` → `OK` a IP/EI/ET; `effectiveProjectEndDate.minusDays(1)` → `NO_OK` IP / `AVIS` EI.
- `evaluateMemberExternalAndDiscrepancyRules`: *(Review Focus #4)*
  - Entitat externa (`CSIC`, `UPC`, `Hospital Vall d'Hebron`) sense contracte vigent UAB (encara que tingui històric) → `EXTERN` (`EXTERNAL_ENTITY`).
  - Entitat externa al PDF amb contracte vigent UAB `PDI_ESTABLE` → `AVIS` (discrepància d'afiliació).
  - Entitat externa al PDF amb contracte vigent UAB `ASSOCIAT` com a IP → manté `NO_OK`.
  - Entitats UAB (`UAB`, `Universitat Autònoma de Barcelona`, `Universidad Autónoma de Barcelona`, buit) no es marquen com a `EXTERN`.
- `evaluateProposalCalculatesEffectiveDatesSummaryAndCatalog`:
  - Per defecte `3` anys des de `referenceDate`; prioritza `durationYearsOverride` > `proposal.expectedEndDate()` > `proposal.durationYears()` > `3`.
  - Semàfor global: `NO_OK` si ≥ 1 `NO_OK`; `AVIS` si ≥ 1 `AVIS` sense `NO_OK` o si `members` és buit; `OK` si tot és `OK`/`EXTERN`.
  - `buildRuleCatalog()` retorna `defaultDurationYears == 3`, `emeritWarningMonths == 12`, regles no buides i definicions de categories.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=PlanNacionalEligibilityEngineTest"`  
Expected: FAIL amb error de compilació perquè encara no existeixen les classes del paquet `plannacional`.

- [ ] **Step 3: Implement model enums, records, and `PlanNacionalEligibilityEngine`**

Crear tots els enums i records dins `com.example.demo.plannacional.model` i implementar `PlanNacionalEligibilityEngine` a `src/main/java/com/example/demo/plannacional/engine/PlanNacionalEligibilityEngine.java` respectant exactament els codis `reasonCode`, missatges en català de l'Spec §3.5 i la classificació normalitzada sense accents (NFKD). Reutilitzar mètodes auxiliars interns per a normalització i regles per rol sense duplicar lògica.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=PlanNacionalEligibilityEngineTest"`  
Expected: PASS (tots els tests verds, 0 errors, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/plannacional/model/ src/main/java/com/example/demo/plannacional/engine/ src/test/java/com/example/demo/plannacional/engine/
git commit -m "feat(plan-nacional): afegir model de domini i motor d'elegibilitat PDI"
```

---

### Tasca 2: Extracció PDF (`PdfBoxTextExtractor`), Parser AEI (`AeiSolicitudParser`) i Fallback vLLM (`VllmProposalFallbackParser`)

**Files:**
- Modify: `pom.xml:114-120` (afegir `org.apache.pdfbox:pdfbox:3.0.4`)
- Modify: `src/main/resources/application.properties:88-95` (afegir `app.plannacional.vllm-fallback.enabled=true`)
- Create: `src/main/java/com/example/demo/plannacional/parser/InvalidPdfException.java`
- Create: `src/main/java/com/example/demo/plannacional/parser/ScannedPdfException.java`
- Create: `src/main/java/com/example/demo/plannacional/parser/PdfTextExtractor.java`
- Create: `src/main/java/com/example/demo/plannacional/parser/PdfBoxTextExtractor.java`
- Create: `src/main/java/com/example/demo/plannacional/parser/AeiSolicitudParser.java`
- Create: `src/main/java/com/example/demo/plannacional/parser/VllmProposalFallbackParser.java`
- Test: `src/test/java/com/example/demo/plannacional/parser/AeiSolicitudParserTest.java`

**Interfaces:**
- Consumes: `ExtractedMember`, `ExtractedProposal`, `MemberRole`, `ExtractionMethod` (Task 1)
- Produces:
  - `PdfTextExtractor`: `String extractText(byte[] pdfBytes) throws InvalidPdfException, ScannedPdfException;`
  - `@Component PdfBoxTextExtractor implements PdfTextExtractor`
  - `@Component AeiSolicitudParser`: `public ExtractedProposal parse(String fileName, String rawText)`
  - `@Service VllmProposalFallbackParser`: `public Optional<ExtractedProposal> parseFallback(String fileName, String rawText)`

- [ ] **Step 1: Add PDFBox 3.0.4 to `pom.xml` and fallback flag to `application.properties`, then write the failing test (`AeiSolicitudParserTest`)**

A `AeiSolicitudParserTest` escriure tests JUnit 5 amb PDFs generats en memòria amb `PDDocument` i fixtures de text AEI:
- `pdfBoxExtractsTextFromValidInMemoryPdf`: genera un PDF amb `PDDocument` contenint > 80 caràcters alfanumèrics i verifica el text extret.
- `pdfBoxRejectsEmptyOrNonPdfBytesWithInvalidPdfException`: bytes buits / `"no-pdf".getBytes()` → llança `InvalidPdfException`.
- `pdfBoxDetectsScannedPdfWithoutTextWithScannedPdfException`: `PDDocument` amb una pàgina en blanc (< 80 caràcters útils) → llança `ScannedPdfException`.
- `parseExtractsAeiHeaderIpEiEtAndExternalMembers`: fixture AEI complet amb `PID2025-123456OB-I00`, acrònim `BIOMED-AI`, títol, `Duración: 4 años`, dates previstes, `Universitat Autònoma de Barcelona`, blocs `INVESTIGADOR PRINCIPAL (IP1)`, `IP2`, `EQUIPO DE INVESTIGACIÓN` i `EQUIPO DE TRABAJO` barrejant `"Cognoms, Nom"` i `"Nom Cognoms"`, NIF complet, NIF emmascarat (`***4567**`), NIE (`X1234567L`), passaport i membre extern (`CSIC`). Verifica `ExtractionMethod.AEI_PARSER`, `rawTextPreview.length() <= 4000` i `warnings`.
- `parseHandlesPageBreaksRepeatedHeadersAndDeduplicatesByHighestRole`: *(Review Focus #1)* text dividit amb salts de pàgina/peus `"MINISTERIO DE CIENCIA... Página 2 de 5"` al mig de les taules d'equip, i mateix NIF present a `IP` i a `EQUIPO DE INVESTIGACIÓN` → no perd membres i conserva un únic registre amb rol `MemberRole.IP`.
- `parseReturnsEmptyMembersWithWarningWhenNoTeamSectionPresent`: text sense seccions d'equip retorna llista buida de membres i afegeix avís d'extracció.
- `vllmFallbackParsesStructuredJsonResponseAndFlagsMethod`: parseig d'una resposta JSON vàlida de vLLM retorna `Optional<ExtractedProposal>` amb `ExtractionMethod.VLLM_FALLBACK` i avís `"Extret amb IA (format no estàndard) — reviseu el llistat"`.
- `vllmFallbackReturnsEmptyGracefullyWhenDisabledOrUnreachable`: quan `enabled=false` o URL no accessible retorna `Optional.empty()` sense llançar excepció.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=AeiSolicitudParserTest"`  
Expected: FAIL per classes de `parser` inexistents.

- [ ] **Step 3: Implement `PdfBoxTextExtractor`, exceptions, `AeiSolicitudParser`, and `VllmProposalFallbackParser`**

- `PdfBoxTextExtractor`: valida no-buit i prefix `%PDF-`, usa `Loader.loadPDF(pdfBytes)` + `PDFTextStripper` (`setSortByPosition(true)`), captura errors de lectura/encriptació com a `InvalidPdfException`, i verifica mínim 80 caràcters alfanumèrics útils abans de retornar o llançar `ScannedPdfException`.
- `AeiSolicitudParser`: normalitza salts de línia i filtra capçaleres/peus de pàgina AEI repetits; extreu codi/acrònim/títol/durada/dates/entitat sol·licitant; segueix la màquina d'estats per seccions (`IP`, `EQUIPO_INVESTIGACION`, `EQUIPO_TRABAJO`); parseja noms i documents identificatius (NIF/NIE/passaport/emmascarats) i deduplica per NIF (o nom normalitzat) prioritzant el rol més alt (`IP > EQUIPO_INVESTIGACION > EQUIPO_TRABAJO`). Acota `rawTextPreview` a 4.000 caràcters.
- `VllmProposalFallbackParser`: segueix el patró d'`HttpClient` + `SSLContext` + `ObjectMapper` de `VllmAffiliationService` reutilitzant `app.vllm.url` / `app.vllm.model` (i controlat per `app.plannacional.vllm-fallback.enabled`).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=AeiSolicitudParserTest"`  
Expected: PASS (tots els tests verds).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/com/example/demo/plannacional/parser/ src/test/java/com/example/demo/plannacional/parser/
git commit -m "feat(plan-nacional): implementar extractor PDFBox, parser AEI i fallback vLLM"
```

---

### Tasca 3: Cerca a `kraken.Persons` i Selecció de Contracte (`PersonPdiLookupService`)

**Files:**
- Create: `src/main/java/com/example/demo/plannacional/lookup/PersonPdiLookupService.java`
- Test: `src/test/java/com/example/demo/plannacional/lookup/PersonPdiLookupServiceTest.java`

**Interfaces:**
- Consumes: `ExtractedMember`, `ContractSnapshot`, `PersonCandidateSummary`, `PersonLookupMatch`, `MatchConfidence`, `PlanNacionalEligibilityEngine#classifyCategory` (Task 1)
- Produces:
  - `@Service PersonPdiLookupService`:
    - `public PersonPdiLookupService(MongoTemplate mongoTemplate, PlanNacionalEligibilityEngine eligibilityEngine)`
    - `public PersonLookupMatch lookup(ExtractedMember member, LocalDate referenceDate)`
    - `public List<PersonLookupMatch> lookupAll(List<ExtractedMember> members, LocalDate referenceDate)` — retorna una llista amb la mateixa mida i ordre 1:1 que `members`

- [ ] **Step 1: Write the failing test (`PersonPdiLookupServiceTest`)**

Fent servir `@ExtendWith(MockitoExtension.class)` amb `MongoTemplate`, `MongoCollection<Document>` i `FindIterable<Document>` mockejats (com a `PublicationDuplicatesControllerTest`), escriure tests per a:
- `lookupMatchesByExactNormalizedNifAndBuildsEgretaUrl`: PDF amb `"12.345.678-a"` casa amb `identifiers` (`DNI` = `"12345678A"`) → `NIF_EXACTE` i `egretaEditorUrl` amb `pureId`.
- `lookupMatchesMaskedNifWithSurnameOrMarksAmbiguous`: *(Review Focus #2)* `"***4567**"` amb cognom `"Garcia"` casa 1 persona (`NIF_EXACTE`); si 2 persones comparteixen dígits finals i cognom → `AMBIGU` amb `ambiguousCandidates` (màx. 5).
- `lookupMatchesByNormalizedNameOrderAndAccentsAndHandlesHomonyms`: `"GARCÍA LÓPEZ, MARÍA"` vs `firstName="Maria"`, `lastName="Garcia Lopez"` → `NOM_EXACTE`; 2 homònims idèntics → `AMBIGU` sense endevinar; aproximació amb partícules `de`/`del` → `NOM_APROXIMAT`; sense coincidència → `NO_TROBAT`.
- `lookupRelegatesAdscripcioARecercaEvenWhenPrimaryAndSelectsRealContract`: *(Review Focus #3)* persona amb `Adscripció a recerca` (`primaryAssociation=true`, `endDate=null`) i `Professor/a associat/da laboral` vigent → `primaryContract` és `ASSOCIAT` (`researchAffiliationOnly=false`), i `activeContracts` conté ambdós.
- `lookupFlagsResearchAffiliationOnlyWhenNoOtherActiveContractExists`: persona amb només `Adscripció a recerca` vigent → `primaryContract.researchAffiliationOnly() == true`.
- `lookupIncludesVisitingScholarAndParsesStringAndDatePeriods`: *(Review Focus #3)* `visitingScholarOrganizationAssociations` (`Personal investigador ICREA`) vigent i `staffOrganizationAssociations` amb `period.startDate` com `java.util.Date` i `period.endDate` com `String` `"2028-08-31"` → es parsegen correctament i s'inclouen a `activeContracts`.
- `lookupFallsBackToLatestHistoricalContractWhenExpired`: tots els contractes anteriors a `referenceDate` → `primaryContract == null` i `latestHistoricalContract` és el de `endDate` més recent.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=PersonPdiLookupServiceTest"`  
Expected: FAIL per classe `PersonPdiLookupService` inexistent.

- [ ] **Step 3: Implement `PersonPdiLookupService` with lightweight projection and 3-level lookup**

Implementar `PersonPdiLookupService` carregant un índex lleuger en memòria per petició/lot des de `mongoTemplate.getCollection("Persons").find().projection(...)` (o reutilitzant-lo dins `lookupAll` per no repetir consultes N vegades per membre en un mateix PDF). Aplicar normalització NFKD sense accents, filtre d'identificadors legals (`DNI`, `NIE`, `NIF/NIE/Passaport`, `Passaport`), normalització de dates (`String` i `Date`), relegació d'`Adscripció a recerca` i associacions sense `employmentType`, lectura de `visitingScholarOrganizationAssociations` (`source = "VISITING"`), i selecció de `latestHistoricalContract`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=PersonPdiLookupServiceTest"`  
Expected: PASS (tots els tests verds).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/plannacional/lookup/ src/test/java/com/example/demo/plannacional/lookup/
git commit -m "feat(plan-nacional): implementar cerca a Persons i selecció de contracte vigent"
```

---

### Tasca 4: Servei d'Orquestració (`PlanNacionalValidationService`) i Controlador REST (`PlanNacionalController`)

**Files:**
- Create: `src/main/java/com/example/demo/plannacional/service/PlanNacionalValidationService.java`
- Create: `src/main/java/com/example/demo/plannacional/controller/PlanNacionalController.java`
- Test: `src/test/java/com/example/demo/plannacional/controller/PlanNacionalControllerTest.java`

**Interfaces:**
- Consumes: `PdfTextExtractor`, `AeiSolicitudParser`, `VllmProposalFallbackParser` (Task 2), `PersonPdiLookupService` (Task 3), `PlanNacionalEligibilityEngine` i DTOs (Task 1)
- Produces:
  - `@Service PlanNacionalValidationService`:
    - `public ProposalValidationResponse validatePdf(String fileName, byte[] pdfBytes, LocalDate referenceDate, Integer durationYears, boolean useAiFallback)`
    - `public ProposalValidationResponse reevaluateTeam(ReevaluateTeamRequest request)`
    - `public RuleCatalogResponse getRuleCatalog()`
  - `@RestController @RequestMapping("/api/plan-nacional") PlanNacionalController`:
    - `@PostMapping(value = "/validar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`
    - `@PostMapping(value = "/validar-equipo", consumes = MediaType.APPLICATION_JSON_VALUE)`
    - `@GetMapping("/reglas")`

- [ ] **Step 1: Write the failing test (`PlanNacionalControllerTest`)**

Utilitzant `MockMvcBuilders.standaloneSetup(controller).build()` amb `@ExtendWith(MockitoExtension.class)`:
- `validarReturns200WithValidationResponseForValidPdf`: `MockMultipartFile("file", "sol.pdf", "application/pdf", "%PDF-1.4...".getBytes())` amb `referenceDate=2026-11-19&durationYears=4` → `200 OK`, JSON amb `overallStatus`, `summaryCounts`, `members`.
- `validarReturns400WhenFileEmptyOrInvalidPdf`: fitxer buit o `InvalidPdfException` → `400 Bad Request` amb cos JSON en català (`message`).
- `validarReturns400WhenDurationYearsOutOfRange`: `durationYears=7` o `durationYears=0` → `400 Bad Request`.
- `validarReturns422WhenPdfIsScannedWithoutTextLayer`: *(Review Focus #5)* `ScannedPdfException` → `422 Unprocessable Entity` amb missatge clar en català.
- `validarReturns400AndValidatesSerializingSpecialCharacters`: *(Review Focus #5)* verifica que caràcters `<script>` dins `title` o `fullName` es serialitzen de forma segura al JSON (sense manipulació HTML al backend; l'escapat al DOM es prova a la Tasca 5).
- `validarRespectsUseAiFallbackFalse`: amb `useAiFallback=false`, si `AeiSolicitudParser` retorna 0 membres no s'invoca `VllmProposalFallbackParser`.
- `validarEquipoRecalculatesWithoutPdfUpload`: `POST /api/plan-nacional/validar-equipo` amb `ReevaluateTeamRequest` → `200 OK`; amb `proposal == null` → `400 Bad Request`.
- `reglasReturns200WithRuleCatalog`: `GET /api/plan-nacional/reglas` → `200 OK` amb `defaultDurationYears == 3`, `emeritWarningMonths == 12`, `rules`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=PlanNacionalControllerTest"`  
Expected: FAIL per classes inexistents.

- [ ] **Step 3: Implement `PlanNacionalValidationService` and `PlanNacionalController`**

Implementar l'orquestració a `PlanNacionalValidationService` (extracció PDF → parser AEI → fallback vLLM opcional només si `members.isEmpty() && useAiFallback` → `lookupAll` → `evaluateProposal`) i el controlador REST amb gestió explícita de `InvalidPdfException` (`400`), `IllegalArgumentException` (`400`) i `ScannedPdfException` (`422`), sense cap excepció d'autenticació ni accés públic.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=PlanNacionalControllerTest"`  
Expected: PASS (tots els tests verds).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/plannacional/service/ src/main/java/com/example/demo/plannacional/controller/ src/test/java/com/example/demo/plannacional/controller/
git commit -m "feat(plan-nacional): afegir servei de validació i endpoints REST /api/plan-nacional"
```

---

### Tasca 5: Interfície Web en Català (`validacio-plan-nacional.html` + `.js`) i Targeta a `index.html`

**Files:**
- Create: `src/main/webapp/validacio-plan-nacional.html`
- Create: `src/main/webapp/validacio-plan-nacional.js`
- Modify: `src/main/webapp/index.html:21-38` (afegir targeta d'accés directe a `validacio-plan-nacional.html`, sense tocar el bloc de `publicacions-duplicades.html`)

**Interfaces:**
- Consumes: `config.js` (`apiUrl`/`apiFetch`), `uab-brand.js`, `uab-theme.css`, SheetJS CDN (`xlsx`), `POST /api/plan-nacional/validar`, `POST /api/plan-nacional/validar-equipo`, `GET /api/plan-nacional/reglas` (Task 4)
- Produces: pàgina `/validacio-plan-nacional.html` en català (`lang="ca"`) amb *drag & drop* en lot, vista Maestro–Detall, modal `Criteris de validació`, finestra emergent `Editor Egreta` i exportació Excel de 2 fulls (`Resum_Projectes`, `Detall_Equips`).

- [ ] **Step 1: Create `src/main/webapp/validacio-plan-nacional.html` (`lang="ca"`)**

Incloure:
- `config.js`, `uab-brand.js`, `uab-theme.css`, Tailwind CSS, FontAwesome 6, SheetJS (`xlsx.full.min.js`).
- Barra de paràmetres (`inputReferenceDate`, `selectDefaultDuration` [3/4 anys], `btnRecalculateBatch`, `btnOpenRulesModal`, `btnExportExcel`).
- Zona *Drag & Drop* multiproyecte (`dropZone`, `pdfFileInput` amb `multiple accept=".pdf"`, `btnClearBatch`, barra de progrés `progressContainer` / `progressBar` / `progressText`).
- 5 KPIs clicables (`Projectes`, `OK`, `Avisos`, `NO OK`, `Membres revisats`).
- Vista Maestro–Detall:
  - Panell esquerre: cercador (`projectSearchInput`), filtre de semàfor (`projectStatusFilter`) i llista (`projectListContainer`).
  - Panell dret: capçalera del projecte seleccionat, selector de durada editable localment (`detailDurationSelect`), filtres de rol (`roleFilter`) i estat (`memberStatusFilter`), taula ordenada (`IP` → `EQUIPO_INVESTIGACION` → `EQUIPO_TRABAJO` i `NO_OK` → `AVIS` → `OK` → `EXTERN`), panell plegable `"Diagnòstic d'extracció"` (`rawTextPreview` + `extractionWarnings`), i modal `rulesModal`.

- [ ] **Step 2: Create `src/main/webapp/validacio-plan-nacional.js`**

Implementar:
- Funció `escapeHtml(value)` aplicada sistemàticament a **tots** els textos provinents de PDF/API (`fileName`, `title`, `fullName`, `nif`, `entity`, `reasonMessage`, `rawTextPreview`, missatges d'error) abans d'inserir HTML al DOM *(Review Focus #5)*.
- Cua concurrent màxim 3 peticions simultànies contra `POST /api/plan-nacional/validar` usant `apiFetch(...)`; si un PDF falla (`400`/`422`/xarxa), desa l'error al projecte sense aturar els altres fitxers del lot *(Review Focus #5)*.
- Recàlcul del lot o de la durada d'un projecte via `POST /api/plan-nacional/validar-equipo` sense re-pujar els PDFs.
- Desplegable per a múltiples `activeContracts` / `ambiguousCandidates` i botó `"Editor Egreta"` obrint `window.open('https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=' + encodeURIComponent(pureId), 'EgretaPersonEditor', ...)`.
- Càrrega dinàmica de `GET /api/plan-nacional/reglas` per al modal `"Criteris de validació"`.
- Exportació `.xlsx` en client amb SheetJS generant exactament els 2 fulls i totes les columnes de l'Spec §4.3 (`Resum_Projectes` i `Detall_Equips`).

- [ ] **Step 3: Add shortcut card to `src/main/webapp/index.html`**

Inserir una targeta en català cap a `validacio-plan-nacional.html` dins `<div class="grid grid-cols-1 sm:grid-cols-2 gap-6">` de `src/main/webapp/index.html`, preservant intactes les línies existents (inclosa la targeta no confirmada de `publicacions-duplicades.html`).

- [ ] **Step 4: Verify JS syntax, HTML-JS element ID parity, XSS escaping, and `index.html` link**

Run:
```powershell
node --check src/main/webapp/validacio-plan-nacional.js
Select-String -Path src/main/webapp/index.html -Pattern "validacio-plan-nacional\.html"
Select-String -Path src/main/webapp/validacio-plan-nacional.js -Pattern "escapeHtml"
```
I verificar amb Node (`node -e ...`) que:
1. Tots els IDs referenciats amb `document.getElementById(...)` a `validacio-plan-nacional.js` existeixen com a `id="..."` dins `validacio-plan-nacional.html`.
2. `escapeHtml('<script>alert("xss")</script>')` escapa `<`, `>`, `"`, `'` i `&`.  
Expected: sortida sense errors de sintaxi ni IDs orfes; `index.html` conté l'enllaç i `escapeHtml` passa la prova d'escapat XSS.

- [ ] **Step 5: Commit**

```bash
git add src/main/webapp/validacio-plan-nacional.html src/main/webapp/validacio-plan-nacional.js
git commit -m "feat(plan-nacional): afegir visor drag & drop i exportació Excel en català"
```
No fer `git add src/main/webapp/index.html` perquè aquest fitxer manté canvis previs no confirmats de `publication-duplicates`; deixar la seva targeta editada al working tree i informar-ne a l'usuari en el resum final.

---

### Tasca 6: Verificació Global, Regressió i Revisió de Seguretat

**Files:**
- Read/Verify only: `src/main/java/com/example/demo/config/DevSecurityConfig.java`, `src/main/java/com/example/demo/config/SecurityConfig.java`, tots els tests del mòdul `plannacional`.

- [ ] **Step 1: Run full Plan Nacional test suite**

Run: `.\mvnw.cmd test "-Dtest=PlanNacional*Test,AeiSolicitudParserTest,PersonPdiLookupServiceTest"`  
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, 0 skipped.

- [ ] **Step 2: Run full project compilation check**

Run: `.\mvnw.cmd test-compile`  
Expected: `BUILD SUCCESS` (cap regressió de compilació en tot el projecte).

- [ ] **Step 3: Verify security and git workspace cleanliness**

Run:
```powershell
Select-String -Path src/main/java/com/example/demo/config/DevSecurityConfig.java,src/main/java/com/example/demo/config/SecurityConfig.java -Pattern "plan-nacional|anyRequest"
git status --short
```
Expected: `/api/plan-nacional/**` i `/validacio-plan-nacional.html` no tenen cap `permitAll()` i resten protegits per `.anyRequest().hasRole("USER")` / `.anyRequest().authenticated()`; cap fitxer aliè de `publication-duplicates` s'ha alterat ni comitejat per error.
