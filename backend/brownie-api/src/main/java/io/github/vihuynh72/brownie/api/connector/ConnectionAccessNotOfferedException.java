package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;

/** A connection whose only use is a change this deployment does not offer: there is nothing to agree to it for. */
public class ConnectionAccessNotOfferedException extends RuntimeException {

    private final ConnectorAccess access;

    public ConnectionAccessNotOfferedException(ConnectorAccess access) {
        super(access + " is not offered here.");
        this.access = access;
    }

    public ConnectorAccess access() {
        return access;
    }
}
