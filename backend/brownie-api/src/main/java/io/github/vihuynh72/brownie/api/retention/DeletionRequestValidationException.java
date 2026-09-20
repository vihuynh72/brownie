package io.github.vihuynh72.brownie.api.retention;

/** A deletion request whose body or path cannot mean anything, reported as a malformed request. */
public class DeletionRequestValidationException extends IllegalArgumentException {

    public DeletionRequestValidationException(String message) {
        super(message);
    }
}
