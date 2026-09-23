package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/**
 * A connection that has just proved it still works, with the short-lived
 * access token the provider issued for this one request. The access token is
 * held only for the request that asked for it; nothing stores it.
 */
public record UsableConnection(Connection connection, String accessToken) {

    public UsableConnection {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(accessToken, "accessToken");
    }

    @Override
    public String toString() {
        return "UsableConnection[connection=" + connection.id() + ", access=" + connection.access() + "]";
    }
}
