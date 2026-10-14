package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated identity inside the {@code /mcp} chain: the principal
 * is the resolved {@link McpCaller}, and the granted authorities spell the
 * caller's scopes so an authorization rule can name one. The token itself
 * is deliberately not kept -- nothing after authentication may re-present
 * it.
 */
final class McpAuthentication extends AbstractAuthenticationToken {

    private final McpCaller caller;

    McpAuthentication(McpCaller caller) {
        super(caller.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope.token()))
                .toList());
        this.caller = caller;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return caller;
    }

    /**
     * The rate limiter counts by {@link #getName()}; an MCP call is
     * counted against the grant, not the person, because one token is
     * the rateable identity here -- the person behind it can hold
     * several, each meant to be bounded on its own.
     */
    @Override
    public String getName() {
        return "mcp grant " + caller.grantId();
    }
}
