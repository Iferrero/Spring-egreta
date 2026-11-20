package com.example.demo.linkaudit.controller;

import com.example.demo.linkaudit.model.AuditJobState;
import com.example.demo.linkaudit.model.AuditJobStatus;
import com.example.demo.linkaudit.model.AuditSummaryResponse;
import com.example.demo.linkaudit.model.CollectionAuditSummary;
import com.example.demo.linkaudit.model.FailureReason;
import com.example.demo.linkaudit.model.FieldAuditCounts;
import com.example.demo.linkaudit.model.LinkAuditIssue;
import com.example.demo.linkaudit.model.LinkIssuesPageResponse;
import com.example.demo.linkaudit.model.LinkStatus;
import com.example.demo.linkaudit.service.LinkAuditService;
import com.example.demo.linkaudit.service.ScanAlreadyRunningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CollectionLinkAuditControllerTest {

    private static final String EXPECTED_CSV_HEADER =
            "Coleccio,Estat,Motiu,Codi_HTTP,Camp,Subtipus,Etiqueta_Enllac,URL,URL_Final,Error,"
                    + "ID_Registre,UUID_Registre,PureID_Registre,Titol_Registre,Tipus_Registre,Enllac_Egreta,Data_Comprovacio";

    @Mock
    private LinkAuditService linkAuditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CollectionLinkAuditController(linkAuditService))
                .build();
    }

    @Test
    void getSummaryStatusAndCancelReturn200WithPayloads() throws Exception {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        AuditJobState idleJob = new AuditJobState(
                null, AuditJobStatus.IDLE, null, null, null, null,
                0, 0, 0L, 0L, 0L, 0L, 0L, null
        );
        CollectionAuditSummary awardsSummary = new CollectionAuditSummary(
                "Awards", "scan-1", now, 1200L, 10L, 25L, 20L, 21L, 3L, 1L,
                Map.of("documents", new FieldAuditCounts(15L, 12L, 2L, 1L)), null
        );
        AuditSummaryResponse summaryResponse = new AuditSummaryResponse(
                now, 1L, 25L, 3L, 1L, now, idleJob, List.of(awardsSummary)
        );
        AuditJobState runningState = new AuditJobState(
                "job-1", AuditJobStatus.RUNNING, "ALL", now, null, "Awards",
                5, 1, 20L, 10L, 15L, 2L, 1L, null
        );
        AuditJobState cancellingState = new AuditJobState(
                "job-1", AuditJobStatus.CANCELLING, "ALL", now, null, "Awards",
                5, 1, 20L, 10L, 15L, 2L, 1L, null
        );

        when(linkAuditService.getSummary()).thenReturn(summaryResponse);
        when(linkAuditService.getStatus()).thenReturn(runningState);
        when(linkAuditService.cancelScan()).thenReturn(cancellingState);

        mockMvc.perform(get("/api/mongo/collections/link-audit/summary"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.collectionsAudited").value(1))
                .andExpect(jsonPath("$.totalLinksChecked").value(25))
                .andExpect(jsonPath("$.totalBroken").value(3))
                .andExpect(jsonPath("$.totalBlocked").value(1))
                .andExpect(jsonPath("$.collections[0].collection").value("Awards"))
                .andExpect(jsonPath("$.job.status").value("IDLE"));

        mockMvc.perform(get("/api/mongo/collections/link-audit/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentCollection").value("Awards"));

        mockMvc.perform(post("/api/mongo/collections/link-audit/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("CANCELLING"));
    }

    @Test
    void postScanReturns202OnStart400OnInvalidAnd409OnConflict() throws Exception {
        Instant startedAt = Instant.parse("2026-11-20T10:15:00Z");
        AuditJobState globalJob = new AuditJobState(
                "job-global", AuditJobStatus.RUNNING, "ALL", startedAt, null, "Awards",
                8, 0, 0L, 0L, 0L, 0L, 0L, null
        );
        AuditJobState awardsJob = new AuditJobState(
                "job-awards", AuditJobStatus.RUNNING, "Awards", startedAt, null, "Awards",
                1, 0, 0L, 0L, 0L, 0L, 0L, null
        );

        when(linkAuditService.startScan(null)).thenReturn(globalJob);
        when(linkAuditService.startScan("Awards")).thenReturn(awardsJob);
        when(linkAuditService.startScan("UnknownCollection"))
                .thenThrow(new IllegalArgumentException("Col·lecció invàlida, interna o inexistent: UnknownCollection"));
        when(linkAuditService.startScan("Persons"))
                .thenThrow(new ScanAlreadyRunningException(globalJob));

        mockMvc.perform(post("/api/mongo/collections/link-audit/scan"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-global"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.scope").value("ALL"));

        mockMvc.perform(post("/api/mongo/collections/link-audit/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-global"))
                .andExpect(jsonPath("$.scope").value("ALL"));

        mockMvc.perform(post("/api/mongo/collections/link-audit/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collection\":\"Awards\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-awards"))
                .andExpect(jsonPath("$.scope").value("Awards"));

        mockMvc.perform(post("/api/mongo/collections/link-audit/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collection\":\"UnknownCollection\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/mongo/collections/link-audit/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collection\":\"Persons\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.jobId").value("job-global"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentCollection").value("Awards"));
    }

    @Test
    void getIssuesAppliesDefaultsClampsPageSizeAndRejectsInternalCollection() throws Exception {
        Instant scannedAt = Instant.parse("2026-11-20T10:30:00Z");
        LinkIssuesPageResponse defaultPage = new LinkIssuesPageResponse(
                "Awards", "scan-1", scannedAt, 0, 50, 0L, 0,
                Map.of(), Map.of(), Map.of(), List.of()
        );
        LinkIssuesPageResponse clampedPage = new LinkIssuesPageResponse(
                "Awards", "scan-1", scannedAt, 0, 200, 1L, 1,
                Map.of("BROKEN", 1L), Map.of("documents", 1L), Map.of("HTTP_404", 1L), List.of()
        );

        when(linkAuditService.getIssues("Awards", "BROKEN", null, null, null, 0, 50))
                .thenReturn(defaultPage);
        when(linkAuditService.getIssues("Awards", "ALL", "documents", "HTTP_404", "missing", 0, 200))
                .thenReturn(clampedPage);
        when(linkAuditService.getIssues("BadCollection", "BROKEN", null, null, null, 0, 50))
                .thenThrow(new IllegalArgumentException("Col·lecció invàlida"));

        mockMvc.perform(get("/api/mongo/collections/link-audit/Awards/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collection").value("Awards"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50));

        mockMvc.perform(get("/api/mongo/collections/link-audit/Awards/issues")
                        .param("status", "ALL")
                        .param("field", "documents")
                        .param("reason", "HTTP_404")
                        .param("q", "missing")
                        .param("page", "-2")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.totalItems").value(1));

        verify(linkAuditService).getIssues("Awards", "BROKEN", null, null, null, 0, 50);
        verify(linkAuditService).getIssues("Awards", "ALL", "documents", "HTTP_404", "missing", 0, 200);

        mockMvc.perform(get("/api/mongo/collections/link-audit/CollectionLinkAuditSummary/issues"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/mongo/collections/link-audit/system.views/issues"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/mongo/collections/link-audit/BadCollection/issues"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportIssuesStreamsUtf8BomCsvEscapingQuotesAndFormulaInjection() throws Exception {
        Instant checkedAt = Instant.parse("2026-11-20T11:00:00Z");
        List<LinkAuditIssue> issues = List.of(
                new LinkAuditIssue(
                        "Awards", "scan-1", LinkStatus.BROKEN, FailureReason.HTTP_404, 404,
                        "=CMD(\"calc\")",
                        "https://example.com/ok", "https://example.com/ok",
                        "documents", "Resolució", "+Etiqueta, \"especial\"",
                        "id-1", "uuid-1", 1001L,
                        "@SUM(1,2)", "Ajut", "https://egreta.uab.cat/admin/workspace.xhtml?uuid=uuid-1",
                        checkedAt
                ),
                new LinkAuditIssue(
                        "Awards", "scan-1", LinkStatus.BLOCKED, FailureReason.HTTP_403, null,
                        "-error",
                        "https://example.com/blocked", null,
                        "links", "Web", "\tTabLabel",
                        "id-2", "uuid-2", null,
                        "\rCrTitle", "Ajut", null,
                        null
                ),
                new LinkAuditIssue(
                        "Awards", "scan-1", LinkStatus.BROKEN, FailureReason.INVALID_SYNTAX, null,
                        "Sintaxi d'URL invàlida",
                        "=HYPERLINK(\"http://evil\",\"click\")",
                        "+HYPERLINK(\"http://evil\",\"final\")",
                        "links", "=SubtipusEvil", "Etiqueta normal",
                        "id-3", "uuid-3", null,
                        "Títol normal", "@TipusEvil", null,
                        checkedAt
                )
        );

        doAnswer(invocation -> {
            Consumer<LinkAuditIssue> consumer = invocation.getArgument(5);
            issues.forEach(consumer);
            return null;
        }).when(linkAuditService).streamIssuesForExport(eq("Awards"), eq("ALL"), isNull(), isNull(), isNull(), any());

        MvcResult result = mockMvc.perform(get("/api/mongo/collections/link-audit/Awards/issues/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment; filename=\"enllacos_incorrectes_Awards_")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".csv\"")))
                .andReturn();

        byte[] rawBytes = result.getResponse().getContentAsByteArray();
        assertTrue(rawBytes.length >= 3, "CSV output must start with UTF-8 BOM");
        assertArrayEquals(
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                Arrays.copyOfRange(rawBytes, 0, 3)
        );

        String csvWithoutBom = new String(rawBytes, 3, rawBytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csvWithoutBom.split("\r\n", -1);
        assertEquals(EXPECTED_CSV_HEADER, lines[0]);

        assertTrue(csvWithoutBom.contains("\"'=CMD(\"\"calc\"\")\""));
        assertTrue(csvWithoutBom.contains("\"'+Etiqueta, \"\"especial\"\"\""));
        assertTrue(csvWithoutBom.contains("\"'@SUM(1,2)\""));
        assertTrue(csvWithoutBom.contains("'-error"));
        assertTrue(csvWithoutBom.contains("'\tTabLabel"));
        assertTrue(csvWithoutBom.contains("\"'\rCrTitle\""));
        assertTrue(csvWithoutBom.contains(",https://example.com/ok,https://example.com/ok,"));
        assertTrue(csvWithoutBom.contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\""));
        assertTrue(csvWithoutBom.contains("\"'+HYPERLINK(\"\"http://evil\"\",\"\"final\"\")\""));
        assertTrue(csvWithoutBom.contains("'=SubtipusEvil"));
        assertTrue(csvWithoutBom.contains("'@TipusEvil"));
    }

    @Test
    void exportIssuesForUnauditedCollectionReturnsHeaderOnlyCsv() throws Exception {
        doAnswer(invocation -> null)
                .when(linkAuditService).streamIssuesForExport(eq("Patents"), eq("ALL"), isNull(), isNull(), isNull(), any());

        MvcResult result = mockMvc.perform(get("/api/mongo/collections/link-audit/Patents/issues/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment; filename=\"enllacos_incorrectes_Patents_")))
                .andReturn();

        byte[] rawBytes = result.getResponse().getContentAsByteArray();
        assertTrue(rawBytes.length >= 3);
        assertArrayEquals(
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                Arrays.copyOfRange(rawBytes, 0, 3)
        );

        String csvWithoutBom = new String(rawBytes, 3, rawBytes.length - 3, StandardCharsets.UTF_8);
        assertEquals(EXPECTED_CSV_HEADER + "\r\n", csvWithoutBom);

        mockMvc.perform(get("/api/mongo/collections/link-audit/system.views/issues/export"))
                .andExpect(status().isBadRequest());
    }
}
