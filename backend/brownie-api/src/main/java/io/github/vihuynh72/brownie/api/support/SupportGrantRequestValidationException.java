package io.github.vihuynh72.brownie.api.support;

/** A support grant request whose body cannot mean anything, reported as a malformed request. */
public class SupportGrantRequestValidationException extends IllegalArgumentException {

    public SupportGrantRequestValidationException(String message) {
        super(message);
    }
}
