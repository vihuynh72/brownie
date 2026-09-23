package io.github.vihuynh72.brownie.core.connector;

/** No usable or reconnectable connection of this kind belongs to this person in this workspace. */
public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(ConnectorAccess access) {
        super("No Google connection for " + access + " exists for this person in this workspace.");
    }
}
