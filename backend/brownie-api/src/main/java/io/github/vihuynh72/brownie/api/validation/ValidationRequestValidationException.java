package io.github.vihuynh72.brownie.api.validation;

/** A validation command does not match the bounded typed request contract. */
public final class ValidationRequestValidationException extends IllegalArgumentException {

    public ValidationRequestValidationException(String message) {
        super(message);
    }
}
