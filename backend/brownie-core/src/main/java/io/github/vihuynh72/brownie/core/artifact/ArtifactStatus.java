package io.github.vihuynh72.brownie.core.artifact;

/**
 * Only the states this codebase actually produces today. SCANNING, READY,
 * DELETE_REQUESTED, and DELETED arrive once malware scanning, preview
 * routes, and deletion exist to produce them.
 */
public enum ArtifactStatus {
    UPLOADING,
    QUARANTINED,
    REJECTED
}
