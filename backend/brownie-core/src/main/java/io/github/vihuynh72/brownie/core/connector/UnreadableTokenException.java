package io.github.vihuynh72.brownie.core.connector;

/** A stored token that cannot be decrypted with the key in use now, for the connection it is stored under. */
public class UnreadableTokenException extends RuntimeException {

    public UnreadableTokenException(String message) {
        super(message);
    }
}
