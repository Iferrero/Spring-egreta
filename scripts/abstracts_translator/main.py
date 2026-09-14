import argparse
import io
import json
import sys
from datetime import datetime, timezone
import pymongo
from tqdm import tqdm

# Force UTF-8 encoding for stdout/stderr if possible
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except AttributeError:
        pass

from .config import (
    MONGO_URI,
    MONGO_DB_NAME,
    MONGO_COLLECTION,
    MONGO_AI_METADATA_COLLECTION,
    EGRETA_API_KEY,
    EGRETA_URL_TEST,
    EGRETA_URL_PROD,
    DEFAULT_MODEL_NAME,
    VLLM_SERVER_URL,
    LM_STUDIO_URL
)
from .processor import AbstractProcessor
from .egreta_sync import sync_abstract_to_egreta
from .engine import (
    MockBenchmarkEngine,
    LMStudioEngine,
    VLLMServerEngine,
    LocalVLLMEngine,
    TransformersEngine
)
from .benchmark import BenchmarkCollector

def extract_title(doc: dict) -> str:
    t = doc.get("title")
    if isinstance(t, dict):
        return t.get("value") or t.get("ca_ES") or t.get("es_ES") or t.get("en_GB") or "Sin titulo"
    elif isinstance(t, str):
        return t
    return "Sin titulo"

def get_engine(engine_type: str, model_name: str, vllm_url: str, lm_studio_url: str):
    if engine_type == "mock":
        return MockBenchmarkEngine(model_name=model_name)
    elif engine_type == "lm-studio":
        return LMStudioEngine(server_url=lm_studio_url, model_name=model_name if model_name != DEFAULT_MODEL_NAME else None)
    elif engine_type == "vllm-server":
        return VLLMServerEngine(server_url=vllm_url, model_name=model_name)
    elif engine_type == "vllm-local":
        return LocalVLLMEngine(model_name=model_name)
    elif engine_type == "transformers":
        return TransformersEngine(model_name=model_name)
    else:
        raise ValueError(f"Unknown engine type: {engine_type}")

def main():
    parser = argparse.ArgumentParser(
        description="Traductor, Benchmark y Sincronizador de Abstracts con MongoDB y Egreta (Pure API)"
    )
    parser.add_argument(
        "--mode",
        choices=["benchmark", "dry-run", "apply"],
        default="benchmark",
        help="Modo de ejecucion: 'benchmark' (pruebas de tiempo y estadisticas), 'dry-run' (simulacion con preview), 'apply' (guarda en MongoDB y opcionalmente Egreta)"
    )
    parser.add_argument(
        "--engine",
        choices=["mock", "lm-studio", "vllm-server", "vllm-local", "transformers"],
        default="lm-studio",
        help="Motor de inferencia a utilizar (default: lm-studio)"
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL_NAME,
        help=f"Nombre o ruta del modelo Salamandra (default: {DEFAULT_MODEL_NAME}, o auto-detect en LM Studio)"
    )
    parser.add_argument(
        "--lm-studio-url",
        default=LM_STUDIO_URL,
        help=f"URL del servidor local LM Studio (default: {LM_STUDIO_URL})"
    )
    parser.add_argument(
        "--vllm-url",
        default=VLLM_SERVER_URL,
        help=f"URL del servidor vLLM OpenAI-compatible (default: {VLLM_SERVER_URL})"
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=10,
        help="Limite de Research Outputs a procesar (0 = sin limite, default: 10)"
    )
    parser.add_argument(
        "--year",
        type=int,
        default=None,
        help="Filtrar por ano de publicacion (submissionYear)"
    )
    parser.add_argument(
        "--only-with-abstract",
        action="store_true",
        default=True,
        help="Procesar unicamente ROs que tengan algun abstract en MongoDB (default: True)"
    )
    parser.add_argument(
        "--sync-egreta",
        action="store_true",
        default=False,
        help="Sincronizar y actualizar los abstracts traducidos en Egreta (Pure REST API)"
    )
    parser.add_argument(
        "--egreta-env",
        choices=["test", "prod"],
        default="test",
        help="Entorno de Egreta a actualizar: 'test' (https://egretat.uab.cat) o 'prod' (default: test)"
    )
    parser.add_argument(
        "--egreta-api-key",
        default=None,
        help="API Key para Egreta (por defecto usa EGRETA_API_KEY configurada)"
    )
    parser.add_argument(
        "--report-file",
        default=None,
        help="Ruta de archivo JSON donde guardar el informe del benchmark"
    )

    args = parser.parse_args()

    print("\n" + "="*60)
    print(">> INICIANDO ABSTRACTS TRANSLATOR & SYNC")
    print("="*60)
    print(f"* Modo:           {args.mode}")
    print(f"* Motor LLM:      {args.engine}")
    if args.engine == "lm-studio":
        print(f"* LM Studio URL:  {args.lm_studio_url}")
    elif args.engine == "vllm-server":
        print(f"* vLLM URL:       {args.vllm_url}")
    print(f"* Modelo:         {args.model}")
    print(f"* Limite:         {args.limit if args.limit > 0 else 'Sin limite'}")
    if args.year:
        print(f"* Filtro Ano:     {args.year}")
    print(f"* Sync Egreta:    {'ACTIVADO (' + args.egreta_env.upper() + ')' if args.sync_egreta else 'Desactivado'}")
    print(f"* Coleccion ROs:  {MONGO_COLLECTION}")
    print(f"* Coleccion Meta: {MONGO_AI_METADATA_COLLECTION}")
    print("="*60 + "\n")

    # Connect to MongoDB
    print("Connecting to MongoDB...")
    try:
        client = pymongo.MongoClient(MONGO_URI, serverSelectionTimeoutMS=5000)
        db = client[MONGO_DB_NAME]
        collection = db[MONGO_COLLECTION]
        collection_ai = db[MONGO_AI_METADATA_COLLECTION]
        client.admin.command('ping')
        print("Connected successfully to MongoDB.")
        
        if args.mode == "apply":
            collection_ai.create_index("uuid", unique=True)
    except Exception as e:
        print(f"[!] Error connecting to MongoDB: {e}")
        sys.exit(1)

    # Initialize Engine & Processor
    try:
        engine = get_engine(args.engine, args.model, args.vllm_url, args.lm_studio_url)
    except Exception as e:
        print(f"[!] Error initializing translation engine '{args.engine}': {e}")
        sys.exit(1)

    processor = AbstractProcessor(engine)
    collector = BenchmarkCollector(engine_name=args.engine, model_name=getattr(engine, "model_name", args.model))

    # Build Mongo Query
    query = {}
    if args.only_with_abstract:
        query["abstract"] = {"$exists": True, "$ne": None, "$ne": {}}
    if args.year:
        query["submissionYear"] = args.year

    cursor = collection.find(query, {"uuid": 1, "title": 1, "abstract": 1, "submissionYear": 1})
    if args.limit > 0:
        cursor = cursor.limit(args.limit)

    docs = list(cursor)
    total_docs = len(docs)
    print(f"Found {total_docs} Research Outputs matching criteria.\n")

    if total_docs == 0:
        print("No documents found. Exiting.")
        return

    # Process Documents with Progress Bar
    applied_count = 0
    egreta_synced_count = 0
    egreta_failed_count = 0

    with tqdm(total=total_docs, desc="Procesando ROs", unit="doc") as pbar:
        for doc in docs:
            uuid = doc.get("uuid", str(doc.get("_id")))
            title = extract_title(doc)
            sub_year = doc.get("submissionYear")
            
            # Retry loop per document (up to 3 attempts) for batch robustness
            max_doc_attempts = 3
            for attempt in range(max_doc_attempts):
                try:
                    res = processor.process_ro_abstracts(doc)
                    collector.add_ro_result(uuid, title, sub_year, res)

                    # If mode is 'apply' and changes/translations occurred, persist to MongoDB
                    if args.mode == "apply" and res["status"] in ["TRANSLATED", "REORGANIZED"]:
                        # 1. Update abstract fields in Researchoutputs
                        collection.update_one(
                            {"uuid": uuid},
                            {"$set": {"abstract": res["updated_abstract"]}}
                        )

                        # 2. Optional: Synchronize to Egreta REST API
                        egreta_status = None
                        if args.sync_egreta:
                            sync_ok, sync_msg = sync_abstract_to_egreta(
                                uuid=uuid,
                                abstracts=res["updated_abstract"],
                                env=args.egreta_env,
                                api_key=args.egreta_api_key
                            )
                            egreta_status = {
                                "synced": sync_ok,
                                "env": args.egreta_env,
                                "synced_at": datetime.now(timezone.utc).isoformat(),
                                "message": sync_msg
                            }
                            if sync_ok:
                                egreta_synced_count += 1
                            else:
                                egreta_failed_count += 1
                                print(f"\n[!] Error sync Egreta ({uuid}): {sync_msg}")

                        # 3. Store AI metadata in dedicated collection (ResearchoutputsAiAbstracts)
                        if res["ai_metadata"]:
                            ai_metadata_payload = dict(res["ai_metadata"])
                            if egreta_status:
                                ai_metadata_payload["egreta_sync"] = egreta_status

                            ai_doc = {
                                "uuid": uuid,
                                "title": title,
                                "submissionYear": sub_year,
                                "abstract_ai_metadata": ai_metadata_payload,
                                "updated_at": datetime.now(timezone.utc).isoformat()
                            }
                            collection_ai.update_one(
                                {"uuid": uuid},
                                {"$set": ai_doc},
                                upsert=True
                            )
                        applied_count += 1
                    
                    # Successfully processed document
                    break

                except Exception as e:
                    if attempt < max_doc_attempts - 1:
                        import time
                        time.sleep(2)
                    else:
                        print(f"\n[!] Error definitivo procesando RO {uuid} tras {max_doc_attempts} intentos: {e}")

            pbar.update(1)

    # Finalize benchmark metrics
    report = collector.finalize()
    collector.print_summary(report)

    if args.mode == "apply":
        print(f"[+] Se han actualizado {applied_count} documentos en MongoDB:")
        print(f"    - Coleccion Researchoutputs ({MONGO_COLLECTION}): abstracts normalizados.")
        print(f"    - Coleccion Metadatos IA ({MONGO_AI_METADATA_COLLECTION}): metadatos y trazabilidad guardados.")
        if args.sync_egreta:
            print(f"    - Sincronizacion Egreta ({args.egreta_env.upper()}): {egreta_synced_count} exitosos, {egreta_failed_count} fallidos.")

    # Save JSON report if specified
    if args.report_file:
        with open(args.report_file, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"[*] Informe guardado en: {args.report_file}")

if __name__ == "__main__":
    main()
