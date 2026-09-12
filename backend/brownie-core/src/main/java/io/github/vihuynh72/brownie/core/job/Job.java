package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;

/**
 * Persisted metadata for one queue item. No request body, source excerpt, or
 * generated document content belongs in this record.
 */
public record Job(
        long id,
        long workspaceId,
        long requestedByUserId,
        JobType type,
        JobTarget target,
        JobStage stage,
        CanonicalRequestHash processingConfigurationHash,
        JobState state,
        int attemptCount,
        OffsetDateTime availableAt,
        OffsetDateTime deadlineAt,
        OffsetDateTime cancellationRequestedAt,
        long fencingToken,
        JobLease lease,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
