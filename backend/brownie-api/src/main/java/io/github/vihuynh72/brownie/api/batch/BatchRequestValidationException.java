package io.github.vihuynh72.brownie.api.batch;

/** A batch request does not match the bounded typed contract. */
public final class BatchRequestValidationException extends IllegalArgumentException {

    public BatchRequestValidationException(String message) {
        super(message);
    }
}
