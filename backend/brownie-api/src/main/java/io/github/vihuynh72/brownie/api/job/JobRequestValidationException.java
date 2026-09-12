package io.github.vihuynh72.brownie.api.job;

/** A client command or stream cursor could not be safely interpreted. */
public final class JobRequestValidationException extends IllegalArgumentException {

    public JobRequestValidationException(String message) {
        super(message);
    }
}
