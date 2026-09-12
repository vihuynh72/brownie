package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** A bounded, safe progress observation emitted while a worker owns a lease. */
public record JobProgress(String safeMessage, Integer current, Integer total) {

    public JobProgress {
        Objects.requireNonNull(safeMessage, "safeMessage must not be null");
        if (safeMessage.isBlank() || safeMessage.length() > 500) {
            throw new IllegalArgumentException("safeMessage must contain non-blank text up to 500 characters.");
        }
        if ((current == null) != (total == null)) {
            throw new IllegalArgumentException("current and total must either both be present or both be absent.");
        }
        if (current != null && (current < 0 || total < 0 || current > total)) {
            throw new IllegalArgumentException("Progress counters must be non-negative and current must not exceed total.");
        }
    }
}
