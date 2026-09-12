package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** An opaque caller-generated key scoped by actor, workspace, and operation. */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("Idempotency key must contain non-blank text up to 200 characters.");
        }
    }
}
