package io.github.vihuynh72.brownie.core.job;

/** The authoritative result of a worker's attempt to release a lease. */
public enum JobReleaseResult {
    RELEASED,
    CANCELLED,
    CANCELLATION_REQUESTED,
    LOST_LEASE
}
