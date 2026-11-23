package com.example.demo.plannacional.parser;

import com.example.demo.plannacional.model.ExtractedMember;
import com.example.demo.plannacional.model.ExtractedProposal;
import com.example.demo.plannacional.model.ExtractionMethod;
import com.example.demo.plannacional.model.MemberRole;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parser determinista per a formularis oficials de sol·licitud de l'AEI / MICIU.
 */
@Component
public class AeiSolicitudParser {

    private static final int RAW_PREVIEW_LIMIT = 4000;
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern PROJECT_CODE_LABEL = Pattern.compile(
            "(?im)^(?:Referencia(?:\\s+administrativa)?|C[óo]digo(?:\\s+de\\s+solicitud)?|Expediente|Codi(?:\\s+de\\s+sol[·.]licitud)?)\\s*[:\\-]\\s*([A-Z0-9][A-Z0-9\\-/]{4,})");
    private static final Pattern PROJECT_CODE_INLINE = Pattern.compile(
            "\\b((?:PID|CNS|TED|PDC|PCI|RED|CPP)\\d{4}-[A-Z0-9-]+)\\b");
    private static final Pattern ACRONYM_PATTERN = Pattern.compile(
            "(?im)^(?:Acr[óo]nimo|Acr[òo]nim|Acronym)\\s*[:\\-]\\s*(.+)$");
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "(?im)^(?:T[íi]tulo(?:\\s+del\\s+proyecto)?|T[íi]tol(?:\\s+del\\s+projecte)?)\\s*[:\\-]\\s*(.+)$");
    private static final Pattern APPLICANT_ENTITY_PATTERN = Pattern.compile(
            "(?im)^(?:Entidad\\s+solicitante|Entitat\\s+sol[·.]licitant|Organismo\\s+solicitante)\\s*[:\\-]\\s*(.+)$");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(?i)(?:Duraci[óo]n(?:\\s+del\\s+proyecto)?|Durada|Plazo\\s+de\\s+ejecuci[óo]n)\\s*(?:\\((a[ñn]os?|anys?|meses|mesos)\\))?\\s*[:\\-]?\\s*(\\d{1,2})\\s*(a[ñn]os?|anys?|meses|mesos)?");
    private static final Pattern START_DATE_PATTERN = Pattern.compile(
            "(?im)^(?:Fecha\\s+(?:de\\s+)?inicio(?:\\s+prevista)?|Data\\s+d['’]?inici(?:\\s+prevista)?)\\s*[:\\-]\\s*(\\d{2}[/-]\\d{2}[/-]\\d{4}|\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern END_DATE_PATTERN = Pattern.compile(
            "(?im)^(?:Fecha\\s+(?:de\\s+)?fin(?:alizaci[óo]n)?(?:\\s+prevista)?|Data\\s+de\\s+fi(?:\\s+prevista)?)\\s*[:\\-]\\s*(\\d{2}[/-]\\d{2}[/-]\\d{4}|\\d{4}-\\d{2}-\\d{2})");

    private static final Pattern PAGE_NOISE_LINE = Pattern.compile(
            "(?i)^(?:[-\\s]*(?:p[áa]g(?:ina)?\\.?\\s*\\d+(?:\\s*(?:de|/)\\s*\\d+)?|ministerio\\s+de\\s+ciencia[^\\n]*|agencia\\s+estatal\\s+de\\s+investigaci[óo]n[^\\n]*)[-\\s]*)$");
    private static final Pattern INLINE_NIF_SEARCH = Pattern.compile(
            "(?i)(?:\\b\\d{1,3}(?:\\.\\d{3}){2}[-]?[A-Z]\\b|\\b\\d{7,8}[-]?[A-Z]\\b|\\b[XYZ][-]?\\d{7}[-]?[A-Z]\\b|\\b[A-Z]{2,3}\\d{5,9}\\b|(?:[0-9A-Z]*\\*{2,}[0-9A-Z*]*))");
    private static final Pattern LEADING_ROW_INDEX = Pattern.compile("^\\d+[.)]?\\s+");

    private static final DateTimeFormatter DMY_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DMY_DASH = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public ExtractedProposal parse(String fileName, String rawText) {
        String safeText = rawText != null ? rawText.replace("\r\n", "\n").replace('\r', '\n') : "";
        String projectCode = extractProjectCode(safeText);
        String acronym = extractFirstGroup(ACRONYM_PATTERN, safeText);
        String title = extractFirstGroup(TITLE_PATTERN, safeText);
        String applicantEntity = extractFirstGroup(APPLICANT_ENTITY_PATTERN, safeText);
        if (applicantEntity == null) {
            applicantEntity = extractFirstGroup(Pattern.compile("(?im)^\\s*Entidad\\s*:\\s*(.+)$"), safeText);
        }
        Integer durationYears = extractDurationYears(safeText);
        LocalDate expectedStart = extractDate(START_DATE_PATTERN, safeText);
        LocalDate expectedEnd = extractDate(END_DATE_PATTERN, safeText);

        List<ExtractedMember> members = extractMembers(safeText);
        List<String> warnings = new ArrayList<>();
        if (members.isEmpty()) {
            warnings.add("No s'han detectat membres de l'equip amb el parser estàndard AEI.");
        }

        String preview = safeText.length() > RAW_PREVIEW_LIMIT
                ? safeText.substring(0, RAW_PREVIEW_LIMIT)
                : safeText;

        return new ExtractedProposal(
                fileName,
                projectCode,
                acronym,
                title,
                durationYears,
                expectedStart,
                expectedEnd,
                applicantEntity,
                ExtractionMethod.AEI_PARSER,
                warnings,
                preview,
                members
        );
    }

    // ---- Format oficial del formulari de la seu electrònica (seccions numerades 6/7/8) ----
    private static final Pattern OFFICIAL_ROLE_HEADING = Pattern.compile(
            "^(\\d{1,2})\\.\\s+(Investigador(?:/a|a|es)?\\s+Principal(?:es)?|Equipo\\s+de\\s+investigaci[óo]n|Equipo\\s+de\\s+trabajo)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFICIAL_ANY_HEADING = Pattern.compile("^(\\d{1,2})\\.\\s+\\S.{0,80}$");
    private static final Pattern OFFICIAL_NAME_LINE = Pattern.compile(
            "^Nombre\\s*:\\s*(.+?)\\s+Apellidos\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFICIAL_DOC_LINE = Pattern.compile(
            "Documento\\s*:\\s*([A-Za-z0-9*.\\-]+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFICIAL_ENTITY_LINE = Pattern.compile(
            "^Entidad\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Parser del formulari oficial: cada persona és un bloc amb «Nombre: X Apellidos: Y»,
     * «Tipo de documento: NIF Documento: Z» i «Entidad: …» (aquesta darrera pot anar abans del nom).
     * Les línies «Firma del/de la investigador/a» i les pàgines de CV no tanquen la secció.
     */
    private List<ExtractedMember> extractOfficialFormMembers(String text) {
        Map<String, ExtractedMember> result = new LinkedHashMap<>();
        MemberRole role = null;
        int sectionNumber = -1;
        OfficialRecord rec = new OfficialRecord();

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim().replaceAll("\\s+", " ");
            if (line.isEmpty() || PAGE_NOISE_LINE.matcher(line).matches()
                    || line.contains("Página ") && line.matches(".*Página \\d+ de \\d+.*")) {
                continue;
            }

            Matcher heading = OFFICIAL_ROLE_HEADING.matcher(line);
            if (heading.matches()) {
                flushOfficial(rec, role, result);
                sectionNumber = Integer.parseInt(heading.group(1));
                String h = normalizeUpper(heading.group(2));
                role = h.startsWith("INVESTIGADOR") ? MemberRole.IP
                        : h.contains("INVESTIGACION") ? MemberRole.EQUIPO_INVESTIGACION
                        : MemberRole.EQUIPO_TRABAJO;
                continue;
            }
            if (role != null) {
                Matcher any = OFFICIAL_ANY_HEADING.matcher(line);
                if (any.matches() && Integer.parseInt(any.group(1)) == sectionNumber + 1) {
                    flushOfficial(rec, role, result);
                    role = null;
                    continue;
                }
            }
            if (role == null) {
                continue;
            }

            String upper = normalizeUpper(line);
            if (upper.startsWith("ENTIDAD A LA QUE PERTENECE") || upper.startsWith("MIEMBRO DEL EQUIPO")) {
                if (rec.hasName()) {
                    flushOfficial(rec, role, result);
                }
                continue;
            }

            Matcher nm = OFFICIAL_NAME_LINE.matcher(line);
            if (nm.find()) {
                if (rec.hasName()) {
                    flushOfficial(rec, role, result);
                }
                rec.firstName = nm.group(1).trim();
                rec.lastName = nm.group(2).trim();
                rec.raw.add(line);
                continue;
            }
            Matcher doc = OFFICIAL_DOC_LINE.matcher(line);
            if (doc.find() && rec.nif == null && upper.contains("DOCUMENTO")) {
                rec.nif = cleanNif(doc.group(1));
                rec.raw.add(line);
                continue;
            }
            Matcher ent = OFFICIAL_ENTITY_LINE.matcher(line);
            if (ent.matches() && rec.entity == null) {
                rec.entity = ent.group(1).trim();
                rec.raw.add(line);
                continue;
            }
            if (upper.startsWith("CATEGORIA PROFESIONAL") && line.contains(":") ) {
                rec.category = line.substring(line.indexOf(':') + 1).trim();
            } else if (upper.startsWith("CATEGORIA PROFESIONAL")) {
                rec.category = line.substring("Categoría profesional".length()).trim();
            } else if (line.contains("presentarla en solicitud)")) {
                String v = line.substring(line.indexOf("presentarla en solicitud)") + "presentarla en solicitud)".length());
                rec.linkage = v.replaceFirst("^\\s*:?\\s*", "").trim();
            }
        }
        flushOfficial(rec, role, result);
        return new ArrayList<>(result.values());
    }

    private void flushOfficial(OfficialRecord rec, MemberRole role, Map<String, ExtractedMember> result) {
        if (role != null && rec.hasName()) {
            NameParts np = splitName(null, rec.firstName, rec.lastName);
            String linkage = Stream.of(rec.category, rec.linkage)
                    .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" · "));
            addDeduplicated(result, new ExtractedMember(role, np.fullName(), np.firstName(), np.lastName(),
                    rec.nif, rec.entity, linkage.isEmpty() ? null : linkage, String.join(" | ", rec.raw)));
        }
        rec.reset();
    }

    private static final class OfficialRecord {
        String firstName;
        String lastName;
        String nif;
        String entity;
        String category;
        String linkage;
        final List<String> raw = new ArrayList<>();

        boolean hasName() {
            return firstName != null && lastName != null;
        }

        void reset() {
            firstName = lastName = nif = entity = category = linkage = null;
            raw.clear();
        }
    }

    private List<ExtractedMember> extractMembers(String text) {
        List<ExtractedMember> official = extractOfficialFormMembers(text);
        if (!official.isEmpty()) {
            return official;
        }
        return extractGenericMembers(text);
    }

    private List<ExtractedMember> extractGenericMembers(String text) {
        Map<String, ExtractedMember> deduplicated = new LinkedHashMap<>();
        MemberRole currentRole = null;
        MemberBlockBuilder block = new MemberBlockBuilder();

        String[] lines = text.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || PAGE_NOISE_LINE.matcher(line).matches()) {
                continue;
            }

            SectionMatch section = detectSectionHeader(line);
            if (section == SectionMatch.END_OF_TEAMS) {
                flushBlockIfReady(block, currentRole, deduplicated);
                currentRole = null;
                continue;
            }
            if (section != null && section.role != null) {
                flushBlockIfReady(block, currentRole, deduplicated);
                currentRole = section.role;
                if (section.trailingRemainder != null && !section.trailingRemainder.isBlank()) {
                    parseMemberContentLine(section.trailingRemainder, currentRole, block, deduplicated);
                }
                continue;
            }

            if (currentRole == null) {
                continue;
            }

            parseMemberContentLine(line, currentRole, block, deduplicated);
        }

        flushBlockIfReady(block, currentRole, deduplicated);
        return new ArrayList<>(deduplicated.values());
    }

    private void parseMemberContentLine(
            String line,
            MemberRole role,
            MemberBlockBuilder block,
            Map<String, ExtractedMember> deduplicated) {
        if (isTableHeaderLine(line)) {
            return;
        }

        if (tryConsumeKeyValueLine(line, role, block, deduplicated)) {
            return;
        }

        // Si hi havia un bloc key-value en curs i arriba una fila delimitada, tanquem el bloc primer
        flushBlockIfReady(block, role, deduplicated);

        ExtractedMember parsedRow = tryParseDelimitedRow(line, role);
        if (parsedRow != null) {
            addDeduplicated(deduplicated, parsedRow);
        }
    }

    private boolean tryConsumeKeyValueLine(
            String line,
            MemberRole role,
            MemberBlockBuilder block,
            Map<String, ExtractedMember> deduplicated) {
        int colon = line.indexOf(':');
        if (colon <= 0 || colon > 35) {
            return false;
        }
        String label = normalizeUpper(line.substring(0, colon));
        String value = line.substring(colon + 1).trim();
        if (value.isEmpty()) {
            return false;
        }

        if (label.startsWith("APELLIDOS Y NOMBRE") || label.startsWith("NOMBRE Y APELLIDOS") || label.startsWith("NOM I COGNOMS")) {
            flushBlockIfReady(block, role, deduplicated);
            block.fullName = value;
            block.rawLines.add(line);
            return true;
        }
        if (label.equals("APELLIDOS") || label.equals("COGNOMS")) {
            if (block.lastName != null || block.fullName != null) {
                flushBlockIfReady(block, role, deduplicated);
            }
            block.lastName = value;
            block.rawLines.add(line);
            return true;
        }
        if (label.equals("NOMBRE") || label.equals("NOM")) {
            if (block.firstName != null) {
                flushBlockIfReady(block, role, deduplicated);
            }
            block.firstName = value;
            block.rawLines.add(line);
            return true;
        }
        if (label.contains("NIF") || label.contains("DNI") || label.contains("NIE") || label.contains("PASAPORTE") || label.contains("PASSAPORT")) {
            block.nif = cleanNif(value);
            block.rawLines.add(line);
            return true;
        }
        if (label.contains("ENTIDAD") || label.contains("ENTITAT") || label.contains("UNIVERSIDAD") || label.contains("UNIVERSITAT") || label.contains("CENTRO") || label.contains("ORGANISMO")) {
            block.entity = value;
            block.rawLines.add(line);
            return true;
        }
        if (label.contains("VINCULACION") || label.contains("VINCULACIO") || label.contains("CATEGORIA") || label.contains("CUERPO") || label.contains("SITUACION")) {
            block.declaredLinkage = value;
            block.rawLines.add(line);
            return true;
        }
        return false;
    }

    private void flushBlockIfReady(
            MemberBlockBuilder block,
            MemberRole role,
            Map<String, ExtractedMember> deduplicated) {
        if (role != null && block.hasName()) {
            ExtractedMember built = block.build(role);
            if (built != null) {
                addDeduplicated(deduplicated, built);
            }
        }
        block.reset();
    }

    private ExtractedMember tryParseDelimitedRow(String line, MemberRole role) {
        String[] parts = null;
        if (line.contains("|")) {
            parts = line.split("\\s*\\|\\s*");
        } else if (line.contains(";")) {
            parts = line.split("\\s*;\\s*");
        } else if (line.contains("\t")) {
            parts = line.split("\\t+");
        } else if (line.contains(" - ")) {
            parts = line.split("\\s+-\\s+");
        } else if (line.matches(".*\\s{2,}.*")) {
            parts = line.split("\\s{2,}");
        }

        if (parts != null && parts.length >= 2) {
            return buildMemberFromColumns(parts, role, line);
        }

        // Línia sense separadors explícits però amb NIF incrustat (p. ex. còpia de taula amb un espai)
        Matcher m = INLINE_NIF_SEARCH.matcher(line);
        if (m.find() && isValidNifCandidate(m.group())) {
            String nif = cleanNif(m.group());
            String before = stripLeadingIndex(line.substring(0, m.start()).trim().replaceAll("[,;\\-]+$", "").trim());
            String after = line.substring(m.end()).trim().replaceAll("^[,;\\-]+", "").trim();
            if (looksLikePersonName(before)) {
                NameParts np = splitName(before, null, null);
                String entity = after.isEmpty() ? null : after;
                return new ExtractedMember(role, np.fullName, np.firstName, np.lastName, nif, entity, null, line);
            }
        }
        return null;
    }

    private ExtractedMember buildMemberFromColumns(String[] rawCols, MemberRole role, String rawLine) {
        List<String> cols = Arrays.stream(rawCols)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (cols.isEmpty()) {
            return null;
        }

        String nameCandidate = null;
        String nif = null;
        List<String> remaining = new ArrayList<>();

        for (String col : cols) {
            String compact = col.replaceAll("[\\s]", "");
            if (nif == null && isValidNifCandidate(compact)) {
                nif = cleanNif(col);
            } else if (nameCandidate == null) {
                String strippedCol = stripLeadingIndex(col);
                if (!strippedCol.matches("^\\d+$") && looksLikePersonName(strippedCol)) {
                    nameCandidate = strippedCol;
                } else if (!col.matches("^\\d+$")) {
                    remaining.add(col);
                }
            } else if (!col.matches("^\\d+$")) {
                remaining.add(col);
            }
        }

        if (nameCandidate == null) {
            return null;
        }
        NameParts np = splitName(nameCandidate, null, null);
        String entity = !remaining.isEmpty() ? remaining.get(0) : null;
        String linkage = remaining.size() > 1 ? remaining.get(1) : null;
        return new ExtractedMember(role, np.fullName, np.firstName, np.lastName, nif, entity, linkage, rawLine);
    }

    private boolean isValidNifCandidate(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String compact = raw.trim().replace(" ", "");
        if (compact.contains("*")) {
            return compact.length() >= 6 && compact.replace("*", "").length() >= 3
                    && compact.matches("(?i)[0-9A-Z*]+");
        }
        String strippedDots = compact.replace(".", "");
        return strippedDots.matches("(?i)(?:\\d{7,8}[-]?[A-Z]|[XYZ][-]?\\d{7}[-]?[A-Z]|[A-Z]{2,3}\\d{5,9})");
    }

    private String stripLeadingIndex(String value) {
        return value == null ? "" : LEADING_ROW_INDEX.matcher(value).replaceFirst("").trim();
    }

    private void addDeduplicated(Map<String, ExtractedMember> map, ExtractedMember candidate) {
        String key = buildDeduplicationKey(candidate);
        ExtractedMember existing = map.get(key);
        if (existing == null || rolePriority(candidate.role()) < rolePriority(existing.role())) {
            map.put(key, candidate);
        }
    }

    private String buildDeduplicationKey(ExtractedMember m) {
        if (m.nif() != null && !m.nif().isBlank() && !m.nif().contains("*")) {
            return "NIF:" + m.nif().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        }
        String normName = normalizeUpper(m.fullName()).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return "NAME:" + normName;
    }

    private int rolePriority(MemberRole role) {
        return switch (role) {
            case IP -> 0;
            case EQUIPO_INVESTIGACION -> 1;
            case EQUIPO_TRABAJO -> 2;
        };
    }

    private SectionMatch detectSectionHeader(String line) {
        String upper = normalizeUpper(line);
        if (upper.startsWith("OBSERVACIONES") || upper.startsWith("PRESUPUESTO")
                || upper.startsWith("RESUMEN DEL PRESUPUESTO") || upper.startsWith("MEMORIA")
                || upper.startsWith("FIRMA")) {
            return SectionMatch.END_OF_TEAMS;
        }
        // Evitar confondre una fila tabular, línia amb NIF o camp key-value amb una capçalera de secció
        if (line.contains("|") || line.contains(";") || line.contains("\t") || line.contains(" - ")
                || isMemberFieldPrefix(upper)
                || hasInlineNif(line)) {
            return null;
        }

        String trailing = null;
        int colon = line.indexOf(':');
        if (colon > 0 && colon < line.length() - 1) {
            trailing = line.substring(colon + 1).trim();
        }

        if (upper.contains("INVESTIGADOR/A PRINCIPAL")
                || upper.contains("INVESTIGADOR PRINCIPAL")
                || upper.contains("INVESTIGADORA PRINCIPAL")
                || upper.contains("CO-INVESTIGADOR")
                || upper.matches("^(?:\\d+\\.?\\s*)?(?:IP\\s*[12]|\\(IP[12]\\)).*")) {
            return new SectionMatch(MemberRole.IP, trailing);
        }
        if (upper.contains("EQUIPO DE INVESTIGACION") || upper.contains("EQUIP D'INVESTIGACIO")) {
            return new SectionMatch(MemberRole.EQUIPO_INVESTIGACION, trailing);
        }
        if (upper.contains("EQUIPO DE TRABAJO") || upper.contains("EQUIP DE TREBALL")) {
            return new SectionMatch(MemberRole.EQUIPO_TRABAJO, trailing);
        }
        return null;
    }

    private boolean isMemberFieldPrefix(String upperLine) {
        int colon = upperLine.indexOf(':');
        if (colon <= 0 || colon > 35) {
            return false;
        }
        String label = upperLine.substring(0, colon);
        return label.startsWith("APELLIDOS") || label.startsWith("COGNOMS")
                || label.startsWith("NOMBRE") || label.equals("NOM") || label.startsWith("NOM I COGNOMS")
                || label.contains("NIF") || label.contains("DNI") || label.contains("NIE")
                || label.contains("PASAPORTE") || label.contains("PASSAPORT")
                || label.contains("ENTIDAD") || label.contains("ENTITAT")
                || label.contains("UNIVERSIDAD") || label.contains("UNIVERSITAT")
                || label.contains("CENTRO") || label.contains("ORGANISMO")
                || label.contains("VINCULACION") || label.contains("VINCULACIO")
                || label.contains("CATEGORIA") || label.contains("CUERPO") || label.contains("SITUACION");
    }

    private boolean hasInlineNif(String line) {
        Matcher m = INLINE_NIF_SEARCH.matcher(line);
        while (m.find()) {
            if (isValidNifCandidate(m.group())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTableHeaderLine(String line) {
        String upper = normalizeUpper(line);
        return (upper.contains("APELLIDOS") && upper.contains("NIF"))
                || (upper.contains("NOMBRE") && upper.contains("ENTIDAD"))
                || (upper.contains("COGNOMS") && upper.contains("NIF"));
    }

    private boolean looksLikePersonName(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 4 || trimmed.length() > 90) {
            return false;
        }
        if (trimmed.matches(".*\\d.*")) {
            return false;
        }
        String upper = normalizeUpper(trimmed);
        return !upper.equals("UAB")
                && !upper.startsWith("UNIVERSITAT")
                && !upper.startsWith("UNIVERSIDAD")
                && trimmed.split("\\s+").length >= 2;
    }

    private String extractProjectCode(String text) {
        String labeled = extractFirstGroup(PROJECT_CODE_LABEL, text);
        if (labeled != null) {
            return labeled;
        }
        Matcher m = PROJECT_CODE_INLINE.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private Integer extractDurationYears(String text) {
        Matcher m = DURATION_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        int qty = Integer.parseInt(m.group(2));
        String unitRaw = m.group(3) != null ? m.group(3) : (m.group(1) != null ? m.group(1) : "años");
        String unit = normalizeUpper(unitRaw);
        if (unit.startsWith("MES")) {
            return qty >= 12 ? Math.max(1, Math.round(qty / 12.0f)) : 1;
        }
        return qty;
    }

    private LocalDate extractDate(Pattern pattern, String text) {
        String raw = extractFirstGroup(pattern, text);
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter fmt : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DMY_SLASH, DMY_DASH)) {
            try {
                return LocalDate.parse(raw.trim(), fmt);
            } catch (DateTimeParseException ignored) {
                // Prova següent format
            }
        }
        return null;
    }

    private String extractFirstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String value = m.group(1);
            return value != null && !value.isBlank() ? value.trim() : null;
        }
        return null;
    }

    private static NameParts splitName(String fullName, String firstName, String lastName) {
        if (lastName != null && firstName != null) {
            String full = lastName.trim() + ", " + firstName.trim();
            return new NameParts(full, firstName.trim(), lastName.trim());
        }
        String cleanFull = fullName != null ? fullName.trim() : "";
        if (cleanFull.contains(",")) {
            String[] parts = cleanFull.split(",", 2);
            return new NameParts(cleanFull, parts[1].trim(), parts[0].trim());
        }
        String[] tokens = cleanFull.split("\\s+");
        if (tokens.length >= 2) {
            String fn = tokens[0];
            String ln = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
            return new NameParts(cleanFull, fn, ln);
        }
        return new NameParts(cleanFull, firstName, lastName);
    }

    private static String cleanNif(String raw) {
        return raw == null ? null : raw.trim().replaceAll("\\s+", "");
    }

    private static String normalizeUpper(String input) {
        if (input == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toUpperCase(Locale.ROOT).trim();
    }

    private record NameParts(String fullName, String firstName, String lastName) {}

    private static final class SectionMatch {
        static final SectionMatch END_OF_TEAMS = new SectionMatch(null, null);
        final MemberRole role;
        final String trailingRemainder;

        SectionMatch(MemberRole role, String trailingRemainder) {
            this.role = role;
            this.trailingRemainder = trailingRemainder;
        }
    }

    private static final class MemberBlockBuilder {
        String fullName;
        String firstName;
        String lastName;
        String nif;
        String entity;
        String declaredLinkage;
        final List<String> rawLines = new ArrayList<>();

        boolean hasName() {
            return (fullName != null && !fullName.isBlank())
                    || (lastName != null && !lastName.isBlank() && firstName != null && !firstName.isBlank());
        }

        ExtractedMember build(MemberRole role) {
            NameParts np = splitName(fullName, firstName, lastName);
            if (np.fullName().isBlank()) {
                return null;
            }
            return new ExtractedMember(role, np.fullName(), np.firstName(), np.lastName(), nif, entity, declaredLinkage, String.join(" | ", rawLines));
        }

        void reset() {
            fullName = null;
            firstName = null;
            lastName = null;
            nif = null;
            entity = null;
            declaredLinkage = null;
            rawLines.clear();
        }
    }
}
