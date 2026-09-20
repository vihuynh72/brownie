package io.github.vihuynh72.brownie.core.job;

/** The job's document changed after the job froze its inputs (or is in the trash), so restarting it could only produce a result nothing accepts; a new run is the way forward. */
public class JobTargetStaleException extends RuntimeException {

    public JobTargetStaleException(long jobId) {
        super("Job " + jobId + " was started for an earlier version of its document. Start a new run instead.");
    }
}
