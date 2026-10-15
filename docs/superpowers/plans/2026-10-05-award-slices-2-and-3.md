# Awards Architecture: Slice 2 (Report Port) & Slice 3 (Classification Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the decomposition of `AwardController.java` by extracting:
1. **Slice 2**: Apache POI Word export into `AwardReportPort` (outbound port) and `WordAwardReportAdapter`.
2. **Slice 3**: Grant taxonomy, nature hierarchies, proposal suggestion heuristics, and batch nature updates into `AwardClassificationEngine`.

**Architecture:**
- Domain package: `com.example.demo.award`
  - `port.AwardReportPort`: Domain port declaring document generation contracts (`byte[] generateCountryReport(String countryCode)`).
  - `adapter.WordAwardReportAdapter`: Infrastructure/Presentation adapter encapsulating Apache POI `XWPFDocument` creation, styling, and cell rendering.
  - `engine.AwardClassificationEngine`: Deep domain engine encapsulating nature hierarchy, unassigned nature discovery, batch proposal suggestions, and nature-funding relationship analysis.
  - `controller.AwardController`: Thin REST adapter handling HTTP parameters, validation, and JSON/binary response wrapping.

**Tech Stack:** Java 21, Spring Boot, Spring Data MongoDB, Apache POI (`ooxml`), JUnit 5, Mockito.

**Spec:** Defined in `GLOSSARY.md` and architecture review candidate 01.

## Global Constraints
- Preserve 100% backward compatibility for all existing HTTP endpoints under `/api/awards/*`.
- Do not alter response schemas (JSON structures or Word document binary format).
- Follow clean architecture boundaries: no Apache POI dependencies inside `AwardFinancialEngine` or `AwardClassificationEngine`.

## Review Focus
- Null/empty country code in `generateCountryReport` -> returns empty or gracefully handled document.
- Unassigned nature batch operations -> atomic updates and error counting matching existing controller behavior.
- Nature hierarchy tree building -> preservation of subcategory nesting and count invariants.

---

### Task 1: Slice 2 - Implement `AwardReportPort` & `WordAwardReportAdapter` (TDD)

**Files:**
- Create: `src/main/java/com/example/demo/award/port/AwardReportPort.java`
- Create: `src/main/java/com/example/demo/award/adapter/WordAwardReportAdapter.java`
- Create: `src/test/java/com/example/demo/award/WordAwardReportAdapterTest.java`

- [ ] **Step 1: Write failing unit test `WordAwardReportAdapterTest`**
  - Verify that `generateCountryReport(countryCode)` returns non-empty `byte[]` containing valid DOCX header bytes (`PK\x03\x04`).
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Define `AwardReportPort` interface**
  - Contract: `byte[] generateCountryReport(String countryCode);`
- [ ] **Step 4: Implement `WordAwardReportAdapter`**
  - Extract `generarInformeWordPais`, `writeAwardBlock`, `wordLabelValue`, `wordFormatDate`, `nestedStr`, `castList`, `countryCodeFromUri`, `extractPersonCountryCode`, `getDocPath` from `AwardController`.
- [ ] **Step 5: Run tests and verify they pass**

---

### Task 2: Slice 2 - Delegate Word Export in `AwardController`

**Files:**
- Modify: `src/main/java/com/example/demo/controller/AwardController.java`
- Create: `src/test/java/com/example/demo/award/AwardReportControllerIntegrationTest.java`

- [ ] **Step 1: Inject `AwardReportPort` into `AwardController`**
- [ ] **Step 2: Delegate `@GetMapping("/reports/word/country")` to `awardReportPort.generateCountryReport(countryCode)`**
- [ ] **Step 3: Remove extracted private POI styling methods from `AwardController`**
- [ ] **Step 4: Run integration test and verify endpoint delegates correctly**

---

### Task 3: Slice 3 - Implement `AwardClassificationEngine` (TDD)

**Files:**
- Create: `src/main/java/com/example/demo/award/engine/AwardClassificationEngine.java`
- Create: `src/test/java/com/example/demo/award/AwardClassificationEngineTest.java`

- [ ] **Step 1: Write failing unit test `AwardClassificationEngineTest`**
  - Verify nature hierarchy calculation and unassigned nature filtering.
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `AwardClassificationEngine`**
  - Extract:
    - `getAllUnassignedNatureAwards(page, size, search, sort, dir, filter)`
    - `updateAwardNature(uuid, request)`
    - `batchUpdateNature(request)`
    - `batchUpdateNatureByUuids(request)`
    - `batchApplySuggestedByUuids(request)`
    - `getProposedRelations(request)`
    - `batchUpdateAllProposals(request)`
    - `getAvailableNatures()`
    - `getNatureHierarchy()`
    - `getNatureFundingRelation(minProjects)`
- [ ] **Step 4: Run `AwardClassificationEngineTest` to verify it passes**

---

### Task 4: Slice 3 - Delegate Classification Endpoints in `AwardController`

**Files:**
- Modify: `src/main/java/com/example/demo/controller/AwardController.java`
- Create: `src/test/java/com/example/demo/award/AwardClassificationControllerIntegrationTest.java`

- [ ] **Step 1: Inject `AwardClassificationEngine` into `AwardController`**
- [ ] **Step 2: Delegate classification and nature endpoints**
- [ ] **Step 3: Run integration tests to ensure 100% pass**

---

### Task 5: Quality Gate & Verification

- [ ] **Step 1: Measure controller LOC reduction (target: < 2,000 LOC, down from 5,610 original)**
- [ ] **Step 2: Run full test suite: `mvn test -Dtest="Award*"`**
- [ ] **Step 3: Confirm all architectural invariants hold**
