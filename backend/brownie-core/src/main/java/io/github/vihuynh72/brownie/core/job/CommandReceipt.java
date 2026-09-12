package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The durable response for an accepted command. A retry with the same
 * idempotency scope and canonical hash returns this same receipt.
 */
public record CommandReceipt(
        UUID commandId,
        long workspaceId,
        long actorUserId,
        JobCommandType commandType,
        long jobId,
        CanonicalRequestHash requestHash,
        CommandReceiptStatus status,
        OffsetDateTime acceptedAt) {
}
