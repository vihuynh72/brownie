package io.github.vihuynh72.brownie.core.export;

/** No export receipt has ever been recorded for this document. */
public class ExportReceiptNotFoundException extends RuntimeException {

    public ExportReceiptNotFoundException(long documentId) {
        super("Document " + documentId + " has no recorded export receipt.");
    }
}
