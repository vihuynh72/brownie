package io.github.vihuynh72.brownie.core.retention;

/** The request is no longer in the state this action needs, for example restoring something already deleted for good. */
public class DeletionStateConflictException extends RuntimeException {

    public DeletionStateConflictException(String message) {
        super(message);
    }
}
