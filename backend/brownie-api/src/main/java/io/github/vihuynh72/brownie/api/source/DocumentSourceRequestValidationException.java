package io.github.vihuynh72.brownie.api.source;

/** A malformed attach request -- mapped to a 400 by {@code ApiExceptionHandler}. */
public class DocumentSourceRequestValidationException extends IllegalArgumentException {

    public DocumentSourceRequestValidationException(String message) {
        super(message);
    }
}
