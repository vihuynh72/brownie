package io.github.vihuynh72.brownie.core.job;

/** No job by that ID is visible in the caller's workspace. */
public final class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(long jobId) {
        super("No job " + jobId + " in this workspace.");
    }
}
