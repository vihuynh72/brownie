package io.github.vihuynh72.brownie.core.mcp;

import java.util.Objects;
import java.util.Set;

/**
 * Who an MCP request is, decided by the bearer chain before any tool
 * runs: the person the grant belongs to, the workspace it is scoped to,
 * the grant it authenticates as, the name that grant was issued under,
 * and the scopes it carries. Both credential kinds -- a pasted personal
 * token and an OAuth client grant -- resolve to this same shape, so the
 * tools never ask which one called.
 */
public record McpCaller(
        long userId,
        long workspaceId,
        long grantId,
        String clientName,
        Set<McpScope> scopes) {

    public McpCaller {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("Client name must not be blank.");
        }
        Objects.requireNonNull(scopes, "scopes");
        scopes = Set.copyOf(scopes);
    }

    public boolean has(McpScope scope) {
        return scopes.contains(scope);
    }
}
