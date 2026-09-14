package io.github.vihuynh72.brownie.core.export;

/** No export approval has ever been recorded for this document. */
public class ExportNotApprovedException extends RuntimeException {

    public ExportNotApprovedException(long documentId) {
        super("Document " + documentId + " has no recorded export approval.");
    }
}
