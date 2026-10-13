import argparse
import io
import json
import sys
import time
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any
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
    LM_STUDIO_URL,
    LANG_EN,
    LANG_ES,
    LANG_CA
)
from .processor import AbstractProcessor
from .intake import (
    IntakeQuery,
    IntakeOptions,
    MongoROStore,
    ROIntakePipeline,
    extract_title,
    extract_publication_year,
    infer_target_langs
)
from .engine import Translator, get_engine
from .benchmark import BenchmarkCollector


def main():
    parser = argparse.ArgumentParser(
        description="Script de re-traducción automática para Research Outputs con alta discrepancia en ResearchoutputsAiAbstracts."
    )
    parser.add_argument(
        "--year", "-y",
        type=str,
        required=True,
        help="Año de publicación/sumisión a procesar (ej. '2024', '2023', '2021') o 'all' para procesar todos los años."
    )
    parser.add_argument(
        "--min-ratio",
        type=float,
        default=4.0,
        help="Umbral mínimo de ratio de discrepancia de palabras (ratio_words >= 4.0 por defecto)."
    )
    parser.add_argument(
        "--min-word-diff",
        type=int,
        default=150,
        help="Umbral mínimo de diferencia de palabras (diff_words >= 150 por defecto)."
    )
    parser.add_argument(
        "--mode",
        choices=["apply", "dry-run", "benchmark"],
        default="apply",
        help="Modo de ejecución: 'apply' (guarda en MongoDB y opcionalmente Egreta), 'dry-run' (simulación/preview), 'benchmark' (medición)."
    )
    parser.add_argument(
        "--engine",
        choices=["mock", "lm-studio", "vllm-server", "vllm-local", "transformers"],
        default="lm-studio",
        help="Motor de inferencia LLM a utilizar (default: lm-studio)"
    )
    parser.add_argument(
        "--lm-studio-url",
        default=LM_STUDIO_URL,
        help=f"URL del servidor LM Studio OpenAI API (default: {LM_STUDIO_URL})"
    )
    parser.add_argument(
        "--vllm-url",
        default=VLLM_SERVER_URL,
        help=f"URL del servidor vLLM OpenAI API (default: {VLLM_SERVER_URL})"
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL_NAME,
        help=f"Nombre del modelo LLM (default: {DEFAULT_MODEL_NAME})"
    )
    parser.add_argument(
        "--sync-egreta",
        action="store_true",
        help="Activa la sincronización REST API con Egreta (Pure API)"
    )
    parser.add_argument(
        "--egreta-env",
        choices=["prod", "test"],
        default="prod",
        help="Entorno de Egreta a utilizar (prod o test, default: prod)"
    )
    parser.add_argument(
        "--egreta-api-key",
        default=EGRETA_API_KEY,
        help="API Key para Egreta"
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Límite máximo de documentos a procesar"
    )
    parser.add_argument(
        "--uuid",
        type=str,
        default=None,
        help="UUID específico para procesar un único documento de prueba"
    )
    parser.add_argument(
        "--report-file",
        default=None,
        help="Ruta de archivo JSON para guardar informe detallado de benchmarking"
    )

    args = parser.parse_args()

    print("=" * 60)
    print(">> RE-TRADUCTOR DE DISCREPANCIAS (ResearchoutputsAiAbstracts)")
    print("=" * 60)
    print(f"* Año seleccionado:     {args.year}")
    print(f"* Umbral Ratio:         >= {args.min_ratio}")
    print(f"* Umbral Dif. Palabras: >= {args.min_word_diff}")
    print(f"* Modo:                 {args.mode}")
    print(f"* Motor LLM:            {args.engine}")
    print(f"* Sync Egreta:          {'ACTIVADO (' + args.egreta_env.upper() + ')' if args.sync_egreta else 'DESACTIVADO'}")
    print("=" * 60)

    # 1. Initialize Engine & Processor
    try:
        engine = get_engine(args.engine, args.model, args.vllm_url, args.lm_studio_url)
    except Exception as e:
        print(f"[!] Error al inicializar motor {args.engine}: {e}")
        sys.exit(1)

    processor = AbstractProcessor(engine)

    # 2. Connect Store & Pipeline
    print("\nConectando a MongoDB...")
    try:
        store = MongoROStore()
        print("Conexión exitosa con MongoDB.")
    except Exception as e:
        print(f"[!] Error de conexión a MongoDB: {e}")
        sys.exit(1)

    pipeline = ROIntakePipeline(store=store, processor=processor)

    query = IntakeQuery(
        uuid=args.uuid,
        year=args.year,
        is_discrepancy_query=True,
        min_ratio=args.min_ratio,
        min_word_diff=args.min_word_diff
    )
    options = IntakeOptions(
        mode=args.mode,
        sync_egreta=args.sync_egreta,
        egreta_env=args.egreta_env,
        egreta_api_key=args.egreta_api_key,
        limit=args.limit or 0,
        report_file=args.report_file,
        force=True
    )

    pipeline.run(query, options)


if __name__ == "__main__":
    main()
