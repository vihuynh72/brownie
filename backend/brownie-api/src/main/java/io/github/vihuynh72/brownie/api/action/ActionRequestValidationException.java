package io.github.vihuynh72.brownie.api.action;

/** An action request that is malformed as a request: a missing field, a value of the wrong shape. */
public class ActionRequestValidationException extends IllegalArgumentException {

    public ActionRequestValidationException(String message) {
        super(message);
    }
}
