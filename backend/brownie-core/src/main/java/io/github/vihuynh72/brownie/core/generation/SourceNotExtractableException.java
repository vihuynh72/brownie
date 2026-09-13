package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;

/** The source snapshot's own plain-text extraction is not COMPLETE, so nothing can be cited from it yet. */
public class SourceNotExtractableException extends RuntimeException {

    public SourceNotExtractableException(long sourceSnapshotId, ExtractionStatus status) {
        super("Source snapshot " + sourceSnapshotId + " has plain-text extraction status " + status + ", not COMPLETE.");
    }
}
