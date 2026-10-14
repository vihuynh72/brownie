package io.github.vihuynh72.brownie.core.mcp;

/**
 * Which credential family a grant row belongs to. Both kinds resolve to
 * the same {@link McpCaller}; the kind decides which columns the row is
 * allowed to carry, enforced by the table's CHECKs.
 */
public enum McpGrantKind {

    PERSONAL_TOKEN,
    OAUTH_CLIENT
}
