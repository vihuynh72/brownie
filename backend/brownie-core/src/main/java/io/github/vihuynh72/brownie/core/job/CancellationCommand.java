package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** A durable request to stop a non-terminal job cooperatively. */
public record CancellationCommand(IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash, long jobId) {

    public CancellationCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive.");
        }
    }
}
