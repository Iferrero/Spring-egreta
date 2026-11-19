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
    private static final long DEFAULT_CACHE_TTL_MS = 60_000L;
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
    private final long cacheTtlMs;
    private volatile CachedLookupIndex cachedIndex;

    @Autowired
    public PersonPdiLookupService(MongoTemplate mongoTemplate, PlanNacionalEligibilityEngine eligibilityEngine) {
        this(mongoTemplate, eligibilityEngine, DEFAULT_CACHE_TTL_MS);
    }

    public PersonPdiLookupService(
            MongoTemplate mongoTemplate,
            PlanNacionalEligibilityEngine eligibilityEngine,
            long cacheTtlMs) {
        this.mongoTemplate = mongoTemplate;
        this.eligibilityEngine = eligibilityEngine;
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
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
        LookupIndex index = getOrLoadLookupIndex();

        List<PersonLookupMatch> results = new ArrayList<>(members.size());
        for (ExtractedMember member : members) {
            results.add(lookupInIndex(member, index, effectiveRef));
        }
        return results;
    }

    private LookupIndex getOrLoadLookupIndex() {
        if (cacheTtlMs == 0L) {
            return loadLookupIndex();
        }
        long now = System.currentTimeMillis();
        CachedLookupIndex current = cachedIndex;
        if (current != null && (now - current.loadedAtMs) < cacheTtlMs) {
            return current.index;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            current = cachedIndex;
            if (current != null && (now - current.loadedAtMs) < cacheTtlMs) {
                return current.index;
            }
            LookupIndex loaded = loadLookupIndex();
            cachedIndex = new CachedLookupIndex(loaded, now);
            return loaded;
        }
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

        // Nivell 3: Concordança aproximada (cognoms + nom propi o inicial)
        List<ScoredPerson> approxCandidates = findApproximateCandidates(member, index.allPersons);
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
        Pattern wildcardPattern = buildMaskWildcardPattern(compactMask);
        List<String> surnameTokens = extractSurnameCandidates(member);
        List<IndexedPerson> candidates = new ArrayList<>();

        for (IndexedPerson person : index.allPersons) {
            boolean idMatches = person.normalizedNifs.stream()
                    .anyMatch(nif -> matchesMask(nif, compactMask, wildcardPattern));
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

    private Pattern buildMaskWildcardPattern(String compactMask) {
        StringBuilder sb = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < compactMask.length(); i++) {
            char c = compactMask.charAt(i);
            if (c == '*') {
                if (!literal.isEmpty()) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                if (sb.length() < 2 || !sb.substring(sb.length() - 2).equals(".*")) {
                    sb.append(".*");
                }
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            sb.append(Pattern.quote(literal.toString()));
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }

    private boolean matchesMask(String candidateNif, String compactMask, Pattern wildcardPattern) {
        if (candidateNif == null || candidateNif.isEmpty()) {
            return false;
        }
        if (candidateNif.length() == compactMask.length()) {
            for (int i = 0; i < compactMask.length(); i++) {
                char mc = compactMask.charAt(i);
                if (mc != '*' && mc != candidateNif.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
        return wildcardPattern.matcher(candidateNif).matches();
    }

    private List<ScoredPerson> findApproximateCandidates(ExtractedMember member, List<IndexedPerson> allPersons) {
        // Rebutja cerques només per cognoms sense cap nom propi/inicial
        boolean explicitSurnameOnly = member.firstName() == null
                && !safeString(member.fullName()).contains(",")
                && member.lastName() != null && !member.lastName().isBlank();
        if (explicitSurnameOnly) {
            return List.of();
        }

        List<String> memberCore = buildOrderedNameTokens(member, true);
        if (memberCore.size() < 2) {
            return List.of();
        }

        boolean hasComma = safeString(member.fullName()).contains(",");
        List<String> preferredSurnameTokens = hasComma
                ? tokenize(safeString(member.fullName()).split(",", 2)[0], true)
                : memberCore;

        List<ScoredPerson> hits = new ArrayList<>();
        for (IndexedPerson person : allPersons) {
            double bestScore = 0.0;
            for (NameVariant variant : person.nameVariants) {
                double score = scoreVariant(memberCore, preferredSurnameTokens, hasComma, member, variant);
                if (score > bestScore) {
                    bestScore = score;
                }
            }
            if (bestScore >= 0.75) {
                hits.add(new ScoredPerson(person, bestScore));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        return hits;
    }

    private double scoreVariant(
            List<String> memberCore,
            List<String> preferredSurnameTokens,
            boolean hasComma,
            ExtractedMember member,
            NameVariant variant) {
        if (variant.lastCoreTokens.isEmpty() || variant.firstCoreTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> surnamePool = new LinkedHashSet<>(preferredSurnameTokens);
        List<String> matchedSurnames = new ArrayList<>();
        for (String lt : variant.lastCoreTokens) {
            if (surnamePool.contains(lt)) {
                matchedSurnames.add(lt);
            }
        }
        if (matchedSurnames.isEmpty()) {
            return 0.0;
        }
        double surnameRatio = (double) matchedSurnames.size() / variant.lastCoreTokens.size();
        if (variant.lastCoreTokens.size() >= 2 && surnameRatio < 0.5) {
            return 0.0;
        }

        // Extreu els tokens restants del membre que NO corresponen al cognom aparellat
        List<String> remainingForFirst;
        if (hasComma && member.firstName() != null && !member.firstName().isBlank()) {
            remainingForFirst = tokenize(member.firstName(), true);
        } else {
            remainingForFirst = new ArrayList<>(memberCore);
            for (String s : matchedSurnames) {
                remainingForFirst.remove(s);
            }
        }
        if (remainingForFirst.isEmpty()) {
            return 0.0;
        }

        int firstMatches = 0;
        for (String r : remainingForFirst) {
            boolean matchesFirst = variant.firstCoreTokens.stream().anyMatch(ft ->
                    ft.equals(r)
                            || (r.length() == 1 && ft.startsWith(r))
                            || (ft.length() == 1 && r.startsWith(ft)));
            if (matchesFirst) {
                firstMatches++;
            }
        }
        if (firstMatches == 0) {
            return 0.0;
        }

        double firstRatio = (double) firstMatches / Math.max(remainingForFirst.size(), variant.firstCoreTokens.size());
        return 0.60 * surnameRatio + 0.40 * Math.max(firstRatio, 0.70);
    }

    private PersonLookupMatch toMatch(IndexedPerson person, MatchConfidence confidence, LocalDate referenceDate) {
        ContractSelection selection = selectContracts(person.contracts, referenceDate);
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
                    ContractSelection sel = selectContracts(p.contracts, referenceDate);
                    String activeEmp = sel.primaryContract != null ? sel.primaryContract.employmentType() : "Sense contracte vigent";
                    String dept = sel.primaryContract != null ? sel.primaryContract.organizationName() : "";
                    return new PersonCandidateSummary(p.uuid, p.pureId, p.displayName, activeEmp, dept);
                })
                .toList();
        return new PersonLookupMatch(MatchConfidence.AMBIGU, null, null, null, null, null, List.of(), null, summaries);
    }

    private ContractSelection selectContracts(List<ContractSnapshot> allContracts, LocalDate referenceDate) {
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
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return b.compareTo(a);
    }

    private List<ContractSnapshot> extractAllContracts(Document personDoc) {
        List<ContractSnapshot> out = new ArrayList<>();
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
        return List.copyOf(out);
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
            Document typeDoc = idDoc.get("type", Document.class);
            String typeLabel = normalize(extractTerm(typeDoc));
            String typeUri = normalize(typeDoc != null ? typeDoc.getString("uri") : "");
            boolean isLegalId = LEGAL_ID_TYPES.contains(typeLabel)
                    || typeUri.endsWith("/dni")
                    || typeUri.endsWith("/nie")
                    || typeUri.endsWith("/nif_nie_passport")
                    || typeUri.endsWith("/passport")
                    || typeLabel.contains("nif")
                    || typeLabel.contains("dni");
            if (isLegalId) {
                addNormalizedIfPresent(normalizedNifs, idDoc.getString("value"));
                addNormalizedIfPresent(normalizedNifs, idDoc.getString("id"));
            }
        }

        List<NameVariant> nameVariants = new ArrayList<>();
        addNameVariant(nameVariants, firstName, lastName);
        for (Document altName : asDocumentList(doc.get("names"))) {
            Document nested = altName.get("name", Document.class);
            Document source = nested != null ? nested : altName;
            addNameVariant(nameVariants, safeString(source.getString("firstName")), safeString(source.getString("lastName")));
        }

        Set<String> sortedTokenKeys = new LinkedHashSet<>();
        Set<String> allSurnameTokens = new LinkedHashSet<>();
        for (NameVariant v : nameVariants) {
            if (!v.fullTokens.isEmpty()) {
                sortedTokenKeys.add(joinTokens(sortedCopy(v.fullTokens)));
            }
            allSurnameTokens.addAll(v.lastCoreTokens);
        }

        List<ContractSnapshot> contracts = extractAllContracts(doc);
        return new IndexedPerson(uuid, pureId, displayName, normalizedNifs, nameVariants, sortedTokenKeys, allSurnameTokens, contracts);
    }

    private void addNormalizedIfPresent(Set<String> out, String rawVal) {
        String normVal = normalizeNif(rawVal);
        if (!normVal.isEmpty()) {
            out.add(normVal);
        }
    }

    private void addNameVariant(List<NameVariant> variants, String first, String last) {
        List<String> fullTokens = tokenize((first + " " + last).trim(), false);
        if (fullTokens.isEmpty()) {
            return;
        }
        List<String> firstCore = tokenize(first, true);
        List<String> lastCore = tokenize(last, true);
        variants.add(new NameVariant(fullTokens, firstCore, lastCore));
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

    private List<String> extractSurnameCandidates(ExtractedMember member) {
        String full = safeString(member.fullName());
        if (full.contains(",")) {
            return tokenize(full.split(",", 2)[0], true);
        }
        if (member.firstName() == null && member.lastName() != null && !member.lastName().isBlank()) {
            return tokenize(member.lastName(), true);
        }
        return tokenize(full, true);
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

    private record NameVariant(List<String> fullTokens, List<String> firstCoreTokens, List<String> lastCoreTokens) {}

    private record CachedLookupIndex(LookupIndex index, long loadedAtMs) {}

    private record LookupIndex(
            List<IndexedPerson> allPersons,
            Map<String, List<IndexedPerson>> byExactNif,
            Map<String, List<IndexedPerson>> bySortedTokens
    ) {}

    private static final class IndexedPerson {
        final String uuid;
        final Long pureId;
        final String displayName;
        final Set<String> normalizedNifs;
        final List<NameVariant> nameVariants;
        final Set<String> sortedTokenKeys;
        final Set<String> surnameTokens;
        final List<ContractSnapshot> contracts;

        IndexedPerson(
                String uuid,
                Long pureId,
                String displayName,
                Set<String> normalizedNifs,
                List<NameVariant> nameVariants,
                Set<String> sortedTokenKeys,
                Set<String> surnameTokens,
                List<ContractSnapshot> contracts) {
            this.uuid = uuid;
            this.pureId = pureId;
            this.displayName = displayName;
            this.normalizedNifs = normalizedNifs;
            this.nameVariants = nameVariants;
            this.sortedTokenKeys = sortedTokenKeys;
            this.surnameTokens = surnameTokens;
            this.contracts = contracts;
        }

        boolean matchesExactOrderedTokens(List<String> candidateOrderedTokens) {
            Set<String> candidateSet = new LinkedHashSet<>(candidateOrderedTokens);
            for (NameVariant variant : nameVariants) {
                if (variant.fullTokens.size() == candidateOrderedTokens.size()
                        && new LinkedHashSet<>(variant.fullTokens).equals(candidateSet)) {
                    return true;
                }
            }
            return false;
        }

        boolean sharesSurnameToken(List<String> targetSurnameTokens) {
            if (surnameTokens.isEmpty()) {
                return false;
            }
            for (String token : targetSurnameTokens) {
                if (token.length() > 1 && surnameTokens.contains(token)) {
                    return true;
                }
            }
            return false;
        }
    }
}
