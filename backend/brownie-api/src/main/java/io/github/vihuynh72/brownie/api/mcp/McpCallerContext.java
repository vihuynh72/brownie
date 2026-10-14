package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the caller the {@code /mcp} chain authenticated. Tools use this rather than ever taking an identity as an argument. */
final class McpCallerContext {

    private McpCallerContext() {
    }

    /** The caller of the request now being handled, never null inside the chain: the filter refuses unauthenticated requests first. */
    static McpCaller require() {
        return current().orElseThrow(
                () -> new IllegalStateException("An MCP tool was reached without an authenticated MCP caller."));
    }

    /**
     * The caller, or empty before authentication has run. The chain's own
     * filters must ask this way: the bearer filter passes a request with no
     * token through unauthenticated rather than answering itself, so the
     * authorization layer at the end of the chain is what says 401.
     */
    static java.util.Optional<McpCaller> current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof McpAuthentication mcp && mcp.getPrincipal() instanceof McpCaller caller) {
            return java.util.Optional.of(caller);
        }
        return java.util.Optional.empty();
    }
}
