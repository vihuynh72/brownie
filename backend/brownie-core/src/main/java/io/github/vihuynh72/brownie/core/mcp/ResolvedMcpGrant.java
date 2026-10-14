package io.github.vihuynh72.brownie.core.mcp;

import java.util.Objects;
import java.util.Set;

/**
 * What the database's grant-resolution function returns for a token
 * digest: the live grant's identity columns, already filtered to
 * non-revoked rows. Lookup happens under the function's owner rather than
 * a workspace member's view because resolving the token is what
 * establishes who is calling in the first place.
 */
public record ResolvedMcpGrant(
        long workspaceId,
        long grantId,
        long userId,
        McpGrantKind kind,
        String name,
        Set<McpScope> scopes) {

    public ResolvedMcpGrant {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scopes, "scopes");
        scopes = Set.copyOf(scopes);
    }
}
