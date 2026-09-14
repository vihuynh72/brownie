package io.github.vihuynh72.brownie.core.export;

/**
 * The document has moved past the exact revision an approval (or the
 * validation manifest a would-be approval names) was granted against --
 * an edit, a fresh validation run, or both happened since. Per this
 * plan's own §9.5, changing any of the things an approval is bound to
 * invalidates it; this is never silently ignored or re-approved
 * automatically.
 */
public class StaleExportApprovalException extends RuntimeException {

    private final long documentId;
    private final long expectedRevisionId;
    private final long currentRevisionId;

    public StaleExportApprovalException(long documentId, long expectedRevisionId, long currentRevisionId) {
        super("Document " + documentId + " is at revision " + currentRevisionId
                + ", not the revision " + expectedRevisionId + " this approval (or manifest) was granted against.");
        this.documentId = documentId;
        this.expectedRevisionId = expectedRevisionId;
        this.currentRevisionId = currentRevisionId;
    }

    public long documentId() {
        return documentId;
    }

    public long expectedRevisionId() {
        return expectedRevisionId;
    }

    public long currentRevisionId() {
        return currentRevisionId;
    }
}
