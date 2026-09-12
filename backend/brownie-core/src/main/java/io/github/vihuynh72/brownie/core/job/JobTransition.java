package io.github.vihuynh72.brownie.core.job;

import java.util.Objects;

/** A requested change between two queue-level states. */
public record JobTransition(JobState from, JobState to) {

    public JobTransition {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }
}
