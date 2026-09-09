package io.github.vihuynh72.brownie.core.artifact;

import java.util.Optional;

/**
 * Every method takes workspace and user context explicitly rather than a
 * bare artifact ID, the same tenant-scoped pattern {@code
 * WorkspaceRepository} established.
 */
public interface ArtifactRepository {

    Artifact initiateUpload(long workspaceId, long userId);

    Optional<Artifact> find(long workspaceId, long userId, long artifactId);

    /**
     * Records the size and hash actually observed while writing content to
     * blob storage. Only takes effect once per artifact, while it is still
     * UPLOADING with no content recorded yet; a later call that cannot
     * apply for that reason simply returns the artifact's current,
     * unchanged state rather than throwing, leaving the caller to decide
     * whether that is a conflict.
     */
    Artifact recordUploadedContent(long workspaceId, long userId, long artifactId, long byteCount, String sha256);

    /**
     * Transitions UPLOADING to QUARANTINED. Idempotent at the storage
     * layer in the same sense as {@link #recordUploadedContent}: a call
     * that cannot apply (already QUARANTINED, not yet uploaded, expired)
     * returns the current state rather than throwing -- the caller decides
     * whether that state means success or conflict.
     */
    Artifact finalizeUpload(long workspaceId, long userId, long artifactId);

    Artifact reject(long workspaceId, long userId, long artifactId, String reason);
}
