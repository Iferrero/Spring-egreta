package com.example.demo.plannacional.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Adaptador d'Apache PDFBox que extreu text directament des de memòria sense escriure a disc.
 */
@Component
public class PdfBoxTextExtractor implements PdfTextExtractor {

    private static final int MIN_USEFUL_ALNUM_CHARS = 80;

    @Override
    public String extractText(byte[] pdfBytes) throws InvalidPdfException, ScannedPdfException {
        if (pdfBytes == null || pdfBytes.length < 5) {
            throw new InvalidPdfException("El fitxer PDF és buit o massa petit.");
        }
        String magic = new String(pdfBytes, 0, Math.min(8, pdfBytes.length), StandardCharsets.US_ASCII);
        if (!magic.startsWith("%PDF-")) {
            throw new InvalidPdfException("El fitxer no correspon a un document PDF vàlid (manca la capçalera %PDF-).");
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                throw new InvalidPdfException("El PDF està protegit i no permet l'extracció de text.");
            }
            if (document.getNumberOfPages() == 0) {
                throw new InvalidPdfException("El document PDF no conté cap pàgina.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String extracted = stripper.getText(document);

            if (countAlnumChars(extracted) < MIN_USEFUL_ALNUM_CHARS) {
                throw new ScannedPdfException(
                        "El PDF sembla un document escanejat sense capa de text. Exporteu el formulari original des de la seu electrònica de l'AEI.");
            }
            return extracted;
        } catch (InvalidPdfException | ScannedPdfException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidPdfException("No s'ha pogut llegir el document PDF (possible fitxer corrupte o encriptat).", e);
        }
    }

    private int countAlnumChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                count++;
                if (count >= MIN_USEFUL_ALNUM_CHARS) {
                    return count;
                }
            }
        }
        return count;
    }
}
