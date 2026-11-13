# Pla d'Implementació: Dashboard de Convocatòries Europees (PJE i PJI)

**Spec:** [2026-11-12-pje-pji-dashboard-design.md](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/docs/superpowers/specs/2026-11-12-pje-pji-dashboard-design.md)

## Tasques

### 1. Backend — Suport PJE/PJI i endpoint de desglossament per convocatòria
- **Fitxer:** `src/main/java/com/example/demo/controller/ApplicationController.java`
  - Extreure `fundingTypeUri` (`type.uri`) i calcular `programCode` (`"PJE"` si `type.uri` acaba en `/pro/pje` o `type.term` conté `PJE` / `Programa Marc Europeu`; `"PJI"` si `type.uri` acaba en `/pro/pji` o `type.term` conté `PJI` / `Internacional`).
  - Mantenir `statsByFundingOpportunity(boolean excludeResigned)` delegant a `statsByFundingOpportunity(boolean excludeResigned, String program)` per compatibilitat amb tests existents.
  - Afegir `GET /api/applications/stats/by-funding-opportunity/{fundingUuid}/breakdown`:
    - Reutilitzar el càlcul de `resignedAppUuids` i les expressions de classificació (`accepted`, `rejected`, `pending`, `isResigned`).
    - Agrupar per `managingOrganization.uuid` (fallback `"_UNKNOWN_"`).
    - Resoldre noms oficials a `Organizations` i `ambit` a `v_orga_ambit`.
    - Retornar `total`, `byAmbit` i `byDepartment`.

### 2. Tests Backend
- **Fitxer:** `src/test/java/com/example/demo/ApplicationControllerTest.java`
  - Afegir test per verificar `programCode` (`PJE`/`PJI`) i el nou endpoint `/breakdown` (amb una convocatòria PJE/PJI real de la BD si existeix, o validant estructura i suma de totals).
- **Verificació:** `.\mvnw.cmd test -Dtest=ApplicationControllerTest`

### 3. Frontend — Dashboard `convocatories-europees.html`
- **Fitxer:** `src/main/webapp/convocatories-europees.html`
  - Seguir `uab-theme.css`, `uab-brand.js`, Tailwind CSS, Chart.js 4, `noUiSlider`, `egreta-modal.js` i FontAwesome.
  - KPIs superiors dinàmics (Enviades, Concedides + % èxit, Rebutjades, Pendents, Renunciades).
  - Filtres: Selector segmentat `Totes (PJE + PJI)` / `PJE` / `PJI`, cercador, slider d'anys (des de 2015), toggle `Excloure renunciats`.
  - Taula densa amb barra de progrés apilada inline i selecció de fila accessible.
  - **Slide-over Drawer** modern al clicar una convocatòria:
    - KPIs de la convocatòria + enllaç a Egreta.
    - Gràfic interactiu per **Àmbit de Coneixement** (barres horitzontals apilades amb Chart.js) + targetes/taula clicables per fer *cross-filtering* sobre els departaments.
    - Gràfic de barres horitzontals apilades per **Departament** + cercador, ordenació i taula detallada.

### 4. Integració al Portal
- **Fitxer:** `src/main/webapp/index.html`
  - Afegir una targeta destacada per accedir a `convocatories-europees.html`.
