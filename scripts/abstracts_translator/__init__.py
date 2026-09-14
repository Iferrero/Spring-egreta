"""
Abstracts Translator & Benchmark Module for UAB Research Outputs
Powered by BSC Salamandra LLM, LM Studio / vLLM, and Egreta API
"""

from .detector import detect_language
from .processor import AbstractProcessor
from .egreta_sync import sync_abstract_to_egreta
from .engine import (
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
    "AbstractProcessor",
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

