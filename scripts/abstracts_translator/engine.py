import re
import html
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
    LANG_MAP_ENGLISH,
    LANG_MAP_PROMPT
)
from .detector import detect_language, clean_text_for_analysis


def build_fallback_messages(texto_original: str, idioma_destino: str) -> List[Dict[str, str]]:
    """
    Builds direct, streamlined prompts for fallback retry when primary structured prompt fails to translate.
    """
    sanitized = sanitize_academic_abstract(texto_original)
    copyright_banner = extract_copyright_banner(sanitized)
    if copyright_banner:
        sanitized = sanitized.replace(copyright_banner, "").strip()
    if idioma_destino in ("ca_ES", "ca") or LANG_MAP_CATALAN.get(idioma_destino) == "català":
        return [
            {"role": "system", "content": "Tradueix aquest títol o resum científic al català. És obligatori traduir totes les paraules al català. No tornis el text original ni en anglès."},
            {"role": "user", "content": f"Text a traduir al català:\n{sanitized}"}
        ]
    elif idioma_destino in ("en_GB", "en") or LANG_MAP_ENGLISH.get(idioma_destino) == "English":
        return [
            {"role": "system", "content": "Translate this scientific title or text into English. It is mandatory to translate all words into English. Do not return the untranslated source text."},
            {"role": "user", "content": f"Text to translate into English:\n{sanitized}"}
        ]
    else:
        return [
            {"role": "system", "content": "Traduce este título o resumen científico al español. Es obligatorio traducir todas las palabras al español. No devuelvas el texto original ni en inglés."},
            {"role": "user", "content": f"Texto a traducir al español:\n{sanitized}"}
        ]


def is_bibliographic_reference_text(text: str) -> bool:
    """
    Returns True if text is primarily composed of bibliographic references, citations, paper titles,
    funding headers, institutional/corporate sponsor lists, book table of contents, or author/collaborator contribution lists.
    Reference sections, chapter listings, contributor lists, and institutional lists naturally retain original publication titles, author names,
    corporate sponsor entity proper nouns, cities, and page numbers, making strict target language validation inapplicable.
    """
    if not text or not isinstance(text, str):
        return False
    years = re.findall(r'\b(?:18|19|20)\d{2}[a-z]?\b', text)
    pages = re.findall(r'\b(?:pp?|vols?|tomo|t)\.\s*[\d\-,\s]+', text, re.IGNORECASE)
    authors = re.findall(r'[A-Z][a-zà-ÿ]+,\s*[A-Z]\.', text)
    et_als = re.findall(r'\bet\s+al\b', text, re.IGNORECASE)
    citations = re.findall(r'\b[A-Z][a-zà-ÿ]+(?:\s+et\s+al\.?|\s+and\s+[A-Z][a-zà-ÿ]+)?\s*\((?:18|19|20)\d{2}\b[^\)]*\)|\((?:18|19|20)\d{2}\b[^\)]*\)', text)
    pub_locations = re.findall(r'\b(?:Paris|Madrid|Barcelona|Bordeaux|Alacant|Sevilla|Valencia|València|Ed\.|Imp\.|Lib\.|Librairie|Presses|Publisher|Publishing|Editorial|Publicacions|Semanario|Diario|Revista|Boletín)\b', text, re.IGNORECASE)
    doi_notices = re.findall(r'\b(?:DOI|https?://doi\.org|corrigendum|erratum|corrects|corrige|corregeix)\b', text, re.IGNORECASE)
    
    funding_headers = re.findall(r'\b(?:FUNDING|FINANÇAMENT|FINANCIACIÓN|AGRADECIMIENTOS|AGRAÏMENTS|ACKNOWLEDGEM?ENTS?|DISCLOSURES?|COMPETING INTERESTS?|CONFLICT OF INTEREST)\b', text, re.IGNORECASE)
    inst_keywords = re.findall(r'\b(?:Cohort|Cohorts|Study|Foundation|Institute|Trust|Group|Committee|Database|Association|Council|Grant|University|Hospital|Center|Centre|Sciences|Healthcare|Pharma|Inc|Ltd|GmbH|Laboratories)\b', text, re.IGNORECASE)
    toc_keywords = re.findall(r'(?:Part|Parte|Capítol|Capítulo|Chapter|Secció|Sección|Índex|Índice|Contents)\b(?:\s+[IVXLCDM\d]+)?', text, re.IGNORECASE)
    contrib_keywords = re.findall(
        r'\b(?:collaborator|collaborators|col·laborador|col·laboradors|colaborador|colaboradores|'
        r'contributor|contributors|contribuent|contribución|contribuciones|'
        r'principal investigator|investigador principal|investigador local|'
        r'acquisition of data|adquisició de dades|adquisición de datos|'
        r'analysis and interpretation|anàlisi i interpretació|análisis e interpretación|'
        r'critical review|revisió crítica|revisión crítica|'
        r'draft manuscript|redacció de l\'article|redacción del artículo|'
        r'steering committee|comitè de direcció|comité de dirección|'
        r'conception and design|concepció i disseny|concepción y diseño)\b',
        text, re.IGNORECASE
    )
    
    score = len(years) + len(pages) * 2 + len(authors) * 2 + len(et_als) * 2 + len(citations) * 2 + len(pub_locations) * 2 + len(funding_headers) * 3 + len(inst_keywords) + len(toc_keywords) * 2 + len(contrib_keywords) * 2 + len(doi_notices) * 2
    return score >= 3 or len(years) >= 4 or (len(funding_headers) >= 1 and len(inst_keywords) >= 1) or len(inst_keywords) >= 2 or len(toc_keywords) >= 2 or len(contrib_keywords) >= 2 or len(pub_locations) >= 2 or len(doi_notices) >= 2




def is_valid_translation_language(content: str, target_lang_code: str) -> bool:
    """
    Validates if the translated text matches the expected target language.
    """
    if not content or len(content.strip()) < 15:
        return True
    if is_bibliographic_reference_text(content):
        return True
    det = detect_language(content)
    if det == "unknown":
        return True
    if target_lang_code in ("ca_ES", "ca") and det != "ca_ES":
        return False
    if target_lang_code in ("es_ES", "es") and det != "es_ES":
        return False
    if target_lang_code in ("en_GB", "en") and det != "en_GB":
        return False
    return True


def build_salamandra_messages(texto_original: str, idioma_destino: str, idioma_origen: Optional[str] = None) -> List[Dict[str, str]]:
    """
    Builds structured system and user messages for Salamandra LLM, explicitly specifying both
    source and target languages for maximum translation fidelity and precision.
    """
    sanitized = sanitize_academic_abstract(texto_original)
    copyright_banner = extract_copyright_banner(sanitized)
    if copyright_banner:
        sanitized = sanitized.replace(copyright_banner, "").strip()

    src_code = idioma_origen or "en_GB"

    # Catalan target prompt
    if idioma_destino in ("ca_ES", "ca") or LANG_MAP_CATALAN.get(idioma_destino) == "català":
        src_from = format_lang_from_ca(src_code)
        src_name = LANG_MAP_CATALAN.get(src_code, src_code)
        system_prompt = (
            "Ets un traductor professional de literatura científica especialitzat en la traducció al català. "
            f"La teva única tasca és traduir fidelment el text d'entrada complet {src_from} a català. "
            f"NO repeteixis el text en {src_name}. NO deixis frases sense traduir. "
            "És CRÍTIC que mantinguis de forma ESTRICTA l'ordre seqüencial exactament igual que el text original. "
            "NO reordenis cap frase. NO ometis cap frase ni part d'una frase. NO fusionis oracions diferents. "
            "Tradueix de forma precisa els encapçalaments de secció (Importance -> Importància:, Objective -> Objectiu:, Design -> Disseny:, Setting -> Àmbit:, Participants -> Participants:, Exposures -> Exposicions:, Interventions -> Intervencions:, Main Outcomes and Measures -> Variables principals i mesures:, Results -> Resultats:, Conclusions and Relevance -> Conclusions i rellevància:, Background -> Antecedents:, Methods -> Mètodes:, Conclusion -> Conclusions:). "
            "Mantén el rigor terminològic màxim, el registre formal acadèmic i conserva intactes les declaracions de copyright (com '© 2019...'), les fórmules químiques, les sigles i els valors numèrics. "
            "Retorna ÚNICAMENT el text traduït al català, sense introduccions ni comentaris."
        )
        user_prompt = f"Text original ({src_name}):\n{sanitized}\n\nTraducció completa al català (tots els paràgrafs):"
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
    # English target prompt
    elif idioma_destino in ("en_GB", "en") or LANG_MAP_ENGLISH.get(idioma_destino) == "English":
        src_name = LANG_MAP_ENGLISH.get(src_code, src_code)
        system_prompt = (
            "You are a professional scientific literature translator specializing in English translation. "
            f"Your only task is to faithfully translate the full input text from {src_name} into English. "
            f"Do NOT repeat the source text in {src_name}. Do NOT leave sentences untranslated. "
            "It is CRITICAL that you maintain strict sentence ordering exactly as in the original text. "
            "Do NOT reorder sentences. Do NOT omit any sentence or part of a sentence. "
            "Translate section headers accurately (e.g. Importance -> Importance:, Objective -> Objective:, Design -> Design:, Setting -> Setting:, Participants -> Participants:, Exposures -> Exposures:, Interventions -> Interventions:, Main Outcomes -> Main Outcomes:, Results -> Results:, Conclusions -> Conclusions:). "
            "Maintain maximum scientific terminology rigor, formal academic style, and keep copyright notices (like '© 2019...'), chemical formulas, acronyms, and numerical values intact. "
            "Return ONLY the translated text in English, with no introductions or comments."
        )
        user_prompt = f"Original text ({src_name}):\n{sanitized}\n\nFull translation into English (all paragraphs):"
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
    # Spanish target prompt (and fallback for other targets)
    else:
        src_from = format_lang_from_es(src_code)
        src_name = LANG_MAP_SPANISH.get(src_code, src_code)
        target_name = LANG_MAP_SPANISH.get(idioma_destino, idioma_destino)
        system_prompt = (
            f"Eres un traductor profesional de literatura científica especializado en la traducción al {target_name}. "
            f"Tu única tarea es traducir fielmente el texto de entrada completo {src_from} a {target_name}. "
            f"NO repitas el texto en {src_name}. NO dejes frases sin traducir. "
            "Es CRÍTICO que mantengas de forma ESTRICTA el orden secuencial exacto de todas las oraciones del texto original. "
            "NO reordenes las frases. NO omitas ninguna frase ni parte de una frase. NO mecles ni fusiones oraciones distintas. "
            "Traduce de forma precisa los encabezados de sección (Importance -> Importancia:, Objective -> Objetivo:, Objectives -> Objetivos:, Design -> Diseño:, Setting -> Ámbito:, Participants -> Participantes:, Exposures -> Exposiciones:, Interventions -> Intervenciones:, Main Outcomes and Measures -> Variables principales y medidas:, Results -> Resultados:, Conclusions and Relevance -> Conclusiones y relevancia:, Background -> Antecedentes:, Methods -> Métodos:, Conclusion -> Conclusión:). "
            "Mantén el máximo rigor terminológico, el estilo formal académico y conserva intactas las declaraciones de copyright (como '© 2019...'), las fórmulas químicas, las siglas y los valores numéricos. "
            f"Devuelve ÚNICAMENTE el texto traducido al {target_name}, sin introducciones ni comentarios."
        )
        user_prompt = f"Texto original ({src_name}):\n{sanitized}\n\nTraducción completa al {target_name} (todos los párrafos):"
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]



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
        m_break = re.search(r"(\s{2,}|\n|\s+(?:Background|Introduction|Objectives?|Objective|Methods?|Method|Results?|Conclusions?|Discussion)\b)", cleaned, flags=re.IGNORECASE)
        if m_break:
            cand = cleaned[:m_break.start()].strip()
        else:
            m_sent = re.search(r"\.\s+[A-Z]", cleaned)
            if m_sent:
                cand = cleaned[:m_sent.start()].strip()
            else:
                cand = cleaned.strip()

        m_year_only = re.match(r"^\s*(?:\bCopyright\b|©|\(c\)|&copy;)\s*(?:©|\(c\)|&copy;)?\s*\d{4}\b", cand, flags=re.IGNORECASE)
        if m_year_only:
            after_year = cand[m_year_only.end():].strip()
            pub_kw = r"\b(?:Elsevier|Wiley|Springer|IEEE|SAGE|Society|Press|Publishing|Authors|Ltd|Inc|Corp|B\.V\.|Karger|AG|MDPI|Frontiers|Nature|BMC|Plos|Lippincott|Williams|Wilkins|Thieme|Emerald|Brill|De Gruyter|Verlag|Academic|All rights reserved|Tous droits|Todos los derechos)\b"
            if after_year and not re.search(pub_kw, after_year, re.IGNORECASE):
                cand = m_year_only.group(0).strip()

        if cand.endswith("."):
            cand = cand[:-1].strip()

        if re.search(r"\b\d{4}\b", cand) or re.search(copyright_symbol_pattern, cand, flags=re.IGNORECASE):
            if len(cand) <= 120:
                return cand.strip()

    # 2. Check TRAILING copyright
    trailing_match = re.search(
        r"(?:\.\s+|\n|\s{2,}|^)(?:" + copyright_symbol_pattern + r")\s*(?:\d{4})?\s*(?:[a-zA-Z0-9à-ÿÀ-ß\&\.\,\-\'\s]{0,120})\s*$",
        cleaned,
        flags=re.IGNORECASE
    )
    if trailing_match:
        candidate = trailing_match.group(0).strip()
        if candidate.startswith("."):
            candidate = candidate[1:].strip()
        if candidate.endswith("."):
            candidate = candidate[:-1].strip()
        if len(candidate) <= 120:
            return candidate.strip()

    return None


def compute_dynamic_max_tokens(text: str, default_cap: int = SAMPLING_MAX_TOKENS) -> int:
    """
    Dynamically computes max_tokens to prevent LLM generation loops while providing generous headroom.
    - Calculated max_tokens = (input_words * 1.4 * 2.2) + 500 buffer.
    - Clamped with a minimum of 1536 tokens and default_cap / 3072 max limit.
    """
    if not text or not isinstance(text, str):
        return 1024
    words = len(text.split())
    estimated_input_tokens = int(words * 1.4)
    calculated = int(estimated_input_tokens * 2.2) + 500
    max_limit = max(default_cap, 3072)
    return max(1536, min(calculated, max_limit))


COMMON_ABBREVIATIONS = (
    r"Fig|Figs|spp|sp|et al|vs|vol|vols|no|nos|e\.g|i\.e|dr|prof|mr|mrs|ms|ref|refs|"
    r"approx|eq|eqs|sec|secs|tab|tabs|al|inc|ltd|co|corp|st|p|pp|cf"
)


def split_into_sentences(text: str) -> List[str]:
    """
    Splits text into sentences while respecting common scientific abbreviations
    (e.g., Fig., spp., et al., vol., e.g., i.e.) and single-capital initials (e.g. A., B.).
    """
    if not text:
        return []
    raw_sentences = re.split(r'(?:(?<=[.!?])|(?<=[.!?]["\'”’\)]))\s+(?=[A-Z0-9"“”\(\[\'])', text)
    sentences = []
    buf = ""
    for s in raw_sentences:
        if buf:
            buf += " " + s
        else:
            buf = s
        last_word = buf.split()[-1] if buf.split() else ""
        clean_word = re.sub(r"[^\w\.]", "", last_word)
        if re.search(r"^(?:" + COMMON_ABBREVIATIONS + r")\.$", clean_word, re.IGNORECASE) or re.search(r"^[A-Z]\.$", clean_word):
            continue
        else:
            sentences.append(buf)
            buf = ""
    if buf:
        sentences.append(buf)
    return sentences


def split_large_text(text: str, max_chunk_words: int = 750) -> List[str]:
    """
    Splits large texts into sentence-aware chunks (default ~750 words) to prevent
    7B LLMs from exceeding context generation or KV cache limits on giant documents.
    Forces hard splitting if an unpunctuated block (e.g., author lists) exceeds max_chunk_words.
    """
    if not text or len(text.split()) <= max_chunk_words:
        return [text]

    paras = [p.strip() for p in text.split("\n\n") if p.strip()]
    chunks = []
    curr_chunk = []
    curr_words = 0

    for p in paras:
        p_words = len(p.split())
        if curr_words + p_words <= max_chunk_words:
            curr_chunk.append(p)
            curr_words += p_words
        else:
            if curr_chunk:
                chunks.append("\n\n".join(curr_chunk))
                curr_chunk = []
                curr_words = 0

            if p_words > max_chunk_words:
                sentences = split_into_sentences(p)
                sub_chunk = []
                sub_words = 0
                for s in sentences:
                    s_w = len(s.split())
                    if s_w > max_chunk_words:
                        s_words = s.split()
                        for i in range(0, len(s_words), max_chunk_words):
                            sub_block = " ".join(s_words[i:i+max_chunk_words])
                            if sub_words + len(sub_block.split()) <= max_chunk_words:
                                sub_chunk.append(sub_block)
                                sub_words += len(sub_block.split())
                            else:
                                if sub_chunk:
                                    chunks.append(" ".join(sub_chunk))
                                sub_chunk = [sub_block]
                                sub_words = len(sub_block.split())
                    elif sub_words + s_w <= max_chunk_words:
                        sub_chunk.append(s)
                        sub_words += s_w
                    else:
                        if sub_chunk:
                            chunks.append(" ".join(sub_chunk))
                        sub_chunk = [s]
                        sub_words = s_w
                if sub_chunk:
                    curr_chunk.append(" ".join(sub_chunk))
                    curr_words = sub_words
            else:
                curr_chunk.append(p)
                curr_words = p_words

    if curr_chunk:
        chunks.append("\n\n".join(curr_chunk))

    return chunks if chunks else [text]


TRANSLATION_CHUNK_WORDS = 200


def split_for_translation(text: str, max_chunk_words: int = TRANSLATION_CHUNK_WORDS) -> List[str]:
    """
    Splits text into translation units ONLY at paragraph or sentence boundaries.
    Each paragraph is its own unit (never grouped); a paragraph longer than max_chunk_words
    is split into sentence groups of up to ~max_chunk_words.
    A single sentence longer than the limit is kept whole (never cut mid-sentence)
    unless it exceeds 3x the limit (e.g. unpunctuated author lists), which is then hard-split.
    """
    if not text:
        return [text]
    chunks: List[str] = []

    for para in [p.strip() for p in text.split("\n\n") if p.strip()]:
        p_words = len(para.split())
        if p_words <= max_chunk_words:
            # One paragraph per request: Salamandra tends to stop at the first paragraph
            # break and silently drop the remaining paragraphs of a multi-paragraph input.
            chunks.append(para)
            continue

        # Long paragraph: split into sentence groups
        sent_buf: List[str] = []
        sent_words = 0
        for s in split_into_sentences(para):
            s_w = len(s.split())
            if s_w > max_chunk_words * 3:
                if sent_buf:
                    chunks.append(" ".join(sent_buf))
                    sent_buf, sent_words = [], 0
                ws = s.split()
                for i in range(0, len(ws), max_chunk_words):
                    chunks.append(" ".join(ws[i:i + max_chunk_words]))
                continue
            if sent_buf and sent_words + s_w > max_chunk_words:
                chunks.append(" ".join(sent_buf))
                sent_buf, sent_words = [], 0
            sent_buf.append(s)
            sent_words += s_w
        if sent_buf:
            chunks.append(" ".join(sent_buf))

    return chunks if chunks else [text]


def _ngram_counts(words: List[str], n: int) -> Dict[tuple, int]:
    counts: Dict[tuple, int] = {}
    for i in range(len(words) - n + 1):
        g = tuple(words[i:i + n])
        counts[g] = counts.get(g, 0) + 1
    return counts


def check_translation_completeness(source: str, translation: str, finish_reason: Optional[str] = None):
    """
    Detects truncated, incomplete or looping translations. Returns (ok, reason).
    - finish_reason == 'length': the model hit max_tokens -> truncated.
    - word-count ratio translation/source outside [0.6, 1.9] (en<->ca/es is usually 0.95-1.35).
    - repetition loop: a 12-word sequence repeated in the output more than any sequence repeats in the source.
    - source has several sentences but the output stops mid-sentence (no final punctuation).
    """
    if finish_reason == "length":
        return False, "truncada (alcanzó max_tokens)"

    src_words = source.split()
    out_words = translation.split()
    n_src = len(src_words)
    if n_src < 15:
        return True, ""

    ratio = len(out_words) / max(n_src, 1)
    if ratio < 0.6:
        return False, f"incompleta (ratio palabras {ratio:.2f})"
    if ratio > 1.9:
        return False, f"demasiado larga / repetida (ratio palabras {ratio:.2f})"

    # Repetition loop: a 12-word sequence repeated in the output. Source and output are in
    # different languages, so compare against the maximum repetition level in the source.
    norm = lambda ws: [re.sub(r"[^\w]", "", w.lower()) for w in ws if re.sub(r"[^\w]", "", w)]
    src_rep = max(_ngram_counts(norm(src_words), 12).values(), default=1)
    for g, c in _ngram_counts(norm(out_words), 12).items():
        if c >= 2 and c > src_rep:
            return False, f"bucle de repetición ('{' '.join(g)}' x{c})"

    src_stripped = source.rstrip()
    out_stripped = translation.rstrip()
    if src_stripped and src_stripped[-1] in ".!?)]\"'”’" and out_stripped and out_stripped[-1] not in ".!?)]\"'”’»:;":
        return False, "termina a media frase"

    return True, ""



def sanitize_academic_abstract(text: str) -> str:
    """
    Sanitizes and formats raw academic abstracts:
    - Unescapes single and double HTML entities (&lt;p&gt; -> <p>, &amp; -> &, &nbsp; -> space).
    - Strips all HTML tags while converting block tags (<div>, <p>, <br>) into line breaks.
    - Normalizes non-standard Unicode mathematical symbols (e.g. \u2212 minus sign to standard ASCII -).
    - Preserves publisher copyright banners (e.g. '© 2019 The British Pharmacological Society').
    - Structures sections with clean paragraph breaks so the model translates the full text.
    """
    if not text:
        return ""
    
    # 0. Unescape HTML entities (handles single and double-escaped &lt;p&gt;, &nbsp;, etc.)
    cleaned = text
    for _ in range(3):
        if "&" in cleaned or "&#" in cleaned:
            cleaned = html.unescape(cleaned)
        else:
            break

    cleaned = cleaned.replace("\xa0", " ").replace("\u00a0", " ")

    # 0.5 Convert raw HTML breaks and paragraph/div/block tags into clean newlines
    cleaned = re.sub(r"<br\s*/?>", "\n\n", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"</?(?:p|div|li|tr|td)\b[^>]*>", "\n\n", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"<[^>]+>", "", cleaned)

    # 1. Normalize unicode minus sign \u2212, dashes and non-breaking hyphens \u2011
    cleaned = cleaned.replace("\u2212", "-").replace("\u2013", "-").replace("\u2014", "-").replace("\u2011", "-")

    # 2. Normalize single line breaks within paragraphs into spaces (preserving double newlines for paragraph breaks)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
    cleaned = re.sub(r"(?<!\n)\n(?!\n)", " ", cleaned)
    cleaned = re.sub(r"[ \t]+", " ", cleaned)

    # 2.5 Strip layout noise like dot leaders (. . . . . . . .) from PDF/journal extraction
    cleaned = re.sub(r"(?:\s*\.\s*){4,}", ". ", cleaned)
    cleaned = re.sub(r"(?:\s*\[-\]\s*){2,}", " ", cleaned)

    # 3. Extract and relocate leading copyright banners to the end of the abstract
    copyright_banner = extract_copyright_banner(cleaned)
    if copyright_banner and cleaned.startswith(copyright_banner):
        cleaned = cleaned[len(copyright_banner):].strip()
        if cleaned.startswith("."):
            cleaned = cleaned[1:].strip()

    # 4. Ensure single space and inline formatting before common structured section headers
    headers_with_colon = [
        "Conclusions and Implications:", "Conclusions and Relevance:", "Materials and Methods:",
        "Experimental Approach:", "Background and Purpose:", "Main Outcomes and Measures:",
        "Main Outcomes:", "Key Results:", "Main Results:", "Methodology:", "Importance:",
        "Exposures:", "Interventions:", "Participants:", "Design, Setting, and Participants:",
        "Background:", "Introduction:", "Objectives:", "Objective:", "Methods:", "Method:",
        "Design:", "Setting:", "Results:", "Conclusions:", "Conclusion:", "Discussion:"
    ]
    pattern_headers = r"(?<=[.\s])(" + "|".join(re.escape(h[:-1]) for h in headers_with_colon) + r")\s*:"
    cleaned = re.sub(pattern_headers, r" \1:", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r":{2,}", ":", cleaned)
    # Collapse horizontal whitespace only; keep paragraph breaks (\n\n) intact
    cleaned = re.sub(r"[ \t]{2,}", " ", cleaned)
    cleaned = re.sub(r"[ \t]*\n[ \t]*", "\n", cleaned)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned).strip()

    # Ensure structured section headers at paragraph starts remain inline with their content
    lines = cleaned.split("\n\n")
    fixed_lines = []
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.endswith(":") and len(line.split()) <= 5 and i + 1 < len(lines):
            fixed_lines.append(f"{line} {lines[i+1].strip()}")
            i += 2
        else:
            fixed_lines.append(line)
            i += 1
    cleaned = "\n\n".join(fixed_lines)

    if copyright_banner and not cleaned.endswith(copyright_banner):
        cleaned = f"{cleaned}\n\n{copyright_banner}"

    return cleaned


def ensure_exact_copyright_preserved(original_text: str, translation_text: str) -> str:
    """
    Ensures that if the original text contains a publisher copyright notice (e.g. '© 2017 Elsevier Ltd'),
    the EXACT original copyright notice is placed strictly at the VERY END of the output abstract,
    without leaving intermediate copyright banners in the middle of chunked translations.
    """
    if not original_text or not translation_text:
        return translation_text

    exact_copyright = extract_copyright_banner(original_text)
    if not exact_copyright:
        return translation_text

    clean_trans = translation_text.strip()

    banner_pattern = re.escape(exact_copyright) + r"(?:\s+(?:Tots els drets reservats|All rights reserved|Todos los derechos reservados))?\.?"
    clean_trans = re.sub(banner_pattern, "", clean_trans, flags=re.IGNORECASE).strip()

    copyright_symbol_pattern = r"(?:Copyright\s+)?(?:©|\(c\)|&copy;|Copyright\b)\s*(?:\d{4})?"
    clean_trans = re.sub(copyright_symbol_pattern, "", clean_trans, flags=re.IGNORECASE).strip()
    clean_trans = re.sub(r"\s{2,}", " ", clean_trans).strip()

    if not clean_trans:
        return translation_text

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

    # Clean up known Cyrillic token hallucinations in Latin text (e.g. COVID-1льs / COVID-1ль9 -> COVID-19, DISEльO -> DISEÑO)
    cleaned = re.sub(r'COVID[-‑]?1[\u0400-\u04FF\uFFFD]+[s9]?', 'COVID-19', cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r'DISE[^\w\s]*[ль\u0400-\u04FF\uFFFD\uFFFC]+O', 'DISEÑO', cleaned)
    cleaned = re.sub(r'dise[^\w\s]*[ль\u0400-\u04FF\uFFFD\uFFFC]+o', 'diseño', cleaned)
    cleaned = re.sub(r'(\d+)\s*[\u0400-\u04FF\uFFFD]+oM[л\u0400-\u04FF\uFFFD]*', r'\1 nmol/L', cleaned, flags=re.IGNORECASE)

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




def format_messages_to_chatml(messages: List[Dict[str, str]]) -> str:
    """Formats role messages into ChatML prompt string for Salamandra LLM."""
    prompt = ""
    for msg in messages:
        role = msg["role"]
        content = msg["content"]
        prompt += f"<|im_start|>{role}\n{content}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


class TranslationError(RuntimeError):
    """Domain exception raised when all prompt escalation and retry attempts fail."""
    def __init__(self, target_lang: str, reason: str, chunk_index: int = 0):
        super().__init__(f"Translation failed for {target_lang} (chunk {chunk_index}): {reason}")
        self.target_lang = target_lang
        self.reason = reason
        self.chunk_index = chunk_index


class RawGeneration:
    """Value object returned by InferenceAdapter at the raw completion seam."""
    def __init__(
        self,
        content: str,
        prompt_tokens: int = 0,
        completion_tokens: int = 0,
        latency_ms: float = 0.0,
        finish_reason: Optional[str] = None,
        simulated: bool = False
    ):
        self.content = content
        self.prompt_tokens = prompt_tokens
        self.completion_tokens = completion_tokens
        self.latency_ms = latency_ms
        self.finish_reason = finish_reason
        self.simulated = simulated


class InferenceAdapter(ABC):
    """
    Seam for raw text generation. Implementations only execute inference for a specific backend
    without managing sentence chunking, retry progression, or translation validation.
    """
    model_name: str = DEFAULT_MODEL_NAME

    @abstractmethod
    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        stop: List[str]
    ) -> Optional[RawGeneration]:
        """Executes a single raw generation turn. Returns RawGeneration, or None on transient connection error."""
        pass


def _detect_target_from_messages(messages: List[Dict[str, str]]) -> str:
    for m in messages:
        c = m.get("content", "").lower()
        if "into english" in c or "al inglés" in c or "a anglès" in c or "text to translate into english" in c:
            return "en_GB"
        if "al català" in c or "a català" in c or "into catalan" in c or "al catalan" in c or "text a traduir al català" in c:
            return "ca_ES"
        if "al español" in c or "a español" in c or "into spanish" in c or "al castellano" in c or "texto a traducir al español" in c:
            return "es_ES"
    for m in messages:
        if m.get("role") == "user":
            c = m.get("content", "").lower()
            if "català" in c or "catalan" in c:
                return "ca_ES"
            if "english" in c or "anglès" in c or "inglés" in c:
                return "en_GB"
            if "español" in c or "spanish" in c or "castellano" in c:
                return "es_ES"
    return "es_ES"


def _extract_source_from_user_message(content: str) -> str:
    m = re.search(r"(?:Text original|Original text|Texto original)[^\n]*:\n(.*?)(?:\n\n|\Z)", content, flags=re.DOTALL | re.IGNORECASE)
    if m:
        return m.group(1).strip()
    m = re.search(r"(?:Text a traduir|Texto a traducir|Text to translate)[^\n]*:\n(.*)", content, flags=re.DOTALL | re.IGNORECASE)
    if m:
        return m.group(1).strip()
    return content.strip()


MOCK_SENTENCES_CA = [
    "Aquest treball analitza en detall l'evolució metodològica i experimental observada en la mostra seleccionada durant aquest període.",
    "Els resultats obtinguts confirmen de manera estadísticament significativa les hipòtesis plantejades inicialment en aquesta recerca.",
    "Es van identificar correlacions rellevants entre les diferents variables analitzades durant el procés d'observació sistemàtica.",
    "L'evidència empírica d'aquest estudi recolza la necessitat d'adoptar nous enfocaments multidisciplinaris en futures investigacions.",
    "El marc conceptual desenvolupat proporciona eines analítiques sòlides per interpretar adequadament les dades presentades.",
    "Les conclusions destaquen la importància de considerar factors contextuals en avaluar el rendiment i impacte global del projecte.",
    "Es van observar diferències substancials entre els grups de control i els grups sotmesos a aquesta intervenció clínica específica.",
    "La metodologia quantitativa emprada va permetre reduir significativament el biaix en la recollida i anàlisi de la informació.",
    "Aquestes troballes aporten noves perspectives teòriques i pràctiques per al desenvolupament de protocols científics més eficients.",
    "Aquest article contribueix al coneixement acadèmic aportant solucions innovadores davant els reptes identificats prèviament."
]

MOCK_SENTENCES_ES = [
    "Este trabajo analiza en detalle la evolución metodológica y experimental observada en la muestra seleccionada durante este período.",
    "Los resultados obtenidos confirman de manera estadísticamente significativa las hipótesis planteadas inicialmente en esta investigación.",
    "Se identificaron correlaciones relevantes entre las diferentes variables analizadas durante el proceso de observación sistemática.",
    "La evidencia empírica de este estudio respalda la necesidad de adoptar nuevos enfoques multidisciplinares en futuras investigaciones.",
    "El marco conceptual desarrollado proporciona herramientas analíticas sólidas para interpretar adecuadamente los datos presentados.",
    "Las conclusiones destacan la importancia de considerar factores contextuales al evaluar el rendimiento e impacto global del proyecto.",
    "Se observaron diferencias sustanciales entre los grupos de control y los grupos sometidos a esta intervención clínica específica.",
    "La metodología cuantitativa empleada permitió reducir significativamente el sesgo en la recolección y análisis de la información.",
    "Estos hallazgos aportan nuevas perspectivas teóricas y prácticas para el desarrollo de protocolos científicos más eficientes.",
    "Este artículo contribuye al conocimiento académico aportando soluciones innovadoras frente a los desafíos identificados previamente."
]

MOCK_SENTENCES_EN = [
    "This work provides a comprehensive analysis of the methodological and experimental developments observed in the selected sample.",
    "The obtained results confirm with high statistical significance the initial research hypotheses formulated in this scientific study.",
    "Relevant correlations were systematically identified across the experimental variables investigated during the observation timeframe.",
    "Empirical evidence gathered throughout this research underscores the necessity of multidisciplinary approaches in future investigations.",
    "The analytical framework introduced here establishes robust tools to accurately interpret complex biological and physical observations.",
    "The conclusions emphasize the critical importance of evaluating contextual parameters when assessing the overall performance metrics.",
    "Substantial variations were documented between the control cohorts and the patient populations undergoing therapeutic intervention.",
    "The quantitative experimental methodology employed successfully minimized measurement bias across data acquisition protocols.",
    "These findings contribute valuable theoretical and practical insights towards improving standardized clinical trial workflows.",
    "This paper expands modern academic knowledge by proposing innovative and scalable solutions to previously unresolved challenges."
]


class MockAdapter(InferenceAdapter):
    """Synthetic generation adapter for offline tests and benchmarking."""
    def __init__(
        self,
        model_name: str = DEFAULT_MODEL_NAME,
        simulated_speed_tok_s: float = 45.0,
        custom_responder: Optional[Any] = None
    ):
        self.model_name = model_name
        self.simulated_speed_tok_s = simulated_speed_tok_s
        self.custom_responder = custom_responder

    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        stop: List[str]
    ) -> Optional[RawGeneration]:
        if self.custom_responder is not None:
            return self.custom_responder(messages, temperature, max_tokens, stop)

        user_content = ""
        for m in messages:
            if m.get("role") == "user":
                user_content = m.get("content", "")

        target_lang = _detect_target_from_messages(messages)
        src_text = _extract_source_from_user_message(user_content)
        target_word_count = max(10, len(src_text.split()))

        if target_lang == "ca_ES":
            pool = MOCK_SENTENCES_CA
        elif target_lang == "es_ES":
            pool = MOCK_SENTENCES_ES
        else:
            pool = MOCK_SENTENCES_EN

        selected_sentences = []
        accumulated_words = 0
        pool_idx = 0
        while accumulated_words < target_word_count:
            sent = pool[pool_idx % len(pool)]
            sent_words = len(sent.split())
            if accumulated_words > 0 and (accumulated_words + sent_words) / target_word_count > 1.5:
                break
            selected_sentences.append(sent)
            accumulated_words += sent_words
            pool_idx += 1
            if accumulated_words >= target_word_count:
                break

        content = " ".join(selected_sentences)

        prompt_tokens = int(len(user_content.split()) * 1.3)
        completion_tokens = int(len(content.split()) * 1.3)
        latency_s = completion_tokens / self.simulated_speed_tok_s
        latency_ms = latency_s * 1000.0

        return RawGeneration(
            content=content,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            latency_ms=round(latency_ms, 2),
            finish_reason="stop",
            simulated=True
        )


class LMStudioAdapter(InferenceAdapter):
    """Inference adapter connecting to a local LM Studio server via /v1/chat/completions."""
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

    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        stop: List[str]
    ) -> Optional[RawGeneration]:
        payload = {
            "model": self.model_name,
            "messages": messages,
            "temperature": temperature,
            "top_p": SAMPLING_TOP_P,
            "max_tokens": max_tokens,
            "stop": stop
        }
        t0 = time.perf_counter()
        try:
            resp = self.session.post(f"{self.server_url}/chat/completions", json=payload, timeout=self.timeout)
        except requests.exceptions.RequestException as e:
            print(f"[!] Error de solicitud a LM Studio: {e}")
            return None
        latency_ms = (time.perf_counter() - t0) * 1000.0
        if resp.status_code != 200:
            print(f"[!] LM Studio devolvió HTTP {resp.status_code}: {resp.text[:200]}")
            return None
        data = resp.json()
        choice = data["choices"][0]
        content = choice.get("message", {}).get("content", "") or ""
        usage = data.get("usage", {})
        return RawGeneration(
            content=content,
            prompt_tokens=usage.get("prompt_tokens", 0),
            completion_tokens=usage.get("completion_tokens", 0),
            latency_ms=latency_ms,
            finish_reason=choice.get("finish_reason"),
            simulated=False
        )


class VLLMServerAdapter(InferenceAdapter):
    """Inference adapter connecting to a vLLM server via OpenAI-compatible /v1/chat/completions."""
    def __init__(self, server_url: str = VLLM_SERVER_URL, model_name: str = DEFAULT_MODEL_NAME, timeout: int = 120):
        url = server_url.rstrip("/")
        if not url.endswith("/v1"):
            url = f"{url}/v1"
        self.server_url = url
        self.model_name = model_name
        self.timeout = timeout
        self.session = get_resilient_session(retries=3, backoff_factor=1.0)

    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        stop: List[str]
    ) -> Optional[RawGeneration]:
        payload = {
            "model": self.model_name,
            "messages": messages,
            "temperature": temperature,
            "top_p": SAMPLING_TOP_P,
            "max_tokens": max_tokens,
            "stop": stop
        }
        t0 = time.perf_counter()
        try:
            resp = self.session.post(f"{self.server_url}/chat/completions", json=payload, timeout=self.timeout)
        except requests.exceptions.RequestException as e:
            print(f"[!] Error de solicitud a vLLM: {e}")
            return None
        latency_ms = (time.perf_counter() - t0) * 1000.0
        if resp.status_code != 200:
            print(f"[!] vLLM devolvió HTTP {resp.status_code}: {resp.text[:200]}")
            return None
        data = resp.json()
        choice = data["choices"][0]
        content = choice.get("message", {}).get("content", "") or ""
        usage = data.get("usage", {})
        return RawGeneration(
            content=content,
            prompt_tokens=usage.get("prompt_tokens", 0),
            completion_tokens=usage.get("completion_tokens", 0),
            latency_ms=latency_ms,
            finish_reason=choice.get("finish_reason"),
            simulated=False
        )


class LocalVLLMAdapter(InferenceAdapter):
    """Inference adapter running inside GPU node directly using vLLM Python package."""
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, tensor_parallel_size: int = 1):
        try:
            from vllm import LLM
        except ImportError:
            raise ImportError("vLLM package is not installed in this environment. Use VLLMServerAdapter or MockAdapter.")
        self.model_name = model_name
        self.llm = LLM(model=model_name, tensor_parallel_size=tensor_parallel_size)

    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        stop: List[str]
    ) -> Optional[RawGeneration]:
        from vllm import SamplingParams
        sampling_params = SamplingParams(
            temperature=temperature,
            top_p=SAMPLING_TOP_P,
            max_tokens=max_tokens,
            stop=stop
        )
        prompt = format_messages_to_chatml(messages)
        t0 = time.perf_counter()
        outputs = self.llm.generate([prompt], sampling_params)
        latency_ms = (time.perf_counter() - t0) * 1000.0
        out = outputs[0]
        content = out.outputs[0].text
        prompt_tokens = len(out.prompt_token_ids) if hasattr(out, "prompt_token_ids") else len(prompt.split())
        completion_tokens = len(out.outputs[0].token_ids) if hasattr(out.outputs[0], "token_ids") else len(content.split())
        finish_reason = getattr(out.outputs[0], "finish_reason", None)
        return RawGeneration(
            content=content,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            latency_ms=latency_ms,
            finish_reason=finish_reason,
            simulated=False
        )


class TransformersAdapter(InferenceAdapter):
    """Inference adapter using HuggingFace Transformers pipeline."""
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

    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        stop: List[str]
    ) -> Optional[RawGeneration]:
        prompt = format_messages_to_chatml(messages)
        t0 = time.perf_counter()
        out = self.pipe(
            prompt,
            max_new_tokens=max_tokens,
            temperature=max(temperature, 1e-4),
            do_sample=(temperature > 0),
            stop_strings=stop,
            tokenizer=self.tokenizer
        )
        latency_ms = (time.perf_counter() - t0) * 1000.0
        full_generated = out[0]["generated_text"]
        raw_content = full_generated[len(prompt):].strip()
        for st in stop:
            if raw_content.endswith(st):
                raw_content = raw_content[:-len(st)].strip()
        return RawGeneration(
            content=raw_content,
            prompt_tokens=len(self.tokenizer.encode(prompt)),
            completion_tokens=len(self.tokenizer.encode(raw_content)),
            latency_ms=latency_ms,
            finish_reason="stop",
            simulated=False
        )


DETERMINISTIC_TRANSLATION_MAP = [
    # Fixed editorial figure placeholders: [Image: see text]
    (
        re.compile(r"^\s*\[(?:image|«imagen»|imagen|imatge)\s*:\s*(?:see\s+text|ver\s+texto|(?:vegeu|veure)\s+(?:el\s+)?text)\]\s*$", re.IGNORECASE),
        {
            "es_ES": "[Imagen: ver texto]",
            "es": "[Imagen: ver texto]",
            "ca_ES": "[Imatge: vegeu el text]",
            "ca": "[Imatge: vegeu el text]",
            "en_GB": "[Image: see text]",
            "en": "[Image: see text]"
        }
    )
]

def get_deterministic_translation(text: Optional[str], target_lang_code: str) -> Optional[str]:
    """
    Returns an authoritative deterministic translation for well-known fixed technical tags
    (e.g. journal figure placeholders like '[Image: see text]') without invoking the LLM.
    """
    if not text or not isinstance(text, str):
        return None
    cleaned = clean_text_for_analysis(text).strip()
    for pattern, lang_map in DETERMINISTIC_TRANSLATION_MAP:
        if pattern.match(cleaned):
            norm_tgt = target_lang_code.strip()
            if norm_tgt in lang_map:
                return lang_map[norm_tgt]
            base = norm_tgt.split("_")[0]
            if base in lang_map:
                return lang_map[base]
    return None


class Translator:
    """
    Deep translation module that encapsulates:
    - Academic text sanitization and exact copyright preservation
    - Sentence-aligned chunking for inputs exceeding chunk word thresholds
    - Dynamic token budgeting per chunk
    - 4-tier prompt and temperature retry escalation
    - Target language verification and completeness validation
    - Multi-chunk token and latency aggregation
    """
    def __init__(self, adapter: InferenceAdapter):
        self.adapter = adapter

    @property
    def model_name(self) -> str:
        return getattr(self.adapter, "model_name", DEFAULT_MODEL_NAME)

    def _translate_chunk(
        self,
        chunk: str,
        target_lang_code: str,
        src_lang_code: Optional[str],
        chunk_index: int = 0
    ) -> Dict[str, Any]:
        max_tokens = compute_dynamic_max_tokens(chunk)
        attempts = [
            ("principal", build_salamandra_messages(chunk, target_lang_code, src_lang_code), SAMPLING_TEMPERATURE),
            ("fallback", build_fallback_messages(chunk, target_lang_code), SAMPLING_TEMPERATURE),
            ("principal-t0.3", build_salamandra_messages(chunk, target_lang_code, src_lang_code), 0.3),
            ("fallback-t0.3", build_fallback_messages(chunk, target_lang_code), 0.3),
        ]
        total_prompt = 0
        total_completion = 0
        total_latency = 0.0
        last_reason = "sin respuesta del servidor"
        simulated = False

        for label, messages, temp in attempts:
            res = self.adapter.generate(messages, temp, max_tokens, STOP_TOKENS)
            if res is None:
                time.sleep(1.5)
                continue

            total_prompt += res.prompt_tokens
            total_completion += res.completion_tokens
            total_latency += res.latency_ms
            simulated = res.simulated

            content = clean_llm_translation(res.content)
            if not content and len(chunk.strip()) >= 15:
                last_reason = "respuesta vacía"
            elif not is_valid_translation_language(content, target_lang_code):
                last_reason = f"idioma incorrecto (detectado={detect_language(content)})"
            else:
                ok, reason = check_translation_completeness(chunk, content, res.finish_reason)
                if ok:
                    return {
                        "translation": content,
                        "prompt_tokens": total_prompt,
                        "completion_tokens": total_completion,
                        "latency_ms": total_latency,
                        "simulated": simulated
                    }
                last_reason = reason
            print(f"[!] Aviso: intento '{label}' rechazado para {target_lang_code}: {last_reason}. Inicio: {repr(content[:80])}")

        raise TranslationError(target_lang=target_lang_code, reason=last_reason, chunk_index=chunk_index)

    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        # Fast path: deterministic translation for fixed editorial placeholders
        deterministic = get_deterministic_translation(text, target_lang_code)
        if deterministic is not None:
            return {
                "translation": deterministic,
                "prompt_tokens": len(text.split()),
                "completion_tokens": len(deterministic.split()),
                "latency_ms": 1.0,
                "simulated": False
            }

        original_text = text
        sanitized_text = sanitize_academic_abstract(text)

        chunks = split_for_translation(sanitized_text, max_chunk_words=TRANSLATION_CHUNK_WORDS)

        translated_parts = []
        total_prompt_tokens = 0
        total_completion_tokens = 0
        total_latency_ms = 0.0
        simulated = False

        for idx, chunk in enumerate(chunks):
            res = self._translate_chunk(chunk, target_lang_code, src_lang_code, chunk_index=idx)
            translated_parts.append(res["translation"])
            total_prompt_tokens += res["prompt_tokens"]
            total_completion_tokens += res["completion_tokens"]
            total_latency_ms += res["latency_ms"]
            simulated = res.get("simulated", False)

        combined = "\n\n".join(translated_parts)
        combined = ensure_exact_copyright_preserved(original_text, combined)
        return {
            "translation": combined,
            "prompt_tokens": total_prompt_tokens,
            "completion_tokens": total_completion_tokens,
            "latency_ms": round(total_latency_ms, 2),
            "model": self.model_name,
            "simulated": simulated
        }


# =====================================================================
# Factory Seam and Backward Compatibility Aliases
# =====================================================================

class TranslationEngine(ABC):
    """Abstract base class preserved for backward compatibility."""
    @abstractmethod
    def translate(self, text: str, target_lang_code: str, src_lang_code: Optional[str] = None) -> Dict[str, Any]:
        pass


class MockBenchmarkEngine(Translator):
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, simulated_speed_tok_s: float = 45.0, custom_responder: Optional[Any] = None):
        super().__init__(MockAdapter(model_name=model_name, simulated_speed_tok_s=simulated_speed_tok_s, custom_responder=custom_responder))


class LMStudioEngine(Translator):
    def __init__(self, server_url: str = LM_STUDIO_URL, model_name: Optional[str] = None, timeout: int = 180):
        super().__init__(LMStudioAdapter(server_url=server_url, model_name=model_name, timeout=timeout))


class VLLMServerEngine(Translator):
    def __init__(self, server_url: str = VLLM_SERVER_URL, model_name: str = DEFAULT_MODEL_NAME, timeout: int = 120):
        super().__init__(VLLMServerAdapter(server_url=server_url, model_name=model_name, timeout=timeout))


class LocalVLLMEngine(Translator):
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, tensor_parallel_size: int = 1):
        super().__init__(LocalVLLMAdapter(model_name=model_name, tensor_parallel_size=tensor_parallel_size))


class TransformersEngine(Translator):
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME, device: Optional[str] = None):
        super().__init__(TransformersAdapter(model_name=model_name, device=device))


def get_translator(
    engine_type: str,
    model_name: Optional[str] = None,
    vllm_url: str = VLLM_SERVER_URL,
    lm_studio_url: str = LM_STUDIO_URL
) -> Translator:
    """Central factory constructing a deep Translator backed by the requested InferenceAdapter."""
    model = model_name or DEFAULT_MODEL_NAME
    if engine_type == "mock":
        return Translator(MockAdapter(model_name=model))
    elif engine_type == "lm-studio":
        return Translator(LMStudioAdapter(server_url=lm_studio_url, model_name=model_name if model_name != DEFAULT_MODEL_NAME else None))
    elif engine_type == "vllm-server":
        return Translator(VLLMServerAdapter(server_url=vllm_url, model_name=model))
    elif engine_type == "vllm-local":
        return Translator(LocalVLLMAdapter(model_name=model))
    elif engine_type == "transformers":
        return Translator(TransformersAdapter(model_name=model))
    else:
        raise ValueError(f"Unknown engine type: {engine_type}")


get_engine = get_translator

