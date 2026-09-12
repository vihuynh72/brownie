package io.github.vihuynh72.brownie.core.document;

/**
 * The package passed upload-time inspection (a well-formed ZIP declaring an
 * OOXML content-types manifest) but its internal XML could not actually be
 * parsed as a well-formed DOCX -- a corruption deep enough that the
 * shallow, cheap check at upload time was never meant to catch it.
 */
public class DocxParseException extends RuntimeException {

    public DocxParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
