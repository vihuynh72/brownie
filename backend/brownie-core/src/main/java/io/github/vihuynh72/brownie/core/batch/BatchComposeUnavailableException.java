package io.github.vihuynh72.brownie.core.batch;

/**
 * The compose step itself was asked for where compose is not offered --
 * a state, not a bad request, so it answers a conflict rather than the
 * unprocessable a COMPOSE mapping gets.
 */
public final class BatchComposeUnavailableException extends RuntimeException {

    public BatchComposeUnavailableException(String message) {
        super(message);
    }
}
