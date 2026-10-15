from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import re
import time
from typing import Optional, List, Dict, Any, Tuple, Callable, Union
from tqdm import tqdm

from .config import (
    MONGO_URI,
    MONGO_DB_NAME,
    MONGO_COLLECTION,
    MONGO_AI_METADATA_COLLECTION,
    LANG_EN,
    LANG_ES,
    LANG_CA
)
from .processor import AbstractProcessor
from .detector import has_identical_cloned_abstracts, analyze_discrepancies
from .egreta_sync import (
    EgretaSyncAdapter,
    HttpEgretaAdapter,
    FakeEgretaAdapter,
    SyncResult,
    sync_abstract_to_egreta
)
from .benchmark import BenchmarkCollector


class CallableEgretaAdapter(EgretaSyncAdapter):
    """Adapter wrapping a legacy callable sync function to satisfy EgretaSyncAdapter."""
    def __init__(self, fn: Callable):
        self.fn = fn

    def sync_abstracts(
        self,
        uuid: str,
        abstracts: Dict[str, str],
        env: str = "test",
        api_key: Optional[str] = None,
        timeout: int = 20
    ) -> SyncResult:
        res = self.fn(uuid=uuid, abstracts=abstracts, env=env, api_key=api_key)
        synced_at = datetime.now(timezone.utc).isoformat()
        if isinstance(res, SyncResult):
            return res
        if isinstance(res, tuple):
            ok, msg = res
            return SyncResult(success=ok, message=msg, synced_at=synced_at)
        return SyncResult(success=bool(res), message=str(res), synced_at=synced_at)


def extract_title(doc: dict) -> str:
    """Extracts human-readable title from Research Output document."""
    t = doc.get("title")
    if isinstance(t, dict):
        return t.get("value") or t.get("ca_ES") or t.get("es_ES") or t.get("en_GB") or "Sin titulo"
    elif isinstance(t, str):
        return t
    return "Sin titulo"


def extract_publication_year(doc: dict) -> Optional[int]:
    """Extracts publication year from publicationStatuses list."""
    pub_statuses = doc.get("publicationStatuses")
    if isinstance(pub_statuses, list):
        for ps in pub_statuses:
            if isinstance(ps, dict) and ps.get("current"):
                pub_date = ps.get("publicationDate")
                if isinstance(pub_date, dict) and pub_date.get("year"):
                    try:
                        return int(pub_date.get("year"))
                    except (ValueError, TypeError):
                        pass
        for ps in pub_statuses:
            if isinstance(ps, dict):
                pub_date = ps.get("publicationDate")
                if isinstance(pub_date, dict) and pub_date.get("year"):
                    try:
                        return int(pub_date.get("year"))
                    except (ValueError, TypeError):
                        pass
    return None


def infer_target_langs(source_lang: Optional[str]) -> List[str]:
    """Infers missing generated target languages based on the source language."""
    if not source_lang:
        return [LANG_CA, LANG_ES]
    if source_lang in (LANG_EN, "en"):
        return [LANG_CA, LANG_ES]
    elif source_lang in (LANG_ES, "es"):
        return [LANG_CA, LANG_EN]
    elif source_lang in (LANG_CA, "ca"):
        return [LANG_ES, LANG_EN]
    else:
        return [LANG_CA, LANG_ES, LANG_EN]


@dataclass
class IntakeQuery:
    """Value object encapsulating document selection criteria."""
    uuid: Optional[str] = None
    year: Optional[str] = None
    year_type: str = "submission"  # 'submission' or 'publication'
    no_year: bool = False
    only_with_abstract: bool = True
    only_ai_collection: bool = False
    # High-discrepancy query options
    is_discrepancy_query: bool = False
    min_ratio: float = 4.0
    min_word_diff: int = 150


@dataclass
class IntakeOptions:
    """Value object encapsulating operational configuration for batch intake."""
    mode: str = "benchmark"  # 'benchmark', 'dry-run', or 'apply'
    sync_egreta: bool = False
    egreta_env: str = "test"
    egreta_api_key: Optional[str] = None
    limit: int = 10
    report_file: Optional[str] = None
    source_lang: Optional[str] = None
    target_lang: Optional[str] = None
    force: bool = False
    show_progress: bool = True


# =====================================================================
# Persistence Seam (ROStore) & Adapters
# =====================================================================

class ROStore(ABC):
    """
    Persistence seam defining the contract for querying Research Outputs
    and storing updated abstracts and AI metadata.
    """
    @abstractmethod
    def fetch_candidates(self, query: IntakeQuery, limit: int = 0) -> List[Dict[str, Any]]:
        """Fetches candidate RO documents matching query criteria."""
        pass

    @abstractmethod
    def fetch_ai_metadata(self, uuid: str) -> Optional[Dict[str, Any]]:
        """Retrieves existing AI metadata document for a given RO UUID."""
        pass

    @abstractmethod
    def update_abstract(self, uuid: str, abstracts: Dict[str, str]) -> None:
        """Updates the abstract field in the primary Researchoutputs collection."""
        pass

    @abstractmethod
    def save_ai_metadata(self, ai_doc: Dict[str, Any]) -> None:
        """Upserts AI metadata document into ResearchoutputsAiAbstracts."""
        pass


class MongoROStore(ROStore):
    """Production MongoDB adapter with resilient codec options and collection upsert indexing."""
    def __init__(
        self,
        mongo_uri: str = MONGO_URI,
        db_name: str = MONGO_DB_NAME,
        collection_name: str = MONGO_COLLECTION,
        ai_collection_name: str = MONGO_AI_METADATA_COLLECTION
    ):
        import pymongo
        from bson.codec_options import CodecOptions
        codec_options = CodecOptions(unicode_decode_error_handler="replace")
        self.client = pymongo.MongoClient(mongo_uri, serverSelectionTimeoutMS=5000)
        self.db = self.client[db_name]
        self.collection = self.db[collection_name].with_options(codec_options=codec_options)
        self.collection_ai = self.db[ai_collection_name].with_options(codec_options=codec_options)
        self.client.admin.command('ping')
        self.collection_ai.create_index("uuid", unique=True)

    def fetch_candidates(self, query: IntakeQuery, limit: int = 0) -> List[Dict[str, Any]]:
        if query.is_discrepancy_query:
            return self._fetch_discrepancy_candidates(query, limit)

        mongo_q: Dict[str, Any] = {}
        if query.uuid:
            mongo_q["uuid"] = query.uuid
        else:
            if query.only_ai_collection:
                ai_uuids = self.collection_ai.distinct("uuid")
                mongo_q["uuid"] = {"$in": ai_uuids}

            if query.only_with_abstract:
                mongo_q["abstract"] = {"$exists": True, "$ne": None, "$ne": {}}

            if query.no_year or (query.year is not None and str(query.year).lower() in ["none", "null", "0"]):
                if query.year_type == "publication":
                    mongo_q["$or"] = [
                        {"publicationStatuses": {"$exists": False}},
                        {"publicationStatuses": None},
                        {"publicationStatuses": []},
                        {"publicationStatuses.publicationDate.year": None},
                        {"publicationStatuses.publicationDate.year": {"$exists": False}},
                        {"publicationStatuses.publicationDate.year": 0},
                        {"publicationStatuses.publicationDate.year": ""}
                    ]
                else:
                    mongo_q["$or"] = [
                        {"submissionYear": None},
                        {"submissionYear": {"$exists": False}},
                        {"submissionYear": 0},
                        {"submissionYear": ""}
                    ]
            elif query.year is not None:
                year_int = int(query.year)
                if query.year_type == "publication":
                    mongo_q["publicationStatuses.publicationDate.year"] = year_int
                else:
                    mongo_q["submissionYear"] = year_int

        cursor = self.collection.find(
            mongo_q,
            {"uuid": 1, "title": 1, "abstract": 1, "submissionYear": 1, "publicationStatuses": 1}
        )
        if not query.uuid and limit > 0:
            cursor = cursor.limit(limit)
        return list(cursor)

    def _fetch_discrepancy_candidates(self, query: IntakeQuery, limit: int = 0) -> List[Dict[str, Any]]:
        ai_query: Dict[str, Any] = {}
        if query.year and str(query.year).lower() != "all":
            try:
                y_int = int(query.year)
                ai_query["$or"] = [{"submissionYear": y_int}, {"publicationYear": y_int}]
            except ValueError:
                pass

        cursor = self.collection_ai.find(ai_query)
        target_docs = []
        for ai_doc in cursor:
            meta = ai_doc.get("abstract_ai_metadata", {})
            disc = ai_doc.get("discrepancy_analysis") or meta.get("discrepancy_analysis", {})
            ratio_val = disc.get("ratio_words", 0.0)
            diff_val = disc.get("diff_words", 0)

            if ratio_val >= query.min_ratio or diff_val >= query.min_word_diff:
                uuid = ai_doc.get("uuid")
                if not uuid:
                    continue
                base_doc = self.collection.find_one({"uuid": uuid})
                if base_doc:
                    # Embed AI metadata guidance onto candidate for forced retranslation
                    base_doc["_ai_metadata_hint"] = meta
                    base_doc["_discrepancy_hint"] = {"ratio": ratio_val, "diff": diff_val}
                    target_docs.append(base_doc)
                    if limit > 0 and len(target_docs) >= limit:
                        break
        return target_docs

    def fetch_ai_metadata(self, uuid: str) -> Optional[Dict[str, Any]]:
        return self.collection_ai.find_one({"uuid": uuid})

    def update_abstract(self, uuid: str, abstracts: Dict[str, str]) -> None:
        self.collection.update_one({"uuid": uuid}, {"$set": {"abstract": abstracts}})

    def save_ai_metadata(self, ai_doc: Dict[str, Any]) -> None:
        self.collection_ai.update_one(
            {"uuid": ai_doc["uuid"]},
            {
                "$set": ai_doc,
                "$unset": {"abstract_ai_metadata.discrepancy_analysis": ""}
            },
            upsert=True
        )


class FakeROStore(ROStore):
    """In-memory dictionary store for fast, deterministic unit testing."""
    def __init__(
        self,
        initial_ros: Optional[List[Dict[str, Any]]] = None,
        initial_ai: Optional[List[Dict[str, Any]]] = None
    ):
        self.ros: Dict[str, Dict[str, Any]] = {}
        self.ai_meta: Dict[str, Dict[str, Any]] = {}
        if initial_ros:
            for r in initial_ros:
                self.ros[r.get("uuid")] = dict(r)
        if initial_ai:
            for a in initial_ai:
                self.ai_meta[a.get("uuid")] = dict(a)

    def fetch_candidates(self, query: IntakeQuery, limit: int = 0) -> List[Dict[str, Any]]:
        results = []
        if query.is_discrepancy_query:
            for uuid, ai_doc in self.ai_meta.items():
                meta = ai_doc.get("abstract_ai_metadata", {})
                disc = ai_doc.get("discrepancy_analysis") or meta.get("discrepancy_analysis", {})
                ratio = disc.get("ratio_words", 0.0)
                diff = disc.get("diff_words", 0)
                if ratio >= query.min_ratio or diff >= query.min_word_diff:
                    base_doc = self.ros.get(uuid)
                    if base_doc:
                        b_copy = dict(base_doc)
                        b_copy["_ai_metadata_hint"] = meta
                        b_copy["_discrepancy_hint"] = {"ratio": ratio, "diff": diff}
                        results.append(b_copy)
                        if limit > 0 and len(results) >= limit:
                            break
            return results

        for r in self.ros.values():
            if query.uuid and r.get("uuid") != query.uuid:
                continue
            if query.only_with_abstract and not r.get("abstract"):
                continue
            if query.year and str(r.get("submissionYear")) != str(query.year):
                continue
            results.append(dict(r))
            if limit > 0 and len(results) >= limit:
                break
        return results

    def fetch_ai_metadata(self, uuid: str) -> Optional[Dict[str, Any]]:
        item = self.ai_meta.get(uuid)
        return dict(item) if item else None

    def update_abstract(self, uuid: str, abstracts: Dict[str, str]) -> None:
        if uuid in self.ros:
            self.ros[uuid]["abstract"] = dict(abstracts)

    def save_ai_metadata(self, ai_doc: Dict[str, Any]) -> None:
        self.ai_meta[ai_doc["uuid"]] = dict(ai_doc)


# =====================================================================
# Deep RO Intake Pipeline
# =====================================================================

class ROIntakePipeline:
    """
    Deep module orchestrating the batch ingestion of Research Outputs.
    Encapsulates:
    - Candidate document resolution
    - ALREADY_COMPLETE pre-checks against existing AI metadata
    - 3-attempt resilient retry loop per document
    - Dual-collection persistence (Researchoutputs + ResearchoutputsAiAbstracts)
    - Egreta REST API synchronization dispatch
    - Progress visualization and BenchmarkCollector metrics aggregation
    """
    def __init__(
        self,
        store: ROStore,
        processor: AbstractProcessor,
        egreta_adapter: Optional[Union[EgretaSyncAdapter, Callable]] = None,
        egreta_syncer: Optional[Callable] = None,
        collector: Optional[BenchmarkCollector] = None
    ):
        self.store = store
        self.processor = processor
        syncer_target = egreta_adapter or egreta_syncer or HttpEgretaAdapter()
        if isinstance(syncer_target, EgretaSyncAdapter):
            self.egreta_adapter = syncer_target
        elif callable(syncer_target):
            self.egreta_adapter = CallableEgretaAdapter(syncer_target)
        else:
            self.egreta_adapter = HttpEgretaAdapter()
        self.egreta_syncer = self.egreta_adapter.sync_abstracts
        self.collector = collector

    def run(self, query: IntakeQuery, options: IntakeOptions) -> Dict[str, Any]:
        engine = getattr(self.processor, "engine", None)
        model_name = getattr(engine, "model_name", "Salamandra")
        engine_name = getattr(engine, "__class__", type(None)).__name__
        collector = self.collector or BenchmarkCollector(engine_name=engine_name, model_name=model_name)

        docs = self.store.fetch_candidates(query, limit=options.limit)
        total_docs = len(docs)

        if total_docs == 0:
            report = collector.finalize()
            return report

        applied_count = 0
        egreta_synced_count = 0
        egreta_failed_count = 0

        pbar = tqdm(total=total_docs, desc="Procesando ROs", unit="doc", disable=not options.show_progress)

        for doc in docs:
            uuid = doc.get("uuid", str(doc.get("_id", "unknown")))
            title = extract_title(doc)
            sub_year = extract_publication_year(doc) if query.year_type == "publication" else doc.get("submissionYear")
            if sub_year is None:
                sub_year = doc.get("submissionYear") or extract_publication_year(doc)

            # Determine forced languages from discrepancy hint if available
            forced_src = options.source_lang
            forced_tgt = options.target_lang
            force_run = options.force or query.is_discrepancy_query

            if query.is_discrepancy_query and "_ai_metadata_hint" in doc:
                hint = doc["_ai_metadata_hint"]
                forced_src = forced_src or hint.get("source_language")
                gen_langs = hint.get("generated_languages")
                if not gen_langs or not isinstance(gen_langs, list):
                    gen_langs = infer_target_langs(forced_src)
                forced_tgt = forced_tgt or ",".join(gen_langs)

            if options.mode == "dry-run" and query.is_discrepancy_query:
                disc_info = doc.get("_discrepancy_hint", {})
                pbar.write(f"\n[DRY-RUN] RO UUID: {uuid}")
                pbar.write(f"  - Título: {title[:70]}...")
                pbar.write(f"  - Discrepancia: ratio={disc_info.get('ratio')}, diff={disc_info.get('diff')}")
                pbar.write(f"  - Idioma origen: {forced_src} -> Re-traducir a: {forced_tgt}")
                pbar.update(1)
                continue

            # Pre-check: Skip already completed and synced records unless forced
            if not force_run and not has_identical_cloned_abstracts(doc.get("abstract")):
                ai_doc_existing = self.store.fetch_ai_metadata(uuid)
                if ai_doc_existing:
                    meta = ai_doc_existing.get("abstract_ai_metadata", {})
                    sync_info = meta.get("egreta_sync", {})
                    is_already_synced = False
                    if options.sync_egreta:
                        if sync_info.get("synced") is True and sync_info.get("env") == options.egreta_env:
                            is_already_synced = True
                    else:
                        if meta.get("generated_languages") or meta.get("source_language"):
                            is_already_synced = True

                    if is_already_synced:
                        collector.add_ro_result(uuid, title, sub_year, {
                            "status": "ALREADY_COMPLETE",
                            "original_abstract": doc.get("abstract"),
                            "updated_abstract": doc.get("abstract"),
                            "ai_metadata": meta,
                            "translations_done": [],
                            "decision_branch": "already_synced_in_ai_collection"
                        })
                        pbar.update(1)
                        continue

            # Resilient 3-attempt execution loop per document
            max_doc_attempts = 3
            for attempt in range(max_doc_attempts):
                try:
                    res = self.processor.process_ro_abstracts(
                        doc,
                        forced_source_lang=forced_src,
                        forced_target_lang=forced_tgt,
                        force_retranslate=force_run
                    )
                    collector.add_ro_result(uuid, title, sub_year, res)

                    # Determine if Egreta sync is pending
                    needs_egreta_sync = False
                    ai_doc_existing = None
                    if options.mode == "apply" and options.sync_egreta:
                        ai_doc_existing = self.store.fetch_ai_metadata(uuid)
                        if ai_doc_existing:
                            existing_sync = ai_doc_existing.get("abstract_ai_metadata", {}).get("egreta_sync", {})
                            if not existing_sync.get("synced") or existing_sync.get("env") != options.egreta_env:
                                needs_egreta_sync = True
                        else:
                            needs_egreta_sync = True

                    # Apply persistence if changes occurred or sync is needed
                    if options.mode == "apply" and (res["status"] in ["TRANSLATED", "REORGANIZED"] or needs_egreta_sync):
                        # 1. Update Researchoutputs
                        if res["status"] in ["TRANSLATED", "REORGANIZED"]:
                            self.store.update_abstract(uuid, res["updated_abstract"])

                        # 2. Dispatch Egreta sync
                        egreta_status = None
                        if options.sync_egreta and (res["status"] in ["TRANSLATED", "REORGANIZED"] or needs_egreta_sync):
                            sync_res = self.egreta_adapter.sync_abstracts(
                                uuid=uuid,
                                abstracts=res["updated_abstract"],
                                env=options.egreta_env,
                                api_key=options.egreta_api_key
                            )
                            sync_ok = sync_res.success
                            sync_msg = sync_res.message
                            egreta_status = {
                                "synced": sync_ok,
                                "env": options.egreta_env,
                                "synced_at": sync_res.synced_at or datetime.now(timezone.utc).isoformat(),
                                "message": sync_msg,
                                "status_code": sync_res.status_code
                            }
                            if sync_ok:
                                egreta_synced_count += 1
                            else:
                                egreta_failed_count += 1
                                pbar.write(f"\n[!] Error sync Egreta ({uuid}): {sync_msg}")

                        # 3. Store AI metadata document
                        ai_metadata_payload = dict(res["ai_metadata"]) if res.get("ai_metadata") else (
                            dict(ai_doc_existing.get("abstract_ai_metadata", {})) if ai_doc_existing else {}
                        )
                        if egreta_status:
                            ai_metadata_payload["egreta_sync"] = egreta_status

                        # Ensure discrepancy_analysis is stored at root, not inside abstract_ai_metadata
                        ai_metadata_payload.pop("discrepancy_analysis", None)
                        discrepancy_payload = res.get("discrepancy_analysis") or analyze_discrepancies(doc.get("abstract"))

                        submission_year_val = doc.get("submissionYear")
                        publication_year_val = extract_publication_year(doc)

                        ai_doc = {
                            "uuid": uuid,
                            "title": title,
                            "submissionYear": submission_year_val,
                            "publicationYear": publication_year_val,
                            "abstract_ai_metadata": ai_metadata_payload,
                            "discrepancy_analysis": discrepancy_payload,
                            "updated_at": datetime.now(timezone.utc).isoformat()
                        }
                        self.store.save_ai_metadata(ai_doc)
                        applied_count += 1

                    # Successfully finished document
                    break

                except Exception as e:
                    if attempt < max_doc_attempts - 1:
                        time.sleep(2)
                    else:
                        pbar.write(f"\n[!] Error definitivo procesando RO {uuid} tras {max_doc_attempts} intentos: {e}")

            pbar.update(1)

        pbar.close()

        # Finalize and print metrics
        report = collector.finalize()
        collector.print_summary(report)

        if options.mode == "apply":
            print(f"[+] Se han actualizado {applied_count} documentos en MongoDB:")
            print(f"    - Coleccion Researchoutputs ({MONGO_COLLECTION}): abstracts normalizados.")
            print(f"    - Coleccion Metadatos IA ({MONGO_AI_METADATA_COLLECTION}): metadatos y trazabilidad guardados.")
            if options.sync_egreta:
                print(f"    - Sincronizacion Egreta ({options.egreta_env.upper()}): {egreta_synced_count} exitosos, {egreta_failed_count} fallidos.")

        if options.report_file:
            with open(options.report_file, "w", encoding="utf-8") as f:
                json.dump(report, f, indent=2, ensure_ascii=False)
            print(f"[*] Informe guardado en: {options.report_file}")

        return report
