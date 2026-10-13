import json
import statistics
import time
from typing import List, Dict, Any, Optional

class BenchmarkCollector:
    def __init__(self, engine_name: str, model_name: str):
        self.engine_name = engine_name
        self.model_name = model_name
        self.start_time = time.time()
        self.end_time: Optional[float] = None
        
        self.ro_results: List[Dict[str, Any]] = []
        self.translation_records: List[Dict[str, Any]] = []

    def add_ro_result(self, ro_uuid: str, title: str, submission_year: Optional[int], process_result: Dict[str, Any]):
        original_abs = process_result.get("original_abstract") or {}
        updated_abs = process_result.get("updated_abstract") or {}
        translations = process_result.get("translations_done", [])
        total_latency_ro = sum(t.get("latency_ms", 0.0) for t in translations)

        ro_entry = {
            "uuid": ro_uuid,
            "title": title,
            "submissionYear": submission_year,
            "status": process_result["status"],
            "decision_branch": process_result.get("decision_branch", ""),
            "original_languages": list(original_abs.keys()) if isinstance(original_abs, dict) else [],
            "resulting_languages": list(updated_abs.keys()) if isinstance(updated_abs, dict) else [],
            "discrepancy_analysis": process_result.get("discrepancy_analysis"),
            "translations_count": len(translations),
            "total_latency_ro_ms": round(total_latency_ro, 2),
            "translations": translations
        }
        self.ro_results.append(ro_entry)

        for t in translations:
            t_copy = dict(t)
            t_copy["ro_uuid"] = ro_uuid
            t_copy["ro_title"] = title
            self.translation_records.append(t_copy)

    def finalize(self) -> Dict[str, Any]:
        self.end_time = time.time()
        total_duration_s = max(0.001, self.end_time - self.start_time)

        total_ros = len(self.ro_results)
        translated_ros = sum(1 for r in self.ro_results if r["translations_count"] > 0)
        skipped_ros = sum(1 for r in self.ro_results if r["status"] == "SKIPPED_NO_ABSTRACT")
        complete_ros = sum(1 for r in self.ro_results if r["status"] == "ALREADY_COMPLETE")
        reorg_ros = sum(1 for r in self.ro_results if r["status"] == "REORGANIZED")

        total_translations = len(self.translation_records)
        latencies_ms = [t["latency_ms"] for t in self.translation_records] if self.translation_records else [0.0]
        prompt_tokens = sum(t.get("prompt_tokens", 0) for t in self.translation_records)
        completion_tokens = sum(t.get("completion_tokens", 0) for t in self.translation_records)
        total_tokens = prompt_tokens + completion_tokens

        mean_latency = statistics.mean(latencies_ms) if latencies_ms else 0.0
        median_latency = statistics.median(latencies_ms) if latencies_ms else 0.0
        min_latency = min(latencies_ms) if latencies_ms else 0.0
        max_latency = max(latencies_ms) if latencies_ms else 0.0
        p95_latency = statistics.quantiles(latencies_ms, n=20)[18] if len(latencies_ms) >= 20 else max_latency

        throughput_tok_s = completion_tokens / (sum(latencies_ms) / 1000.0) if sum(latencies_ms) > 0 else 0.0

        # Translation direction breakdown
        by_pair = {}
        for t in self.translation_records:
            pair = f"{t.get('from_lang', '?')} -> {t.get('to_lang', '?')}"
            by_pair.setdefault(pair, []).append(t["latency_ms"])

        pair_summary = {}
        for pair, lats in by_pair.items():
            pair_summary[pair] = {
                "count": len(lats),
                "mean_ms": round(statistics.mean(lats), 2),
                "median_ms": round(statistics.median(lats), 2)
            }

        # Extrapolations (Estimates for larger database sizes)
        sec_per_ro = (mean_latency / 1000.0) * (total_translations / max(1, translated_ros)) if translated_ros > 0 else 1.0
        
        projections = {
            "100_abstracts_min": round((100 * sec_per_ro) / 60.0, 2),
            "1000_abstracts_min": round((1000 * sec_per_ro) / 60.0, 2),
            "10000_abstracts_hours": round((10000 * sec_per_ro) / 3600.0, 2),
            "75000_abstracts_hours": round((75000 * sec_per_ro) / 3600.0, 2),
            "75000_abstracts_days": round((75000 * sec_per_ro) / 86400.0, 2)
        }

        report = {
            "engine": self.engine_name,
            "model": self.model_name,
            "total_duration_sec": round(total_duration_s, 2),
            "sample_counts": {
                "total_ros_analyzed": total_ros,
                "translated_ros": translated_ros,
                "already_complete_ros": complete_ros,
                "reorganized_ros": reorg_ros,
                "skipped_no_abstract_ros": skipped_ros,
                "total_translations_executed": total_translations
            },
            "latency_metrics_ms": {
                "mean": round(mean_latency, 2),
                "median": round(median_latency, 2),
                "min": round(min_latency, 2),
                "max": round(max_latency, 2),
                "p95": round(p95_latency, 2)
            },
            "token_metrics": {
                "total_prompt_tokens": prompt_tokens,
                "total_completion_tokens": completion_tokens,
                "total_tokens": total_tokens,
                "avg_tokens_per_translation": round(completion_tokens / max(1, total_translations), 1),
                "generation_throughput_tok_sec": round(throughput_tok_s, 2)
            },
            "by_language_pair": pair_summary,
            "projections": projections,
            "research_outputs": self.ro_results
        }
        return report

    def print_summary(self, report: Dict[str, Any]):
        print("\n" + "="*75)
        print("[*] INFORME DE RENDIMIENTO & BENCHMARKING: SALAMANDRA LLM")
        print("="*75)
        print(f"* Motor de Inferencia:  {report['engine']}")
        print(f"* Modelo:                {report['model']}")
        print(f"* Tiempo de ejecucion:   {report['total_duration_sec']} segundos")
        print(f"* ROs procesados:        {report['sample_counts']['total_ros_analyzed']}")
        print(f"* ROs traducidos:        {report['sample_counts']['translated_ros']}")
        print(f"* Traducciones totales:  {report['sample_counts']['total_translations_executed']}")
        print(f"* ROs ya completos (3L): {report['sample_counts']['already_complete_ros']}")
        print(f"* ROs sin abstract:      {report['sample_counts']['skipped_no_abstract_ros']}")
        print("-"*75)
        print("[*] METRICAS DE LATENCIA (por traduccion individual):")
        lat = report["latency_metrics_ms"]
        print(f"  - Media:   {lat['mean']} ms ({round(lat['mean']/1000, 2)} s)")
        print(f"  - Mediana: {lat['median']} ms ({round(lat['median']/1000, 2)} s)")
        print(f"  - P95:     {lat['p95']} ms")
        print(f"  - Min/Max: {lat['min']} ms / {lat['max']} ms")
        print("-"*75)
        print("[*] METRICAS DE TOKENS & THROUGHPUT:")
        tok = report["token_metrics"]
        print(f"  - Tokens de Prompt:      {tok['total_prompt_tokens']}")
        print(f"  - Tokens Generados:      {tok['total_completion_tokens']}")
        print(f"  - Media Tokens/Abstract: {tok['avg_tokens_per_translation']}")
        print(f"  - Velocidad Generacion:  {tok['generation_throughput_tok_sec']} tokens/segundo")
        print("-"*75)
        print("[*] PROYECCION DE TIEMPO ESTIMADO:")
        proj = report["projections"]
        print(f"  - Para 100 abstracts:    ~{proj['100_abstracts_min']} minutos")
        print(f"  - Para 1,000 abstracts:  ~{proj['1000_abstracts_min']} minutos ({round(proj['1000_abstracts_min']/60, 2)} h)")
        print(f"  - Para 10,000 abstracts: ~{proj['10000_abstracts_hours']} horas")
        print(f"  - Para 75,000 abstracts: ~{proj['75000_abstracts_hours']} horas ({proj['75000_abstracts_days']} dias a 1 stream)")
        print("-"*75)
        print("[*] DETALLE POR RESEARCH OUTPUT Y SUS TRADUCCIONES:")
        ros = report.get("research_outputs", [])
        for i, ro in enumerate(ros, 1):
            title_disp = (ro.get("title") or "Sin titulo")[:55]
            year_disp = f"({ro.get('submissionYear')})" if ro.get("submissionYear") else ""
            print(f"\n[{i}/{len(ros)}] RO UUID: {ro['uuid']} {year_disp}")
            print(f"    Titulo: {title_disp}")
            print(f"    Estado: {ro['status']} | Rama: {ro['decision_branch']}")
            print(f"    Idiomas iniciales: {ro['original_languages']} -> Finales: {ro['resulting_languages']}")
            
            trans_list = ro.get("translations", [])
            if not trans_list:
                print("    -> No requirio traducciones.")
            else:
                for t in trans_list:
                    print(f"    -> [{t['from_lang']} -> {t['to_lang']}] Latencia: {t['latency_ms']} ms ({t['completion_tokens']} tokens generados)")
                    if t.get("source_preview"):
                        print(f"       Original ({t['from_lang']}): \"{t['source_preview']}\"")
                    if t.get("translation_preview"):
                        print(f"       Traduccion ({t['to_lang']}): \"{t['translation_preview']}\"")
        print("="*75 + "\n")
