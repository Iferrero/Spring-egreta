import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional, Dict, Any, List

from .config import LANG_EN, LANG_ES, LANG_CA

try:
    from lingua import Language, LanguageDetectorBuilder
    _lingua_languages = [
        Language.SPANISH, Language.CATALAN, Language.ENGLISH,
        Language.FRENCH, Language.GERMAN, Language.RUSSIAN,
        Language.ITALIAN, Language.PORTUGUESE
    ]
    LINGUA_DETECTOR = LanguageDetectorBuilder.from_languages(*_lingua_languages).build()
    HAS_LINGUA = True
except ImportError:
    HAS_LINGUA = False

try:
    from langdetect import detect as langdetect_detect, DetectorFactory
    DetectorFactory.seed = 0
    HAS_LANGDETECT = True
except ImportError:
    HAS_LANGDETECT = False

LINGUA_MAP = {
    "SPANISH": "es_ES",
    "CATALAN": "ca_ES",
    "ENGLISH": "en_GB",
    "FRENCH": "fr",
    "GERMAN": "de",
    "ITALIAN": "it",
    "PORTUGUESE": "pt",
    "RUSSIAN": "ru"
}

# Distinct Stopwords for verification / fallback
CA_DISTINCT = {
    "amb", "dels", "pels", "aquest", "aquesta", "aquests", "aquestes", "perque", "perquè", "seva",
    "seus", "seves", "tambe", "també", "gairebe", "gairebé", "gaire", "quelcom", "adhuc", "àdhuc",
    "nomes", "només", "doncs", "mateix", "mateixa", "qualque", "llur", "llurs", "despres", "després",
    "mentre", "sovint", "tanmateix", "hom", "cadascu", "cadascú", "cadascun", "cadascuna", "aleshores",
    "treball", "estudi", "objectiu", "resultats", "conclusions", "metodologia"
}

ES_DISTINCT = {
    "los", "las", "por", "para", "con", "sus", "este", "esta", "estos", "estas", "tambien", "también",
    "hacia", "hasta", "despues", "después", "entonces", "ademas", "además", "mientras", "aunque",
    "donde", "desde", "sobre", "mismo", "misma", "cada", "estudio", "analisis", "análisis", "objetivo",
    "trabajo", "desarrollo", "resultados", "conclusiones", "investigacion", "investigación"
}

EN_DISTINCT = {
    "the", "and", "of", "in", "to", "is", "for", "with", "that", "this", "from", "which", "by", "as",
    "are", "was", "were", "their", "they", "our", "we", "study", "results", "paper", "aim", "methods",
    "between", "these", "been", "have", "has", "background", "conclusion", "present", "approach"
}

FR_DISTINCT = {
    "cette", "recherche", "examine", "les", "conséquences", "mondialisation", "sur",
    "marchés", "financiers", "france", "des", "dans", "pour", "est", "une", "avec", "par",
    "nous", "vous", "ils", "elles", "sont", "cet", "ces"
}


WORD_PATTERN = re.compile(r"\b[a-zA-Zà-ÿÀ-ß'-]+\b")
CYRILLIC_PATTERN = re.compile(r"[\u0400-\u04FF]")
CYRILLIC_CHAR_PATTERN = re.compile(r"[\u0400-\u04FF]")
LATIN_CHAR_PATTERN = re.compile(r"[a-zA-Zà-ÿÀ-ß]")
HTML_PATTERN = re.compile(r"<[^>]+>")

def clean_text_for_analysis(text: str) -> str:
    if not text:
        return ""
    text = HTML_PATTERN.sub(" ", text)
    return " ".join(text.split()).strip()

def detect_language(text: Optional[str]) -> str:
    """
    Detects language of an abstract text.
    Returns:
      'en_GB' for English
      'es_ES' for Spanish
      'ca_ES' for Catalan
      2-letter ISO code (e.g., 'ru', 'fr', 'de', 'it', 'pt') for foreign languages
      'unknown' if text is too short or undetermined
    """
    if not text:
        return "unknown"
    
    cleaned = clean_text_for_analysis(text)
    if len(cleaned) < 15:
        return "unknown"

    # Cyrillic check for Russian / Cyrillic-script languages (must be predominantly Cyrillic)
    n_cyrillic = len(CYRILLIC_CHAR_PATTERN.findall(cleaned))
    n_latin = len(LATIN_CHAR_PATTERN.findall(cleaned))
    total_letters = n_cyrillic + n_latin
    if n_cyrillic > 0 and (total_letters > 0 and n_cyrillic / total_letters > 0.3):
        return "ru"

    # 1. Primary: Use Lingua detector (State-of-the-art accuracy for Spanish vs Catalan)
    if HAS_LINGUA:
        try:
            detected_lingua = LINGUA_DETECTOR.detect_language_of(cleaned)
            if detected_lingua:
                name = detected_lingua.name
                if name in LINGUA_MAP:
                    return LINGUA_MAP[name]
        except Exception:
            pass

    # 2. Secondary: Use langdetect if available
    if HAS_LANGDETECT:
        try:
            detected = langdetect_detect(cleaned)
            if detected == "en":
                return "en_GB"
            if detected == "es":
                return "es_ES"
            if detected == "ca":
                return "ca_ES"
            return detected
        except Exception:
            pass

    # 3. Heuristic fallback based on distinct vocabulary
    words = [w.lower() for w in WORD_PATTERN.findall(cleaned)]
    if not words:
        return "unknown"

    en_count = sum(1 for w in words if w in EN_DISTINCT)
    es_count = sum(1 for w in words if w in ES_DISTINCT)
    ca_count = sum(1 for w in words if w in CA_DISTINCT)
    fr_count = sum(1 for w in words if w in FR_DISTINCT)

    max_count = max(en_count, es_count, ca_count, fr_count)
    if max_count > 0:
        if fr_count == max_count:
            return "fr"
        if en_count == max_count:
            return "en_GB"
        if ca_count == max_count:
            return "ca_ES"
        if es_count == max_count:
            return "es_ES"
    return "unknown"


PLACEHOLDER_PATTERNS = [
    r"^\s*sense\s+resum(?:\s+en\s+[a-zA-ZÀ-ÿ\s]+)?[\.\s]*$",
    r"^\s*sin\s+resumen(?:\s+en\s+[a-zA-ZÀ-ÿ\s]+)?[\.\s]*$",
    r"^\s*no\s+abstract(?:\s+available)?[\.\s]*$",
    r"^\s*without\s+abstract[\.\s]*$",
    r"^\s*(?:resum|resumen|abstract)\s+no\s+disponible[\.\s]*$",
    r"^\s*no\s+disponible[\.\s]*$",
    r"^\s*sense\s+informaci[oó][\.\s]*$",
    r"^\s*sin\s+informaci[oó]n[\.\s]*$",
]

def is_placeholder_abstract(text: Optional[str]) -> bool:
    """
    Returns True if text is a placeholder statement (e.g. 'Sense resum en castellà.',
    'Sense resum en català.', 'Sin resumen.', 'No abstract available') rather than a genuine abstract.
    """
    if not text or not isinstance(text, str):
        return True
    cleaned = clean_text_for_analysis(text).strip()
    if not cleaned or len(cleaned) < 5:
        return True
    for pattern in PLACEHOLDER_PATTERNS:
        if re.match(pattern, cleaned, flags=re.IGNORECASE):
            return True
    return False


CYRILLIC_HOMOGLYPHS = set("аеорсухАВЕКМНОРСТХ")

def is_corrupted_abstract(text: Optional[str]) -> bool:
    """
    Returns True if text contains LLM generation artifacts, Cyrillic corruptions in non-Russian text (e.g. µg -> льg),
    subtoken garbage (-oB-, -cB-), or infinite repetition loops.
    """
    if not text or not isinstance(text, str):
        return False

    cleaned = clean_text_for_analysis(text)
    n_cyrillic = len(CYRILLIC_CHAR_PATTERN.findall(cleaned))
    n_latin = len(LATIN_CHAR_PATTERN.findall(cleaned))
    total_letters = n_cyrillic + n_latin

    # 1. Cyrillic artifacts in predominantly non-Cyrillic (Latin) text
    # Filter out Latin homoglyphs (e.g. OCR artifacts like 'aраreix') and isolated scientific/math terms (e.g. є-subdifferentials, NF-кB)
    if n_latin > n_cyrillic and total_letters > 0:
        non_homoglyph_cyrillic = [ch for ch in CYRILLIC_CHAR_PATTERN.findall(cleaned) if ch not in CYRILLIC_HOMOGLYPHS]
        n_cyrillic_non_homoglyph = len(non_homoglyph_cyrillic)
        ratio = n_cyrillic_non_homoglyph / total_letters
        if n_cyrillic_non_homoglyph >= 2 and 0.01 < ratio <= 0.4:
            return True

    # 2. LLM special tokens (<unk>, [UNK], <|im_end|>, etc.)
    if re.search(r"<\/?(?:unk|pad|mask|im_start|im_end|s|eos|bos|endoftext)\b[^>]*>|\[UNK\]|<\|[^>]+\|>", text, flags=re.IGNORECASE):
        return True

    # 3. Subtoken garbage
    if re.search(r"-[oOcCeE][bB]-", text):
        return True

    # 4. Repetition loop (sequence or word repeated 4+ times)
    for rep_match in re.finditer(r"(.{3,25})\1{4,}", cleaned):
        pat = rep_match.group(1)
        # Only flag as corruption loop if repeating pattern contains alphanumeric characters
        if re.search(r"[a-zA-Z0-9à-ÿÀ-ß\u0400-\u04FF]", pat):
            return True
    if re.search(r"(\b\w{3,}\b)(?:\s+\1){3,}", cleaned, flags=re.IGNORECASE):
        return True

    # 5. NER / token classification dump artifacts (e.g. [('Objective', 'O'), ('Results', 'O')])
    if re.search(r"\[\s*\('[^']+',\s*'[A-Z_-]+'\)", text):
        return True

    return False


def _norm_for_overlap(text: str) -> str:
    t = clean_text_for_analysis(text).lower()
    return re.sub(r'[\u2013\u2014\u2212\u2011]', '-', t)


def has_identical_cloned_abstracts(raw_abstracts: Optional[dict]) -> bool:
    """
    Returns True if raw_abstracts dictionary contains identical/cloned text across multiple language slots
    OR if non-English slots contain substantial untranslated English sentences (partial translations),
    indicating that re-translation is required.
    """
    if not raw_abstracts or not isinstance(raw_abstracts, dict):
        return False
        
    valid_texts = []
    norm_texts = {}
    for k, v in raw_abstracts.items():
        if v and isinstance(v, str) and len(v.strip()) >= 10:
            norm = _norm_for_overlap(v)
            valid_texts.append(norm)
            norm_texts[k] = norm
            
    # 1. Check exact/near-exact duplicates across slots
    if len(valid_texts) >= 2 and len(set(valid_texts)) < len(valid_texts):
        return True
        
    # 2. Check if a non-English slot (es_ES, ca_ES) contains 20%+ untranslated sentences from en_GB
    en_text = norm_texts.get("en_GB") or norm_texts.get("en")
    if en_text:
        en_sentences = [s.strip() for s in re.split(r'(?<=[.!?])\s+', en_text) if len(s.strip()) > 25]
        if en_sentences:
            total_en_chars = sum(len(s) for s in en_sentences)
            for k, target_text in norm_texts.items():
                if k not in ("en_GB", "en") and len(target_text) > 50:
                    matched_chars = sum(len(s) for s in en_sentences if s in target_text)
                    if total_en_chars > 0 and (matched_chars / total_en_chars) >= 0.20:
                        return True

    return False


STANDARD_TARGET_LANGS = ["en_GB", "es_ES", "ca_ES"]

def analyze_discrepancies(raw_abstracts: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Computes discrepancy analysis according to the exact schema:
    - has_ca, has_es, has_en
    - words_ca, words_es, words_en
    - chars_ca, chars_es, chars_en
    - diff_words, ratio_words, pct_discrepancy
    - shortest_lang, longest_lang
    - severity ("none", "low", "medium", "high", "critical")
    - has_discrepancy (bool)
    - detected_ca, detected_es, detected_en ("none", "missing", "placeholder", "corrupted", or mismatch)
    - analyzed_at (ISO timestamp)
    """
    if not isinstance(raw_abstracts, dict):
        raw_abstracts = {}

    text_ca = raw_abstracts.get("ca_ES") or raw_abstracts.get("ca")
    text_es = raw_abstracts.get("es_ES") or raw_abstracts.get("es")
    text_en = raw_abstracts.get("en_GB") or raw_abstracts.get("en")

    def _get_clean_info(text: Optional[str]):
        if not text or not isinstance(text, str):
            return "", 0, 0, False
        cleaned = clean_text_for_analysis(text)
        words = len(cleaned.split())
        chars = len(cleaned)
        valid = words > 0 and not is_placeholder_abstract(text) and not is_corrupted_abstract(text)
        return cleaned, words, chars, valid

    cleaned_ca, words_ca, chars_ca, valid_ca = _get_clean_info(text_ca)
    cleaned_es, words_es, chars_es, valid_es = _get_clean_info(text_es)
    cleaned_en, words_en, chars_en, valid_en = _get_clean_info(text_en)

    has_ca = valid_ca
    has_es = valid_es
    has_en = valid_en

    def _detect_slot(text: Optional[str], valid: bool, expected_lang_prefix: str) -> str:
        if not text or not isinstance(text, str) or not text.strip():
            return "missing"
        if is_placeholder_abstract(text):
            return "placeholder"
        if is_corrupted_abstract(text):
            return "corrupted"
        cleaned = clean_text_for_analysis(text)
        if len(cleaned.split()) >= 20:
            det = detect_language(text)
            if det != "unknown":
                det_prefix = det.split("_")[0]
                if det_prefix != expected_lang_prefix:
                    return f"mismatch_{det}"
        return "none"

    detected_ca = _detect_slot(text_ca, valid_ca, "ca")
    detected_es = _detect_slot(text_es, valid_es, "es")
    detected_en = _detect_slot(text_en, valid_en, "en")

    present_langs = {}
    if has_ca:
        present_langs["ca"] = words_ca
    if has_es:
        present_langs["es"] = words_es
    if has_en:
        present_langs["en"] = words_en

    if not present_langs:
        shortest_lang = "none"
        longest_lang = "none"
        diff_words = 0
        ratio_words = 0.0
        pct_discrepancy = 0.0
        severity = "critical"
        has_discrepancy = True
    else:
        sorted_by_words = sorted(present_langs.items(), key=lambda x: (x[1], x[0]))
        shortest_lang = sorted_by_words[0][0]
        longest_lang = sorted_by_words[-1][0]

        min_words = sorted_by_words[0][1]
        max_words = sorted_by_words[-1][1]

        diff_words = max_words - min_words
        ratio_words = round(max_words / max(1, min_words), 1) if min_words > 0 else 0.0
        pct_discrepancy = round((diff_words / max(1, max_words)) * 100.0, 1) if max_words > 0 else 0.0

        has_all_three = has_ca and has_es and has_en
        has_detected_issues = (detected_ca != "none") or (detected_es != "none") or (detected_en != "none")

        if not has_all_three or has_detected_issues:
            has_discrepancy = True
            if len(present_langs) == 0:
                severity = "critical"
            elif len(present_langs) < 3 or has_detected_issues:
                severity = "medium"
            else:
                severity = "low"
        else:
            if pct_discrepancy < 25.0:
                severity = "none"
                has_discrepancy = False
            elif pct_discrepancy < 40.0:
                severity = "low"
                has_discrepancy = True
            elif pct_discrepancy < 60.0:
                severity = "medium"
                has_discrepancy = True
            else:
                severity = "high"
                has_discrepancy = True

    return {
        "has_ca": has_ca,
        "has_es": has_es,
        "has_en": has_en,
        "words_ca": words_ca,
        "words_es": words_es,
        "words_en": words_en,
        "chars_ca": chars_ca,
        "chars_es": chars_es,
        "chars_en": chars_en,
        "diff_words": diff_words,
        "ratio_words": ratio_words,
        "pct_discrepancy": pct_discrepancy,
        "shortest_lang": shortest_lang,
        "longest_lang": longest_lang,
        "severity": severity,
        "has_discrepancy": has_discrepancy,
        "detected_ca": detected_ca,
        "detected_es": detected_es,
        "detected_en": detected_en,
        "analyzed_at": datetime.now(timezone.utc).isoformat()
    }


@dataclass(frozen=True)
class AbstractSlot:
    """Value object representing a validated abstract slot."""
    slot_key: str
    text: str
    detected_lang: str


class AbstractCluster:
    """
    Deep domain module encapsulating the multi-lingual abstract cluster of a Research Output.
    Encapsulates:
    - Slot normalization and cleaning
    - Filtering of placeholder statements ('Sense resum...', 'Sin resumen...')
    - Screening of LLM corruptions and homoglyphs
    - Detection and removal of truncated text fragments
    - Language classification across slots
    - Cross-slot deduplication and partial clone overlap detection
    - Automated discrepancy analysis metrics calculation
    """
    def __init__(
        self,
        raw_abstracts: Optional[Dict[str, Any]],
        valid_entries: Dict[str, str],
        unique_slots: List[AbstractSlot],
        discrepancy_analysis: Dict[str, Any],
        has_cloned_or_untranslated: bool
    ):
        self.raw_abstracts = raw_abstracts
        self.valid_entries = valid_entries
        self.unique_slots = unique_slots
        self.discrepancy_analysis = discrepancy_analysis
        self.has_cloned_or_untranslated = has_cloned_or_untranslated

    @property
    def is_empty(self) -> bool:
        return len(self.valid_entries) == 0

    @property
    def english_candidates(self) -> List[AbstractSlot]:
        return [s for s in self.unique_slots if s.detected_lang == LANG_EN]

    @property
    def spanish_candidates(self) -> List[AbstractSlot]:
        return [s for s in self.unique_slots if s.detected_lang == LANG_ES]

    @property
    def catalan_candidates(self) -> List[AbstractSlot]:
        return [s for s in self.unique_slots if s.detected_lang == LANG_CA]

    @property
    def foreign_candidates(self) -> List[AbstractSlot]:
        return [s for s in self.unique_slots if s.detected_lang not in (LANG_EN, LANG_ES, LANG_CA)]

    @property
    def preferred_source(self) -> Optional[AbstractSlot]:
        if self.english_candidates:
            return next((s for s in self.english_candidates if s.slot_key == LANG_EN), self.english_candidates[0])
        if self.foreign_candidates:
            return self.foreign_candidates[0]
        if self.spanish_candidates:
            return next((s for s in self.spanish_candidates if s.slot_key == LANG_ES), self.spanish_candidates[0])
        if self.catalan_candidates:
            return next((s for s in self.catalan_candidates if s.slot_key == LANG_CA), self.catalan_candidates[0])
        if self.unique_slots:
            return self.unique_slots[0]
        return None

    @classmethod
    def from_raw(cls, raw_abstracts: Optional[Dict[str, Any]]) -> "AbstractCluster":
        discrepancies = analyze_discrepancies(raw_abstracts)
        has_clones = has_identical_cloned_abstracts(raw_abstracts)

        if not raw_abstracts or not isinstance(raw_abstracts, dict):
            return cls(raw_abstracts, {}, [], discrepancies, has_clones)

        valid: Dict[str, str] = {}
        for k, v in raw_abstracts.items():
            if v and isinstance(v, str) and len(clean_text_for_analysis(v)) >= 10 and not is_placeholder_abstract(v) and not is_corrupted_abstract(v):
                valid[k] = v.strip()

        if valid:
            max_len = max(len(v) for v in valid.values())
            if max_len >= 250:
                for k, v in list(valid.items()):
                    if len(v) < 200 and (len(v) / max_len) < 0.40:
                        del valid[k]

        unique_slots: List[AbstractSlot] = []
        seen_norm: Dict[str, int] = {}
        for k, text in valid.items():
            norm = clean_text_for_analysis(text).lower()
            if norm not in seen_norm:
                det = detect_language(text)
                seen_norm[norm] = len(unique_slots)
                unique_slots.append(AbstractSlot(slot_key=k, text=text, detected_lang=det))

        return cls(raw_abstracts, valid, unique_slots, discrepancies, has_clones)





