package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;

/**
 * An append-only, ordered event for one job. {@code safeMessage} is an
 * operator-safe status phrase, never application source or document text.
 */
public record JobEvent(
        long id,
        long workspaceId,
        long jobId,
        JobTarget target,
        JobStage stage,
        long sequence,
        JobEventType type,
        JobState state,
        String safeMessage,
        Integer progressCurrent,
        Integer progressTotal,
        OffsetDateTime createdAt) {
}
