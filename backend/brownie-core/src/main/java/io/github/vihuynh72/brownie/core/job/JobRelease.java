package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A lease-ending outcome that either schedules a retry, waits for a person,
 * or records retry exhaustion. It does not accept a successful result.
 */
public record JobRelease(JobState nextState, OffsetDateTime availableAt, String safeMessage) {

    public JobRelease {
        Objects.requireNonNull(nextState, "nextState must not be null");
        if (nextState != JobState.QUEUED && nextState != JobState.WAITING_FOR_INPUT && nextState != JobState.DEAD) {
            throw new IllegalArgumentException("A lease release must target QUEUED, WAITING_FOR_INPUT, or DEAD.");
        }
        if (nextState == JobState.QUEUED && availableAt == null) {
            throw new IllegalArgumentException("A requeued job needs an availableAt time.");
        }
        if (nextState != JobState.QUEUED && availableAt != null) {
            throw new IllegalArgumentException("Only a requeued job may have an availableAt time.");
        }
        if (safeMessage != null && (safeMessage.isBlank() || safeMessage.length() > 500)) {
            throw new IllegalArgumentException("safeMessage must be non-blank when present and at most 500 characters.");
        }
    }
}
