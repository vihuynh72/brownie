package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.util.Objects;

/** The current lease metadata recorded on a claimed job. */
public record JobLease(String workerId, OffsetDateTime expiresAt) {

    public JobLease {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("workerId must contain non-blank text up to 128 characters.");
        }
    }
}
