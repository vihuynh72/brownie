package io.github.vihuynh72.brownie.core.connector;

/** No open choice of this person's has this id: nothing they picked, or something they picked and later took back. */
public class ConnectorResourceNotFoundException extends RuntimeException {

    public ConnectorResourceNotFoundException(long grantId) {
        super("No open choice " + grantId + " for Brownie to read belongs to this person.");
    }
}
