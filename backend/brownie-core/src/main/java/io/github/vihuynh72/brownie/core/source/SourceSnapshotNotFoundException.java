package io.github.vihuynh72.brownie.core.source;

/** No source snapshot by that ID exists in the caller's workspace -- whether it never existed or belongs to someone else. */
public class SourceSnapshotNotFoundException extends RuntimeException {

    public SourceSnapshotNotFoundException(long snapshotId) {
        super("No source snapshot " + snapshotId + " in this workspace.");
    }
}
