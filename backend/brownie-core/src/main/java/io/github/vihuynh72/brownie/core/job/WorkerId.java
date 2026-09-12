package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** A stable runtime identity for one worker process or instance. */
public record WorkerId(String value) {

    public WorkerId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Worker identifier must contain non-blank text up to 128 characters.");
        }
    }
}
