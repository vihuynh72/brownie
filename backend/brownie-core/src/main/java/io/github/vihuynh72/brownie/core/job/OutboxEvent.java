package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A transactional delivery record pointing to a durable {@link JobEvent}.
 * Consumers receive identifiers and state metadata, then load any authority
 * they need separately; the outbox does not carry work input or output.
 */
public record OutboxEvent(
        UUID deliveryKey,
        long workspaceId,
        long jobId,
        long jobEventId,
        JobEventType type,
        OffsetDateTime occurredAt,
        OffsetDateTime publishedAt) {
}
