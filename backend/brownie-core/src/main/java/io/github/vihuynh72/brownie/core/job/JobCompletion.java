package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/**
 * A final result request from a worker. The target is repeated as an explicit
 * optimistic guard, so a lease cannot attach a result to a changed resource
 * identity or version.
 */
public record JobCompletion(JobTarget expectedTarget, JobState finalState, String safeMessage) {

    public JobCompletion {
        Objects.requireNonNull(expectedTarget, "expectedTarget must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
        if (finalState != JobState.SUCCEEDED && finalState != JobState.FAILED) {
            throw new IllegalArgumentException("A completion must target SUCCEEDED or FAILED.");
        }
        if (safeMessage != null && (safeMessage.isBlank() || safeMessage.length() > 500)) {
            throw new IllegalArgumentException("safeMessage must be non-blank when present and at most 500 characters.");
        }
    }
}
