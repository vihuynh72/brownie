package io.github.vihuynh72.brownie.core.revision;

/** The edit was based on a revision that is no longer current. */
public class DocumentRevisionConflictException extends RuntimeException {

    private final long documentId;
    private final long expectedRevisionId;
    private final long currentRevisionId;

    public DocumentRevisionConflictException(long documentId, long expectedRevisionId, long currentRevisionId) {
        super("Document " + documentId + " is at revision " + currentRevisionId
                + ", not the expected revision " + expectedRevisionId + ".");
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
