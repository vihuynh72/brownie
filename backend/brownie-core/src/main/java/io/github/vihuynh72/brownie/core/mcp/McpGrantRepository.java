package io.github.vihuynh72.brownie.core.mcp;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persists MCP grants. {@code workspaceId} and {@code userId} ride on
 * every member-facing method the way every other tenant-scoped
 * repository's methods carry them; the one exception is
 * {@link #resolvePersonalToken}, which finds the grant by digest alone
 * because the token is what identifies the caller.
 */
public interface McpGrantRepository {

    McpGrant create(
            long workspaceId,
            long userId,
            McpGrantKind kind,
            String name,
            String registeredClientId,
            Set<McpScope> scopes,
            String tokenSha256,
            String tokenHint);

    /** The live grant for this token digest, or empty when the token is unknown or its grant is revoked. */
    Optional<ResolvedMcpGrant> resolvePersonalToken(String tokenSha256);

    /**
     * The live OAuth-client grant this person holds for the registered
     * client, or empty when none was granted or it was revoked. The
     * caller's workspace rides on the grant row itself, so the lookup
     * needs only the pair the authorization carries.
     */
    Optional<ResolvedMcpGrant> findLiveOAuthGrant(long userId, String registeredClientId);

    /**
     * Records a consent grant: creates the OAuth-client grant when none is
     * live, or refreshes the live one's client name and scopes when the
     * same person consents to the same client again.
     */
    McpGrant createOrRefreshOAuthGrant(
            long workspaceId, long userId, String clientName, String registeredClientId, Set<McpScope> scopes);

    /**
     * Touches the grant's {@code last_used_at}, at most once a minute --
     * the throttle lives in the database function the call rides on, so
     * a chatty client cannot turn each call into a write.
     */
    void markUsed(long grantId);

    /** Every grant this member owns in this workspace, live and revoked, newest first. */
    List<McpGrant> findAllForUser(long workspaceId, long userId);

    /** Marks the grant revoked and returns it, or empty when no live grant with that id belongs to this member. */
    Optional<McpGrant> revoke(long workspaceId, long userId, long grantId);
}
