package io.github.vihuynh72.brownie.core.prepare;

import java.util.Objects;

/**
 * The file was accepted, but no fillable copy could be made of it. The
 * {@link Reason} is what the person is told: a file that did not open is
 * worth saving again from the program that made it, a damaged one is worth
 * repairing first, and one that took too long may be too large or too
 * complicated.
 */
public class FillableFormFailedException extends RuntimeException {

    public enum Reason {
        CANNOT_OPEN,
        DAMAGED,
        TIMED_OUT
    }

    private final Reason reason;

    public FillableFormFailedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
