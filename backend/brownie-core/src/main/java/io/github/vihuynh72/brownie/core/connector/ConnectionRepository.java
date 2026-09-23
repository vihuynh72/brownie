package io.github.vihuynh72.brownie.core.connector;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped persistence for a person's connections. Every method sees
 * only the caller's own connections in the named workspace; another member's
 * are invisible, not merely refused. Connecting and disconnecting each write
 * their audit row in the same transaction as the change.
 */
public interface ConnectionRepository {

    /** Every connection this person has made in this workspace, disconnected ones included, newest first. */
    List<Connection> findForPerson(long workspaceId, long userId);

    /** The usable or reconnect-waiting connection for this kind of access, if there is one. */
    Optional<Connection> findOpen(long workspaceId, long userId, ConnectorAccess access);

    /** The stored token of a usable connection; empty for any other state, or for a connection that is not this person's. */
    Optional<SealedToken> findToken(long workspaceId, long userId, long connectionId);

    /**
     * Records a completed consent: a new connection, or, when one is already
     * open for this kind of access, that same connection made usable again
     * with the new token. Two consents completing together are taken one at a
     * time. Refuses with {@link ConnectionAccountMismatchException} when the
     * open connection belongs to a different account.
     */
    Connection saveActive(
            long workspaceId, long userId, ConnectorAccess access, ProviderAccount account, List<String> grantedScopes, SealedToken token);

    /** A usable connection whose token stopped working: the token is wiped and the reason recorded. Empty when it was no longer usable. */
    Optional<Connection> requireReconnect(long workspaceId, long userId, long connectionId, ReconnectReason reason);

    /**
     * Ends an open connection for good: its token is wiped, every read it had
     * been granted is revoked, and what the provider said about revocation is
     * recorded. Empty when it was already disconnected.
     */
    Optional<Connection> disconnect(long workspaceId, long userId, long connectionId, ProviderRevocation revocation);
}
