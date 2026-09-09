package io.github.vihuynh72.brownie.core.artifact;

import java.util.Optional;

/**
 * Every method takes workspace and user context explicitly rather than a
 * bare artifact ID, the same tenant-scoped pattern {@code
 * WorkspaceRepository} established.
 */
public interface ArtifactRepository {

    /** {@code displayFilename} is already-sanitized display metadata, or null when the caller supplied none. */
    Artifact initiateUpload(long workspaceId, long userId, String displayFilename);

    Optional<Artifact> find(long workspaceId, long userId, long artifactId);

    /**
     * Records the size, hash, and detected media type actually observed
     * while writing content to blob storage. Only takes effect once per
     * artifact, while it is still UPLOADING with no content recorded yet;
     * a later call that cannot apply for that reason simply returns the
     * artifact's current, unchanged state rather than throwing, leaving
     * the caller to decide whether that is a conflict.
     */
    Artifact recordUploadedContent(
            long workspaceId, long userId, long artifactId, long byteCount, String sha256, SupportedMediaType detectedMediaType);

    /**
     * Transitions UPLOADING to QUARANTINED. Idempotent at the storage
     * layer in the same sense as {@link #recordUploadedContent}: a call
     * that cannot apply (already QUARANTINED, not yet uploaded, expired)
     * returns the current state rather than throwing -- the caller decides
     * whether that state means success or conflict.
     */
    Artifact finalizeUpload(long workspaceId, long userId, long artifactId);

    /** From UPLOADING or SCANNING (a scan can find something after either the first content upload or a retried scan). */
    Artifact reject(long workspaceId, long userId, long artifactId, String reason);

    /**
     * Transitions QUARANTINED to SCANNING. Unlike the other transitions
     * here, this one is a strict single-winner exclusion rather than an
     * apply-or-return-current-state idempotence: if the artifact is not
     * QUARANTINED (including when it is already SCANNING) the call throws
     * {@link ArtifactStateConflictException} instead of returning the
     * current state. This is what makes it safe for the caller to treat
     * a successful return as exclusive ownership of the scan -- two
     * concurrent callers cannot both proceed to interpret a scan result
     * for the same artifact. The tradeoff: a process that crashes after
     * entering SCANNING leaves the artifact stuck there with no automatic
     * retry.
     */
    Artifact beginScanning(long workspaceId, long userId, long artifactId);

    /** Transitions SCANNING to READY: the scan completed and found nothing. */
    Artifact markReady(long workspaceId, long userId, long artifactId);

    /** Transitions SCANNING back to QUARANTINED: the scan itself failed, not the content -- retryable. */
    Artifact revertToQuarantined(long workspaceId, long userId, long artifactId);
}
