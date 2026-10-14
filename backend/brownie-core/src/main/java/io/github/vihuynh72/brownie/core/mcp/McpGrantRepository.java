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

    /** Every grant this member owns in this workspace, live and revoked, newest first. */
    List<McpGrant> findAllForUser(long workspaceId, long userId);

    /** Marks the grant revoked and returns it, or empty when no live grant with that id belongs to this member. */
    Optional<McpGrant> revoke(long workspaceId, long userId, long grantId);
}
