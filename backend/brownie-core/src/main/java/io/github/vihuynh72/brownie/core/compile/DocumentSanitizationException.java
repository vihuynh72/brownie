package io.github.vihuynh72.brownie.core.compile;

/** A filled document's own metadata could not be read back and stripped. */
public class DocumentSanitizationException extends RuntimeException {

    public DocumentSanitizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
