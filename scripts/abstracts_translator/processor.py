from datetime import datetime, timezone
from typing import Dict, Any, Optional, Tuple, List
from .config import LANG_EN, LANG_ES, LANG_CA
from .detector import detect_language, clean_text_for_analysis
from .engine import TranslationEngine

TARGET_LANGS = [LANG_EN, LANG_ES, LANG_CA]

def normalize_text_for_compare(text: Optional[str]) -> str:
    if not text:
        return ""
    cleaned = clean_text_for_analysis(text)
    return cleaned.lower()

def make_preview(text: Optional[str], max_len: int = 120) -> str:
    if not text:
        return ""
    cleaned = clean_text_for_analysis(text)
    if len(cleaned) <= max_len:
        return cleaned
    return cleaned[:max_len] + "..."

class AbstractProcessor:
    def __init__(self, engine: TranslationEngine):
        self.engine = engine

    def process_ro_abstracts(self, doc: Dict[str, Any]) -> Dict[str, Any]:
        """
        Processes a single Research Output document according to the user specification rules:
        - If no abstract: skip.
        - Detect true language for every text.
        - English text mistakenly located in Spanish (or Catalan) slot MUST be removed from Spanish
          and placed in English, generating a true Spanish translation.
        - Genuine English, Spanish, and Catalan texts are placed in their respective slots.
        - Missing slots are translated preferably from English.
        - Foreign languages reserved in Spanish slot only if no original Spanish exists.
        """
        raw_abstracts = doc.get("abstract")
        if not raw_abstracts or not isinstance(raw_abstracts, dict):
            return {
                "status": "SKIPPED_NO_ABSTRACT",
                "original_abstract": raw_abstracts,
                "updated_abstract": raw_abstracts,
                "ai_metadata": None,
                "translations_done": [],
                "decision_branch": "no_abstract"
            }

        # Filter out empty or whitespace-only entries
        valid_entries = {}
        for k, v in raw_abstracts.items():
            if v and isinstance(v, str) and len(clean_text_for_analysis(v)) >= 10:
                valid_entries[k] = v.strip()

        if not valid_entries:
            return {
                "status": "SKIPPED_NO_ABSTRACT",
                "original_abstract": raw_abstracts,
                "updated_abstract": raw_abstracts,
                "ai_metadata": None,
                "translations_done": [],
                "decision_branch": "empty_abstract_values"
            }

        # Deduplicate identical texts across slots
        unique_texts: List[Tuple[str, str, str]] = [] # (original_key, original_text, detected_lang)
        seen_normalized = {}

        for orig_key, orig_text in valid_entries.items():
            norm = normalize_text_for_compare(orig_text)
            if norm not in seen_normalized:
                det_lang = detect_language(orig_text)
                seen_normalized[norm] = len(unique_texts)
                unique_texts.append((orig_key, orig_text, det_lang))

        # Categorize by detected language
        en_candidates = [t for t in unique_texts if t[2] == LANG_EN]
        es_candidates = [t for t in unique_texts if t[2] == LANG_ES]
        ca_candidates = [t for t in unique_texts if t[2] == LANG_CA]
        foreign_candidates = [t for t in unique_texts if t[2] not in [LANG_EN, LANG_ES, LANG_CA]]

        new_abstracts: Dict[str, str] = {}
        ai_generated_langs: List[str] = []
        translations_done: List[Dict[str, Any]] = []
        source_lang_used: Optional[str] = None
        decision_branch = ""

        # Case 1: Genuine English exists
        if en_candidates:
            # Prefer the one originally in en_GB if multiple, else the first
            orig_en = next((t for t in en_candidates if t[0] == LANG_EN), en_candidates[0])
            new_abstracts[LANG_EN] = orig_en[1]
            source_lang_used = LANG_EN

            # Handle Spanish slot
            if es_candidates:
                orig_es = next((t for t in es_candidates if t[0] == LANG_ES), es_candidates[0])
                new_abstracts[LANG_ES] = orig_es[1]
            elif foreign_candidates:
                # Rule: "Si hay algún idioma que no sea (en, es, ca) ponerlo en la casilla del castellano excepto si existe una versión en original en castellano."
                new_abstracts[LANG_ES] = foreign_candidates[0][1]
            else:
                # If Spanish slot had English or was missing, eliminate it and translate from English
                trans_es = self._translate_and_record(orig_en[1], LANG_ES, "en_GB", translations_done)
                new_abstracts[LANG_ES] = trans_es
                ai_generated_langs.append(LANG_ES)

            # Handle Catalan slot
            if ca_candidates:
                orig_ca = next((t for t in ca_candidates if t[0] == LANG_CA), ca_candidates[0])
                new_abstracts[LANG_CA] = orig_ca[1]
            else:
                # If Catalan slot had English or was missing, eliminate it and translate from English
                trans_ca = self._translate_and_record(orig_en[1], LANG_CA, "en_GB", translations_done)
                new_abstracts[LANG_CA] = trans_ca
                ai_generated_langs.append(LANG_CA)

            decision_branch = f"en_source (es_present={bool(es_candidates)}, ca_present={bool(ca_candidates)})"

        # Case 2: No English, but Genuine Spanish exists
        elif es_candidates:
            orig_es = next((t for t in es_candidates if t[0] == LANG_ES), es_candidates[0])
            new_abstracts[LANG_ES] = orig_es[1]
            source_lang_used = LANG_ES

            # Translate to English
            trans_en = self._translate_and_record(orig_es[1], LANG_EN, "es_ES", translations_done)
            new_abstracts[LANG_EN] = trans_en
            ai_generated_langs.append(LANG_EN)

            # Handle Catalan slot
            if ca_candidates:
                orig_ca = next((t for t in ca_candidates if t[0] == LANG_CA), ca_candidates[0])
                new_abstracts[LANG_CA] = orig_ca[1]
            else:
                trans_ca = self._translate_and_record(orig_es[1], LANG_CA, "es_ES", translations_done)
                new_abstracts[LANG_CA] = trans_ca
                ai_generated_langs.append(LANG_CA)

            decision_branch = f"es_source (ca_present={bool(ca_candidates)})"

        # Case 3: No English, No Spanish, but Genuine Catalan exists
        elif ca_candidates:
            orig_ca = next((t for t in ca_candidates if t[0] == LANG_CA), ca_candidates[0])
            new_abstracts[LANG_CA] = orig_ca[1]
            source_lang_used = LANG_CA

            # Translate to English and Spanish from Catalan
            trans_en = self._translate_and_record(orig_ca[1], LANG_EN, "ca_ES", translations_done)
            trans_es = self._translate_and_record(orig_ca[1], LANG_ES, "ca_ES", translations_done)
            new_abstracts[LANG_EN] = trans_en
            new_abstracts[LANG_ES] = trans_es
            ai_generated_langs.extend([LANG_EN, LANG_ES])

            decision_branch = "ca_source"

        # Case 4: Only Foreign Language exists (e.g. French, German, Italian)
        elif foreign_candidates:
            orig_foreign = foreign_candidates[0]
            foreign_text = orig_foreign[1]
            foreign_lang = orig_foreign[2]

            # Rule: "Si el único idioma disponible es de otra lengua se realiza la traducción
            # desde esa lengua al inglés y al catalán y se reserva el original a la casilla del castellano."
            new_abstracts[LANG_ES] = foreign_text
            trans_en = self._translate_and_record(foreign_text, LANG_EN, foreign_lang, translations_done)
            trans_ca = self._translate_and_record(foreign_text, LANG_CA, foreign_lang, translations_done)
            new_abstracts[LANG_EN] = trans_en
            new_abstracts[LANG_CA] = trans_ca
            ai_generated_langs.extend([LANG_EN, LANG_CA])
            source_lang_used = f"foreign_{foreign_lang}"
            decision_branch = f"foreign_source ({foreign_lang})"

        # Build AI metadata if any translation was performed or reorganized
        total_latency_ms = sum(t["latency_ms"] for t in translations_done)
        ai_metadata = None
        if ai_generated_langs:
            ai_metadata = {
                "generated_languages": ai_generated_langs,
                "source_language": source_lang_used,
                "model": self.engine.model_name if hasattr(self.engine, "model_name") else "Salamandra",
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "total_latency_ms": round(total_latency_ms, 2),
                "translations_detail": translations_done
            }
            status = "TRANSLATED"
        elif new_abstracts != raw_abstracts:
            status = "REORGANIZED"
        else:
            status = "ALREADY_COMPLETE"

        return {
            "status": status,
            "original_abstract": raw_abstracts,
            "updated_abstract": new_abstracts,
            "ai_metadata": ai_metadata,
            "translations_done": translations_done,
            "decision_branch": decision_branch
        }

    def _translate_and_record(self, text: str, target_lang: str, src_lang: str, records: List[Dict[str, Any]]) -> str:
        res = self.engine.translate(text, target_lang, src_lang)
        translation_text = res.get("translation", "")
        records.append({
            "from_lang": src_lang,
            "to_lang": target_lang,
            "latency_ms": res.get("latency_ms", 0.0),
            "prompt_tokens": res.get("prompt_tokens", 0),
            "completion_tokens": res.get("completion_tokens", 0),
            "source_preview": make_preview(text, 140),
            "translation_preview": make_preview(translation_text, 140),
            "simulated": res.get("simulated", False)
        })
        return translation_text
