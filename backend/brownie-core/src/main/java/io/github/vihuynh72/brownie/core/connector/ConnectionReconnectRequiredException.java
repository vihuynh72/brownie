package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/** The connection exists but cannot be used until its person connects again; {@code reason} says why. */
public class ConnectionReconnectRequiredException extends RuntimeException {

    private final ConnectorAccess access;
    private final ReconnectReason reason;

    public ConnectionReconnectRequiredException(ConnectorAccess access, ReconnectReason reason) {
        super("The Google connection for " + access + " needs to be connected again (" + reason + ").");
        this.access = Objects.requireNonNull(access, "access");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ConnectorAccess access() {
        return access;
    }

    public ReconnectReason reason() {
        return reason;
    }
}
