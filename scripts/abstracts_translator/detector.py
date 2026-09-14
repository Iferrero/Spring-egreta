import re
from typing import Optional

try:
    from langdetect import detect as langdetect_detect, DetectorFactory
    DetectorFactory.seed = 0
    HAS_LANGDETECT = True
except ImportError:
    HAS_LANGDETECT = False

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

    # Quick Cyrillic check for Russian
    if CYRILLIC_PATTERN.search(cleaned):
        return "ru"


    # 1. Primary: Use langdetect if available
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

    # 2. Heuristic fallback based on distinct vocabulary
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

