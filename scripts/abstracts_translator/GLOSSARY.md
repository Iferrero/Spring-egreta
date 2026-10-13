# Domain Glossary

## Core Architecture Concepts

### Translation Module
A deep module responsible for the end-to-end translation of academic abstracts. Encapsulates text sanitization, sentence-aligned chunking, dynamic token budgeting, structured prompt generation (primary and fallback templates), 4-tier retry escalation with temperature progression, translation completeness checking, target language validation, and exact copyright banner preservation. Presents a minimal single-method interface to callers.

### Inference Adapter
A concrete adapter satisfying the raw generation seam (`generate`). Translates structured chat messages into backend-specific HTTP or library requests, returning token counts, latency, and generated text without managing chunking, retries, or translation quality checks.
Adapters:
- `LMStudioAdapter`: Local LM Studio HTTP REST server.
- `VLLMServerAdapter`: OpenAI-compatible vLLM HTTP server.
- `LocalVLLMAdapter`: In-process GPU engine via vLLM Python SDK.
- `TransformersAdapter`: In-process HuggingFace PyTorch pipeline.
- `MockAdapter`: Instant synthetic responses for deterministic tests and benchmarks.

### Translation Result
A value object returned by the Translation Module containing the verified translation text, prompt token count, completion token count, measured latency in milliseconds, model identifier, and simulation flag.

### Translation Error
Domain exception raised when all prompt escalation and retry attempts fail completeness validation, language verification, or backend connectivity for a given input chunk.

### RO Intake Pipeline
A deep module orchestrating the end-to-end batch ingestion of Research Outputs. Encapsulates candidate query resolution, per-document 3-attempt retry resilience, translation execution via `AbstractProcessor`, atomic dual-collection database mutation (`Researchoutputs` and `ResearchoutputsAiAbstracts`), and Egreta REST API synchronization dispatch.

### Research Output Store (`ROStore`)
A persistence seam defining the contract for querying Research Outputs and persisting updated abstracts and AI traceability metadata.
Adapters:
- `MongoROStore`: Production MongoDB adapter with resilient pooling, UTF-8 codec options, and collection upsert indexing.
- `FakeROStore`: In-memory dictionary-backed adapter enabling offline, deterministic batch pipeline tests without database access.

### Intake Query
A value object encapsulating document selection criteria: single UUID lookup, publication/submission year filters, missing-year constraints, abstract presence filters, or high-discrepancy thresholds (`min_ratio`, `min_word_diff`).

### Execution Mode
The operational mode governing the intake pipeline:
- `dry-run`: Simulates processing and previews translations without database or API mutations.
- `benchmark`: Measures latencies and throughput across candidates without writing mutations.
- `apply`: Persists updated abstracts to MongoDB and synchronizes with Egreta.

### Abstract Cluster (`AbstractCluster`)
A deep domain module encapsulating the multi-lingual abstract representations associated with a Research Output. Manages slot normalization (`en_GB`, `es_ES`, `ca_ES`), filtering of placeholder statements (*"Sense resum..."*), screening of LLM corruptions and homoglyphs, detection of truncated text fragments, classification of genuine languages, cross-slot deduplication, and automated discrepancy metrics analysis. Presents a validated, high-level interface to translation decision workflows.

### Abstract Slot (`AbstractSlot`)
An immutable value object representing a validated, cleaned abstract slot. Encapsulates its original slot key (e.g. `en_GB`, `es_ES`, `ca_ES`), the sanitized text payload, and the verified detected language code.

### Egreta Synchronization Seam (`EgretaSyncAdapter`)
A seam defining the contract for dispatching updated multi-lingual abstracts to the institutional research repository (Egreta / Pure REST API).
Adapters:
- `HttpEgretaAdapter`: Production adapter communicating over HTTPS with session-level keep-alive, Cloudflare WAF challenge exponential backoff, XML/XHTML escaping, and canonical UUID synchronization.
- `FakeEgretaAdapter`: Deterministic in-memory simulation adapter maintaining call histories and enabling offline pipeline verification and simulated network failures.

### Sync Result (`SyncResult`)
An immutable value object encapsulating the outcome of an Egreta synchronization attempt. Contains `success` (boolean), `status_code` (HTTP status integer or None), `message` (human-readable status or diagnostic detail), and `synced_at` (ISO timestamp). Supports backward-compatible tuple unpacking `(success, message)`.
