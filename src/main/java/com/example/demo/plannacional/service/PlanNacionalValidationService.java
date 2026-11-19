package com.example.demo.plannacional.service;

import com.example.demo.plannacional.engine.PlanNacionalEligibilityEngine;
import com.example.demo.plannacional.lookup.PersonPdiLookupService;
import com.example.demo.plannacional.model.*;
import com.example.demo.plannacional.parser.AeiSolicitudParser;
import com.example.demo.plannacional.parser.InvalidPdfException;
import com.example.demo.plannacional.parser.PdfTextExtractor;
import com.example.demo.plannacional.parser.VllmProposalFallbackParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Orquestra la validació en memòria de PDFs del Pla Nacional sense persistència.
 */
@Service
public class PlanNacionalValidationService {

    private static final Logger log = LoggerFactory.getLogger(PlanNacionalValidationService.class);
    private static final int MIN_DURATION_YEARS = 1;
    private static final int MAX_DURATION_YEARS = 6;

    private final PdfTextExtractor pdfTextExtractor;
    private final AeiSolicitudParser aeiSolicitudParser;
    private final VllmProposalFallbackParser vllmFallbackParser;
    private final PersonPdiLookupService lookupService;
    private final PlanNacionalEligibilityEngine eligibilityEngine;

    @Autowired
    public PlanNacionalValidationService(
            PdfTextExtractor pdfTextExtractor,
            AeiSolicitudParser aeiSolicitudParser,
            VllmProposalFallbackParser vllmFallbackParser,
            PersonPdiLookupService lookupService,
            PlanNacionalEligibilityEngine eligibilityEngine) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.aeiSolicitudParser = aeiSolicitudParser;
        this.vllmFallbackParser = vllmFallbackParser;
        this.lookupService = lookupService;
        this.eligibilityEngine = eligibilityEngine;
    }

    public ProposalValidationResponse validatePdf(
            String fileName,
            byte[] pdfBytes,
            LocalDate referenceDate,
            Integer durationYears,
            boolean useAiFallback) {
        validateDurationYears(durationYears);
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new InvalidPdfException("El fitxer PDF és buit.");
        }

        long tStart = System.nanoTime();
        LocalDate effectiveRef = referenceDate != null ? referenceDate : LocalDate.now();
        String rawText = pdfTextExtractor.extractText(pdfBytes);
        long tPdf = System.nanoTime();

        ExtractedProposal proposal = aeiSolicitudParser.parse(fileName, rawText);
        long tParse = System.nanoTime();

        long vllmMs = 0L;
        if (proposal.members().isEmpty() && useAiFallback) {
            long tVllmStart = System.nanoTime();
            Optional<ExtractedProposal> fallback = vllmFallbackParser.parseFallback(fileName, rawText);
            vllmMs = elapsedMs(tVllmStart);
            if (fallback.isPresent()) {
                proposal = mergeFallbackMetadata(proposal, fallback.get());
            }
        }

        long tLookupStart = System.nanoTime();
        List<PersonLookupMatch> matches = lookupService.lookupAll(proposal.members(), effectiveRef);
        long lookupMs = elapsedMs(tLookupStart);

        long tEvalStart = System.nanoTime();
        ProposalValidationResponse response = eligibilityEngine.evaluateProposal(proposal, matches, effectiveRef, durationYears);
        long evalMs = elapsedMs(tEvalStart);

        log.info("Pla Nacional validat: fitxer={}, membres={}, metode={}, pdfMs={}, parseMs={}, vllmMs={}, lookupMs={}, evalMs={}, totalMs={}",
                fileName,
                proposal.members().size(),
                proposal.extractionMethod(),
                (tPdf - tStart) / 1_000_000L,
                (tParse - tPdf) / 1_000_000L,
                vllmMs,
                lookupMs,
                evalMs,
                elapsedMs(tStart));
        return response;
    }

    public ProposalValidationResponse reevaluateTeam(ReevaluateTeamRequest request) {
        if (request == null || request.proposal() == null) {
            throw new IllegalArgumentException("Cal especificar les dades del projecte a reavaluar.");
        }
        validateDurationYears(request.durationYears());
        long tStart = System.nanoTime();
        LocalDate effectiveRef = request.referenceDate() != null ? request.referenceDate() : LocalDate.now();

        List<PersonLookupMatch> matches = lookupService.lookupAll(request.proposal().members(), effectiveRef);
        long lookupMs = elapsedMs(tStart);

        long tEvalStart = System.nanoTime();
        ProposalValidationResponse response = eligibilityEngine.evaluateProposal(
                request.proposal(), matches, effectiveRef, request.durationYears());
        long evalMs = elapsedMs(tEvalStart);

        log.info("Pla Nacional reavaluat: membres={}, lookupMs={}, evalMs={}, totalMs={}",
                request.proposal().members().size(),
                lookupMs,
                evalMs,
                elapsedMs(tStart));
        return response;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public RuleCatalogResponse getRuleCatalog() {
        return eligibilityEngine.buildRuleCatalog();
    }

    private void validateDurationYears(Integer durationYears) {
        if (durationYears != null && (durationYears < MIN_DURATION_YEARS || durationYears > MAX_DURATION_YEARS)) {
            throw new IllegalArgumentException(
                    "La durada del projecte ha d'estar compresa entre " + MIN_DURATION_YEARS + " i " + MAX_DURATION_YEARS + " anys.");
        }
    }

    private ExtractedProposal mergeFallbackMetadata(ExtractedProposal base, ExtractedProposal fb) {
        return new ExtractedProposal(
                base.fileName(),
                fb.projectCode() != null ? fb.projectCode() : base.projectCode(),
                fb.acronym() != null ? fb.acronym() : base.acronym(),
                fb.title() != null ? fb.title() : base.title(),
                fb.durationYears() != null ? fb.durationYears() : base.durationYears(),
                base.expectedStartDate(),
                base.expectedEndDate(),
                fb.applicantEntity() != null ? fb.applicantEntity() : base.applicantEntity(),
                fb.extractionMethod(),
                fb.warnings(),
                base.rawTextPreview(),
                fb.members()
        );
    }
}
