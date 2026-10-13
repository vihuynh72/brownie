package io.github.vihuynh72.brownie.core.batch;

/**
 * A COMPOSE mapping was asked for where compose is not available -- the
 * deployment does not offer it, or the field is not a text field a model
 * could write for.
 */
public class BatchComposeNotOfferedException extends RuntimeException {

    public BatchComposeNotOfferedException(String message) {
        super(message);
    }
}
