package io.github.vihuynh72.brownie.core.document;

/** The package passed upload-time inspection but could not actually be parsed as a well-formed PDF -- corruption deep enough the shallow upload-time check was never meant to catch it. */
public class PdfParseException extends RuntimeException {

    public PdfParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
