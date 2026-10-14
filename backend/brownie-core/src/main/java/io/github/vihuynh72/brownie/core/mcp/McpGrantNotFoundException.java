package io.github.vihuynh72.brownie.core.mcp;

/**
 * The grant id names nothing this member can see: never issued, issued to
 * someone else, or already revoked. One exception for all three so the
 * response cannot be used to tell them apart.
 */
public class McpGrantNotFoundException extends RuntimeException {

    public McpGrantNotFoundException(long grantId) {
        super("No MCP grant " + grantId + " in this workspace.");
    }
}
