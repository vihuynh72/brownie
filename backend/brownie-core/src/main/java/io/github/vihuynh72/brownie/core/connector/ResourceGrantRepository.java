package io.github.vihuynh72.brownie.core.connector;

import java.util.List;
import java.util.Optional;

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

    /** One of this person's grants, open or revoked; empty when it is not theirs or does not exist. */
    Optional<ResourceGrant> find(long workspaceId, long userId, long grantId);

    /**
     * Revokes one of this person's open grants, for the reason given, and
     * returns it as it now is; empty when it is not theirs, does not exist or
     * was already revoked. A revoked grant is never opened again.
     */
    Optional<ResourceGrant> revoke(long workspaceId, long userId, long grantId, GrantRevocationReason reason);
}
