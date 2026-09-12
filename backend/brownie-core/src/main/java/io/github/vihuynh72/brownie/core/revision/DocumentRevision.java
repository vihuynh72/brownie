package io.github.vihuynh72.brownie.core.revision;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One immutable snapshot of a document's typed field content.
 */
public record DocumentRevision(
        long id,
        long workspaceId,
        long documentId,
        int revisionNumber,
        Long parentRevisionId,
        DocumentContent content,
        String contentHash,
        long actorUserId,
        String editReason,
        OffsetDateTime createdAt) {

    public DocumentRevision {
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("Revision number must be positive.");
        }
        Objects.requireNonNull(content, "content");
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Content hash must be a lowercase SHA-256 hex value.");
        }
        if (editReason == null || editReason.isBlank()) {
            throw new IllegalArgumentException("Edit reason must not be blank.");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
