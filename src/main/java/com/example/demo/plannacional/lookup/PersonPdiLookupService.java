package com.example.demo.plannacional.lookup;

import com.example.demo.plannacional.engine.PlanNacionalEligibilityEngine;
import com.example.demo.plannacional.model.*;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Servei de cerca de personal PDI sobre la col·lecció Persons d'Egreta (MongoDB kraken)
 * amb emparellament en cascada (NIF -> nom exacte -> nom aproximat) i selecció del contracte real.
 */
@Service
public class PersonPdiLookupService {

    private static final int BATCH_SIZE = 500;
    private static final int MAX_AMBIGUOUS_CANDIDATES = 5;
    private static final String EGRETA_EDITOR_PREFIX =
            "https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=";

    private static final Bson PERSONS_PROJECTION = Projections.include(
            "uuid", "pureId", "name", "names", "identifiers", "retirementDate",
            "staffOrganizationAssociations", "visitingScholarOrganizationAssociations"
    );

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Set<String> NAME_PARTICLES = Set.of(
            "de", "del", "dela", "dels", "la", "las", "los", "el", "els", "les", "i", "y", "d", "l"
    );
    private static final Set<String> LEGAL_ID_TYPES = Set.of(
            "dni", "nie", "nif/nie/passaport", "nif/nie/passport", "passaport", "passport"
    );

    private final MongoTemplate mongoTemplate;
    private final PlanNacionalEligibilityEngine eligibilityEngine;

    @Autowired
    public PersonPdiLookupService(MongoTemplate mongoTemplate, PlanNacionalEligibilityEngine eligibilityEngine) {
        this.mongoTemplate = mongoTemplate;
        this.eligibilityEngine = eligibilityEngine;
    }

    public PersonLookupMatch lookup(ExtractedMember member, LocalDate referenceDate) {
        if (member == null) {
            return notFound();
        }
        return lookupAll(List.of(member), referenceDate).get(0);
    }

    public List<PersonLookupMatch> lookupAll(List<ExtractedMember> members, LocalDate referenceDate) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        LocalDate effectiveRef = referenceDate != null ? referenceDate : LocalDate.now();
        LookupIndex index = loadLookupIndex();

        List<PersonLookupMatch> results = new ArrayList<>(members.size());
        for (ExtractedMember member : members) {
            results.add(lookupInIndex(member, index, effectiveRef));
        }
        return results;
    }

    private PersonLookupMatch lookupInIndex(ExtractedMember member, LookupIndex index, LocalDate referenceDate) {
        if (member == null) {
            return notFound();
        }

        // Nivell 1: NIF exacte o emmascarat
        String rawNif = member.nif();
        if (rawNif != null && !rawNif.isBlank()) {
            if (rawNif.contains("*")) {
                PersonLookupMatch maskedResult = matchMaskedNif(member, rawNif, index, referenceDate);
                if (maskedResult != null) {
                    return maskedResult;
                }
            } else {
                String normNif = normalizeNif(rawNif);
                if (!normNif.isEmpty()) {
                    List<IndexedPerson> nifHits = index.byExactNif.getOrDefault(normNif, List.of());
                    if (nifHits.size() == 1) {
                        return toMatch(nifHits.get(0), MatchConfidence.NIF_EXACTE, referenceDate);
                    }
                    if (nifHits.size() > 1) {
                        return toAmbiguous(nifHits, referenceDate);
                    }
                }
            }
        }

        // Nivell 2: Nom i cognoms exactes (sense accents, tolerant ordre "Cognoms, Nom" vs "Nom Cognoms")
        List<String> memberFullTokens = buildOrderedNameTokens(member, false);
        if (!memberFullTokens.isEmpty()) {
            String exactKey = joinTokens(sortedCopy(memberFullTokens));
            List<IndexedPerson> exactHits = index.bySortedTokens.getOrDefault(exactKey, List.of()).stream()
                    .filter(p -> p.matchesExactOrderedTokens(memberFullTokens))
                    .toList();
            if (exactHits.size() == 1) {
                return toMatch(exactHits.get(0), MatchConfidence.NOM_EXACTE, referenceDate);
            }
            if (exactHits.size() > 1) {
                return toAmbiguous(exactHits, referenceDate);
            }
        }

        // Nivell 3: Concordança aproximada (sense partícules o subconjunt estricte de tokens)
        List<String> coreTokens = buildOrderedNameTokens(member, true);
        if (coreTokens.size() >= 2) {
            List<ScoredPerson> approxCandidates = findApproximateCandidates(coreTokens, member, index.allPersons);
            if (approxCandidates.size() == 1) {
                return toMatch(approxCandidates.get(0).person, MatchConfidence.NOM_APROXIMAT, referenceDate);
            }
            if (approxCandidates.size() > 1) {
                double topScore = approxCandidates.get(0).score;
                List<IndexedPerson> topTied = approxCandidates.stream()
                        .filter(sp -> Math.abs(sp.score - topScore) < 0.05)
                        .map(sp -> sp.person)
                        .toList();
                if (topTied.size() == 1 && topScore >= 0.85) {
                    return toMatch(topTied.get(0), MatchConfidence.NOM_APROXIMAT, referenceDate);
                }
                return toAmbiguous(topTied.size() > 1 ? topTied : approxCandidates.stream().map(s -> s.person).toList(), referenceDate);
            }
        }

        return notFound();
    }

    private PersonLookupMatch matchMaskedNif(
            ExtractedMember member,
            String rawMaskedNif,
            LookupIndex index,
            LocalDate referenceDate) {
        String compactMask = rawMaskedNif.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-.]", "");
        String visibleDigits = compactMask.replace("*", "");
        if (visibleDigits.length() < 3) {
            return null;
        }

        List<String> surnameTokens = extractSurnameTokens(member);
        List<IndexedPerson> candidates = new ArrayList<>();

        for (IndexedPerson person : index.allPersons) {
            boolean idMatches = person.normalizedNifs.stream().anyMatch(nif -> matchesMask(nif, compactMask, visibleDigits));
            if (!idMatches) {
                continue;
            }
            if (surnameTokens.isEmpty() || person.sharesSurnameToken(surnameTokens)) {
                candidates.add(person);
            }
        }

        if (candidates.size() == 1) {
            return toMatch(candidates.get(0), MatchConfidence.NIF_EXACTE, referenceDate);
        }
        if (candidates.size() > 1) {
            return toAmbiguous(candidates, referenceDate);
        }
        return null;
    }

    private boolean matchesMask(String candidateNif, String compactMask, String visibleFragment) {
        if (candidateNif == null || candidateNif.isEmpty()) {
            return false;
        }
        if (candidateNif.length() == compactMask.length()) {
            boolean positionalMatch = true;
            for (int i = 0; i < compactMask.length(); i++) {
                char mc = compactMask.charAt(i);
                if (mc != '*' && mc != candidateNif.charAt(i)) {
                    positionalMatch = false;
                    break;
                }
            }
            if (positionalMatch) {
                return true;
            }
        }
        return candidateNif.contains(visibleFragment);
    }

    private List<ScoredPerson> findApproximateCandidates(
            List<String> memberCoreTokens,
            ExtractedMember member,
            List<IndexedPerson> allPersons) {
        Set<String> memberSet = new LinkedHashSet<>(memberCoreTokens);
        List<String> surnameTokens = extractSurnameTokens(member);
        List<ScoredPerson> hits = new ArrayList<>();

        for (IndexedPerson person : allPersons) {
            List<String> pTokens = person.coreTokens;
            if (pTokens.isEmpty()) {
                continue;
            }
            Set<String> pSet = new LinkedHashSet<>(pTokens);
            long shared = memberSet.stream().filter(pSet::contains).count();
            if (shared < 2) {
                continue;
            }
            if (!surnameTokens.isEmpty() && !person.sharesSurnameToken(surnameTokens)) {
                continue;
            }
            int union = memberSet.size() + pSet.size() - (int) shared;
            double jaccard = union > 0 ? (double) shared / union : 0.0;
            double coverage = (double) shared / Math.min(memberSet.size(), pSet.size());
            if (jaccard >= 0.60 || coverage >= 0.90) {
                double score = 0.6 * jaccard + 0.4 * coverage;
                hits.add(new ScoredPerson(person, score));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        return hits;
    }

    private PersonLookupMatch toMatch(IndexedPerson person, MatchConfidence confidence, LocalDate referenceDate) {
        ContractSelection selection = selectContracts(person.doc, referenceDate);
        String url = person.pureId != null ? EGRETA_EDITOR_PREFIX + person.pureId : null;
        return new PersonLookupMatch(
                confidence,
                person.uuid,
                person.pureId,
                person.displayName,
                url,
                selection.primaryContract,
                selection.activeContracts,
                selection.latestHistoricalContract,
                List.of()
        );
    }

    private PersonLookupMatch toAmbiguous(List<IndexedPerson> candidates, LocalDate referenceDate) {
        List<PersonCandidateSummary> summaries = candidates.stream()
                .limit(MAX_AMBIGUOUS_CANDIDATES)
                .map(p -> {
                    ContractSelection sel = selectContracts(p.doc, referenceDate);
                    String activeEmp = sel.primaryContract != null ? sel.primaryContract.employmentType() : "Sense contracte vigent";
                    String dept = sel.primaryContract != null ? sel.primaryContract.organizationName() : "";
                    return new PersonCandidateSummary(p.uuid, p.pureId, p.displayName, activeEmp, dept);
                })
                .toList();
        return new PersonLookupMatch(MatchConfidence.AMBIGU, null, null, null, null, null, List.of(), null, summaries);
    }

    private ContractSelection selectContracts(Document personDoc, LocalDate referenceDate) {
        List<ContractSnapshot> allContracts = new ArrayList<>();
        extractStaffContracts(personDoc, allContracts);
        extractVisitingContracts(personDoc, allContracts);

        List<ContractSnapshot> activeRaw = new ArrayList<>();
        for (ContractSnapshot c : allContracts) {
            if (isActiveAt(c, referenceDate)) {
                activeRaw.add(c);
            }
        }

        if (!activeRaw.isEmpty()) {
            List<ContractSnapshot> realContracts = activeRaw.stream()
                    .filter(c -> !isAdscripcioARecerca(c) && hasMeaningfulEmployment(c))
                    .toList();

            if (!realContracts.isEmpty()) {
                ContractSnapshot primary = realContracts.stream()
                        .min(this::compareContractPreference)
                        .orElse(realContracts.get(0));
                return new ContractSelection(primary, activeRaw, null);
            }

            // Només té "Adscripció a recerca" o associació sense contracte laboral real
            List<ContractSnapshot> normalizedActive = activeRaw.stream()
                    .map(c -> isAdscripcioARecerca(c)
                            ? new ContractSnapshot(c.source(), c.organizationName(), c.organizationUuid(),
                            c.employmentType(), c.contractType(), c.staffType(),
                            c.startDate(), c.endDate(), c.primaryAssociation(), true)
                            : c)
                    .toList();
            ContractSnapshot primary = normalizedActive.stream()
                    .filter(ContractSnapshot::researchAffiliationOnly)
                    .findFirst()
                    .orElse(normalizedActive.get(0));
            return new ContractSelection(primary, normalizedActive, null);
        }

        ContractSnapshot latestHist = allContracts.stream()
                .min((a, b) -> {
                    int endCmp = compareDatesDesc(a.endDate(), b.endDate());
                    if (endCmp != 0) {
                        return endCmp;
                    }
                    return compareDatesDesc(a.startDate(), b.startDate());
                })
                .orElse(null);

        return new ContractSelection(null, List.of(), latestHist);
    }

    private int compareContractPreference(ContractSnapshot a, ContractSnapshot b) {
        int primCmp = Boolean.compare(b.primaryAssociation(), a.primaryAssociation());
        if (primCmp != 0) {
            return primCmp;
        }
        int catCmp = Integer.compare(categoryRank(eligibilityEngine.classifyCategory(a)),
                categoryRank(eligibilityEngine.classifyCategory(b)));
        if (catCmp != 0) {
            return catCmp;
        }
        return compareDatesDesc(a.endDate(), b.endDate());
    }

    private int categoryRank(EmploymentCategory cat) {
        return switch (cat) {
            case PDI_ESTABLE -> 0;
            case PDI_TEMPORAL -> 1;
            case EMERIT -> 2;
            case ASSOCIAT -> 3;
            case SUBSTITUT -> 4;
            case PREDOCTORAL -> 5;
            case PTGAS -> 6;
            case HONORARI_JUBILAT -> 7;
            case ADSCRIPCIO_RECERCA -> 8;
            case DESCONEGUDA -> 9;
        };
    }

    private int compareDatesDesc(LocalDate a, LocalDate b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1; // sense data de fi = vigència més llarga
        }
        if (b == null) {
            return 1;
        }
        return b.compareTo(a);
    }

    private void extractStaffContracts(Document personDoc, List<ContractSnapshot> out) {
        for (Document assoc : asDocumentList(personDoc.get("staffOrganizationAssociations"))) {
            Document period = assoc.get("period", Document.class);
            LocalDate start = parseLocalDate(period != null ? period.get("startDate") : null);
            LocalDate end = parseLocalDate(period != null ? period.get("endDate") : null);
            Document org = assoc.get("organization", Document.class);
            String orgUuid = org != null ? org.getString("uuid") : null;
            String orgName = extractOrgName(org);
            String empType = extractTerm(assoc.get("employmentType", Document.class));
            String contractType = extractTerm(assoc.get("contractType", Document.class));
            String staffType = extractTerm(assoc.get("staffType", Document.class));
            boolean primary = Boolean.TRUE.equals(assoc.getBoolean("primaryAssociation"));

            out.add(new ContractSnapshot("STAFF", orgName, orgUuid, empType, contractType, staffType, start, end, primary, false));
        }
    }

    private void extractVisitingContracts(Document personDoc, List<ContractSnapshot> out) {
        for (Document assoc : asDocumentList(personDoc.get("visitingScholarOrganizationAssociations"))) {
            Document period = assoc.get("period", Document.class);
            LocalDate start = parseLocalDate(period != null ? period.get("startDate") : null);
            LocalDate end = parseLocalDate(period != null ? period.get("endDate") : null);
            Document org = assoc.get("organization", Document.class);
            String orgUuid = org != null ? org.getString("uuid") : null;
            String orgName = extractOrgName(org);
            String jobTitle = extractTerm(assoc.get("jobTitle", Document.class));
            boolean primary = Boolean.TRUE.equals(assoc.getBoolean("primaryAssociation"));

            out.add(new ContractSnapshot("VISITING", orgName, orgUuid, jobTitle, null, "Acadèmic", start, end, primary, false));
        }
    }

    private boolean isActiveAt(ContractSnapshot c, LocalDate referenceDate) {
        boolean started = c.startDate() == null || !c.startDate().isAfter(referenceDate);
        boolean notEnded = c.endDate() == null || !c.endDate().isBefore(referenceDate);
        return started && notEnded;
    }

    private boolean isAdscripcioARecerca(ContractSnapshot c) {
        return normalize(c.employmentType()).equals("adscripcio a recerca");
    }

    private boolean hasMeaningfulEmployment(ContractSnapshot c) {
        return (c.employmentType() != null && !c.employmentType().isBlank())
                || (c.contractType() != null && !c.contractType().isBlank());
    }

    private LookupIndex loadLookupIndex() {
        List<IndexedPerson> allPersons = new ArrayList<>();
        Map<String, List<IndexedPerson>> byExactNif = new HashMap<>();
        Map<String, List<IndexedPerson>> bySortedTokens = new HashMap<>();

        try (MongoCursor<Document> cursor = mongoTemplate.getCollection("Persons")
                .find()
                .projection(PERSONS_PROJECTION)
                .batchSize(BATCH_SIZE)
                .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                IndexedPerson indexed = buildIndexedPerson(doc);
                allPersons.add(indexed);

                for (String nif : indexed.normalizedNifs) {
                    byExactNif.computeIfAbsent(nif, k -> new ArrayList<>()).add(indexed);
                }
                for (String key : indexed.sortedTokenKeys) {
                    bySortedTokens.computeIfAbsent(key, k -> new ArrayList<>()).add(indexed);
                }
            }
        }
        return new LookupIndex(allPersons, byExactNif, bySortedTokens);
    }

    private IndexedPerson buildIndexedPerson(Document doc) {
        String uuid = doc.getString("uuid");
        Number pureNum = doc.get("pureId", Number.class);
        Long pureId = pureNum != null ? pureNum.longValue() : null;

        Document nameDoc = doc.get("name", Document.class);
        String firstName = nameDoc != null ? safeString(nameDoc.getString("firstName")) : "";
        String lastName = nameDoc != null ? safeString(nameDoc.getString("lastName")) : "";
        String displayName = (!lastName.isEmpty() || !firstName.isEmpty())
                ? (lastName + (lastName.isEmpty() || firstName.isEmpty() ? "" : ", ") + firstName)
                : safeString(doc.getString("systemName"));

        Set<String> normalizedNifs = new LinkedHashSet<>();
        for (Document idDoc : asDocumentList(doc.get("identifiers"))) {
            String typeLabel = normalize(extractTerm(idDoc.get("type", Document.class)));
            String typeUri = normalize(idDoc.get("type", Document.class) != null ? idDoc.get("type", Document.class).getString("uri") : "");
            boolean isLegalId = LEGAL_ID_TYPES.contains(typeLabel)
                    || typeUri.endsWith("/dni")
                    || typeUri.endsWith("/nie")
                    || typeUri.endsWith("/nif_nie_passport")
                    || typeUri.endsWith("/passport")
                    || typeLabel.contains("nif")
                    || typeLabel.contains("dni");
            if (isLegalId) {
                String normVal = normalizeNif(idDoc.getString("value"));
                if (!normVal.isEmpty()) {
                    normalizedNifs.add(normVal);
                }
            }
        }

        List<List<String>> nameVariantsTokens = new ArrayList<>();
        List<String> primaryTokens = tokenize((firstName + " " + lastName).trim(), false);
        if (!primaryTokens.isEmpty()) {
            nameVariantsTokens.add(primaryTokens);
        }
        for (Document altName : asDocumentList(doc.get("names"))) {
            Document nested = altName.get("name", Document.class);
            if (nested != null) {
                List<String> altTokens = tokenize(
                        (safeString(nested.getString("firstName")) + " " + safeString(nested.getString("lastName"))).trim(),
                        false
                );
                if (!altTokens.isEmpty()) {
                    nameVariantsTokens.add(altTokens);
                }
            }
        }

        Set<String> sortedTokenKeys = new LinkedHashSet<>();
        for (List<String> variant : nameVariantsTokens) {
            sortedTokenKeys.add(joinTokens(sortedCopy(variant)));
        }

        List<String> surnameTokens = tokenize(lastName, true);
        List<String> coreTokens = tokenize((firstName + " " + lastName).trim(), true);

        return new IndexedPerson(doc, uuid, pureId, displayName, normalizedNifs, nameVariantsTokens, sortedTokenKeys, surnameTokens, coreTokens);
    }

    private List<String> buildOrderedNameTokens(ExtractedMember member, boolean stripParticles) {
        if (member.firstName() != null && !member.firstName().isBlank()
                && member.lastName() != null && !member.lastName().isBlank()) {
            return tokenize(member.firstName() + " " + member.lastName(), stripParticles);
        }
        String full = safeString(member.fullName());
        if (full.contains(",")) {
            String[] parts = full.split(",", 2);
            return tokenize(parts[1] + " " + parts[0], stripParticles);
        }
        return tokenize(full, stripParticles);
    }

    private List<String> extractSurnameTokens(ExtractedMember member) {
        if (member.lastName() != null && !member.lastName().isBlank()) {
            return tokenize(member.lastName(), true);
        }
        String full = safeString(member.fullName());
        if (full.contains(",")) {
            return tokenize(full.split(",", 2)[0], true);
        }
        List<String> all = tokenize(full, true);
        return all.size() > 1 ? all.subList(1, all.size()) : all;
    }

    private static List<String> tokenize(String raw, boolean stripParticles) {
        String norm = normalize(raw).replaceAll("[^a-z0-9]", " ").replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tok : norm.split(" ")) {
            if (tok.isEmpty()) {
                continue;
            }
            if (stripParticles && NAME_PARTICLES.contains(tok)) {
                continue;
            }
            out.add(tok);
        }
        return out;
    }

    private static List<String> sortedCopy(List<String> tokens) {
        List<String> copy = new ArrayList<>(tokens);
        Collections.sort(copy);
        return copy;
    }

    private static String joinTokens(List<String> tokens) {
        return String.join(" ", tokens);
    }

    private static String normalizeNif(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }

    private String extractTerm(Document wrapper) {
        if (wrapper == null) {
            return null;
        }
        Document term = wrapper.get("term", Document.class);
        if (term == null) {
            return null;
        }
        for (String locale : List.of("ca_ES", "es_ES", "en_GB")) {
            String val = term.getString(locale);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        Object textObj = term.get("text");
        if (textObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Document td) {
            String val = td.getString("value");
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        for (Object val : term.values()) {
            if (val instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    private String extractOrgName(Document org) {
        if (org == null) {
            return null;
        }
        Object nameObj = org.get("name");
        if (nameObj instanceof Document nameDoc) {
            Object textObj = nameDoc.get("text");
            if (textObj instanceof List<?> list) {
                String fallback = null;
                for (Object item : list) {
                    if (item instanceof Document d) {
                        String val = d.getString("value");
                        String loc = d.getString("locale");
                        if (val != null && !val.isBlank()) {
                            if ("ca_ES".equals(loc)) {
                                return val.trim();
                            }
                            if (fallback == null) {
                                fallback = val.trim();
                            }
                        }
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            }
            for (String locale : List.of("ca_ES", "es_ES", "en_GB", "value")) {
                String val = nameDoc.getString(locale);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return nameObj instanceof String s ? s.trim() : null;
    }

    private LocalDate parseLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (raw instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (raw instanceof String s && s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private List<Document> asDocumentList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Document> docs = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Document d) {
                    docs.add(d);
                }
            }
            return docs;
        }
        if (raw instanceof Document d) {
            return List.of(d);
        }
        return List.of();
    }

    private String safeString(String s) {
        return s != null ? s.trim() : "";
    }

    private PersonLookupMatch notFound() {
        return new PersonLookupMatch(MatchConfidence.NO_TROBAT, null, null, null, null, null, List.of(), null, List.of());
    }

    private record ContractSelection(
            ContractSnapshot primaryContract,
            List<ContractSnapshot> activeContracts,
            ContractSnapshot latestHistoricalContract
    ) {}

    private record ScoredPerson(IndexedPerson person, double score) {}

    private record LookupIndex(
            List<IndexedPerson> allPersons,
            Map<String, List<IndexedPerson>> byExactNif,
            Map<String, List<IndexedPerson>> bySortedTokens
    ) {}

    private static final class IndexedPerson {
        final Document doc;
        final String uuid;
        final Long pureId;
        final String displayName;
        final Set<String> normalizedNifs;
        final List<List<String>> nameVariantsTokens;
        final Set<String> sortedTokenKeys;
        final List<String> surnameTokens;
        final List<String> coreTokens;

        IndexedPerson(
                Document doc,
                String uuid,
                Long pureId,
                String displayName,
                Set<String> normalizedNifs,
                List<List<String>> nameVariantsTokens,
                Set<String> sortedTokenKeys,
                List<String> surnameTokens,
                List<String> coreTokens) {
            this.doc = doc;
            this.uuid = uuid;
            this.pureId = pureId;
            this.displayName = displayName;
            this.normalizedNifs = normalizedNifs;
            this.nameVariantsTokens = nameVariantsTokens;
            this.sortedTokenKeys = sortedTokenKeys;
            this.surnameTokens = surnameTokens;
            this.coreTokens = coreTokens;
        }

        boolean matchesExactOrderedTokens(List<String> candidateOrderedTokens) {
            Set<String> candidateSet = new LinkedHashSet<>(candidateOrderedTokens);
            for (List<String> variant : nameVariantsTokens) {
                if (variant.size() == candidateOrderedTokens.size() && new LinkedHashSet<>(variant).equals(candidateSet)) {
                    return true;
                }
            }
            return false;
        }

        boolean sharesSurnameToken(List<String> targetSurnameTokens) {
            if (surnameTokens.isEmpty()) {
                return false;
            }
            Set<String> mine = new LinkedHashSet<>(surnameTokens);
            for (String token : targetSurnameTokens) {
                if (token.length() > 1 && mine.contains(token)) {
                    return true;
                }
            }
            return false;
        }
    }
}
