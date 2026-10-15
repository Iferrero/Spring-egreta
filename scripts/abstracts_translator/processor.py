from datetime import datetime, timezone
from typing import Dict, Any, Optional, Tuple, List
from .config import LANG_EN, LANG_ES, LANG_CA
from .detector import (
    AbstractCluster,
    AbstractSlot,
    detect_language,
    clean_text_for_analysis,
    analyze_discrepancies
)

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

def normalize_lang_code(lang: Optional[str]) -> str:
    if not lang:
        return ""
    l = lang.strip().lower()
    if l in ["en", "en_gb", "en-gb", "english", "ingles", "inglés"]:
        return LANG_EN
    if l in ["es", "es_es", "es-es", "spanish", "castellano", "español", "espanol"]:
        return LANG_ES
    if l in ["ca", "ca_es", "ca-es", "catalan", "català", "catala"]:
        return LANG_CA
    base = l.split("_")[0].split("-")[0]
    return base

def parse_target_langs(target_arg: Optional[str]) -> List[str]:
    if not target_arg:
        return TARGET_LANGS
    results = []
    parts = target_arg.split(",")
    for p in parts:
        norm = normalize_lang_code(p.strip())
        if norm and norm in TARGET_LANGS and norm not in results:
            results.append(norm)
    return results if results else TARGET_LANGS

class AbstractProcessor:
    def __init__(self, engine: TranslationEngine):
        self.engine = engine

    def process_ro_abstracts(
        self,
        doc: Dict[str, Any],
        forced_source_lang: Optional[str] = None,
        forced_target_lang: Optional[str] = None,
        force_retranslate: bool = False
    ) -> Dict[str, Any]:
        """
        Processes a single Research Output document according to the user specification rules:
        - If no abstract: skip.
        - Detect true language for every text.
        - Filter out placeholder texts (e.g. 'Sense resum en castellà.', 'Sin resumen.', 'No abstract available').
        - Filter out corrupted entries (containing Cyrillic artifacts, repetition loops, subtoken garbage, or <unk>).
        - English text mistakenly located in Spanish (or Catalan) slot MUST be removed from Spanish
          and placed in English, generating a true Spanish translation.
        - Genuine English, Spanish, and Catalan texts are placed in their respective slots.
        - Missing slots are translated preferably from English.
        - Foreign languages reserved in Spanish slot only if no original Spanish exists.
        - Supports forced_source_lang to specify the source language.
        - Supports forced_target_lang to restrict translation to specific target languages.
        """
        raw_abstracts = doc.get("abstract")
        cluster = AbstractCluster.from_raw(raw_abstracts)

        if cluster.is_empty:
            decision = "no_abstract" if not raw_abstracts or not isinstance(raw_abstracts, dict) else "empty_abstract_values"
            return {
                "status": "SKIPPED_NO_ABSTRACT",
                "original_abstract": raw_abstracts,
                "updated_abstract": raw_abstracts,
                "ai_metadata": None,
                "discrepancy_analysis": cluster.discrepancy_analysis,
                "translations_done": [],
                "decision_branch": decision
            }

        if cluster.has_cloned_or_untranslated:
            force_retranslate = True

        valid_entries = cluster.valid_entries
        unique_texts = cluster.unique_slots

        new_abstracts: Dict[str, str] = {}
        ai_generated_langs: List[str] = []
        translations_done: List[Dict[str, Any]] = []
        source_lang_used: Optional[str] = None
        decision_branch = ""
        active_target_langs = parse_target_langs(forced_target_lang)

        # --- OPTION: FORCED TARGET LANGUAGE RESTRICTION ---
        if forced_target_lang:
            new_abstracts = dict(raw_abstracts)
            source_text = None
            source_lang_used = None

            if forced_source_lang:
                norm_forced = normalize_lang_code(forced_source_lang)
                if norm_forced in valid_entries:
                    source_text = valid_entries[norm_forced]
                    source_lang_used = norm_forced
                elif forced_source_lang in valid_entries:
                    source_text = valid_entries[forced_source_lang]
                    source_lang_used = forced_source_lang
                else:
                    matched = next((s for s in unique_texts if s.detected_lang == norm_forced or s.slot_key == norm_forced or s.detected_lang.split('_')[0] == norm_forced.split('_')[0]), None)
                    if matched:
                        source_text = matched.text
                        source_lang_used = matched.detected_lang

            if not source_text:
                en_candidates = cluster.english_candidates
                es_candidates = cluster.spanish_candidates
                ca_candidates = cluster.catalan_candidates
                foreign_candidates = cluster.foreign_candidates

                if en_candidates:
                    source_text = en_candidates[0].text
                    source_lang_used = LANG_EN
                elif es_candidates:
                    source_text = es_candidates[0].text
                    source_lang_used = LANG_ES
                elif ca_candidates:
                    source_text = ca_candidates[0].text
                    source_lang_used = LANG_CA
                elif foreign_candidates:
                    source_text = foreign_candidates[0].text
                    source_lang_used = foreign_candidates[0].detected_lang
                elif valid_entries:
                    k0 = list(valid_entries.keys())[0]
                    source_text = valid_entries[k0]
                    source_lang_used = k0

            if source_text:
                for target_lang in active_target_langs:
                    existing_t = valid_entries.get(target_lang)
                    needs_trans = force_retranslate or not existing_t or detect_language(existing_t) != target_lang
                    if target_lang == source_lang_used and existing_t and not force_retranslate:
                        needs_trans = False

                    if needs_trans:
                        trans_text = self._translate_and_record(source_text, target_lang, source_lang_used, translations_done)
                        new_abstracts[target_lang] = trans_text
                        ai_generated_langs.append(target_lang)

            decision_branch = f"target_restricted ({forced_target_lang})"

        else:
            # --- STANDARD MULTI-LANGUAGE TRANSLATION FLOW ---
            src_entry = None
            if forced_source_lang:
                norm_forced = normalize_lang_code(forced_source_lang)
                if norm_forced in valid_entries:
                    src_entry = (norm_forced, valid_entries[norm_forced], norm_forced)
                elif forced_source_lang in valid_entries:
                    src_entry = (forced_source_lang, valid_entries[forced_source_lang], forced_source_lang)
                else:
                    matched = next((s for s in unique_texts if s.detected_lang == norm_forced or s.slot_key == norm_forced), None)
                    if matched:
                        src_entry = (matched.slot_key, matched.text, matched.detected_lang)
                    elif norm_forced in TARGET_LANGS:
                        raw_t = raw_abstracts.get(norm_forced) or raw_abstracts.get(forced_source_lang)
                        if raw_t and isinstance(raw_t, str) and len(clean_text_for_analysis(raw_t)) >= 10:
                            src_entry = (norm_forced, raw_t.strip(), norm_forced)

            if src_entry:
                source_text = src_entry[1]
                source_lang_used = src_entry[2]
                norm_forced = normalize_lang_code(forced_source_lang)
                if norm_forced in TARGET_LANGS:
                    source_slot = norm_forced
                elif src_entry[0] in TARGET_LANGS:
                    source_slot = src_entry[0]
                else:
                    source_slot = LANG_ES

                new_abstracts[source_slot] = source_text

                for k, v in valid_entries.items():
                    if k in TARGET_LANGS and k != source_slot and k not in active_target_langs:
                        new_abstracts[k] = v

                for target_lang in active_target_langs:
                    if target_lang != source_slot:
                        trans_text = self._translate_and_record(source_text, target_lang, source_lang_used, translations_done)
                        new_abstracts[target_lang] = trans_text
                        ai_generated_langs.append(target_lang)

                decision_branch = f"forced_source ({forced_source_lang})"

            else:
                if forced_source_lang:
                    print(f"\n[!] Aviso: Idioma de origen especificado '{forced_source_lang}' no encontrado en RO {doc.get('uuid')}. Usando deteccion automatica.")

                # Categorize by detected language
                en_candidates = cluster.english_candidates
                es_candidates = cluster.spanish_candidates
                ca_candidates = cluster.catalan_candidates
                foreign_candidates = cluster.foreign_candidates

                # Case 1: Genuine English exists
                if en_candidates:
                    orig_en = next((s for s in en_candidates if s.slot_key == LANG_EN), en_candidates[0])
                    new_abstracts[LANG_EN] = orig_en.text
                    source_lang_used = LANG_EN

                    # Handle Spanish slot
                    if LANG_ES in active_target_langs:
                        if es_candidates and not force_retranslate:
                            orig_es = next((s for s in es_candidates if s.slot_key == LANG_ES), es_candidates[0])
                            new_abstracts[LANG_ES] = orig_es.text
                        elif foreign_candidates and not force_retranslate:
                            new_abstracts[LANG_ES] = foreign_candidates[0].text
                        else:
                            trans_es = self._translate_and_record(orig_en.text, LANG_ES, "en_GB", translations_done)
                            new_abstracts[LANG_ES] = trans_es
                            ai_generated_langs.append(LANG_ES)
                    elif es_candidates:
                        orig_es = next((s for s in es_candidates if s.slot_key == LANG_ES), es_candidates[0])
                        new_abstracts[LANG_ES] = orig_es.text

                    # Handle Catalan slot
                    if LANG_CA in active_target_langs:
                        if ca_candidates and not force_retranslate:
                            orig_ca = next((s for s in ca_candidates if s.slot_key == LANG_CA), ca_candidates[0])
                            new_abstracts[LANG_CA] = orig_ca.text
                        else:
                            trans_ca = self._translate_and_record(orig_en.text, LANG_CA, "en_GB", translations_done)
                            new_abstracts[LANG_CA] = trans_ca
                            ai_generated_langs.append(LANG_CA)
                    elif ca_candidates:
                        orig_ca = next((s for s in ca_candidates if s.slot_key == LANG_CA), ca_candidates[0])
                        new_abstracts[LANG_CA] = orig_ca.text

                    decision_branch = f"en_source (es_present={bool(es_candidates)}, ca_present={bool(ca_candidates)})"

                # Case 2: No English, but Foreign Language exists (e.g. Slovenian, French, German, Russian)
                elif foreign_candidates:
                    orig_foreign = foreign_candidates[0]
                    foreign_text = orig_foreign.text
                    foreign_lang = orig_foreign.detected_lang

                    # Rule: Reserve original foreign text in Spanish slot and translate to English and Catalan
                    new_abstracts[LANG_ES] = foreign_text

                    if LANG_EN in active_target_langs:
                        trans_en = self._translate_and_record(foreign_text, LANG_EN, foreign_lang, translations_done)
                        new_abstracts[LANG_EN] = trans_en
                        ai_generated_langs.append(LANG_EN)

                    if LANG_CA in active_target_langs:
                        trans_ca = self._translate_and_record(foreign_text, LANG_CA, foreign_lang, translations_done)
                        new_abstracts[LANG_CA] = trans_ca
                        ai_generated_langs.append(LANG_CA)

                    source_lang_used = f"foreign_{foreign_lang}"
                    decision_branch = f"foreign_source ({foreign_lang})"

                # Case 3: No English, No Foreign, but Genuine Spanish exists
                elif es_candidates:
                    orig_es = next((s for s in es_candidates if s.slot_key == LANG_ES), es_candidates[0])
                    new_abstracts[LANG_ES] = orig_es.text
                    source_lang_used = LANG_ES

                    if LANG_EN in active_target_langs:
                        trans_en = self._translate_and_record(orig_es.text, LANG_EN, "es_ES", translations_done)
                        new_abstracts[LANG_EN] = trans_en
                        ai_generated_langs.append(LANG_EN)

                    if LANG_CA in active_target_langs:
                        if ca_candidates and not force_retranslate:
                            orig_ca = next((s for s in ca_candidates if s.slot_key == LANG_CA), ca_candidates[0])
                            new_abstracts[LANG_CA] = orig_ca.text
                        else:
                            trans_ca = self._translate_and_record(orig_es.text, LANG_CA, "es_ES", translations_done)
                            new_abstracts[LANG_CA] = trans_ca
                            ai_generated_langs.append(LANG_CA)
                    elif ca_candidates:
                        orig_ca = next((s for s in ca_candidates if s.slot_key == LANG_CA), ca_candidates[0])
                        new_abstracts[LANG_CA] = orig_ca.text

                    decision_branch = f"es_source (ca_present={bool(ca_candidates)})"

                # Case 4: No English, No Foreign, No Spanish, but Genuine Catalan exists
                elif ca_candidates:
                    orig_ca = next((s for s in ca_candidates if s.slot_key == LANG_CA), ca_candidates[0])
                    new_abstracts[LANG_CA] = orig_ca.text
                    source_lang_used = LANG_CA

                    if LANG_EN in active_target_langs:
                        trans_en = self._translate_and_record(orig_ca.text, LANG_EN, "ca_ES", translations_done)
                        new_abstracts[LANG_EN] = trans_en
                        ai_generated_langs.append(LANG_EN)

                    if LANG_ES in active_target_langs:
                        trans_es = self._translate_and_record(orig_ca.text, LANG_ES, "ca_ES", translations_done)
                        new_abstracts[LANG_ES] = trans_es
                        ai_generated_langs.append(LANG_ES)

                    decision_branch = f"ca_source"



        # Build AI metadata if any translation was performed or reorganized
        discrepancy_analysis = analyze_discrepancies(new_abstracts)
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
            "discrepancy_analysis": discrepancy_analysis,
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
