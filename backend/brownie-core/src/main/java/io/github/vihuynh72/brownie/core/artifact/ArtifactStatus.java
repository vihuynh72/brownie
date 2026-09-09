package io.github.vihuynh72.brownie.core.artifact;

/**
 * Only the states this codebase actually produces today. DELETE_REQUESTED
 * and DELETED arrive once deletion exists to produce them.
 */
public enum ArtifactStatus {
    UPLOADING,
    QUARANTINED,
    SCANNING,
    READY,
    REJECTED
}
