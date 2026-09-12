package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** A claimed job paired with the exact token that authorizes its worker writes. */
public record LeasedJob(Job job, JobLeaseToken leaseToken) {

    public LeasedJob {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        if (job.id() != leaseToken.jobId() || job.fencingToken() != leaseToken.fencingToken()) {
            throw new IllegalArgumentException("Job and lease token must identify the same claimed attempt.");
        }
        if (job.lease() == null || !job.lease().workerId().equals(leaseToken.workerId())) {
            throw new IllegalArgumentException("Job lease owner must match the lease token.");
        }
    }
}
