# Project Glossary: Spring-egreta

This document establishes the ubiquitous domain language for Spring-egreta. Use these terms consistently in code, commit messages, and architectural design.

## External Organizations & Curation

### External Organization
An external legal entity, university, research institute, company, or funding body outside the Universitat Autònoma de Barcelona (UAB) referenced in research outputs, grants, awards, or applications.

### Organization Curation
The process of inspecting, disambiguating, enriching with global persistent identifiers (e.g., ROR ID), detecting duplicates, and validating metadata for external organizations.

### Metadata Enrichment
Querying external registry authorities (ROR, OpenAlex, CrossRef) and linguistic/AI models to suggest or fill missing attributes (country, institution type, parent organization, website) for an organization.

### Batch Reconciliation (Auto-Apply)
Background automated workflows that apply inferred metadata (country, typology, funding authority, canonical name) or validate records when confidence scores exceed predefined thresholds.

### Organization Deduplication / Merge
Identifying duplicate organization profiles through name similarity (Levenshtein, Jaccard) and identifier overlap, allowing administrators to merge them into a single canonical entity.

### Metadata Provider Port
The seam interface through which external authorities (ROR, OpenAlex, CrossRef, LLM) supply candidate entity attributes to the curation engine.

### Reconciliation Job
An asynchronous unit of work executed within the curation module that runs rules against uncurated organizations and records progress without exposing raw threads or mutable locks to callers.

## Grants & Concessions (Awards)

### Award (Grant / Concession)
A competitive research grant, public call concession, external agreement, or fellowship awarded to UAB researchers or entities, tracking financial amounts, funding periods, and institutional commitments.

### Award Financial Engine
The deep module responsible for calculating economic indicators, overhead distributions, multi-year funding progressions, cost-center aggregations, and project resignation analytics.

### Award Classification Engine
The deep module handling grant typology categorization (estatals, internacionals, convenis), nature hierarchies, and automated batch proposal assignments.

### Award Report Port
The outbound seam interface through which grant analytics and aggregations are rendered into formatted reports (Word DOCX, Excel exports) without coupling the core domain to presentation binaries.

