package io.github.vihuynh72.brownie.core.retention;

/** What the database answered when asked to delete something for good. */
public enum PurgeOutcome {
    PURGED,
    /** The request exists but was restored before this call, so there is nothing to delete. */
    NOT_OPEN,
    /**
     * A worker still holds a live lease on work for this target. Its
     * cancellation has been requested; the deletion goes through once that
     * worker lets go or its lease runs out.
     */
    JOBS_STILL_STOPPING,
    NOT_FOUND
}
