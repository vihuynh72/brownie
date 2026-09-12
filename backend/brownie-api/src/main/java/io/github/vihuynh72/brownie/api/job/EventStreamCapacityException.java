package io.github.vihuynh72.brownie.api.job;

/** The bounded event-stream pool has no available connection slot. */
public final class EventStreamCapacityException extends RuntimeException {

    public EventStreamCapacityException() {
        super("Too many event streams are already open. Poll for updates and try streaming again shortly.");
    }
}
