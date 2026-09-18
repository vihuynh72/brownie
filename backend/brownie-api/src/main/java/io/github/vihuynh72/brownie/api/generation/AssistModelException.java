package io.github.vihuynh72.brownie.api.generation;

/** The one bounded model call a composer command makes did not produce a usable answer -- mapped to a 502 by {@code ApiExceptionHandler}. */
public final class AssistModelException extends RuntimeException {

    public AssistModelException(String message) {
        super(message);
    }
}
