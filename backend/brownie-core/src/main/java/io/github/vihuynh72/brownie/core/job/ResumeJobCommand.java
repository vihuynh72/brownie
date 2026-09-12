package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** A durable request to make a waiting job eligible for another worker claim. */
public record ResumeJobCommand(IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, long jobId) {

    public ResumeJobCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive.");
        }
    }
}
