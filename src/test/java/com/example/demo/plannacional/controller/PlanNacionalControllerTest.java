package com.example.demo.plannacional.controller;

import com.example.demo.plannacional.engine.PlanNacionalEligibilityEngine;
import com.example.demo.plannacional.lookup.PersonPdiLookupService;
import com.example.demo.plannacional.model.*;
import com.example.demo.plannacional.parser.*;
import com.example.demo.plannacional.service.PlanNacionalValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PlanNacionalControllerTest {

    @Mock
    private PdfTextExtractor pdfTextExtractor;

    @Mock
    private VllmProposalFallbackParser vllmFallbackParser;

    @Mock
    private PersonPdiLookupService lookupService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        PlanNacionalValidationService service = new PlanNacionalValidationService(
                pdfTextExtractor,
                new AeiSolicitudParser(),
                vllmFallbackParser,
                lookupService,
                new PlanNacionalEligibilityEngine()
        );
        PlanNacionalController controller = new PlanNacionalController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private PersonLookupMatch stableMatch(String name) {
        ContractSnapshot stable = new ContractSnapshot(
                "STAFF", "Dept. Química", "org-1",
                "Catedràtic/a d'universitat", "Funcionari/a", "Acadèmic",
                LocalDate.of(2015, 1, 1), null, true, false
        );
        return new PersonLookupMatch(
                MatchConfidence.NIF_EXACTE, "u-1", 123L, name,
                "https://egreta.uab.cat/admin/editor/dk/atira/pure/api/shared/model/person/editor/personeditor.xhtml?scheme=&type=&id=123",
                stable, List.of(stable), null, List.of()
        );
    }

    @Test
    void validarReturns200WithValidationResponseForValidPdf() throws Exception {
        String aeiText = """
                Referencia: PID2025-111222OB-I00
                Acrónimo: ECO-CAT
                Título: Projecte de prova
                Entidad solicitante: UAB
                INVESTIGADOR/A PRINCIPAL
                GARCIA LOPEZ, JOAN | 12345678A | UAB | Catedràtic
                """;
        when(pdfTextExtractor.extractText(any())).thenReturn(aeiText);
        when(lookupService.lookupAll(anyList(), any())).thenReturn(List.of(stableMatch("Garcia Lopez, Joan")));

        MockMultipartFile file = new MockMultipartFile(
                "file", "solicitud.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/plan-nacional/validar")
                        .file(file)
                        .param("referenceDate", "2026-11-19")
                        .param("durationYears", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectCode").value("PID2025-111222OB-I00"))
                .andExpect(jsonPath("$.effectiveDurationYears").value(4))
                .andExpect(jsonPath("$.overallStatus").value("OK"))
                .andExpect(jsonPath("$.summaryCounts.total").value(1))
                .andExpect(jsonPath("$.summaryCounts.ok").value(1))
                .andExpect(jsonPath("$.members[0].status").value("OK"));
    }

    @Test
    void validarReturns400WhenFileEmptyOrInvalidPdf() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "buit.pdf", "application/pdf", new byte[0]);
        mockMvc.perform(multipart("/api/plan-nacional/validar").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        when(pdfTextExtractor.extractText(any())).thenThrow(new InvalidPdfException("Document PDF no vàlid."));
        MockMultipartFile invalidFile = new MockMultipartFile("file", "bad.pdf", "application/pdf", "not-pdf".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/plan-nacional/validar").file(invalidFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document PDF no vàlid."));
    }

    @Test
    void validarReturns400WhenDurationYearsOutOfRange() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sol.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/plan-nacional/validar").file(file).param("durationYears", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(multipart("/api/plan-nacional/validar").file(file).param("durationYears", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validarReturns422WhenPdfIsScannedWithoutTextLayer() throws Exception {
        when(pdfTextExtractor.extractText(any()))
                .thenThrow(new ScannedPdfException("El PDF sembla escanejat sense capa de text."));
        MockMultipartFile scanned = new MockMultipartFile(
                "file", "escanejat.pdf", "application/pdf", "%PDF-1.4 img".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/plan-nacional/validar").file(scanned))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("escanejat")));
    }

    @Test
    void validarSerializesSpecialCharactersSafelyInJson() throws Exception {
        String textWithScript = """
                Referencia: PID2025-333444OB-I00
                Título: Estudi <script>alert('xss')</script>
                INVESTIGADOR/A PRINCIPAL
                <script>alert('xss')</script> TEST, USER | 12345678A | UAB | Catedràtic
                """;
        when(pdfTextExtractor.extractText(any())).thenReturn(textWithScript);
        when(lookupService.lookupAll(anyList(), any())).thenReturn(List.of(stableMatch("Test, User")));

        MockMultipartFile file = new MockMultipartFile(
                "file", "xss.pdf", "application/pdf", "%PDF-1.4 xss".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/plan-nacional/validar").file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("Estudi <script>alert('xss')</script>"))
                .andExpect(jsonPath("$.members[0].extractedMember.fullName").value("<script>alert('xss')</script> TEST, USER"));
    }

    @Test
    void validarRespectsUseAiFallbackFalse() throws Exception {
        when(pdfTextExtractor.extractText(any())).thenReturn("Text sense seccions d'equip reconegudes.");
        when(lookupService.lookupAll(anyList(), any())).thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile(
                "file", "sense_equip.pdf", "application/pdf", "%PDF-1.4 no-team".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/plan-nacional/validar")
                        .file(file)
                        .param("useAiFallback", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("AVIS"))
                .andExpect(jsonPath("$.members").isEmpty());

        verify(vllmFallbackParser, never()).parseFallback(anyString(), anyString());

        // Quan useAiFallback=true (per defecte) i membres és buit, sí invoca el fallback
        when(vllmFallbackParser.parseFallback(anyString(), anyString())).thenReturn(Optional.empty());
        mockMvc.perform(multipart("/api/plan-nacional/validar").file(file))
                .andExpect(status().isOk());
        verify(vllmFallbackParser, times(1)).parseFallback(anyString(), anyString());
    }

    @Test
    void validarEquipoRecalculatesWithoutPdfUpload() throws Exception {
        ExtractedMember assocMember = new ExtractedMember(
                MemberRole.EQUIPO_INVESTIGACION, "Soler, Pau", "Pau", "Soler", "99887766A", "UAB", null, "");
        ExtractedProposal proposal = new ExtractedProposal(
                "sol.pdf", "PID2025-555", "RE-EVAL", "Projecte", 3, null, null, "UAB",
                ExtractionMethod.AEI_PARSER, List.of(), "preview", List.of(assocMember));
        ReevaluateTeamRequest req = new ReevaluateTeamRequest(proposal, LocalDate.of(2026, 11, 19), 4);

        ContractSnapshot assocContract = new ContractSnapshot(
                "STAFF", "Dept. Economia", "org-2",
                "Professor/a associat/da laboral", "Contracte laboral temporal", "Acadèmic",
                LocalDate.of(2024, 1, 1), null, true, false
        );
        when(lookupService.lookupAll(anyList(), any())).thenReturn(List.of(
                new PersonLookupMatch(MatchConfidence.NIF_EXACTE, "u-2", 456L, "Soler, Pau", "url",
                        assocContract, List.of(assocContract), null, List.of())
        ));

        mockMvc.perform(post("/api/plan-nacional/validar-equipo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveDurationYears").value(4))
                .andExpect(jsonPath("$.overallStatus").value("NO_OK"))
                .andExpect(jsonPath("$.members[0].reasonCode").value("INELIGIBLE_ASSOCIAT"));

        ReevaluateTeamRequest missingProposal = new ReevaluateTeamRequest(null, LocalDate.of(2026, 11, 19), 3);
        mockMvc.perform(post("/api/plan-nacional/validar-equipo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingProposal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void reglasReturns200WithRuleCatalog() throws Exception {
        mockMvc.perform(get("/api/plan-nacional/reglas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultDurationYears").value(3))
                .andExpect(jsonPath("$.emeritWarningMonths").value(12))
                .andExpect(jsonPath("$.rules").isArray())
                .andExpect(jsonPath("$.categoryDefinitions").isArray());
    }
}
