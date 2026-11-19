package com.example.demo.plannacional.parser;

/**
 * Port d'extracció de text en memòria des dels bytes d'un PDF.
 */
public interface PdfTextExtractor {
    String extractText(byte[] pdfBytes) throws InvalidPdfException, ScannedPdfException;
}
