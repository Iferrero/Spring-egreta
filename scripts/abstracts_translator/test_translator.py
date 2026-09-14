import unittest

try:
    from abstracts_translator.processor import AbstractProcessor
    from abstracts_translator.engine import MockBenchmarkEngine, build_salamandra_prompt, build_salamandra_messages, clean_llm_translation
    from abstracts_translator.detector import detect_language

except ImportError:
    from .processor import AbstractProcessor
    from .engine import MockBenchmarkEngine, build_salamandra_prompt, build_salamandra_messages, clean_llm_translation
    from .detector import detect_language



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


if __name__ == "__main__":
    unittest.main()


