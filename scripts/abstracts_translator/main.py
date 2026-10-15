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
from .intake import (
    IntakeQuery,
    IntakeOptions,
    MongoROStore,
    ROIntakePipeline,
    extract_title,
    extract_publication_year
)
from .engine import Translator, get_engine
from .benchmark import BenchmarkCollector

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
        default=None,
        help="Filtrar por ano (ej: 2024, o 'none'/'0' para procesar los que no tienen ano asignado)"
    )
    parser.add_argument(
        "--year-type",
        choices=["submission", "publication"],
        default="submission",
        help="Tipo de ano por el que filtrar: 'submission' (submissionYear, default) o 'publication' (publicationStatuses.publicationDate.year)"
    )
    parser.add_argument(
        "--no-year",
        action="store_true",
        default=False,
        help="Procesar exclusivamente los Research Outputs que NO tienen ano asignado (segun --year-type)"
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
    parser.add_argument(
        "--only-ai-collection",
        action="store_true",
        default=False,
        help="Procesar unicamente los Research Outputs presentes en la coleccion ResearchoutputsAiAbstracts"
    )
    parser.add_argument(
        "--uuid",
        default=None,
        help="UUID especifico del Research Output a procesar/traducir"
    )
    parser.add_argument(
        "--source-lang",
        default=None,
        help="Especificar idioma de origen desde el cual traducir (ej: 'en_GB', 'es_ES', 'ca_ES', 'ru', 'en', 'es', 'ca')"
    )
    parser.add_argument(
        "--target-lang",
        default=None,
        help="Especificar idioma(s) de destino especifico(s) a traducir (ej: 'en_GB', 'ca_ES', 'es_ES', 'en', 'ca', 'es'). Varias opciones separadas por coma."
    )
    parser.add_argument(
        "--force",
        action="store_true",
        default=False,
        help="Forzar la re-traduccion de todos los idiomas de destino aunque ya existan"
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
    if args.uuid:
        print(f"* RO UUID:        {args.uuid}")
    else:
        print(f"* Limite:         {args.limit if args.limit > 0 else 'Sin limite'}")
    if args.source_lang:
        print(f"* Origen Forzado: {args.source_lang}")
    if args.target_lang:
        print(f"* Destino Forzado:{args.target_lang}")
    if args.force:
        print(f"* Re-traduccion:  FORZADA (--force)")
    if args.year:
        print(f"* Filtro Ano:     {args.year}")
    print(f"* Sync Egreta:    {'ACTIVADO (' + args.egreta_env.upper() + ')' if args.sync_egreta else 'Desactivado'}")
    print(f"* Coleccion ROs:  {MONGO_COLLECTION}")
    print(f"* Coleccion Meta: {MONGO_AI_METADATA_COLLECTION}")
    print("="*60 + "\n")

    # Initialize Engine & Processor
    try:
        engine = get_engine(args.engine, args.model, args.vllm_url, args.lm_studio_url)
    except Exception as e:
        print(f"[!] Error initializing translation engine '{args.engine}': {e}")
        sys.exit(1)

    processor = AbstractProcessor(engine)

    # Initialize Store & Pipeline
    print("Connecting to MongoDB...")
    try:
        store = MongoROStore()
        print("Connected successfully to MongoDB.")
    except Exception as e:
        print(f"[!] Error connecting to MongoDB: {e}")
        sys.exit(1)

    pipeline = ROIntakePipeline(store=store, processor=processor)

    query = IntakeQuery(
        uuid=args.uuid,
        year=args.year,
        year_type=args.year_type,
        no_year=args.no_year,
        only_with_abstract=args.only_with_abstract,
        only_ai_collection=args.only_ai_collection
    )
    options = IntakeOptions(
        mode=args.mode,
        sync_egreta=args.sync_egreta,
        egreta_env=args.egreta_env,
        egreta_api_key=args.egreta_api_key,
        limit=args.limit,
        report_file=args.report_file,
        source_lang=args.source_lang,
        target_lang=args.target_lang,
        force=args.force
    )

    pipeline.run(query, options)


if __name__ == "__main__":
    main()
