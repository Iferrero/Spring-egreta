# Scopus Affiliation Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the Elsevier Scopus Affiliation Search API into the External Organizations module to enrich organization metadata (validated country, city, Scopus AF-ID, and publication volume) and surface these indicators in `organizaciones-externas-kpis.html`.

**Architecture:** 
1. Build `ScopusAffiliationService` to query Elsevier's `https://api.elsevier.com/content/search/affiliation`, parse affiliation profiles (AF-ID, name, country, city, document count), and cache responses in MongoDB (`scopus_affiliations_cache`) using the configured `app.scopus.api-key`.
2. Expose a REST endpoint in `ExternalOrganizationController` (`/stats/scopus-affiliation`) and enrich `suggest-metadata` so that Scopus signals contribute to country resolution and confidence boosting.
3. Update `organizaciones-externas-kpis.html` to display a Scopus indicator badge (showing publication count and linking to the Scopus affiliation profile) and utilize Scopus suggestions in the UI.

**Tech Stack:** Java 21, Spring Boot 4.x / Spring Data MongoDB, Java `HttpClient`, HTML5 / Vanilla JS / Tailwind CSS.

**Spec:** Conversational design approved in chat (Bounded enhancement integrating Scopus Affiliation Search API with caching, controller endpoints, and frontend indicators in `organizaciones-externas-kpis.html`).

## Global Constraints

- Re-use the existing Scopus API Key (`app.scopus.api-key`) from `application.properties`.
- Use non-blocking/cached requests to avoid unnecessary consumption of Elsevier API quota.
- Graceful degradation: If Scopus API fails, times out, or returns 404/no match, existing ROR and IA flows must continue uninterrupted.
- Preserve all existing comments and UI styles in `organizaciones-externas-kpis.html`.

## Review Focus

1. **Empty / No-match responses:** When an organization is not in Scopus (e.g. local government or small SME), the service returns an empty result cleanly without logging errors or breaking metadata inference.
2. **Special characters in organization names:** Accents, ampersands, or quotes in names must be properly URL-encoded for the Scopus search query without HTTP 400 errors.
3. **API Rate Limiting or Network Timeouts:** In case of Scopus API timeout (>= 4s) or 429 quota limit, the fallback to cache or empty result must be immediate.
4. **Cache hits:** A repeated search for the same organization name must hit MongoDB cache without making outbound HTTP calls to Elsevier.
5. **Frontend rendering:** Missing Scopus affiliation must not render broken badges or alter table row heights.

---

### Task 1: Create `ScopusAffiliationService` with MongoDB Caching

**Files:**
- Create: `src/main/java/com/example/demo/service/ScopusAffiliationService.java`
- Test: `src/test/java/com/example/demo/ScopusAffiliationServiceTest.java`

- [ ] **Step 1.1: Write failing test for `ScopusAffiliationService`**
  - Verify that query parsing works for affiliation entries (afid, name, city, country, documentCount, url).
  - Verify cache storage and retrieval using a mocked or real MongoTemplate.
- [ ] **Step 1.2: Run the test to confirm it fails**
- [ ] **Step 1.3: Implement `ScopusAffiliationService`**
  - Implement `findAffiliation(String orgName)` with `HttpClient`, timeouts, and query sanitization.
  - Implement MongoDB collection caching in `scopus_affiliations_cache` (store `query`, `afid`, `affiliationName`, `city`, `country`, `documentCount`, `url`, `timestamp`).
  - Implement cache check before outbound HTTP call.
- [ ] **Step 1.4: Run test to confirm it passes**

---

### Task 2: Expose Endpoint & Integrate with `ExternalOrganizationController`

**Files:**
- Modify: `src/main/java/com/example/demo/controller/ExternalOrganizationController.java`
- Test: `src/test/java/com/example/demo/ExternalOrganizationScopusEndpointTest.java`

- [ ] **Step 2.1: Write integration test for `/api/external-organizations/stats/scopus-affiliation`**
  - Test GET endpoint with a known organization (e.g. "Universitat Autònoma de Barcelona").
  - Expect JSON response with fields `afid`, `name`, `country`, `city`, `documentCount`, `url`.
- [ ] **Step 2.2: Run test to confirm it fails**
- [ ] **Step 2.3: Implement endpoint in `ExternalOrganizationController`**
  - Inject `ScopusAffiliationService`.
  - Add `@GetMapping("/stats/scopus-affiliation")`.
  - Enrich `suggestMetadataByName` so if Scopus confirms the country and confidence is low, it boosts confidence or provides corroboration.
- [ ] **Step 2.4: Run test to confirm it passes**

---

### Task 3: Update `organizaciones-externas-kpis.html` UI

**Files:**
- Modify: `src/main/webapp/organizaciones-externas-kpis.html`

- [ ] **Step 3.1: Update JavaScript bundle fetcher**
  - In `fetchSuggestionBundle`, incorporate call to `/stats/scopus-affiliation` (or include in bundle) to get Scopus info.
- [ ] **Step 3.2: Render Scopus badge in the organization row**
  - If Scopus data is present (`afid` and `documentCount > 0`):
    - Render a pill/badge: `[🟠 Scopus: X docs]` with link to `https://www.scopus.com/affil/profile.uri?afid=...` target="_blank".
- [ ] **Step 3.3: Manual / headless visual verification**
  - Verify table displays properly with and without Scopus data.

---

### Task 4: Full System Verification

- [ ] **Step 4.1: Run all related tests**
  - Run `mvn test -Dtest=ScopusAffiliationServiceTest,ExternalOrganizationScopusEndpointTest`.
- [ ] **Step 4.2: Verify integration and document changes**
