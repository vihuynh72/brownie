package io.github.vihuynh72.brownie.core.artifact;

import java.time.OffsetDateTime;

/**
 * An uploaded file's identity and lifecycle state, kept separate from its
 * bytes: {@code blobKey} names the object in blob storage, but this record
 * carries no content of its own. {@code byteCount}/{@code sha256} are null
 * until content has actually been written and observed once, and never
 * change after that -- the guarantee that makes an artifact's bytes
 * immutable once uploaded.
 */
public record Artifact(
        long id,
        long workspaceId,
        String blobKey,
        ArtifactStatus status,
        Long byteCount,
        String sha256,
        String rejectionReason,
        OffsetDateTime createdAt,
        OffsetDateTime finalizedAt) {
}
