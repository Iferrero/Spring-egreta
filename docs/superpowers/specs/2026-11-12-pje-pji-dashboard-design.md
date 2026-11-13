# Disseny: Dashboard de Convocatòries Europees (PJE i PJI)

**Data:** 2026-11-12  
**Estat:** Pendent d'aprovació  
**Idioma de la interfície:** Català (`ca`)

## 1. Objectiu i Abast
Crear un dashboard dedicat a les convocatòries europees **PJE** i **PJI** que permeti:
1. Visualitzar les sol·licituds totals enviades, i el desglossament complet de resolució: **Concedides** (`accepted`), **Rebutjades** (`rejected`), **Pendents** (`pending`) i **Renunciades** (`resigned`), així com la taxa d'èxit.
2. En clicar sobre una convocatòria, obrir un **panel lateral lliscant (*Slide-over Drawer*)** amb tècniques modernes de visualització que mostri la distribució de les sol·licituds i concessions per **Àmbit de Coneixement** i per **Departament** (atribuït via `managingOrganization`).

## 2. Arquitectura Backend

### 2.1 Identificació de convocatòries PJE i PJI
A `ApplicationController.java`:
- Al mètode `statsByFundingOpportunity(...)`, incloure `fundingTypeUri` (extret de `type.uri` a la col·lecció `FundingOpportunities`, p. ex. `/dk/atira/pure/fundingopportunity/fundingopportunitytypes/externalfundingopportunity/pro/pje` i `.../pro/pji`), a més de `fundingType` (`type.term`).
- Afegir un camp calculat `programCode` (`"PJE"`, `"PJI"` o `null`) basat en `type.uri` (sufix `/pro/pje` o `/pro/pji`, o coincidència al `type.term` si faltés `uri`), i suportar un paràmetre opcional `@RequestParam(required = false) String program` (p. ex. `PJE,PJI` o `PJE` / `PJI`) per filtrar directament al servidor o retornar tots els camps perquè el frontend també pugui filtrar ràpidament.

### 2.2 Nou endpoint de detall per convocatòria
**Endpoint:** `GET /api/applications/stats/by-funding-opportunity/{fundingUuid}/breakdown`  
**Paràmetres:**
- `fundingUuid` (path variable)
- `excludeResigned` (boolean, opcional, per defecte `false`)

**Lògica d'agregació (MongoDB):**
1. Filtrar documents a `Applications` per `fundingOpportunity.uuid == fundingUuid` (excloent `resignedAppUuids` si `excludeResigned=true`).
2. Classificar cada sol·licitud amb la mateixa lògica existent a `statsByFundingOpportunity`:
   - `rejected`, `accepted`, `pending` (`sent - accepted - rejected`, mínim 0), `isResigned`.
3. Extreure `managingOrgUuid` de `$managingOrganization.uuid` (amb fallback `"_UNKNOWN_"` si no n'hi ha).
4. Agrupar per `managingOrgUuid` sumant `sent`, `accepted`, `rejected`, `pending` i `resigned`.
5. Resoldre metadades de cada `managingOrgUuid`:
   - Nom oficial de l'organització des de `Organizations` (`name.ca_ES` / `name.es_ES` / `name.en_GB` / `name.text`).
   - Àmbit de coneixement (`ambit`) des de `v_orga_ambit` creuant per `uuid == managingOrgUuid` (fallback `"Sense àmbit assignat"`).
6. Retornar JSON estructurat amb:
   - `fundingUuid`, `total` (`sent`, `accepted`, `rejected`, `pending`, `resigned`)
   - `byAmbit`: llista agregada per `ambit`, ordenada per `sent` descendent
   - `byDepartment`: llista per departament (`orgUuid`, `departmentName`, `ambit`, `sent`, `accepted`, `rejected`, `pending`, `resigned`), ordenada per `sent` descendent.

## 3. Arquitectura Frontend (`src/main/webapp/convocatories-europees.html`)

### 3.1 Estil i Sistema de Disseny
- Segueix l'estil UAB existent: [uab-theme.css](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/src/main/webapp/uab-theme.css), [uab-brand.js](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/src/main/webapp/uab-brand.js), Tailwind CSS i FontAwesome.
- Aplicació dels criteris de `ui-ux-pro-max` (*Data-Dense Dashboard*):
  - Contrast ≥ 4.5:1, estats `:focus-visible` per a navegació amb teclat, `cursor-pointer` a tots els elements clicables, transicions suaus (150–300ms) respectant `prefers-reduced-motion`.
  - Tipografia tabular (`tabular-nums`) per a totes les xifres i percentatges.

### 3.2 Estructura de la pàgina principal
1. **Capçalera i KPIs globals (5 targetes):**
   - Total Sol·licituds Enviades
   - Concedides (amb % taxa d'èxit)
   - Rebutjades (amb %)
   - Pendents (amb %)
   - Renunciades (amb %)
   *(Es recalculen dinàmicament segons els filtres actius).*
2. **Barra de filtres compacta:**
   - **Selector segmentat (pills):** `Totes (PJE + PJI)` | `PJE` | `PJI`
   - **Cercador** per títol de convocatòria o UUID
   - **Rang d'anys** (slider `noUiSlider` o selector ràpid des de 2015)
   - **Toggle:** `Excloure renunciats`
3. **Taula densa de convocatòries:**
   - Columnes: Convocatòria (amb badge `PJE` / `PJI`), Any, Enviades, Concedides, Rebutjades, Pendents, Renunciades, **Distribució visual (barra apilada inline)** i **Taxa d'èxit (%)**.
   - Tota la fila és clicable (i accessible amb `Enter`/`Space`) per obrir el *drawer* de detall, destacant la fila seleccionada.

### 3.3 Detall modern per convocatòria (*Slide-over Drawer*)
En clicar una convocatòria s'obre un panell lateral dret (65% d'amplada en escriptori, 100% en mòbil) amb:
1. **Capçalera del Drawer:** Títol, badge `PJE`/`PJI`, any, botó d'enllaç extern a Egreta (`pureId`) i botó de tancament (`Esc` o clic fora).
2. **KPIs de la convocatòria:** Resum numèric + anell/barra de taxa d'èxit.
3. **Secció 1 — Distribució per Àmbit de Coneixement (`v_orga_ambit`):**
   - **Gràfic interactiu (Chart.js):** Barres horitzontals apilades (`Concedides`, `Rebutjades`, `Pendents`, `Renunciades`) o Donut de volum + taxa d'èxit.
   - **Interacció creuada (Cross-filtering):** Clicar un àmbit (o una targeta/fila d'àmbit) filtra automàticament la secció de Departaments per aquell àmbit, amb un chip per netejar el filtre.
4. **Secció 2 — Distribució per Departament (`managingOrganization`):**
   - Controls: Cercador de departament + selector d'ordenació (`Més sol·licituds` / `Major taxa d'èxit` / `Més concedides`).
   - **Gràfic de barres horitzontals apilades (Chart.js)** amb els departaments principals (Top N o tots amb scroll) mostrant clarament el desglossament.
   - **Taula detallada** amb tots els departaments, el seu àmbit (badge), xifres desglossades i taxa d'èxit.

### 3.4 Integració al Portal
- Afegir una targeta d'accés a [index.html](file:///c:/Users/1000788/OneDrive%20-%20UAB/Documentos/GitHub/Spring-egreta/src/main/webapp/index.html) enllaçant a `convocatories-europees.html`.

## 4. Verificació
- **Tests unitaris / integració backend (`ApplicationControllerTest.java`):**
  - Verificar que `statsByFundingOpportunity` retorna `fundingTypeUri` i `programCode` (`PJE` / `PJI`) i filtra correctament per `program`.
  - Verificar el nou endpoint `/breakdown` amb dades mockejades de `Applications`, `Organizations` i `v_orga_ambit` (agregació per `managingOrganization.uuid`, càlcul de `byAmbit` i `byDepartment`, i flag `excludeResigned`).
- **Comprovació de compilació:** `mvn test-compile` / `mvn test -Dtest=ApplicationControllerTest`.
