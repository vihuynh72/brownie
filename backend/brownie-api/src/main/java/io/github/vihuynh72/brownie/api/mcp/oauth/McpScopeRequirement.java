package io.github.vihuynh72.brownie.api.mcp.oauth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

/**
 * A consent with nothing to approve can never finish -- the page would
 * list no permission and its Allow has nothing to grant back -- so an
 * authorize request that asks for no scope is refused at once with
 * {@code invalid_scope} rather than parked on that screen. RFC 6749 §3.3
 * lets a server fail a request that omits the scope parameter, and the
 * clients this server serves read {@code scopes_supported} and ask for
 * scopes.
 */
final class McpScopeRequirement {

    private McpScopeRequirement() {
    }

    /** Authorize-side validation, added to the code-request validator chain after the resource check. */
    static void validate(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken token =
                (OAuth2AuthorizationCodeRequestAuthenticationToken) context.getAuthentication();
        if (token.getScopes().isEmpty()) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE,
                            "scope is required: ask for read, fill, export or a subset", null),
                    token);
        }
    }
}
