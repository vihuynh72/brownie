package io.github.vihuynh72.brownie.core.job;

/** What the database answered when a member asked to start a job that had given up again. */
public enum JobRetryOutcome {
    RETRIED,
    /** Already queued, running, waiting for input or stopping: there is nothing to start again, and that is not an error. */
    ALREADY_ACTIVE,
    /** Finished or cancelled: a job that succeeded or that someone stopped is not restarted. */
    NOT_RETRYABLE,
    /** The job's document has moved on or is in the trash, so the inputs it froze no longer describe where its result would land. */
    STALE_TARGET,
    NOT_FOUND
}
