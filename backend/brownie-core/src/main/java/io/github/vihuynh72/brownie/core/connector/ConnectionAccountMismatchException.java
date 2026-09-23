package io.github.vihuynh72.brownie.core.connector;

/**
 * A consent came from a different account than the one this kind of access is
 * already connected to. Connecting a second account silently would leave it
 * unclear whose files an earlier copy came from, so it is refused: disconnect
 * first, then connect the other account.
 */
public class ConnectionAccountMismatchException extends RuntimeException {

    public ConnectionAccountMismatchException(ConnectorAccess access) {
        super("This workspace's " + access + " connection belongs to a different Google account.");
    }
}
