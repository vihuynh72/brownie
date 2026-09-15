package io.github.vihuynh72.brownie.api.generation;

/** A generation command does not match the bounded typed request contract. */
public final class GenerationRequestValidationException extends IllegalArgumentException {

    public GenerationRequestValidationException(String message) {
        super(message);
    }
}
