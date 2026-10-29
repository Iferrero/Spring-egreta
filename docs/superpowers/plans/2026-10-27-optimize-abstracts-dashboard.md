# Pla d'Optimització de Rendiment: Abstracts Dashboard

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Aquest pla resol el coll d'ampolla sever de rendiment a la pàgina `abstracts-dashboard.html` i al servei backend `PublicacionAbstractsService.java`.
Objectiu: Reduir el temps de càrrega de ~50 minuts (o timeouts) a **menys d'1-2 segons**.

---

## Fitxers Afectats

- `src/main/java/com/example/demo/service/PublicacionAbstractsService.java`
  - Implementar detector lingüístic híbrid d'alta velocitat (`detectFastLanguage`) com a primera línia abans de Lingua.
  - Optimitzar `buildHasTextCond` canviant `$regexMatch` feixuc per `$strLenCP` natiu.
  - Paral·lelitzar `computeDynamicLinguisticStats` amb fil d'execució concurrent (`parallelStream` / batch processing) + cache ampliada.
  - Optimitzar `getAbstractsList` evitant projeccions massives en memòria a MongoDB.
- `src/test/java/com/example/demo/PublicacionAbstractsLanguageTest.java`
  - Validar precisió del detector de llengües i temps d'execució.
- `src/test/java/com/example/demo/PublicacionAbstractsServiceOptimizationTest.java` (nou)
  - Prova d'integració de rendiment que verifica que `getAbstractStats`, `getAbstractsByYear` i `getAbstractsList` responen en temps ràpid.
- `src/main/webapp/abstracts-dashboard.html`
  - Carregar primer els elements prioritaris (KPIs i taula) i després els gràfics en segona fase per alliberar el thread i el pool de connexions.
  - Cache en memòria frontend per evitar re-peticions innecessàries en canviar de pestanya o any.

---

## Tasca 1: Detector Lingüístic Híbrid d'Alta Velocitat

**Fitxers:**
- `src/main/java/com/example/demo/service/PublicacionAbstractsService.java`
- `src/test/java/com/example/demo/PublicacionAbstractsLanguageTest.java`

- [ ] **Pas 1.1:** Afegir mètode de classificació ràpida per marcadors i stopwords (`detectFastLanguage`) a `PublicacionAbstractsService.java`.
  - Reconeixement català: apòstrofs (`l'`, `d'`, `s'`, `m'`, `n'`, `t'`), dígrafs (`l·l`, `ŀ`), conjuncions/articles exclusius (`amb`, `dels`, `aquest`, `aquesta`, `són`, `també`).
  - Reconeixement castellà: conjuncions/articles exclusius (`con`, `los`, `las`, `para`, `este`, `esta`, `también`).
  - Reconeixement anglès: paraules troncals (`the`, `and`, `with`, `this`, `which`, `between`, `study`, `results`).
  - Si la confiança és alta (> llindar segur), retorna immediatament el codi d'idioma (`ca_ES`, `es_ES`, `en_GB`).
  - Si hi ha dubte o text molt curt, fa fallback al detector existent `LINGUA_DETECTOR`.
- [ ] **Pas 1.2:** Executar els tests existents `PublicacionAbstractsLanguageTest` per verificar que no hi ha cap regressió de precisió.
- [ ] **Pas 1.3:** Verificar que el temps mitjà de detecció per document baixa de 32 ms a < 0.5 ms.

---

## Tasca 2: Optimització de les Consultes MongoDB a `PublicacionAbstractsService`

**Fitxers:**
- `src/main/java/com/example/demo/service/PublicacionAbstractsService.java`

- [ ] **Pas 2.1:** Substituir `buildHasTextCond(langField)`:
  - Canviar el `$regexMatch` de `HTML_EMPTY_REGEX` per una condició nativa MongoDB basada en `$strLenCP` sobre el camp retallat (`$trim`), discriminant textos de longitud > 20 caràcters.
- [ ] **Pas 2.2:** Optimitzar la canalització de `getAbstractsList`:
  - En la fase d'ordenació i paginació, no projectar el contingut íntegre dels 3 abstracts per a les 247.000 publicacions.
  - Projectar només els metadats mínims de cerca, ordenar, paginar (`$skip`, `$limit 15`) i formatar únicament la pàgina seleccionada.
- [ ] **Pas 2.3:** Validar que les consultes MongoDB triguen menys de 800 ms.

---

## Tasca 3: Paral·lelització i Caché d'`computeDynamicLinguisticStats`

**Fitxers:**
- `src/main/java/com/example/demo/service/PublicacionAbstractsService.java`

- [ ] **Pas 3.1:** Actualitzar `computeDynamicLinguisticStats`:
  - Processar els documents obtinguts mitjançant un pool concurrent (`parallelStream()` o `ForkJoinPool` amb batching) utilitzant el detector híbrid ràpid.
  - Reduir el temps de càlcul de 50 minuts a ~2-3 segons en cas de càrrega en fred.
- [ ] **Pas 3.2:** Ampliar el TTL de la memòria cau (`CACHE_TTL_MS`) de 5 minuts a 60 minuts per a les estadístiques globals.
- [ ] **Pas 3.3:** Mantenir el mètode de neteja forçada `/cache/clear` perquè l'usuari pugui forçar el recàlcul quan vulgui des del botó "Actualitzar dades".

---

## Tasca 4: Millora de Càrrega i Concurrència al Frontend (`abstracts-dashboard.html`)

**Fitxers:**
- `src/main/webapp/abstracts-dashboard.html`

- [ ] **Pas 4.1:** Escalonar les crides a `carregarTot()`:
  - Fase 1 (Prioritat alta): Carregar immediatament KPIs (`carregarKPIsIGrafics`) i taula (`carregarTaula`). L'usuari veu la informació essencial en menys d'un segon.
  - Fase 2 (Secundari): Carregar els gràfics d'evolució històrica (`carregarEvolucioAnysPub`, `carregarEvolucioAnysSub`, `carregarIdiomesPerAny`, `carregarTipologies`).
- [ ] **Pas 4.2:** Implementar una memòria cau frontend simple per als filtres seleccionats prèviament per evitar repetir consultes idèntiques en canviar de filtre i tornar.
- [ ] **Pas 4.3:** Assegurar que els indicadors de càrrega (spinners) es comporten fluidament.

---

## Tasca 5: Verificació i Proves E2E de Rendiment

**Fitxers:**
- `src/test/java/com/example/demo/PublicacionAbstractsServiceOptimizationTest.java`

- [ ] **Pas 5.1:** Crear test de verificació que executa:
  - `getAbstractStats` (mesurar temps de resposta)
  - `getAbstractsByYear`
  - `getAbstractsList`
- [ ] **Pas 5.2:** Executar tota la suite de proves del projecte amb `mvn test -Dtest=PublicacionAbstractsLanguageTest,PublicacionAbstractsServiceOptimizationTest`.
- [ ] **Pas 5.3:** Validar l'aplicació en el navegador i comprovar la càrrega fluida.
