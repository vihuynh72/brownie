package io.github.vihuynh72.brownie.core.support;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped persistence for support grants. Creating and revoking one each write their audit row in the same transaction. */
public interface SupportGrantRepository {

    /**
     * Opens a grant lasting {@code days} whole days from the database's own
     * clock, which is also the clock its seven-day ceiling is checked
     * against. Empty when an open grant of this scope already exists; two
     * requests arriving together cannot both succeed.
     */
    Optional<SupportGrant> create(long workspaceId, long userId, SupportGrantScope scope, int days);

    /** Most recent first, active or not. */
    List<SupportGrant> findAll(long workspaceId, long userId);

    /** Takes an open grant back; empty when there is no such grant, or it was already revoked or has run out, so nothing is recorded as taken back that was no longer given. */
    Optional<SupportGrant> revoke(long workspaceId, long userId, long grantId);
}
