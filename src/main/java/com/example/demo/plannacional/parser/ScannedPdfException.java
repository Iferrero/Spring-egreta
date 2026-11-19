package com.example.demo.plannacional.parser;

/**
 * Excepció llançada quan un PDF no conté capa de text extraïble (document escanejat com a imatge).
 */
public class ScannedPdfException extends RuntimeException {
    public ScannedPdfException(String message) {
        super(message);
    }
}
