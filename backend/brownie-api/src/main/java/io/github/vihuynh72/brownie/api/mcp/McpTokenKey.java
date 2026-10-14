package io.github.vihuynh72.brownie.api.mcp;

import java.util.Base64;

/**
 * The 32-byte key behind {@code BROWNIE_MCP_TOKEN_KEY}, required the
 * moment MCP is switched on. The authorization server uses it to
 * fingerprint stored token values so a leaked table leaks nothing that
 * authenticates; the key is validated at start-up even before that slice
 * ships so the switch-on is never half-configured.
 */
record McpTokenKey(byte[] key) {

    private static final int KEY_BYTES = 32;

    static McpTokenKey fromBase64(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "BROWNIE_MCP_ENABLED is true but BROWNIE_MCP_TOKEN_KEY is not set: MCP will not start without it."
                            + " Generate one with: openssl rand -base64 32");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BROWNIE_MCP_TOKEN_KEY is not base64. Generate one with: openssl rand -base64 32");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "BROWNIE_MCP_TOKEN_KEY must be 32 bytes base64-encoded; got " + key.length + " bytes."
                            + " Generate one with: openssl rand -base64 32");
        }
        return new McpTokenKey(key);
    }
}
