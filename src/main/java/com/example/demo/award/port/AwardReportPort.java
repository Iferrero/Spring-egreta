package com.example.demo.award.port;

import com.example.demo.award.model.AwardCountryReportCriteria;
import java.io.OutputStream;

/**
 * Outbound seam port for generating award and research reports.
 * Decouples domain logic and HTTP controllers from concrete document formatting libraries (e.g. Apache POI).
 */
public interface AwardReportPort {

    /**
     * Streams the generated country Word DOCX report directly to the provided OutputStream.
     */
    void generateCountryWordReport(AwardCountryReportCriteria criteria, OutputStream outputStream) throws Exception;

    /**
     * Generates and returns the complete binary bytes for the country Word DOCX report.
     */
    byte[] generateCountryWordReportBytes(AwardCountryReportCriteria criteria) throws Exception;
}
