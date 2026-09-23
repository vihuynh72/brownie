package io.github.vihuynh72.brownie.api.connector;

/** A connection request that names an unknown kind of access or a return page Brownie does not send people to. */
public class ConnectionRequestValidationException extends IllegalArgumentException {

    public ConnectionRequestValidationException(String message) {
        super(message);
    }
}
