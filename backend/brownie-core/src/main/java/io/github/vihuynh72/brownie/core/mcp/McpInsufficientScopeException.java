package io.github.vihuynh72.brownie.core.mcp;

/**
 * The caller's grant is real but does not carry the scope this action
 * needs. The {@code /mcp} chain turns this into the bearer challenge's
 * {@code insufficient_scope} error; reaching it from inside a tool is
 * defense in depth, since the chain already gates every tool on its
 * scope.
 */
public class McpInsufficientScopeException extends RuntimeException {

    private final McpScope needed;

    public McpInsufficientScopeException(McpScope needed) {
        super("This credential does not carry the '" + needed.token() + "' scope.");
        this.needed = needed;
    }

    public McpScope needed() {
        return needed;
    }
}
