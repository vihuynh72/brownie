package io.github.vihuynh72.brownie.core.retention;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One entry in a workspace's deletion ledger. The ledger itself stores only
 * ids, states and times; {@code targetTitle} is read from the document
 * while that document still exists in the trash, and is null from the
 * moment it is deleted for good, so nothing a person wrote outlives the
 * thing they asked to remove. {@code pendingObjectCount} is how many stored
 * files of a purged target the worker has not removed yet.
 */
public record DeletionRequest(
        long id,
        long workspaceId,
        DeletionScope scope,
        long targetId,
        DeletionState state,
        long requestedByUserId,
        OffsetDateTime requestedAt,
        OffsetDateTime purgeAfter,
        OffsetDateTime restoredAt,
        OffsetDateTime purgedAt,
        OffsetDateTime verifiedAt,
        int pendingObjectCount,
        String targetTitle) {

    public DeletionRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (pendingObjectCount < 0) {
            throw new IllegalArgumentException("pendingObjectCount must not be negative.");
        }
    }
}
