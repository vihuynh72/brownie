package io.github.vihuynh72.brownie.api.generation;

/** A composer request that cannot be carried out as asked -- mapped to a 400 by {@code ApiExceptionHandler}. */
public final class AssistRequestValidationException extends IllegalArgumentException {

    public AssistRequestValidationException(String message) {
        super(message);
    }
}
