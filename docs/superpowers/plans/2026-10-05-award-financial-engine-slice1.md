# Slice 1: AwardFinancialEngine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract financial aggregations, PowerTable analytics, cost-center statistics, prefixed award filters (PK/EA/PO), and concession resignation tracking from `AwardController` into a deep, testable `AwardFinancialEngine` module in `com.example.demo.award`.

**Architecture:** Create `com.example.demo.award` package hierarchy with `engine` and `model`. Encapsulate all raw BSON aggregation pipelines and financial calculation rules behind typed criteria and method contracts in `AwardFinancialEngine`. `AwardController` delegates its financial endpoints to `AwardFinancialEngine`, reducing controller mass by ~2,500 lines while preserving 100% JSON contract compatibility. Pure unit tests in `AwardFinancialEngineTest` prove calculation invariants and criteria construction without spinning up servlet containers.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data MongoDB, JUnit 5, AssertJ, Mockito.

**Spec:** Refactor Candidate 01 from architecture review, design decisions settled in Grilling rounds 1 & 2 (`A, A, A, A`).

## Global Constraints

- Preserve 100% backward compatibility for all existing HTTP endpoints and JSON response structures.
- Do not modify frontend JavaScript or HTML files consuming `/api/awards/*`.
- All tests must pass with `mvn test`.
- Package structure: `com.example.demo.award.engine` and `com.example.demo.award.model`.

## Review Focus

1. **Prefix parameterization parity:** Ensure `getPkAwardsStats()`, `getEaAwardsStats()`, and `getPoAwardsStats()` produce identical pipeline semantics when consolidated under `getPrefixedAwardsStats(prefix)`.
2. **Date & Year conversion invariants:** In MongoDB pipelines, date conversions (`$convert` to date, `$year`) must handle null, empty, and numeric years identically to legacy controller code.
3. **Resignation reason regex matches:** Status type discriminators (`TerminatedAwardStatus`, `CurtailedAwardStatus`, `DeclinedAwardStatus`) and reason regex clusters must preserve exact filtering logic.
4. **PowerTable Excel cache fallback:** `AwardFinancialEngine` must cooperate cleanly with `AwardService`'s static Excel cache for "Prestació de Serveis" without circular dependencies.
5. **Pagination & sorting consistency:** Paged endpoints like `getResignedAwards` must return identical `content`, `totalElements`, `totalPages`, `size`, and `page` keys.

---

## Tasks

### Task 1: Create Model and Query Criteria Records in `com.example.demo.award.model`

**Files:**
- Create: `src/main/java/com/example/demo/award/model/ResignedAwardsQueryCriteria.java`
- Create: `src/main/java/com/example/demo/award/model/PowerTableQueryCriteria.java`
- Create: `src/main/java/com/example/demo/award/model/CostCodePrefix.java`
- Test: `src/test/java/com/example/demo/award/AwardCriteriaTest.java`

- [ ] **Step 1.1**: Write failing unit test `AwardCriteriaTest` verifying criteria record builders, default values, and `CostCodePrefix` matching rules.
- [ ] **Step 1.2**: Implement `CostCodePrefix` enum (`PK("PK")`, `EA("EA")`, `PO("PO")`, `ALL(".*")`).
- [ ] **Step 1.3**: Implement `PowerTableQueryCriteria` record with `desde`, `hasta`, `modoAnio`, and `collaboratorUuid`.
- [ ] **Step 1.4**: Implement `ResignedAwardsQueryCriteria` record with pagination, filters, and sorting parameters.
- [ ] **Step 1.5**: Run `mvn test -Dtest=AwardCriteriaTest` to ensure green.

---

### Task 2: Implement Core Pipelines & Invariants in `AwardFinancialEngine` (TDD)

**Files:**
- Create: `src/main/java/com/example/demo/award/engine/AwardFinancialEngine.java`
- Test: `src/test/java/com/example/demo/award/AwardFinancialEngineTest.java`

- [ ] **Step 2.1**: Write unit tests in `AwardFinancialEngineTest` with mocked `MongoTemplate` testing:
  - Pipeline construction for prefixed awards (PK, EA, PO)
  - Pipeline construction for cost-center convocatorias
  - Resignation query criteria assembly and reason categorization regexes
  - PowerTable aggregation stage sequence and filtering
- [ ] **Step 2.2**: Implement `AwardFinancialEngine` with `@Service` annotation, injecting `MongoTemplate` and `AwardService` (for Excel cache access).
- [ ] **Step 2.3**: Implement prefixed awards methods (`getPkAwardsStats`, `getEaAwardsStats`, `getPoAwardsStats`, `getPrefixedAwardsStats(CostCodePrefix)`).
- [ ] **Step 2.4**: Implement cost-center and funding relationship methods (`getCostCenterConvocatorias`, `getNatureFundingRelation`).
- [ ] **Step 2.5**: Implement resignation and concession methods (`getResignedAwards`, `getReasonComparison`, `getDistinctReasons`, `getConcessionsByProgram`, `getConcessionsAwards`).
- [ ] **Step 2.6**: Implement PowerTable and multi-year methods (`getPowerTable`, `getTipusPerCategoria`, `getAwardsByAmount`, `getImportPerTipusAnio`, `getTotalStats`, `getProyectosPorAnio`).
- [ ] **Step 2.7**: Run `mvn test -Dtest=AwardFinancialEngineTest` to verify all tests pass.

---

### Task 3: Delegate Financial Endpoints in `AwardController` to `AwardFinancialEngine`

**Files:**
- Modify: `src/main/java/com/example/demo/controller/AwardController.java`
- Test: `src/test/java/com/example/demo/award/AwardFinancialControllerIntegrationTest.java`

- [ ] **Step 3.1**: Write integration test `AwardFinancialControllerIntegrationTest` using `@WebMvcTest(AwardController.class)` or standalone MockMvc verifying endpoints delegate correctly to `AwardFinancialEngine`.
- [ ] **Step 3.2**: Inject `AwardFinancialEngine` into `AwardController` constructor.
- [ ] **Step 3.3**: Replace inline BSON aggregation logic in `getPkAwardsStats`, `getEaAwardsStats`, `getPoAwardsStats`, `getPrefixedAwardsStats` with calls to `awardFinancialEngine`.
- [ ] **Step 3.4**: Replace inline BSON logic in `getCostCenterConvocatorias` and `getNatureFundingRelation` with calls to `awardFinancialEngine`.
- [ ] **Step 3.5**: Replace inline logic in `getResignedAwards`, `getReasonComparison`, `getDistinctReasons` with calls to `awardFinancialEngine`.
- [ ] **Step 3.6**: Verify clean build and run full test suite with `mvn test`.

---

### Task 4: Verification and Quality Gate

**Files:**
- Check: `src/main/java/com/example/demo/controller/AwardController.java`
- Check: `src/main/java/com/example/demo/award/engine/AwardFinancialEngine.java`

- [ ] **Step 4.1**: Verify LOC reduction in `AwardController.java` (expected drop: >1,500 lines removed from controller).
- [ ] **Step 4.2**: Run full Maven build and all project tests: `mvn clean test`.
- [ ] **Step 4.3**: Confirm zero regressions in existing tests (`ApplicationControllerTest`, `StrategicIndicatorsControllerMockTest`, `AiAbstractsTest`).
