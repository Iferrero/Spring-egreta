package com.example.demo.curation.util;

import com.example.demo.curation.model.TypeAggregate;
import com.example.demo.curation.model.TypeInferenceResult;

import java.util.Comparator;
import java.util.List;

/**
 * Maps an organization (its name plus a coarse external type such as the ROR/OpenAlex "education" or
 * "healthcare") onto the organization-type taxonomy that Egreta actually uses (Universitat, Institucions
 * sanitàries, Entitat empresarial, Agència estatal, Centre R+D...). Single source of truth shared by the
 * controller and the provider adapters, so that no provider can emit a label that does not exist in Egreta.
 */
public final class EgretaTypeResolver {

    private EgretaTypeResolver() {}

    public static TypeInferenceResult resolve(
            String orgName,
            String canonicalName,
            String broadType,
            List<TypeAggregate> typeCatalog,
            double baseConfidence,
            String reasonPrefix) {

        String combined = (CurationTextNormalizer.normalizeText(orgName) + " " + CurationTextNormalizer.normalizeText(canonicalName)).trim();
        if (combined.isBlank() || (typeCatalog == null || typeCatalog.isEmpty())) {
            return TypeInferenceResult.noSignal();
        }

        // 1. Departament universitari
        if (matchesAnyKeyword(combined, "department", "departament", "departamento", "dept", "seccio", "seccion", "section", "division", "catedra", "chair", "faculty", "facultat", "facultad", "scola", "school of", "college of", "escola")) {
            String resolved = resolveTypeByHint(List.of("departament universitari", "departament", "departamento"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":department-structure");
            }
        }

        // 2. Institut Universitari d'investigació
        if (matchesAnyKeyword(combined, "institut universitari", "instituto universitario", "university institute", "instituts universitaris de recerca")) {
            String resolved = resolveTypeByHint(List.of("institut universitari d'investigacio", "institut universitari", "instituto universitario"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":university-institute");
            }
        }

        // 3. CIBER
        if (matchesAnyKeyword(combined, "ciber", "ciber-bbn", "ciberehd", "ciberes", "ciberned", "ciberobn", "ciberon", "ciberer", "ciberesp", "cibermed", "cibersam")) {
            String resolved = resolveTypeByHint(List.of("ciber"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.99, Math.max(0.95, baseConfidence * 0.99)), reasonPrefix + ":ciber");
            }
        }

        // 4. Entitat gestora del sistema nacional de salut
        if (matchesAnyKeyword(combined, "catsalut", "servei catala de la salut", "servicio de salud", "sergas", "sacyl", "sescam", "osakidetza", "sms", "sas", "ib-salut", "ibsalut", "sistema nacional de salud", "servei de salut")) {
            String resolved = resolveTypeByHint(List.of("entitat gestora del sistema nacional de salut", "gestora del sistema nacional de salut", "sistema nacional de salut"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":health-system-admin");
            }
        }

        // 5. Institucions sanitàries (Hospitals, clíniques, etc.)
        if (matchesAnyKeyword(combined, "hospital", "clinic", "clinica", "sanitari", "sanitaria", "healthcare", "medical center", "centre medic", "assistencial", "policlinica", "hospital universitari", "nhs trust", "centre d'atencio primaria", "cap", "infirmary", "dispensari", "mutua")
                || "healthcare".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("institucions sanitaries", "instituciones sanitarias", "sanitari", "hospital"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":healthcare");
            }
        }

        // 6. Organisme públic d'investigació (OPI)
        if (matchesAnyKeyword(combined, "csic", "cnrs", "max planck", "inserm", "inria", "inrae", "helmholtz", "fraunhofer", "conicet", "cnpq", "conacyt", "iisc", "organisme public d'investigacio", "organismo publico de investigacion", "national research council", "consejo superior de investigaciones cientificas", "institut nacional d'investigacio", "instituto nacional de investigacion")) {
            String resolved = resolveTypeByHint(List.of("organisme public d'investigacio", "organismo publico de investigacion", "recerca"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":opi");
            }
        }

        // 7. Centre tecnològic / Centres d'innovació i tecnologia
        if (matchesAnyKeyword(combined, "centre tecnologic", "centro tecnologico", "technology center", "technological centre", "eurecat", "leitat", "tecnalia", "aimen", "itene", "cidaut", "parc tecnologic", "parc cientific", "research park", "innovacio i tecnologia")) {
            String resolved = resolveTypeByHint(List.of("centre tecnologic", "centres d'innovacio i tecnologia", "centro tecnologico"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":tech-center");
            }
        }

        // 8. Centre R+D
        if (matchesAnyKeyword(combined, "centre de recerca", "centro de investigacion", "research centre", "research center", "centre r+d", "centro i+d", "r&d centre", "r&d center", "laboratori de recerca", "laboratorio de investigacion")
                || "facility".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("centre r+d", "centre de recerca", "organismes publics d'investigacio", "recerca"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.95, Math.max(0.88, baseConfidence * 0.95)), reasonPrefix + ":rd-center");
            }
        }

        // 9. Fundació
        if (matchesAnyKeyword(combined, "fundacio", "fundacion", "foundation", "stiftung", "fondation", "fondazione")) {
            String resolved = resolveTypeByHint(List.of("fundacio", "fundacion", "foundation"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.97, Math.max(0.90, baseConfidence * 0.98)), reasonPrefix + ":foundation");
            }
        }

        // 10. Associacions i agrupacions
        if (matchesAnyKeyword(combined, "associacio", "asociacion", "association", "societat", "sociedad", "society", "agrupacio", "agrupacion", "col legi", "colegio oficial", "collegi oficial", "federacio", "federacion", "federation", "e.v.", "e v", "eingetragener verein", "verein", "gremi", "gremio", "guild", "consell", "council", "academy", "academia", "akademie")
                || "nonprofit".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("associacions i agrupacions", "associacio", "asociaciones y agrupaciones", "fundacio"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.95, Math.max(0.88, baseConfidence * 0.95)), reasonPrefix + ":association");
            }
        }

        // 11. Administració internacional
        if (matchesAnyKeyword(combined, "onu", "unesco", "who", "oms", "comissio europea", "european commission", "world bank", "banc mundial", "unicef", "oecd", "ocde", "nato", "otant", "united nations", "naciones unidas", "nacions unides", "fao", "ilo", "oit")) {
            String resolved = resolveTypeByHint(List.of("administracio internacional", "administracion internacional", "international administration"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.98, Math.max(0.92, baseConfidence * 0.98)), reasonPrefix + ":intl-admin");
            }
        }

        // 12. Agència estatal
        if (matchesAnyKeyword(combined, "agencia estatal", "agencia de", "state agency", "aemet", "aemps", "aei", "agencia nacional")) {
            String resolved = resolveTypeByHint(List.of("agencia estatal", "state agency"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":state-agency");
            }
        }

        // 13. Sector administració pública
        if (matchesAnyKeyword(combined, "ministeri", "ministerio", "ministry", "generalitat", "govern", "gobierno", "government", "ajuntament", "ayuntamiento", "diputacio", "diputacion", "conselleria", "consejeria", "consell comarcal", "alcaldia", "secretaria d'estat", "secretaria de estado", "department of state", "departament de la generalitat")
                || "government".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("sector administracio publica", "administracio publica", "administracion publica"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":gov-admin");
            }
        }

        // 14. Entitat empresarial (Empreses privades)
        if (matchesAnyKeyword(combined, "s l", "s a", "slu", "sau", "slp", "ltd", "llc", "inc", "corp", "corporation", "gmbh", "srl", "spa", "kgaa", "sas", "sarl", "bv", "nv", "plc", "company", "empresa", "pharma", "pharmaceuticals", "laboratories", "laboratorios", "therapeutics", "biotech", "consulting", "solutions", "s.l.", "s.a.")
                || "company".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("entitat empresarial", "empresa", "enterprise"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":company");
            }
        }

        // 15. Universitat (només si no era departament o institut universitari)
        if (matchesAnyKeyword(combined, "universitat", "universidad", "university", "universite", "universitaet", "universita", "universitad", "college", "hochschule", "faculdade", "polytechnic", "politecnica")
                || "education".equalsIgnoreCase(broadType)) {
            String resolved = resolveTypeByHint(List.of("universitat", "universidad", "university"), typeCatalog);
            if (!resolved.isBlank()) {
                return new TypeInferenceResult(resolved, Math.min(0.96, Math.max(0.90, baseConfidence * 0.96)), reasonPrefix + ":university");
            }
        }

        return TypeInferenceResult.noSignal();
    }

    private static boolean matchesAnyKeyword(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null) return false;
        String norm = CurationTextNormalizer.normalizeText(text);
        for (String kw : keywords) {
            if (CurationTextNormalizer.containsWord(norm, CurationTextNormalizer.normalizeText(kw))) {
                return true;
            }
        }
        return false;
    }

    private static String resolveTypeByHint(List<String> hintTerms, List<TypeAggregate> catalog) {
        return catalog.stream()
            .filter(t -> hintTerms.stream().anyMatch(k -> t.normalizedLabel().contains(CurationTextNormalizer.normalizeText(k))))
            .max(Comparator.comparingInt(TypeAggregate::count))
            .map(TypeAggregate::label)
            .orElse("");
    }
}
