package io.github.vihuynh72.brownie.core.retention;

/**
 * A worker is still stopping work for the target. Nothing was deleted; the
 * same request succeeds once that work has stopped, which takes at most one
 * lease period.
 */
public class DeletionWaitingForRunningWorkException extends RuntimeException {

    public DeletionWaitingForRunningWorkException(String message) {
        super(message);
    }
}
