package io.github.vihuynh72.brownie.core.artifact;

/**
 * Every state an artifact row can be in. There is no deleted state:
 * permanent deletion removes the row itself and records the removal in the
 * deletion ledger, so a status here always describes a file that is still
 * on record.
 */
public enum ArtifactStatus {
    UPLOADING,
    QUARANTINED,
    SCANNING,
    READY,
    REJECTED
}
