package com.example.demo.plannacional.parser;

/**
 * Excepció llançada quan el fitxer pujat no és un PDF vàlid o no es pot obrir.
 */
public class InvalidPdfException extends RuntimeException {
    public InvalidPdfException(String message) {
        super(message);
    }

    public InvalidPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
