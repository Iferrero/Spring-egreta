"""
Abstracts Translator & Benchmark Module for UAB Research Outputs
Powered by BSC Salamandra LLM, LM Studio / vLLM, and Egreta API
"""

from .detector import (
    detect_language,
    AbstractCluster,
    AbstractSlot
)
from .processor import AbstractProcessor
from .egreta_sync import (
    EgretaSyncAdapter,
    HttpEgretaAdapter,
    FakeEgretaAdapter,
    SyncResult,
    sync_abstract_to_egreta
)
from .intake import (
    ROIntakePipeline,
    ROStore,
    MongoROStore,
    FakeROStore,
    IntakeQuery,
    IntakeOptions
)
from .engine import (
    Translator,
    RawGeneration,
    TranslationError,
    InferenceAdapter,
    MockAdapter,
    LMStudioAdapter,
    VLLMServerAdapter,
    LocalVLLMAdapter,
    TransformersAdapter,
    get_translator,
    TranslationEngine,
    MockBenchmarkEngine,
    LMStudioEngine,
    VLLMServerEngine,
    LocalVLLMEngine,
    TransformersEngine,
    build_salamandra_messages,
    build_salamandra_prompt
)

__all__ = [
    "detect_language",
    "AbstractCluster",
    "AbstractSlot",
    "AbstractProcessor",
    "ROIntakePipeline",
    "ROStore",
    "MongoROStore",
    "FakeROStore",
    "IntakeQuery",
    "IntakeOptions",
    "Translator",
    "RawGeneration",
    "TranslationError",
    "InferenceAdapter",
    "MockAdapter",
    "LMStudioAdapter",
    "VLLMServerAdapter",
    "LocalVLLMAdapter",
    "TransformersAdapter",
    "get_translator",
    "EgretaSyncAdapter",
    "HttpEgretaAdapter",
    "FakeEgretaAdapter",
    "SyncResult",
    "sync_abstract_to_egreta",
    "TranslationEngine",
    "MockBenchmarkEngine",
    "LMStudioEngine",
    "VLLMServerEngine",
    "LocalVLLMEngine",
    "TransformersEngine",
    "build_salamandra_messages",
    "build_salamandra_prompt"
]

