package io.github.vihuynh72.brownie.core.connector;

/**
 * The person agreed, but the organization that manages their account does
 * not let this app use their data. Neither the person nor whoever runs
 * Brownie can change that; only the organization's administrator can.
 */
public class ConnectorBlockedByOrganizationException extends RuntimeException {

    public ConnectorBlockedByOrganizationException(ConnectorAccess access) {
        super("The organization that manages this account does not allow " + access + " for this app.");
    }
}
