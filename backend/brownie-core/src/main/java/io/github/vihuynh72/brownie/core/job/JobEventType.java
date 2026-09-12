package io.github.vihuynh72.brownie.core.job;

/** Stable event names emitted alongside queue metadata changes. */
public enum JobEventType {
    QUEUED,
    RESUMED,
    CANCELLATION_REQUESTED,
    CANCELLED,
    LEASED,
    RELEASED,
    COMPLETED,
    PROGRESS,
    STAGED_OUTPUT_RECORDED
}
