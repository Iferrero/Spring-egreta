import re
import time
from abc import ABC, abstractmethod
from typing import Optional, List, Dict, Any
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry


def get_resilient_session(retries: int = 3, backoff_factor: float = 1.0) -> requests.Session:
    session = requests.Session()
    retry_strategy = Retry(
        total=retries,
        backoff_factor=backoff_factor,
        status_forcelist=[429, 500, 502, 503, 504],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retry_strategy)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

from .config import (
    DEFAULT_MODEL_NAME,
    VLLM_SERVER_URL,
    LM_STUDIO_URL,
    SAMPLING_TEMPERATURE,
    SAMPLING_TOP_P,
    SAMPLING_MAX_TOKENS,
    STOP_TOKENS,
    LANG_MAP_CATALAN,
    LANG_MAP_SPANISH,
    LANG_MAP_PROMPT
)


def extract_copyright_banner(text: str) -> Optional[str]:
    """
    Extracts publisher copyright notice from text if present (leading or trailing).
    Returns the exact copyright string or None.
    """
    if not text or not isinstance(text, str):
        return None

    cleaned = text.strip()
    copyright_symbol_pattern = r"(?:Copyright\s+)?(?:©|\(c\)|&copy;)|Copyright\b"

    # 1. Check LEADING copyright
    if re.match(r"^\s*(?:" + copyright_symbol_pattern + r")", cleaned, flags=re.IGNORECASE):
        header_break = r"(\s{2,}|\n|\s+(?:Background|Introduction|Objectives?|Objective|Methods?|Method|Results?|Conclusions?|Discussion)\b|\.\s+(?!(?:All|Tots|Todos)\b)[A-Z])"
        m_break = re.search(header_break, cleaned)
        if m_break:
            candidate = cleaned[:m_break.start()].strip()
            if re.search(r"\b\d{4}\b", candidate) and len(candidate) <= 150:
                return candidate
        else:
            if re.search(r"\b\d{4}\b", cleaned) and len(cleaned) <= 150:
                return cleaned

    # 2. Check TRAILING copyright
    trailing_match = re.search(
        r"(?:\.\s+|\n|\s{2,}|^)(?:" + copyright_symbol_pattern + r")[^\n]{0,140}\s*$",
        cleaned,
        flags=re.IGNORECASE
    )
    if trailing_match:
        candidate = trailing_match.group(0).strip()
        if candidate.startswith("."):
            candidate = candidate[1:].strip()
        if re.search(r"\b\d{4}\b", candidate) and len(candidate) <= 150:
            return candidate

    return None


def sanitize_academic_abstract(text: str) -> str:
    """
    Sanitizes and formats raw academic abstracts:
    - Normalizes non-standard Unicode mathematical symbols (e.g. \u2212 minus sign to standard ASCII -).
    - Preserves publisher copyright banners (e.g. '© 2019 The British Pharmacological Society').
    - Structures sections with clean paragraph breaks so the model translates the full text.
    """
    if not text:
        return ""
    
    # 1. Normalize unicode minus sign \u2212, dashes and non-breaking hyphens \u2011
    cleaned = text.replace("\u2212", "-").replace("\u2013", "-").replace("\u2014", "-").replace("\u2011", "-")

    # 2. Normalize single whitespace
    cleaned = re.sub(r"[ \t]+", " ", cleaned)

    # 3. Extract and relocate leading copyright banners to the end of the abstract
    # to prevent LLM models from terminating early while avoiding cutting into body text.
    copyright_banner = extract_copyright_banner(cleaned)
    if copyright_banner and cleaned.startswith(copyright_banner):
        cleaned = cleaned[len(copyright_banner):].strip()
        if cleaned.startswith("."):
            cleaned = cleaned[1:].strip()

    # 4. Ensure single space before common structured section headers
    # Sorted from longest to shortest to avoid partial replacements
    headers = [
        "Conclusions and Implications:", "Materials and Methods:", "Experimental Approach:",
        "Background and Purpose:", "Main Results:", "Key Results:", "Methodology:",
        "Background:", "Introduction:", "Objectives:", "Objective:", "Methods:",
        "Method:", "Results:", "Conclusions:", "Conclusion:", "Discussion:",
        "Conclusions and Implications", "Materials and Methods", "Experimental Approach",
        "Background and Purpose", "Main Results", "Key Results", "Methodology",
        "Background", "Introduction", "Objectives", "Objective", "Methods",
        "Method", "Results", "Conclusions", "Conclusion", "Discussion"
    ]
    pattern_headers = r"(?<!\n)(?<=[.\s])(" + "|".join(re.escape(h) for h in headers) + r")(?=[:\s])"
    cleaned = re.sub(pattern_headers, r" \1:", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r":{2,}", ":", cleaned)
    cleaned = re.sub(r"\s{2,}", " ", cleaned).strip()

    if copyright_banner and not cleaned.endswith(copyright_banner):
        cleaned = f"{cleaned} {copyright_banner}"

    return cleaned


def ensure_exact_copyright_preserved(original_text: str, translation_text: str) -> str:
    """
    Ensures that if the original text contains a publisher copyright notice (e.g. '© 2019 Elsevier B.V.'),
    the EXACT original copyright notice is strictly preserved untranslated at the end of the output abstract.
    """
    if not original_text or not translation_text:
        return translation_text

    exact_copyright = extract_copyright_banner(original_text)
    if not exact_copyright:
        return translation_text

    # Remove any translated, partial, or duplicate trailing copyright from translation_text
    clean_trans = re.sub(
        r"\s*(?:Copyright\s+)?(?:©|\(c\)|&copy;).*$",
        "",
        translation_text,
        flags=re.IGNORECASE
    ).strip()

    # Also remove if leading copyright was translated at the beginning of translation_text
    if re.match(r"^\s*(?:Copyright\s+)?(?:©|\(c\)|&copy;)", clean_trans, flags=re.IGNORECASE):
        header_break = r"(\s{2,}|\n|\s+(?:Background|Antecedents|Antecedentes|Introduction|Introducció|Introducción|Objectives?|Objectius?|Objetivos?|Methods?|Mètodes?|Métodos?|Results?|Resultats?|Resultados?|Conclusions?|Discussions?)\b|\.\s+[A-Z])"
        m_break = re.search(header_break, clean_trans)
        if m_break:
            clean_trans = clean_trans[m_break.start():].strip()

    return f"{clean_trans} {exact_copyright}"


def clean_llm_translation(text: str, original_text: Optional[str] = None) -> str:
    """
    Cleans unwanted introductory conversational labels or formatting artifacts that LLMs produce.
    e.g., "Traducció al català: ...", "Certainly! Here is the translation:\n..."
    """
    if not text:
        return ""
    cleaned = text.strip()
    
    # Remove surrounding quotes if model enclosed whole response in quotes
    if (cleaned.startswith('"') and cleaned.endswith('"')) or (cleaned.startswith("“") and cleaned.endswith("”")):
        cleaned = cleaned[1:-1].strip()

    # Common prefix patterns
    prefix_patterns = [
        r"^(?:Background and Purpose[:\s\-]*)+",
        r"^(?:Experimental Approach[:\s\-]*)+",
        r"^(?:Key Results[:\s\-]*)+",
        r"^(?:Conclusions and Implications[:\s\-]*)+",
        r"^(?:(?:absolutely|certainly|sure|of course|great|perfect|definitely)[!,.]?\s*)+",
        r"^(?:(?:here is|here\'s|below is)\s+(?:the\s+)?(?:translation|translated text)(?:\s+(?:in|into|to)\s+[a-zA-Z\u00C0-\u017F]+)?[:\s\-]*)+",
        r"^(?:translation\s+(?:in|into|to|of)\s+[a-zA-Z\u00C0-\u017F]+[:\s\-]*)+",
        r"^(?:translation[:\s\-]+)+",
        r"^(?:aquí (?:está|tienes|tenéis) la traducción(?:\s+al\s+[a-zA-Z\u00C0-\u017F]+)?[:\s\-]*)+",
        r"^(?:aquí teniu la traducció(?:\s+al\s+[a-zA-Z\u00C0-\u017F]+)?[:\s\-]*)+",
        r"^(?:traducción(?:\s+(?:al|el|en))?\s*[a-zA-Z\u00C0-\u017F]*[:\s\-]*)+",
        r"^(?:traducció(?:\s+(?:al|el|en))?\s*[a-zA-Z\u00C0-\u017F]*[:\s\-]*)+",
        r"^(?:texto original(?:\s+\([a-zA-Z\u00C0-\u017F]+\))?[:\s\-]*)+",
        r"^(?:text original(?:\s+\([a-zA-Z\u00C0-\u017F]+\))?[:\s\-]*)+",
        r"^(?:abstract(?:\s+en\s+[a-zA-Z\u00C0-\u017F]+)?[:\s\-]*)+"
    ]
    
    # Iterate until all stacked prefixes are stripped
    prev = ""
    while prev != cleaned:
        prev = cleaned
        for pattern in prefix_patterns:
            cleaned = re.sub(pattern, "", cleaned, flags=re.IGNORECASE).strip()

    # Clean up known Cyrillic token hallucinations in Latin text (e.g. COVID-1льs / COVID-1ль9 -> COVID-19)
    cleaned = re.sub(r'COVID[-‑]?1[\u0400-\u04FF]+[s9]?', 'COVID-19', cleaned, flags=re.IGNORECASE)

    if original_text:
        cleaned = ensure_exact_copyright_preserved(original_text, cleaned)

    return cleaned


def format_lang_from_ca(lang_code: str) -> str:
    name = LANG_MAP_CATALAN.get(lang_code, lang_code)
    if name in ("anglès", "espanyol", "alemany", "italià"):
        return f"de l'{name}"
    elif name in ("francès", "portuguès", "rus"):
        return f"del {name}"
    elif name == "català":
        return "del català"
    return f"de {name}"


def format_lang_from_es(lang_code: str) -> str:
    name = LANG_MAP_SPANISH.get(lang_code, lang_code)
    if name in ("inglés", "español", "catalán", "francés", "alemán", "italiano", "portugués", "ruso"):
        return f"del {name}"
    return f"de {name}"



def build_salamandra_messages(texto_original: str, idioma_destino: str, idioma_origen: Optional[str] = None) -> List[Dict[str, str]]:
    """
    Builds structured system and user messages for Salamandra LLM, explicitly specifying both
    source and target languages for maximum translation fidelity and precision.
    """
    sanitized = sanitize_academic_abstract(texto_original)
    src_code = idioma_origen or "en_GB"

    if idioma_destino in ("ca_ES", "ca") or LANG_MAP_CATALAN.get(idioma_destino) == "català":
        src_from = format_lang_from_ca(src_code)
        system_prompt = (
            "Ets un traductor professional de literatura científica especialitzat en la traducció al català. "
            f"La teva única tasca és traduir fidelment el text d'entrada complet {src_from} a català. "
            "NO repeteixis el text en anglès. NO deixis frases sense traduir. "
            "És CRÍTIC que mantinguis de forma ESTRICTA l'ordre seqüencial exactament igual que el text original. "
            "NO reordenis cap frase. NO ometis cap frase ni part d'una frase. NO fusionis oracions diferents. "
            "Tradueix de forma precisa els encapçalaments de secció (Objective -> Objectiu:, Background -> Antecedents:, Background and Purpose -> Context i propòsit:, Methods -> Mètodes:, Experimental Approach -> Enfocament experimental:, Results -> Resultats:, Key Results -> Resultats principals:, Conclusion -> Conclusions:, Conclusions and Implications -> Conclusions i implicacions:). "
            "Mantén el rigor terminològic màxim, el registre formal acadèmic i conserva intactes les declaracions de copyright (com '© 2019...'), les fórmules químiques, les sigles i els valors numèrics. "
            "Retorna ÚNICAMENT el text traduït al català, sense introduccions ni comentaris."
        )
        user_prompt = f"Text original (anglès):\n{sanitized}\n\nTraducció completa al català (tots els paràgrafs):"
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
    else:
        src_from = format_lang_from_es(src_code)
        target_name = LANG_MAP_SPANISH.get(idioma_destino, idioma_destino)
        system_prompt = (
            "Eres un traductor profesional de literatura científica especializado en la traducción al español. "
            f"Tu única tarea es traducir fielmente el texto de entrada completo {src_from} a {target_name}. "
            "NO repitas el texto en inglés. NO dejes frases sin traducir. "
            "Es CRÍTICO que mantengas de forma ESTRICTA el orden secuencial exacto de todas las oraciones del texto original. "
            "NO reordenes las frases. NO omitas ninguna frase ni parte de una frase. NO mecles ni fusiones oraciones distintas. "
            "Traduce de forma precisa los encabezados de sección (Objective -> Objetivo:, Background -> Antecedentes:, Background and Purpose -> Antecedentes y Objetivo:, Methods -> Métodos:, Experimental Approach -> Enfoque experimental:, Results -> Resultados:, Key Results -> Resultados principales:, Conclusion -> Conclusión:, Conclusions and Implications -> Conclusiones e implicaciones:). "
            "Mantén el máximo rigor terminológico, el estilo formal académico y conserva intactas las declaraciones de copyright (como '© 2019...'), las fórmulas químicas, las siglas y los valores numéricos. "
            "Devuelve ÚNICAMENTE el texto traducido al {target_name}, sin introducciones ni comentarios."
        )
        user_prompt = f"Texto original (inglés):\n{sanitized}\n\nTraducción completa al {target_name} (todos los párrafos):"
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]




def build_salamandra_prompt(texto_original: str, idioma_destino: str, idioma_origen: Optional[str] = None) -> str:
    """
    Builds the clean ChatML prompt for Salamandra LLM using build_salamandra_messages.
    Formatted into standard ChatML turns including system, few-shot, and user blocks.
    """
    messages = build_salamandra_messages(texto_original, idioma_destino, idioma_origen)
    prompt = ""
    for msg in messages:
        role = msg["role"]
        content = msg["content"]
        prompt += f"<|im_start|>{role}\n{content}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt




class TranslationEngine(ABC):
    @abstractmethod
    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        pass


class MockBenchmarkEngine(TranslationEngine):
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, simulated_speed_tok_s: float = 45.0):
        self.model_name = model_name
        self.simulated_speed_tok_s = simulated_speed_tok_s

    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        target_name = LANG_MAP_PROMPT.get(target_lang_code, target_lang_code)
        src_name = LANG_MAP_PROMPT.get(src_lang_code, src_lang_code) if src_lang_code else None
        sanitized = sanitize_academic_abstract(text)
        prompt = build_salamandra_prompt(sanitized, target_lang_code, src_lang_code)
        
        t0 = time.perf_counter()
        prompt_tokens = int(len(prompt.split()) * 1.3)
        completion_tokens = int(len(sanitized.split()) * 1.3)
        
        latency_s = completion_tokens / self.simulated_speed_tok_s
        t1 = time.perf_counter()
        elapsed_ms = (t1 - t0) * 1000.0 + (latency_s * 1000.0)

        simulated_translation = clean_llm_translation(f"[{target_name.upper()} TRANSLATION by {self.model_name}]: {sanitized}", original_text=text)

        return {
            "translation": simulated_translation,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "latency_ms": round(elapsed_ms, 2),
            "model": self.model_name,
            "simulated": True
        }


class LMStudioEngine(TranslationEngine):
    """
    Connects to LM Studio local server with full-text translation instructions and resilient retries.
    """
    def __init__(self, server_url: str = LM_STUDIO_URL, model_name: Optional[str] = None, timeout: int = 180):
        url = server_url.rstrip("/")
        if not url.endswith("/v1"):
            url = f"{url}/v1"
        self.server_url = url
        self.timeout = timeout
        self.session = get_resilient_session(retries=3, backoff_factor=1.0)
        self.model_name = model_name or self._detect_model()

    def _detect_model(self) -> str:
        try:
            resp = self.session.get(f"{self.server_url}/models", timeout=5)
            if resp.status_code == 200:
                data = resp.json()
                if "data" in data and len(data["data"]) > 0:
                    loaded_id = data["data"][0]["id"]
                    print(f"[*] Modelo detectado en LM Studio: {loaded_id}")
                    return loaded_id
        except Exception:
            pass
        return DEFAULT_MODEL_NAME

    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        messages = build_salamandra_messages(text, target_lang_code, src_lang_code)

        payload = {
            "model": self.model_name,
            "messages": messages,
            "temperature": SAMPLING_TEMPERATURE,
            "top_p": SAMPLING_TOP_P,
            "max_tokens": SAMPLING_MAX_TOKENS,
            "stop": STOP_TOKENS
        }

        # Attempt up to 3 times in case of transient empty responses or HTTP delays
        max_attempts = 3
        for attempt in range(max_attempts):
            t0 = time.perf_counter()
            try:
                resp = self.session.post(f"{self.server_url}/chat/completions", json=payload, timeout=self.timeout)
                t1 = time.perf_counter()
                latency_ms = (t1 - t0) * 1000.0

                if resp.status_code == 200:
                    data = resp.json()
                    choice = data["choices"][0]
                    raw_content = choice.get("message", {}).get("content", "")
                    content = clean_llm_translation(raw_content, original_text=text)

                    # Ensure non-empty response when source text is substantial
                    if content or len(text.strip()) < 15:
                        usage = data.get("usage", {})
                        return {
                            "translation": content,
                            "prompt_tokens": usage.get("prompt_tokens", sum(len(m["content"].split()) for m in messages)),
                            "completion_tokens": usage.get("completion_tokens", len(content.split())),
                            "latency_ms": round(latency_ms, 2),
                            "model": self.model_name,
                            "simulated": False
                        }
            except requests.exceptions.RequestException:
                pass

            if attempt < max_attempts - 1:
                time.sleep(1.5)

        # Fallback to /completions
        prompt = build_salamandra_prompt(text, target_lang_code, src_lang_code)
        payload_raw = {
            "model": self.model_name,
            "prompt": prompt,
            "temperature": SAMPLING_TEMPERATURE,
            "top_p": SAMPLING_TOP_P,
            "max_tokens": SAMPLING_MAX_TOKENS,
            "stop": STOP_TOKENS
        }

        t0 = time.perf_counter()
        resp = self.session.post(f"{self.server_url}/completions", json=payload_raw, timeout=self.timeout)
        t1 = time.perf_counter()
        latency_ms = (t1 - t0) * 1000.0

        if resp.status_code != 200:
            raise RuntimeError(f"LM Studio server error ({resp.status_code}): {resp.text}")

        data = resp.json()
        choice = data["choices"][0]
        translation = clean_llm_translation(choice.get("text", ""), original_text=text)
        usage = data.get("usage", {})

        return {
            "translation": translation,
            "prompt_tokens": usage.get("prompt_tokens", len(prompt.split())),
            "completion_tokens": usage.get("completion_tokens", len(translation.split())),
            "latency_ms": round(latency_ms, 2),
            "model": self.model_name,
            "simulated": False
        }


class VLLMServerEngine(TranslationEngine):
    """
    Connects to a running vLLM server via HTTP / OpenAI Compatible endpoint.
    """
    def __init__(self, server_url: str = VLLM_SERVER_URL, model_name: str = DEFAULT_MODEL_NAME, timeout: int = 120):
        self.server_url = server_url.rstrip("/")
        self.model_name = model_name
        self.timeout = timeout
        self.session = get_resilient_session(retries=3, backoff_factor=1.0)

    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        prompt = build_salamandra_prompt(text, target_lang_code, src_lang_code)

        payload = {
            "model": self.model_name,
            "prompt": prompt,
            "max_tokens": SAMPLING_MAX_TOKENS,
            "temperature": SAMPLING_TEMPERATURE,
            "stop": STOP_TOKENS
        }

        t0 = time.perf_counter()
        resp = self.session.post(f"{self.server_url}/completions", json=payload, timeout=self.timeout)
        t1 = time.perf_counter()
        latency_ms = (t1 - t0) * 1000.0

        if resp.status_code != 200:
            raise RuntimeError(f"vLLM server error ({resp.status_code}): {resp.text}")

        data = resp.json()
        choice = data["choices"][0]
        translation = clean_llm_translation(choice.get("text", ""), original_text=text)
        usage = data.get("usage", {})

        return {
            "translation": translation,
            "prompt_tokens": usage.get("prompt_tokens", len(prompt.split())),
            "completion_tokens": usage.get("completion_tokens", len(translation.split())),
            "latency_ms": round(latency_ms, 2),
            "model": self.model_name,
            "simulated": False
        }


class LocalVLLMEngine(TranslationEngine):
    """
    Uses vLLM Python package directly when running inside a GPU node.
    """
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, tensor_parallel_size: int = 1):
        try:
            from vllm import LLM, SamplingParams
        except ImportError:
            raise ImportError("vLLM package is not installed in this environment. Use VLLMServerEngine or MockBenchmarkEngine.")
        
        self.model_name = model_name
        self.llm = LLM(model=model_name, tensor_parallel_size=tensor_parallel_size)
        self.sampling_params = SamplingParams(
            temperature=SAMPLING_TEMPERATURE,
            max_tokens=SAMPLING_MAX_TOKENS,
            stop=STOP_TOKENS
        )

    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        prompt = build_salamandra_prompt(text, target_lang_code, src_lang_code)

        t0 = time.perf_counter()
        outputs = self.llm.generate([prompt], self.sampling_params)
        t1 = time.perf_counter()
        latency_ms = (t1 - t0) * 1000.0

        output = outputs[0]
        translation = clean_llm_translation(output.outputs[0].text, original_text=text)
        prompt_tokens = len(output.prompt_token_ids) if hasattr(output, "prompt_token_ids") else len(prompt.split())
        completion_tokens = len(output.outputs[0].token_ids) if hasattr(output.outputs[0], "token_ids") else len(translation.split())

        return {
            "translation": translation,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "latency_ms": round(latency_ms, 2),
            "model": self.model_name,
            "simulated": False
        }


class TransformersEngine(TranslationEngine):
    """
    Fallback using HuggingFace Transformers pipeline.
    """
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, device: Optional[str] = None):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer, pipeline

        self.model_name = model_name
        if device is None:
            device = "cuda" if torch.cuda.is_available() else "cpu"
        
        self.tokenizer = AutoTokenizer.from_pretrained(model_name)
        self.model = AutoModelForCausalLM.from_pretrained(
            model_name,
            torch_dtype=torch.float16 if device == "cuda" else torch.float32,
            device_map="auto" if device == "cuda" else None
        )
        self.pipe = pipeline(
            "text-generation",
            model=self.model,
            tokenizer=self.tokenizer,
            device=0 if device == "cuda" else -1
        )

    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        prompt = build_salamandra_prompt(text, target_lang_code, src_lang_code)

        t0 = time.perf_counter()
        out = self.pipe(
            prompt,
            max_new_tokens=SAMPLING_MAX_TOKENS,
            temperature=SAMPLING_TEMPERATURE,
            do_sample=False,
            stop_strings=STOP_TOKENS,
            tokenizer=self.tokenizer
        )
        t1 = time.perf_counter()
        latency_ms = (t1 - t0) * 1000.0

        full_generated = out[0]["generated_text"]
        raw_translation = full_generated[len(prompt):].strip()
        for st in STOP_TOKENS:
            if raw_translation.endswith(st):
                raw_translation = raw_translation[:-len(st)].strip()

        translation = clean_llm_translation(raw_translation, original_text=text)

        return {
            "translation": translation,
            "prompt_tokens": len(self.tokenizer.encode(prompt)),
            "completion_tokens": len(self.tokenizer.encode(translation)),
            "latency_ms": round(latency_ms, 2),
            "model": self.model_name,
            "simulated": False
        }
