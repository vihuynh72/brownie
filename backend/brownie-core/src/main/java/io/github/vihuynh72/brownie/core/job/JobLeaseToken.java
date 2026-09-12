package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/**
 * The ownership proof required for worker-side mutations. Implementations
 * must compare all three fields atomically with a job's current row before
 * storing a result or changing its state.
 */
public record JobLeaseToken(long jobId, String workerId, long fencingToken) {

    public JobLeaseToken {
        Objects.requireNonNull(workerId, "workerId must not be null");
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive.");
        }
        if (workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must contain non-blank text up to 128 characters.");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive.");
        }
    }
}
