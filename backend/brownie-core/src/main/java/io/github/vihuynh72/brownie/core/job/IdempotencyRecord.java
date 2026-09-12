package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The stored idempotency identity. It contains a digest of the canonical
 * request, not the request payload itself.
 */
public record IdempotencyRecord(
        long id,
        long workspaceId,
        long actorUserId,
        JobCommandType commandType,
        IdempotencyKey key,
        CanonicalRequestHash requestHash,
        UUID commandId,
        OffsetDateTime createdAt) {
}
