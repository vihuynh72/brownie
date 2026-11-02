package io.github.vihuynh72.brownie.core.detail;

/** The document asked to learn from does not exist or is not the caller's. */
public class DetailDocumentNotFoundException extends RuntimeException {

    public DetailDocumentNotFoundException(long documentId) {
        super("No document " + documentId + " to learn details from.");
    }
}
