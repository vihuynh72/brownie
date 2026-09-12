package io.github.vihuynh72.brownie.core.job;

/**
 * Queue-level state for one durable unit of work. Product resources keep
 * their own lifecycles; this state only describes whether the unit can be
 * claimed, is held by a worker, awaits a person, or has reached a terminal
 * outcome.
 */
public enum JobState {
    QUEUED,
    LEASED,
    WAITING_FOR_INPUT,
    SUCCEEDED,
    FAILED,
    DEAD,
    CANCEL_REQUESTED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD || this == CANCELLED;
    }
}
