package io.github.vihuynh72.brownie.core.mcp;

import java.util.Optional;

/**
 * The three capabilities an MCP credential can carry, stored on the grant
 * as the lowercase tokens a chat client would name. Membership on the
 * grant is what the {@code /mcp} chain checks a call against; a tool
 * never widens it.
 */
public enum McpScope {

    READ("read"),
    FILL("fill"),
    EXPORT("export");

    private final String token;

    McpScope(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<McpScope> parse(String token) {
        for (McpScope scope : values()) {
            if (scope.token.equals(token)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
