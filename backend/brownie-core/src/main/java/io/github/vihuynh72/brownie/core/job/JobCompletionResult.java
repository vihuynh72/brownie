package io.github.vihuynh72.brownie.core.job;

/** The authoritative result of a worker's result-publication attempt. */
public enum JobCompletionResult {
    COMPLETED,
    LOST_LEASE,
    CANCELLATION_REQUESTED,
    STALE_TARGET
}
