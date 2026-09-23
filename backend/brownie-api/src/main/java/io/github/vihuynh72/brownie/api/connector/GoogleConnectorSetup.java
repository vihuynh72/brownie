package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.connector.google.GoogleClientSettings;

/**
 * Whether this deployment can connect Google accounts, and if so with which
 * registration. Absent settings mean it cannot: every route that would reach
 * Google says so instead, and the capabilities route tells the interface not
 * to offer it.
 */
public record GoogleConnectorSetup(GoogleClientSettings settings) {

    public boolean configured() {
        return settings != null;
    }
}
