package io.github.vihuynh72.brownie.core.mcp;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * One MCP credential as listed for its owner: every column a person or
 * the OAuth layer needs to recognize and revoke it, and deliberately not
 * the token digest -- what is stored for a token never leaves the
 * database, and what is shown is only the four-character hint.
 */
public record McpGrant(
        long id,
        long workspaceId,
        long userId,
        McpGrantKind kind,
        String name,
        String registeredClientId,
        Set<McpScope> scopes,
        String tokenHint,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime revokedAt) {

    public McpGrant {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scopes, "scopes");
        scopes = Set.copyOf(scopes);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
