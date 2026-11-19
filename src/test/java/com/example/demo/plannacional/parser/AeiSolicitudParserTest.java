package com.example.demo.plannacional.parser;

import com.example.demo.plannacional.model.ExtractedMember;
import com.example.demo.plannacional.model.ExtractedProposal;
import com.example.demo.plannacional.model.ExtractionMethod;
import com.example.demo.plannacional.model.MemberRole;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AeiSolicitudParserTest {

    private final PdfBoxTextExtractor pdfExtractor = new PdfBoxTextExtractor();
    private final AeiSolicitudParser parser = new AeiSolicitudParser();

    private byte[] createPdfWithLines(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (lines.length > 0) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.newLineAtOffset(40, 740);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void pdfBoxExtractsTextFromValidInMemoryPdf() throws Exception {
        byte[] pdf = createPdfWithLines(
                "SOLICITUD DE PROYECTO PLAN NACIONAL PID2025-123456OB-I00",
                "INVESTIGADOR PRINCIPAL (IP1): GARCIA LOPEZ, JOAN - 12345678A - Universitat Autonoma de Barcelona",
                "EQUIPO DE INVESTIGACION: Puig Soler, Anna - 87654321B - UAB"
        );
        String text = pdfExtractor.extractText(pdf);
        assertNotNull(text);
        assertTrue(text.contains("PID2025-123456OB-I00"));
        assertTrue(text.contains("GARCIA LOPEZ, JOAN"));
    }

    @Test
    void pdfBoxRejectsEmptyOrNonPdfBytesWithInvalidPdfException() {
        assertThrows(InvalidPdfException.class, () -> pdfExtractor.extractText(null));
        assertThrows(InvalidPdfException.class, () -> pdfExtractor.extractText(new byte[0]));
        assertThrows(InvalidPdfException.class, () -> pdfExtractor.extractText("Aixo no es un PDF".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pdfBoxDetectsScannedPdfWithoutTextWithScannedPdfException() throws Exception {
        byte[] blankPdf = createPdfWithLines();
        assertThrows(ScannedPdfException.class, () -> pdfExtractor.extractText(blankPdf));
    }

    @Test
    void parseExtractsAeiHeaderIpEiEtAndExternalMembers() {
        String longFiller = "A".repeat(4500);
        String sampleText = """
                MINISTERIO DE CIENCIA, INNOVACIÓN Y UNIVERSIDADES - AGENCIA ESTATAL DE INVESTIGACIÓN
                Referencia: PID2025-123456OB-I00
                Acrónimo: BIOMED-AI
                Título del proyecto: Inteligencia artificial aplicada a biomarcadores clínicos
                Entidad solicitante: Universitat Autònoma de Barcelona
                Duración: 4 años
                Fecha de inicio prevista: 01/09/2026
                Fecha de fin prevista: 31/08/2030

                1. INVESTIGADOR/A PRINCIPAL (IP1)
                GARCÍA LÓPEZ, JOAN | 12345678A | Universitat Autònoma de Barcelona | Funcionario
                2. CO-INVESTIGADOR/A PRINCIPAL (IP2)
                María Puig Soler | ***4567** | UAB | Contratado doctor

                3. EQUIPO DE INVESTIGACIÓN
                Apellidos: Martínez Vila
                Nombre: Pere
                NIF/NIE: X1234567L
                Entidad: Universitat Autònoma de Barcelona
                Vinculación: Lector

                RODRÍGUEZ CANO, ELENA | PAS998877 | CSIC | Investigador científico

                4. EQUIPO DE TRABAJO
                SOLER PRAT, LAIA | 44556677K | UAB | Predoctoral FPI

                Observaciones: %s
                """.formatted(longFiller);

        ExtractedProposal proposal = parser.parse("solicitud_biomed.pdf", sampleText);

        assertEquals("solicitud_biomed.pdf", proposal.fileName());
        assertEquals("PID2025-123456OB-I00", proposal.projectCode());
        assertEquals("BIOMED-AI", proposal.acronym());
        assertEquals("Inteligencia artificial aplicada a biomarcadores clínicos", proposal.title());
        assertEquals("Universitat Autònoma de Barcelona", proposal.applicantEntity());
        assertEquals(4, proposal.durationYears());
        assertEquals(LocalDate.of(2026, 9, 1), proposal.expectedStartDate());
        assertEquals(LocalDate.of(2030, 8, 31), proposal.expectedEndDate());
        assertEquals(ExtractionMethod.AEI_PARSER, proposal.extractionMethod());
        assertTrue(proposal.rawTextPreview().length() <= 4000);

        assertEquals(5, proposal.members().size());
        ExtractedMember ip1 = proposal.members().get(0);
        assertEquals(MemberRole.IP, ip1.role());
        assertEquals("12345678A", ip1.nif());
        assertEquals("GARCÍA LÓPEZ", ip1.lastName());
        assertEquals("JOAN", ip1.firstName());

        ExtractedMember ip2 = proposal.members().get(1);
        assertEquals(MemberRole.IP, ip2.role());
        assertEquals("***4567**", ip2.nif());

        ExtractedMember ei1 = proposal.members().get(2);
        assertEquals(MemberRole.EQUIPO_INVESTIGACION, ei1.role());
        assertEquals("X1234567L", ei1.nif());
        assertEquals("Martínez Vila", ei1.lastName());
        assertEquals("Pere", ei1.firstName());

        ExtractedMember ei2 = proposal.members().get(3);
        assertEquals(MemberRole.EQUIPO_INVESTIGACION, ei2.role());
        assertEquals("CSIC", ei2.entity());

        ExtractedMember et1 = proposal.members().get(4);
        assertEquals(MemberRole.EQUIPO_TRABAJO, et1.role());
        assertEquals("44556677K", et1.nif());
    }

    @Test
    void parseHandlesPageBreaksRepeatedHeadersAndDeduplicatesByHighestRole() {
        String textWithPageBreaks = """
                Código de solicitud: PID2025-999888AA-I00
                Acrónimo: CLEAN-DATA
                Entidad solicitante: UAB
                Duración: 3 años

                INVESTIGADOR/A PRINCIPAL
                GARCÍA LÓPEZ, JOAN | 12345678A | UAB | Catedrático

                MINISTERIO DE CIENCIA E INNOVACIÓN
                Página 2 de 5
                EQUIPO DE INVESTIGACIÓN
                GARCÍA LÓPEZ, JOAN | 12345678A | UAB | Catedrático
                VALLS MAS, MARC | 22334455C | UAB | Titular
                --- Página 3 de 5 ---
                SOLER FONT, NÚRIA | 33445566D | UAB | Agregada

                EQUIPO DE TRABAJO
                VALLS MAS, MARC | 22334455C | UAB | Titular
                PRAT ROIG, PAU | 55667788E | UAB | FPI
                """;

        ExtractedProposal proposal = parser.parse("paginat.pdf", textWithPageBreaks);

        // GARCÍA LÓPEZ només apareix una vegada amb rol IP; VALLS MAS una vegada amb EI
        assertEquals(4, proposal.members().size());
        assertEquals(MemberRole.IP, proposal.members().stream()
                .filter(m -> "12345678A".equals(m.nif())).findFirst().orElseThrow().role());
        assertEquals(MemberRole.EQUIPO_INVESTIGACION, proposal.members().stream()
                .filter(m -> "22334455C".equals(m.nif())).findFirst().orElseThrow().role());
        assertTrue(proposal.members().stream().anyMatch(m -> "33445566D".equals(m.nif())));
        assertTrue(proposal.members().stream().anyMatch(m -> "55667788E".equals(m.nif()) && m.role() == MemberRole.EQUIPO_TRABAJO));
    }

    @Test
    void parseReturnsEmptyMembersWithWarningWhenNoTeamSectionPresent() {
        ExtractedProposal proposal = parser.parse("buit.pdf", "Resumen ejecutivo del proyecto sin tablas de personal.");
        assertTrue(proposal.members().isEmpty());
        assertFalse(proposal.warnings().isEmpty());
    }

    @Test
    void parsePreservesKeyValueMemberAcrossBlankLinesAtPageBreak() {
        String splitAcrossPageBreak = """
                Referencia: PID2025-888111OB-I00
                INVESTIGADOR/A PRINCIPAL
                Apellidos: Vila Mas

                MINISTERIO DE CIENCIA E INNOVACIÓN
                Página 2 de 4

                Nombre: Pere

                NIF/NIE: 11223344M

                Entidad: Universitat Autònoma de Barcelona
                Vinculación: Titular de universidad
                """;
        ExtractedProposal proposal = parser.parse("split.pdf", splitAcrossPageBreak);
        assertEquals(1, proposal.members().size());
        ExtractedMember m = proposal.members().get(0);
        assertEquals(MemberRole.IP, m.role());
        assertEquals("Pere", m.firstName());
        assertEquals("Vila Mas", m.lastName());
        assertEquals("11223344M", m.nif());
        assertEquals("Universitat Autònoma de Barcelona", m.entity());
    }

    @Test
    void parseHandlesDottedNifsLowercaseNieMidMaskedAndNumberedSingleSpaceRows() {
        String messyRows = """
                Referencia: PID2025-888222OB-I00
                INVESTIGADOR/A PRINCIPAL
                1 GARCÍA LÓPEZ, JOAN 12.345.678-a UAB

                EQUIPO DE INVESTIGACIÓN
                GARCÍA LÓPEZ, JOAN | 12345678A | UAB | Catedrático
                2 Perez Vila, Anna x-1234567-l UAB
                3 Soler Mas, Pere | 123****8A | UAB | Lector
                """;
        ExtractedProposal proposal = parser.parse("messy.pdf", messyRows);

        assertEquals(3, proposal.members().size());
        ExtractedMember joan = proposal.members().stream()
                .filter(m -> m.fullName().contains("GARCÍA"))
                .findFirst().orElseThrow();
        assertEquals(MemberRole.IP, joan.role());
        assertEquals("12345678A", joan.nif().replaceAll("[^A-Z0-9a-z]", "").toUpperCase());

        ExtractedMember anna = proposal.members().stream()
                .filter(m -> m.fullName().contains("Anna"))
                .findFirst().orElseThrow();
        assertEquals(MemberRole.EQUIPO_INVESTIGACION, anna.role());
        assertEquals("x-1234567-l", anna.nif());

        ExtractedMember pere = proposal.members().stream()
                .filter(m -> m.fullName().contains("Soler Mas, Pere"))
                .findFirst().orElseThrow();
        assertEquals("123****8A", pere.nif());
        assertEquals("UAB", pere.entity());
    }

    @Test
    void parseIgnoresRoleKeywordsInsideLinkageOrSemicolonRowsAndDetectsFemaleIpHeader() {
        String text = """
                Referencia: PID2025-888333OB-I00
                INVESTIGADORA PRINCIPAL (IP1)
                Apellidos: Serra Font
                Nombre: Marta
                NIF/NIE: 99887766Z
                Entidad: UAB
                Vinculación: Co-investigador principal equipo de trabajo

                Apellidos: Camps Roig
                Nombre: Oriol
                NIF/NIE: 55443322P
                Entidad: UAB

                EQUIPO DE INVESTIGACIÓN
                Puig, Laura; 44556677K; UAB; Investigador principal
                Valls, Marc; 33445566N; UAB; Titular
                """;
        ExtractedProposal proposal = parser.parse("female_ip.pdf", text);

        assertEquals(4, proposal.members().size());
        assertEquals(MemberRole.IP, proposal.members().get(0).role());
        assertEquals(MemberRole.IP, proposal.members().get(1).role());
        assertEquals(MemberRole.EQUIPO_INVESTIGACION, proposal.members().get(2).role());
        assertEquals(MemberRole.EQUIPO_INVESTIGACION, proposal.members().get(3).role());
    }

    @Test
    void vllmFallbackParsesStructuredJsonResponseAndFlagsMethod() {
        VllmProposalFallbackParser fallback = new VllmProposalFallbackParser(true, "https://localhost:9999/v1/chat/completions", "test-model", "", 1000);
        String openAiResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"projectCode\\":\\"PID2025-777\\",\\"acronym\\":\\"AI-LAB\\",\\"title\\":\\"Projecte Fallback\\",\\"durationYears\\":3,\\"applicantEntity\\":\\"UAB\\",\\"members\\":[{\\"role\\":\\"IP\\",\\"fullName\\":\\"Joan Garcia\\",\\"firstName\\":\\"Joan\\",\\"lastName\\":\\"Garcia\\",\\"nif\\":\\"12345678A\\",\\"entity\\":\\"UAB\\"}]}"
                      }
                    }
                  ]
                }
                """;

        Optional<ExtractedProposal> parsed = fallback.parseVllmJson("alt.pdf", "text brut", openAiResponse);
        assertTrue(parsed.isPresent());
        ExtractedProposal p = parsed.get();
        assertEquals(ExtractionMethod.VLLM_FALLBACK, p.extractionMethod());
        assertEquals("PID2025-777", p.projectCode());
        assertEquals(1, p.members().size());
        assertEquals(MemberRole.IP, p.members().get(0).role());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("Extret amb IA")));
    }

    @Test
    void vllmFallbackReturnsEmptyGracefullyWhenDisabledOrUnreachable() {
        VllmProposalFallbackParser disabled = new VllmProposalFallbackParser(false, "http://127.0.0.1:1/v1/chat/completions", "m", "", 500);
        assertTrue(disabled.parseFallback("a.pdf", "text").isEmpty());

        VllmProposalFallbackParser unreachable = new VllmProposalFallbackParser(true, "http://127.0.0.1:1/v1/chat/completions", "m", "", 500);
        assertTrue(unreachable.parseFallback("a.pdf", "text").isEmpty());
    }
}
