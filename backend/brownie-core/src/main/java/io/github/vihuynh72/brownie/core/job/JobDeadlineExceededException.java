package io.github.vihuynh72.brownie.core.job;

/** Raised when a command reaches a job after its total execution deadline. */
public final class JobDeadlineExceededException extends IllegalStateException {

    public JobDeadlineExceededException(long jobId) {
        super("Job " + jobId + " reached its deadline and cannot resume.");
    }
}
