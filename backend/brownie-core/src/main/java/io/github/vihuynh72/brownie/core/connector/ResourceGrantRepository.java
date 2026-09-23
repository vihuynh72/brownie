package io.github.vihuynh72.brownie.core.connector;

import java.util.List;

/**
 * Tenant-scoped persistence for what a person chose for Brownie to read. Only
 * the person whose connection a grant belongs to sees it, and the only change
 * a grant ever sees is being revoked, which disconnecting does to every grant
 * of the connection.
 */
public interface ResourceGrantRepository {

    /**
     * Records the choice, or returns the open grant that already records it.
     * The connection must be usable: the database refuses a grant through a
     * connection that is not.
     */
    ResourceGrant grant(long workspaceId, long userId, long connectionId, ResourceGrantType type, String externalId, String displayName);

    /** The open grants through one connection, most recent first. */
    List<ResourceGrant> findOpen(long workspaceId, long userId, long connectionId);
}
