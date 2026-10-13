import unittest

try:
    from abstracts_translator.processor import AbstractProcessor
    from abstracts_translator.engine import (
        MockBenchmarkEngine,
        Translator,
        TranslationError,
        InferenceAdapter,
        MockAdapter,
        RawGeneration,
        get_translator,
        build_salamandra_prompt,
        build_salamandra_messages,
        clean_llm_translation,
        split_large_text
    )
    from abstracts_translator.detector import (
        detect_language,
        is_placeholder_abstract,
        is_corrupted_abstract,
        analyze_discrepancies,
        AbstractCluster,
        AbstractSlot
    )
    from abstracts_translator.egreta_sync import (
        escape_xml_abstract,
        EgretaSyncAdapter,
        HttpEgretaAdapter,
        FakeEgretaAdapter,
        SyncResult,
        sync_abstract_to_egreta
    )
    from abstracts_translator.intake import IntakeQuery, IntakeOptions, FakeROStore, ROIntakePipeline

except ImportError:
    from .processor import AbstractProcessor
    from .engine import (
        MockBenchmarkEngine,
        Translator,
        TranslationError,
        InferenceAdapter,
        MockAdapter,
        RawGeneration,
        get_translator,
        build_salamandra_prompt,
        build_salamandra_messages,
        clean_llm_translation,
        split_large_text
    )
    from .detector import (
        detect_language,
        is_placeholder_abstract,
        is_corrupted_abstract,
        analyze_discrepancies,
        AbstractCluster,
        AbstractSlot
    )
    from .egreta_sync import (
        escape_xml_abstract,
        EgretaSyncAdapter,
        HttpEgretaAdapter,
        FakeEgretaAdapter,
        SyncResult,
        sync_abstract_to_egreta
    )
    from .intake import IntakeQuery, IntakeOptions, FakeROStore, ROIntakePipeline




class TestAbstractTranslator(unittest.TestCase):
    def setUp(self):
        self.engine = MockBenchmarkEngine()
        self.processor = AbstractProcessor(self.engine)

    def test_language_detection(self):
        en_text = "This paper presents a novel approach for deep learning in biomedical natural language processing with high accuracy."
        es_text = "Este estudio analiza el impacto de las energías renovables en el desarrollo sostenible y la economía circular en España."
        ca_text = "Aquest treball investiga l'evolució de la població a Catalunya durant el segle vint amb noves dades estadístiques."
        fr_text = "Cette recherche examine les conséquences de la mondialisation sur les marchés financiers en France."

        self.assertEqual(detect_language(en_text), "en_GB")
        self.assertEqual(detect_language(es_text), "es_ES")
        self.assertEqual(detect_language(ca_text), "ca_ES")
        self.assertEqual(detect_language(fr_text), "fr")

    def test_case_no_abstract(self):
        doc1 = {"uuid": "1", "abstract": None}
        doc2 = {"uuid": "2", "abstract": {}}
        doc3 = {"uuid": "3", "abstract": {"en_GB": ""}}

        self.assertEqual(self.processor.process_ro_abstracts(doc1)["status"], "SKIPPED_NO_ABSTRACT")
        self.assertEqual(self.processor.process_ro_abstracts(doc2)["status"], "SKIPPED_NO_ABSTRACT")
        self.assertEqual(self.processor.process_ro_abstracts(doc3)["status"], "SKIPPED_NO_ABSTRACT")

    def test_case_1_english_abstract(self):
        doc = {
            "uuid": "test-1",
            "abstract": {
                "en_GB": "This paper presents a comprehensive study on quantum computing algorithms and their performance."
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertIn("en_GB", res["updated_abstract"])
        self.assertIn("es_ES", res["updated_abstract"])
        self.assertIn("ca_ES", res["updated_abstract"])
        self.assertEqual(len(res["translations_done"]), 2)
        self.assertEqual(res["ai_metadata"]["source_language"], "en_GB")
        self.assertCountEqual(res["ai_metadata"]["generated_languages"], ["es_ES", "ca_ES"])

    def test_case_english_in_spanish_slot(self):
        # English abstract mistakenly placed in es_ES slot
        en_text = "This paper presents a comprehensive study on quantum computing algorithms and their performance."
        doc = {
            "uuid": "test-en-in-es",
            "abstract": {
                "es_ES": en_text
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        # The English text must be placed in en_GB
        self.assertEqual(res["updated_abstract"]["en_GB"], en_text)
        # The es_ES slot must NOT contain the English text, but the translation
        self.assertNotEqual(res["updated_abstract"]["es_ES"], en_text)
        self.assertIn("es_ES", res["ai_metadata"]["generated_languages"])
        self.assertIn("ca_ES", res["ai_metadata"]["generated_languages"])

    def test_case_1_spanish_in_wrong_slot(self):
        # Spanish text mistakenly stored under ca_ES
        es_text = "Este trabajo presenta un análisis exhaustivo del impacto económico de la inteligencia artificial."
        doc = {
            "uuid": "test-2",
            "abstract": {
                "ca_ES": es_text
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(res["updated_abstract"]["es_ES"], es_text)
        self.assertIn("en_GB", res["updated_abstract"])
        self.assertIn("ca_ES", res["updated_abstract"])
        self.assertCountEqual(res["ai_metadata"]["generated_languages"], ["en_GB", "ca_ES"])

    def test_case_2_identical_abstracts(self):
        # English abstract cloned into en_GB and es_ES
        doc = {
            "uuid": "test-3",
            "abstract": {
                "en_GB": "Optimal elective surgery for splenic flexure cancer is unclear. Three types of surgical treatment are used.",
                "es_ES": "Optimal elective surgery for splenic flexure cancer is unclear. Three types of surgical treatment are used."
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(len(res["translations_done"]), 2) # Translated to es_ES and ca_ES
        self.assertCountEqual(res["ai_metadata"]["generated_languages"], ["es_ES", "ca_ES"])

    def test_case_2_distinct_abstracts(self):
        # EN and ES provided, CA missing
        doc = {
            "uuid": "test-4",
            "abstract": {
                "en_GB": "This paper presents a new optimization algorithm.",
                "es_ES": "Este artículo presenta un nuevo algoritmo de optimización."
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(len(res["translations_done"]), 1) # Only 1 translation needed (ca_ES)
        self.assertCountEqual(res["ai_metadata"]["generated_languages"], ["ca_ES"])

    def test_case_3_identical_abstracts(self):
        # English cloned in all 3
        text = "This paper explores machine learning in climate change modeling with high accuracy."
        doc = {
            "uuid": "test-5",
            "abstract": {
                "en_GB": text,
                "es_ES": text,
                "ca_ES": text
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(len(res["translations_done"]), 2)
        self.assertCountEqual(res["ai_metadata"]["generated_languages"], ["es_ES", "ca_ES"])

    def test_case_3_distinct_abstracts(self):
        # Genuine 3 languages already present
        doc = {
            "uuid": "test-6",
            "abstract": {
                "en_GB": "This paper presents a new optimization algorithm for neural networks.",
                "es_ES": "Este artículo presenta un nuevo algoritmo de optimización para redes neuronales.",
                "ca_ES": "Aquest article presenta un nou algoritme d'optimització per a xarxes neuronals."
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertIn(res["status"], ["ALREADY_COMPLETE", "REORGANIZED"])
        self.assertEqual(len(res["translations_done"]), 0)
        self.assertIsNone(res["ai_metadata"])

    def test_case_foreign_language(self):
        # French abstract only
        doc = {
            "uuid": "test-7",
            "abstract": {
                "en_GB": "Cette recherche examine les conséquences de la mondialisation sur les marchés financiers en France."
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(res["updated_abstract"]["es_ES"], doc["abstract"]["en_GB"])
        self.assertIn("en_GB", res["updated_abstract"])
        self.assertIn("ca_ES", res["updated_abstract"])
    def test_case_complex_biomedical_abstract(self):
        # Abstract with copyright prefix, unicode minus, section headers, chemical formulas
        biomed_text = (
            "© 2019 The British Pharmacological Society  Background and Purpose: ACh exerts its actions via nicotinic (nAChR) "
            "and muscarinic receptors. In the peripheral nervous system, ionotropic nAChR mediate responses in excitable cells. "
            "However, recent studies demonstrate the expression of nAChR in the colonic epithelium, which are coupled to an induction "
            "of Cl− secretion via activation of the Na+ -K+-pump. Experimental Approach: In order to find out whether these epithelial "
            "nAChR function as ionotropic receptors, intracellular microelectrode and imaging experiments were performed in isolated "
            "crypts from rat colon. Key Results: Imaging experiments with the Na+ -sensitive dye SBFI revealed that nicotine induced a "
            "decrease in the cytosolic Na+ concentration. Conclusions and Implications: These results suggest that epithelial nAChR "
            "activate the Na+ -K+-pump via a PKC dependent on a sufficient cytosolic Ca2+ concentration."
        )
        doc = {
            "uuid": "test-biomed",
            "abstract": {
                "en_GB": biomed_text
            }
        }
        res = self.processor.process_ro_abstracts(doc)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(res["updated_abstract"]["en_GB"], biomed_text)
        self.assertIn("es_ES", res["updated_abstract"])
        self.assertIn("ca_ES", res["updated_abstract"])
        self.assertEqual(len(res["translations_done"]), 2)

    def test_clean_conversational_prefixes(self):
        sample1 = "Absolutely! Translation in catalan: Antecedents i objectiu: L'ACh exerceix..."
        sample2 = "Certainly! Here is the translation into Catalan:\n\nAntecedents: Aquest estudi..."
        sample3 = "Sure! Translation in spanish: Antecedentes: Este estudio..."
        sample4 = "Traducció al català:\nAntecedents:..."
        
        self.assertTrue(clean_llm_translation(sample1).startswith("Antecedents"))
        self.assertTrue(clean_llm_translation(sample2).startswith("Antecedents"))
        self.assertTrue(clean_llm_translation(sample3).startswith("Antecedentes"))
        self.assertTrue(clean_llm_translation(sample4).startswith("Antecedents"))

    def test_salamandra_prompt_building(self):
        sample_text = "Background: Quantum computing offers exponential speedup. Methods: We developed a 53-qubit system. Results: 99.9% fidelity achieved."
        
        # Test English -> Catalan prompt construction
        prompt_ca = build_salamandra_prompt(sample_text, "ca_ES", "en_GB")
        self.assertIn("de l'anglès a català", prompt_ca)
        self.assertIn("Antecedents", prompt_ca)
        self.assertIn("Mètodes", prompt_ca)
        
        # Test English -> Catalan messages construction
        messages_ca = build_salamandra_messages(sample_text, "ca_ES", "en_GB")
        self.assertEqual(len(messages_ca), 2)
        self.assertEqual(messages_ca[0]["role"], "system")
        self.assertEqual(messages_ca[1]["role"], "user")
        self.assertIn("anglès", messages_ca[0]["content"])
        self.assertIn("anglès", messages_ca[1]["content"])


        # Test Spanish -> Catalan prompt construction
        prompt_es_ca = build_salamandra_prompt(sample_text, "ca_ES", "es_ES")
        self.assertIn("de l'espanyol a català", prompt_es_ca)

    def test_copyright_preservation_variations(self):
        # Test leading copyright
        orig1 = "© 2019 Elsevier B.V. Background: Study on quantum physics."
        trans1 = "Antecedents: Estudi sobre física quàntica. © 2019 Elsevier B.V. Tots els drets reservats."
        cleaned1 = clean_llm_translation(trans1, original_text=orig1)
        self.assertTrue(cleaned1.endswith("© 2019 Elsevier B.V"))

        # Test trailing copyright
        orig2 = "Background: Study on climate change. © 2020 American Mathematical Society"
        trans2 = "Antecedentes: Estudio sobre cambio climático."
        cleaned2 = clean_llm_translation(trans2, original_text=orig2)
        self.assertTrue(cleaned2.endswith("© 2020 American Mathematical Society"))

        # Test Copyright 2021 without symbol
        orig3 = "Copyright 2021 John Wiley & Sons Ltd. Objective: Cancer therapy."
        trans3 = "Objetivo: Terapia del cáncer."
        cleaned3 = clean_llm_translation(trans3, original_text=orig3)
        self.assertTrue(cleaned3.endswith("Copyright 2021 John Wiley & Sons Ltd"))

    def test_xhtml_abstract_escaping_and_repair(self):
        # 1. Test missing opening <p> tag (like UUID 4ce2ba0e-b3fe-4cc5-8ecc-abb45660448e)
        bad_p_end = "Estudiamos la tolerancia clínica y complicaciones.</p>"
        repaired1 = escape_xml_abstract(bad_p_end)
        self.assertTrue(repaired1.startswith("<p>"))
        self.assertTrue(repaired1.endswith("</p>"))

        # 2. Test missing closing </p> tag
        bad_p_start = "<p>Estudiamos la tolerancia clínica y complicaciones."
        repaired2 = escape_xml_abstract(bad_p_start)
        self.assertTrue(repaired2.startswith("<p>"))
        self.assertTrue(repaired2.endswith("</p>"))

        # 3. Test unclosed void tag (<br> -> <br/>)
        bad_br = "<p>Línea 1<br>Línea 2</p>"
        repaired3 = escape_xml_abstract(bad_br)
        self.assertIn("<br/>", repaired3)

        # 4. Test lone ampersand (& -> &amp;)
        bad_amp = "<p>Resultados en AT&T y Johnson & Johnson</p>"
        repaired4 = escape_xml_abstract(bad_amp)
        self.assertIn("AT&amp;T", repaired4)
        self.assertIn("Johnson &amp; Johnson", repaired4)

    def test_placeholder_abstract_detection(self):
        # 1. Test placeholder phrases
        self.assertTrue(is_placeholder_abstract("Sense resum en castellà."))
        self.assertTrue(is_placeholder_abstract("Sense resum en català."))
        self.assertTrue(is_placeholder_abstract("Sense resum en anglès"))
        self.assertTrue(is_placeholder_abstract("Sin resumen en castellano."))
        self.assertTrue(is_placeholder_abstract("Sin resumen."))
        self.assertTrue(is_placeholder_abstract("No abstract available."))
        self.assertTrue(is_placeholder_abstract("Resum no disponible."))

        # 2. Test genuine text is not marked as placeholder
        genuine_text = "This paper presents a novel approach for biomedical NLP using deep learning models."
        self.assertFalse(is_placeholder_abstract(genuine_text))

        # 3. Test document processing replaces placeholders with AI translations
        doc_with_placeholders = {
            "uuid": "test-placeholder-1",
            "abstract": {
                "en_GB": genuine_text,
                "es_ES": "Sense resum en castellà.",
                "ca_ES": "Sense resum en català."
            }
        }
        res = self.processor.process_ro_abstracts(doc_with_placeholders)
        self.assertEqual(res["status"], "TRANSLATED")
        self.assertEqual(res["updated_abstract"]["en_GB"], genuine_text)
        self.assertNotEqual(res["updated_abstract"]["es_ES"], "Sense resum en castellà.")
        self.assertNotEqual(res["updated_abstract"]["ca_ES"], "Sense resum en català.")
        self.assertCountEqual(res["ai_metadata"]["generated_languages"], ["es_ES", "ca_ES"])

    def test_corrupted_abstract_detection(self):
        # 1. Test Cyrillic artifacts in non-Russian abstract (like µg -> льg)
        self.assertTrue(is_corrupted_abstract("durante los últimos 28льaños"))
        self.assertTrue(is_corrupted_abstract("concentración de 40льg/l"))

        # 2. Test repetition loops and subtoken garbage
        self.assertTrue(is_corrupted_abstract("darrers 28льeB-cB-oB-oB-oB-oB-oB-oB-oB-oB"))
        self.assertTrue(is_corrupted_abstract("antecedents antecedents antecedents antecedents antecedents antecedents"))

        # 3. Test LLM special tokens
        self.assertTrue(is_corrupted_abstract("Estudio del cáncer <unk> en pacientes"))
        self.assertTrue(is_corrupted_abstract("Resultados [UNK] preliminares"))

        # 4. Test document processing replaces corrupted entries with fresh AI translations
        doc_corrupted = {
            "uuid": "40f5e0d4-f863-4d94-8180-97322a4b8164",
            "abstract": {
                "en_GB": "<p>Sea surface temperatures (SST) and input of continental materials have been reconstructed from long-chain alkenones over the last 280 kyr.</p>",
                "es_ES": "<p>Las temperaturas de la superficie del mar... durante los últimos 28льaños...</p>",
                "ca_ES": "<p>Les temperatures de la superfície del mar... darrers 28льeB-cB-oB-oB-oB-oB-oB-oB-oB-oB-oB</p>"
            }
        }
        res = self.processor.process_ro_abstracts(doc_corrupted)
        self.assertEqual(res["status"], "TRANSLATED")
        # 5. Test genuine Russian text is NOT marked as corrupted and is preserved in Spanish slot
        ru_text = "Тема Covid‑19, актуальная для этого года, обретает новые грани как исследовательская проблема мирового масштаба в социокоммуникативном и лингвистическом аспектах."
        self.assertFalse(is_corrupted_abstract(ru_text))
        self.assertEqual(detect_language(ru_text), "ru")

        doc_russian = {
            "uuid": "0ed2a4a0-76a9-4249-b34d-f12d0dc6b4cc",
            "abstract": {
                "en_GB": "Covid-19 is highly relevant in 2020; among other things, it is attracting new global research.",
                "es_ES": ru_text
            }
        }
        res_ru = self.processor.process_ro_abstracts(doc_russian)
        self.assertEqual(res_ru["status"], "TRANSLATED")
        self.assertEqual(res_ru["updated_abstract"]["es_ES"], ru_text)
        # 6. Test forced source language parameter
        doc_multilang = {
            "uuid": "test-forced",
            "abstract": {
                "en_GB": "This paper presents a new neural network architecture.",
                "es_ES": "Este artículo presenta una nueva arquitectura de red neuronal.",
                "ca_ES": "Aquest article presenta una nova arquitectura de xarxa neuronal."
            }
        }
        res_forced_es = self.processor.process_ro_abstracts(doc_multilang, forced_source_lang="es_ES")
        # 7. Test forced target language parameter (only modifies specified target_lang)
        doc_partial = {
            "uuid": "test-target-only",
            "abstract": {
                "en_GB": "This paper presents a new neural network architecture.",
                "es_ES": "Este artículo presenta una nueva arquitectura de red neuronal."
            }
        }
        res_target_ca = self.processor.process_ro_abstracts(
            doc_partial,
            forced_target_lang="ca_ES"
        )
        self.assertEqual(res_target_ca["status"], "TRANSLATED")
        self.assertEqual(len(res_target_ca["translations_done"]), 1)
        self.assertEqual(res_target_ca["translations_done"][0]["to_lang"], "ca_ES")
        self.assertEqual(res_target_ca["updated_abstract"]["en_GB"], doc_partial["abstract"]["en_GB"])
        self.assertEqual(res_target_ca["updated_abstract"]["es_ES"], doc_partial["abstract"]["es_ES"])
        self.assertIn("ca_ES", res_target_ca["updated_abstract"])

    def test_split_large_text(self):
        long_paragraph_1 = " ".join(["Sentence one."] * 250) # 500 words
        long_paragraph_2 = " ".join(["Sentence two."] * 250) # 500 words
        text = f"{long_paragraph_1}\n\n{long_paragraph_2}"
        chunks = split_large_text(text, max_chunk_words=350)
        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertLessEqual(len(chunk.split()), 360)

    def test_discrepancy_analysis(self):
        # 1. User example verification
        doc_user_example = {
            "en_GB": "Reviewed work: Joan BROSSA, The Beard of the Crab. Barcelona : Edicions 62, 1974",
            "es_ES": "Obra revisada: Joan BROSSA, La barba del cangrejo. Barcelona : Edicions 62, 1974",
            "ca_ES": "Obra revisada: Joan BROSSA, La barba del cranc. Barcelona : Edicions 62, 1974"
        }
        analysis = analyze_discrepancies(doc_user_example)
        self.assertTrue(analysis["has_ca"])
        self.assertTrue(analysis["has_es"])
        self.assertTrue(analysis["has_en"])
        self.assertEqual(analysis["words_ca"], 13)
        self.assertEqual(analysis["words_es"], 13)
        self.assertEqual(analysis["words_en"], 14)
        self.assertEqual(analysis["chars_ca"], 77)
        self.assertEqual(analysis["chars_es"], 80)
        self.assertEqual(analysis["chars_en"], 80)
        self.assertEqual(analysis["diff_words"], 1)
        self.assertEqual(analysis["ratio_words"], 1.1)
        self.assertEqual(analysis["pct_discrepancy"], 7.1)
        self.assertEqual(analysis["shortest_lang"], "ca")
        self.assertEqual(analysis["longest_lang"], "en")
        self.assertEqual(analysis["severity"], "none")
        self.assertFalse(analysis["has_discrepancy"])
        self.assertEqual(analysis["detected_ca"], "none")
        self.assertEqual(analysis["detected_es"], "none")
        self.assertEqual(analysis["detected_en"], "none")
        self.assertIn("analyzed_at", analysis)

        # 2. Processor return payload test (translates from en_GB to es_ES and ca_ES)
        doc_ro = {
            "uuid": "test-discrepancy-ro",
            "abstract": {
                "en_GB": "Reviewed work: Joan BROSSA, The Beard of the Crab. Barcelona : Edicions 62, 1974"
            }
        }
        res = self.processor.process_ro_abstracts(doc_ro)
        self.assertIn("discrepancy_analysis", res)
        self.assertNotIn("discrepancy_analysis", res["ai_metadata"])
        res_discrepancy = res["discrepancy_analysis"]
        self.assertTrue(res_discrepancy["has_en"])
        self.assertTrue(res_discrepancy["has_es"])
        self.assertTrue(res_discrepancy["has_ca"])


class TestDeepTranslator(unittest.TestCase):
    def setUp(self):
        self.adapter = MockAdapter()
        self.translator = Translator(self.adapter)

    def test_chunking_and_reassembly_on_long_abstract(self):
        # 300+ words to force split_for_translation into multiple chunks
        long_abstract = " ".join([
            "Quantum computing algorithms provide exponential speedup for integer factorization and discrete logarithms.",
            "Recent advancements in superconducting qubits demonstrate enhanced coherence times and lower gate error rates.",
            "This investigation examines the scalability of fault-tolerant surface codes in large-scale quantum processors.",
            "We analyze error threshold boundaries under diverse environmental decoherence noise models.",
            "Our simulation results establish that logical qubit fidelity scales favorably with code distance."
        ] * 6)
        self.assertGreater(len(long_abstract.split()), 250)

        res = self.translator.translate(long_abstract, "ca_ES", "en_GB")
        self.assertIn("translation", res)
        self.assertGreater(res["prompt_tokens"], 0)
        self.assertGreater(res["completion_tokens"], 0)
        self.assertGreater(res["latency_ms"], 0.0)
        # Translation must be valid Catalan
        self.assertEqual(detect_language(res["translation"]), "ca_ES")

    def test_exact_copyright_preservation(self):
        copyright_notice = "© 2024 Universitat Autònoma de Barcelona. All rights reserved"
        abstract_with_copyright = f"This paper examines advanced molecular biology techniques.\n\n{copyright_notice}."

        res = self.translator.translate(abstract_with_copyright, "es_ES", "en_GB")
        self.assertTrue(res["translation"].endswith(copyright_notice))

    def test_retry_escalation_triggers_fallback_and_recovers(self):
        call_count = 0

        def failing_first_responder(messages, temp, max_tokens, stop):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                # Primary prompt greedy returns empty/rejected string
                return RawGeneration(content="", prompt_tokens=50, completion_tokens=0, latency_ms=10.0)
            # Fallback prompt succeeds
            return RawGeneration(
                content="Este estudio analiza en detalle la evolución metodológica observada en la muestra seleccionada.",
                prompt_tokens=50,
                completion_tokens=20,
                latency_ms=15.0
            )

        failing_adapter = MockAdapter(custom_responder=failing_first_responder)
        translator = Translator(failing_adapter)
        res = translator.translate("Short input text for translation.", "es_ES", "en_GB")

        self.assertGreater(call_count, 1)
        self.assertIn("Este estudio analiza", res["translation"])
        self.assertEqual(res["prompt_tokens"], 100) # Accumulated tokens across both attempts

    def test_translation_error_raised_when_all_retries_fail(self):
        def always_failing_responder(messages, temp, max_tokens, stop):
            return RawGeneration(content="", prompt_tokens=20, completion_tokens=0, latency_ms=5.0)

        failing_adapter = MockAdapter(custom_responder=always_failing_responder)
        translator = Translator(failing_adapter)

        with self.assertRaises(TranslationError) as ctx:
            translator.translate("Valid text that cannot be translated.", "ca_ES", "en_GB")

        self.assertEqual(ctx.exception.target_lang, "ca_ES")
        self.assertEqual(ctx.exception.chunk_index, 0)

    def test_get_translator_factory(self):
        mock_t = get_translator("mock")
        self.assertIsInstance(mock_t, Translator)
        self.assertIsInstance(mock_t.adapter, MockAdapter)

        lm_t = get_translator("lm-studio", lm_studio_url="http://127.0.0.1:1234/v1")
        self.assertIsInstance(lm_t, Translator)
        self.assertEqual(lm_t.adapter.server_url, "http://127.0.0.1:1234/v1")


class TestROIntakePipeline(unittest.TestCase):
    def setUp(self):
        self.engine = MockBenchmarkEngine()
        self.processor = AbstractProcessor(self.engine)

    def test_pipeline_apply_translates_and_persists_in_store(self):
        initial_ro = {
            "uuid": "ro-101",
            "title": "Novel machine learning architectures in biology",
            "submissionYear": 2024,
            "abstract": {
                "en_GB": "This paper presents a novel approach for deep learning in biomedical natural language processing."
            }
        }
        store = FakeROStore(initial_ros=[initial_ro])
        pipeline = ROIntakePipeline(store=store, processor=self.processor)

        query = IntakeQuery(uuid="ro-101")
        options = IntakeOptions(mode="apply", show_progress=False)

        report = pipeline.run(query, options)
        self.assertEqual(report["sample_counts"]["total_ros_analyzed"], 1)
        self.assertEqual(report["sample_counts"]["translated_ros"], 1)

        # Verify Researchoutputs updated with 3 languages
        updated_ro = store.ros["ro-101"]
        self.assertIn("en_GB", updated_ro["abstract"])
        self.assertIn("es_ES", updated_ro["abstract"])
        self.assertIn("ca_ES", updated_ro["abstract"])

        # Verify AI metadata was saved into store
        saved_ai = store.ai_meta["ro-101"]
        self.assertEqual(saved_ai["uuid"], "ro-101")
        self.assertEqual(saved_ai["title"], initial_ro["title"])
        meta = saved_ai["abstract_ai_metadata"]
        self.assertEqual(meta["source_language"], "en_GB")
        self.assertCountEqual(meta["generated_languages"], ["es_ES", "ca_ES"])

    def test_pipeline_skips_already_complete_records(self):
        complete_ro = {
            "uuid": "ro-202",
            "title": "Already complete study",
            "submissionYear": 2023,
            "abstract": {
                "en_GB": "English content.",
                "es_ES": "Contenido en español.",
                "ca_ES": "Contingut en català."
            }
        }
        existing_ai = {
            "uuid": "ro-202",
            "abstract_ai_metadata": {
                "source_language": "en_GB",
                "generated_languages": ["es_ES", "ca_ES"],
                "egreta_sync": {"synced": True, "env": "test"}
            }
        }
        store = FakeROStore(initial_ros=[complete_ro], initial_ai=[existing_ai])
        pipeline = ROIntakePipeline(store=store, processor=self.processor)

        query = IntakeQuery(uuid="ro-202")
        options = IntakeOptions(mode="apply", sync_egreta=True, egreta_env="test", show_progress=False)

        report = pipeline.run(query, options)
        self.assertEqual(report["sample_counts"]["already_complete_ros"], 1)
        self.assertEqual(report["sample_counts"]["total_translations_executed"], 0)

    def test_pipeline_discrepancy_forced_retranslation(self):
        base_ro = {
            "uuid": "ro-disc-1",
            "title": "Discrepant study",
            "submissionYear": 2024,
            "abstract": {
                "en_GB": "Original extensive research text with substantial academic content.",
                "ca_ES": "Sense resum."
            }
        }
        discrepant_ai = {
            "uuid": "ro-disc-1",
            "abstract_ai_metadata": {
                "source_language": "en_GB",
                "generated_languages": ["ca_ES"]
            },
            "discrepancy_analysis": {
                "ratio_words": 6.5,
                "diff_words": 180
            }
        }
        store = FakeROStore(initial_ros=[base_ro], initial_ai=[discrepant_ai])
        pipeline = ROIntakePipeline(store=store, processor=self.processor)

        query = IntakeQuery(is_discrepancy_query=True, min_ratio=4.0, min_word_diff=150)
        options = IntakeOptions(mode="apply", show_progress=False)

        report = pipeline.run(query, options)
        self.assertEqual(report["sample_counts"]["total_ros_analyzed"], 1)
        self.assertGreater(report["sample_counts"]["total_translations_executed"], 0)

        # Verify new translations replaced the placeholder in store
        self.assertNotEqual(store.ros["ro-disc-1"]["abstract"]["ca_ES"], "Sense resum.")

    def test_pipeline_dispatches_egreta_sync(self):
        ro = {
            "uuid": "ro-sync-303",
            "title": "Egreta sync test",
            "submissionYear": 2024,
            "abstract": {"en_GB": "Testing Egreta sync dispatch mechanism."}
        }
        store = FakeROStore(initial_ros=[ro])
        synced_calls = []

        def mock_egreta_syncer(uuid, abstracts, env, api_key):
            synced_calls.append({"uuid": uuid, "abstracts": abstracts, "env": env})
            return True, "Synced OK"

        pipeline = ROIntakePipeline(store=store, processor=self.processor, egreta_syncer=mock_egreta_syncer)
        query = IntakeQuery(uuid="ro-sync-303")
        options = IntakeOptions(mode="apply", sync_egreta=True, egreta_env="prod", show_progress=False)

        pipeline.run(query, options)
        self.assertEqual(len(synced_calls), 1)
        self.assertEqual(synced_calls[0]["uuid"], "ro-sync-303")
        self.assertEqual(synced_calls[0]["env"], "prod")
        self.assertTrue(store.ai_meta["ro-sync-303"]["abstract_ai_metadata"]["egreta_sync"]["synced"])


class TestAbstractCluster(unittest.TestCase):
    def test_empty_and_invalid_inputs(self):
        c1 = AbstractCluster.from_raw(None)
        self.assertTrue(c1.is_empty)
        self.assertEqual(len(c1.unique_slots), 0)
        self.assertIsNone(c1.preferred_source)

        c2 = AbstractCluster.from_raw({})
        self.assertTrue(c2.is_empty)

        c3 = AbstractCluster.from_raw({"es_ES": "   \n\t  "})
        self.assertTrue(c3.is_empty)

    def test_placeholder_filtering(self):
        raw = {
            "ca_ES": "Sense resum en català.",
            "es_ES": "Sin resumen."
        }
        cluster = AbstractCluster.from_raw(raw)
        self.assertTrue(cluster.is_empty)
        self.assertEqual(len(cluster.valid_entries), 0)

    def test_corruption_filtering(self):
        raw = {
            "ca_ES": "Text que conté tokens no vàlids <unk> i brossa de subtoken.",
            "es_ES": "Dosi de 5 льg per dia administrada en pacients amb diabetis mellitus."
        }
        cluster = AbstractCluster.from_raw(raw)
        self.assertTrue(cluster.is_empty)

    def test_truncated_fragment_filtering(self):
        long_abstract = "This extensive academic paper analyzes machine learning in healthcare. " * 6
        raw = {
            "en_GB": long_abstract,
            "es_ES": "Resumen muy breve."
        }
        cluster = AbstractCluster.from_raw(raw)
        self.assertFalse(cluster.is_empty)
        self.assertIn("en_GB", cluster.valid_entries)
        self.assertNotIn("es_ES", cluster.valid_entries)

    def test_cross_slot_deduplication_and_classification(self):
        en_text = "This paper presents a novel approach for deep learning in biomedical natural language processing."
        raw = {
            "en_GB": en_text,
            "es_ES": en_text  # identical cloned text in Spanish slot
        }
        cluster = AbstractCluster.from_raw(raw)
        self.assertFalse(cluster.is_empty)
        # Unique slots should be deduplicated to 1 slot
        self.assertEqual(len(cluster.unique_slots), 1)
        self.assertEqual(len(cluster.english_candidates), 1)
        self.assertEqual(len(cluster.spanish_candidates), 0)
        self.assertTrue(cluster.has_cloned_or_untranslated)
        self.assertIsNotNone(cluster.preferred_source)
        self.assertEqual(cluster.preferred_source.detected_lang, "en_GB")

    def test_preferred_source_selection_hierarchy(self):
        # 1. English preferred over Spanish and Catalan
        raw_multi = {
            "es_ES": "Este trabajo analiza la evolución metodológica observada en la muestra seleccionada.",
            "en_GB": "This study analyzes the methodological evolution observed in the selected sample.",
            "ca_ES": "Aquest treball analitza l'evolució metodològica observada en la mostra seleccionada."
        }
        cluster = AbstractCluster.from_raw(raw_multi)
        self.assertEqual(cluster.preferred_source.detected_lang, "en_GB")
        self.assertEqual(cluster.preferred_source.slot_key, "en_GB")

        # 2. Spanish preferred when English is missing
        raw_es_ca = {
            "es_ES": "Este trabajo analiza la evolución metodológica observada en la muestra seleccionada.",
            "ca_ES": "Aquest treball analitza l'evolució metodològica observada en la mostra seleccionada."
        }
        cluster_es = AbstractCluster.from_raw(raw_es_ca)
        self.assertEqual(cluster_es.preferred_source.detected_lang, "es_ES")

    def test_ner_dump_corruption_filtering(self):
        raw = {
            "ca_ES": "[('Objective', 'O'), ('Objectiu:', 'O'), ('Background', 'O')]",
            "en_GB": "<p>[Image: see text]</p>"
        }
        cluster = AbstractCluster.from_raw(raw)
        self.assertNotIn("ca_ES", cluster.valid_entries)
        self.assertIn("en_GB", cluster.valid_entries)

    def test_deterministic_figure_placeholder_translation(self):
        trans = Translator(MockAdapter())
        res_es = trans.translate("[Image: see text]", "es_ES", "en_GB")
        self.assertEqual(res_es["translation"], "[Imagen: ver texto]")

        res_ca = trans.translate("[Image: see text]", "ca_ES", "en_GB")
        self.assertEqual(res_ca["translation"], "[Imatge: vegeu el text]")


class TestEgretaSync(unittest.TestCase):
    def test_sync_result_value_object(self):
        res = SyncResult(success=True, status_code=200, message="OK", synced_at="2026-10-05T00:00:00Z")
        self.assertTrue(res.success)
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.message, "OK")
        self.assertEqual(res.synced_at, "2026-10-05T00:00:00Z")

        # Test tuple unpacking
        ok, msg = res
        self.assertTrue(ok)
        self.assertEqual(msg, "OK")

    def test_fake_egreta_adapter_success_and_history(self):
        adapter = FakeEgretaAdapter()
        abstracts = {
            "en_GB": "<p>English study</p>",
            "es_ES": "Estudio espanol & analisis"
        }
        res = adapter.sync_abstracts(uuid="ro-fake-1", abstracts=abstracts, env="prod")
        self.assertTrue(res.success)
        self.assertEqual(res.status_code, 200)
        self.assertIn("ro-fake-1", adapter.synced_records)
        self.assertEqual(len(adapter.call_history), 1)
        self.assertEqual(adapter.call_history[0]["uuid"], "ro-fake-1")
        self.assertEqual(adapter.call_history[0]["env"], "prod")
        # Ensure XML escaping was applied
        self.assertIn("&amp;", adapter.synced_records["ro-fake-1"]["abstract"]["es_ES"])

    def test_fake_egreta_adapter_simulated_failure(self):
        adapter = FakeEgretaAdapter(simulate_failure_uuids={"ro-fail-99"})
        res = adapter.sync_abstracts(uuid="ro-fail-99", abstracts={"en_GB": "Test"}, env="test")
        self.assertFalse(res.success)
        self.assertEqual(res.status_code, 500)
        self.assertNotIn("ro-fail-99", adapter.synced_records)

    def test_pipeline_with_fake_egreta_adapter(self):
        fake_adapter = FakeEgretaAdapter()
        store = FakeROStore(initial_ros=[{
            "uuid": "ro-adapter-101",
            "title": "Adapter integration test",
            "submissionYear": 2024,
            "abstract": {"en_GB": "Testing adapter injection in pipeline."}
        }])
        engine = MockBenchmarkEngine()
        processor = AbstractProcessor(engine)
        pipeline = ROIntakePipeline(store=store, processor=processor, egreta_adapter=fake_adapter)

        query = IntakeQuery(uuid="ro-adapter-101")
        options = IntakeOptions(mode="apply", sync_egreta=True, egreta_env="prod", show_progress=False)

        pipeline.run(query, options)
        self.assertIn("ro-adapter-101", fake_adapter.synced_records)
        self.assertEqual(fake_adapter.synced_records["ro-adapter-101"]["env"], "prod")
        self.assertTrue(store.ai_meta["ro-adapter-101"]["abstract_ai_metadata"]["egreta_sync"]["synced"])
        self.assertEqual(store.ai_meta["ro-adapter-101"]["abstract_ai_metadata"]["egreta_sync"]["status_code"], 200)

    def test_http_egreta_adapter_with_mocked_session(self):
        from unittest.mock import MagicMock
        mock_session = MagicMock()

        # Mock GET response (existing document in Egreta)
        mock_get_resp = MagicMock()
        mock_get_resp.status_code = 200
        mock_get_resp.json.return_value = {"uuid": "ro-mock-http", "title": "Test Title"}
        mock_get_resp.text = '{"uuid": "ro-mock-http"}'

        # Mock PUT response (successful update)
        mock_put_resp = MagicMock()
        mock_put_resp.status_code = 200
        mock_put_resp.text = ""

        mock_session.get.return_value = mock_get_resp
        mock_session.put.return_value = mock_put_resp

        adapter = HttpEgretaAdapter(session=mock_session)
        res = adapter.sync_abstracts(
            uuid="ro-mock-http",
            abstracts={"en_GB": "Hello world", "es_ES": "Hola mundo"},
            env="test"
        )
        self.assertTrue(res.success)
        self.assertEqual(res.status_code, 200)
        self.assertTrue(mock_session.get.called)
        self.assertTrue(mock_session.put.called)

    def test_http_egreta_adapter_404_not_found(self):
        from unittest.mock import MagicMock
        mock_session = MagicMock()

        mock_get_resp = MagicMock()
        mock_get_resp.status_code = 404
        mock_get_resp.text = "Not found"
        mock_session.get.return_value = mock_get_resp

        adapter = HttpEgretaAdapter(session=mock_session)
        res = adapter.sync_abstracts(uuid="ro-404", abstracts={"en_GB": "Test"}, env="test")
        self.assertFalse(res.success)
        self.assertEqual(res.status_code, 404)
        self.assertIn("HTTP 404", res.message)


if __name__ == "__main__":
    unittest.main()








