package io.github.vihuynh72.brownie.core.connector;

/** This deployment has no connection to the provider set up, so nothing can be connected, read or revoked through it. */
public class ConnectorNotConfiguredException extends RuntimeException {

    public ConnectorNotConfiguredException() {
        super("Connecting a Google account is not set up on this Brownie.");
    }
}
