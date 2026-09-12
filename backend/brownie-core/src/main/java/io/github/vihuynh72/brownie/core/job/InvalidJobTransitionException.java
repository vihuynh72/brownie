package io.github.vihuynh72.brownie.core.job;

/** Raised before persistence when a queue state change is not allowed. */
public final class InvalidJobTransitionException extends IllegalArgumentException {

    public InvalidJobTransitionException(JobState from, JobState to) {
        super("Job transition from " + from + " to " + to + " is not allowed.");
    }
}
