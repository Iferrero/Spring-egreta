import os

# MongoDB Default Configuration
MONGO_URI = os.getenv("MONGO_URI", "mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
MONGO_DB_NAME = os.getenv("MONGO_DB_NAME", "kraken")
MONGO_COLLECTION = os.getenv("MONGO_COLLECTION", "Researchoutputs")
MONGO_AI_METADATA_COLLECTION = os.getenv("MONGO_AI_METADATA_COLLECTION", "ResearchoutputsAiAbstracts")

# Egreta (Pure) API Configuration
EGRETA_API_KEY = os.getenv("EGRETA_API_KEY", "9971c3cc-b3e0-48e3-9ff9-e990c795e92f")
EGRETA_URL_TEST = os.getenv("EGRETA_URL_TEST", "https://egretat.uab.cat/ws/api/")
EGRETA_URL_PROD = os.getenv("EGRETA_URL_PROD", "https://egreta.uab.cat/ws/api/")

# LLM Models & Server Configuration
DEFAULT_MODEL_NAME = os.getenv("SALAMANDRA_MODEL", "bsc/salamandra-7b-instruct")
VLLM_SERVER_URL = os.getenv("VLLM_SERVER_URL", "http://localhost:8000/v1")
LM_STUDIO_URL = os.getenv("LM_STUDIO_URL", "http://127.0.0.1:1234/v1")

# Standard Language Codes in UAB / Pure system
LANG_EN = "en_GB"
LANG_ES = "es_ES"
LANG_CA = "ca_ES"

# Standard Language Names for Salamandra Prompts
LANG_MAP_CATALAN = {
    LANG_EN: "anglès",
    LANG_ES: "espanyol",
    LANG_CA: "català",
    "en": "anglès",
    "es": "espanyol",
    "ca": "català",
    "fr": "francès",
    "fr_FR": "francès",
    "de": "alemany",
    "it": "italià",
    "pt": "portuguès",
    "ru": "rus"
}

LANG_MAP_SPANISH = {
    LANG_EN: "inglés",
    LANG_ES: "español",
    LANG_CA: "catalán",
    "en": "inglés",
    "es": "español",
    "ca": "catalán",
    "fr": "francés",
    "fr_FR": "francés",
    "de": "alemán",
    "it": "italiano",
    "pt": "portugués",
    "ru": "ruso"
}

LANG_MAP_ENGLISH = {
    LANG_EN: "English",
    LANG_ES: "Spanish",
    LANG_CA: "Catalan",
    "en": "English",
    "es": "Spanish",
    "ca": "Catalan",
    "fr": "French",
    "fr_FR": "French",
    "de": "German",
    "it": "Italian",
    "pt": "Portuguese",
    "ru": "Russian"
}


# Alias for backward compatibility
LANG_MAP_PROMPT = LANG_MAP_SPANISH


# Sampling parameters specified for Salamandra (Optimal for translation tasks)
SAMPLING_TEMPERATURE = 0.0  # Greedy decoding for maximum translation accuracy and zero hallucination
SAMPLING_TOP_P = 0.95
SAMPLING_MAX_TOKENS = 2048
STOP_TOKENS = ["<|im_end|>", "<|im_start|>", "</s>", "<|endoftext|>"]

