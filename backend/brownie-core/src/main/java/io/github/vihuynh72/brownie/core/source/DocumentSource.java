package io.github.vihuynh72.brownie.core.source;

import java.time.OffsetDateTime;

/**
 * One document's link to one workspace-level {@link SourceSnapshot}: the
 * fact that these notes or this transcript belong to this document. A
 * snapshot may be linked to many documents; a document links each
 * snapshot at most once.
 */
public record DocumentSource(long workspaceId, long documentId, long sourceSnapshotId, long attachedByUserId, OffsetDateTime attachedAt) {
}
