package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;

/** The source snapshot's own extraction is not usable, so nothing can be cited from it yet. */
public class SourceNotExtractableException extends RuntimeException {

    public SourceNotExtractableException(long sourceSnapshotId, ExtractionStatus status) {
        super("Source snapshot " + sourceSnapshotId + " has extraction status " + status + ", not COMPLETE.");
    }

    public SourceNotExtractableException(long sourceSnapshotId, String detail) {
        super("Source snapshot " + sourceSnapshotId + ": " + detail);
    }
}
