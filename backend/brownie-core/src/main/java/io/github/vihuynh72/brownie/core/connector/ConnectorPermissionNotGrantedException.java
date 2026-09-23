package io.github.vihuynh72.brownie.core.connector;

/** The person agreed, but left out a permission this kind of access cannot work without. */
public class ConnectorPermissionNotGrantedException extends RuntimeException {

    public ConnectorPermissionNotGrantedException(ConnectorAccess access) {
        super("The consent for " + access + " did not include the permission it needs.");
    }
}
