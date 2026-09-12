package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;

/** Persisted metadata for a temporary output associated with one lease attempt. */
public record StagedOutput(
        long id,
        long workspaceId,
        long jobId,
        String workerId,
        long fencingToken,
        StagedOutputRequest metadata,
        StagedOutputState state,
        OffsetDateTime createdAt,
        OffsetDateTime verifiedAt,
        OffsetDateTime attachedAt,
        OffsetDateTime cleanedAt) {
}
