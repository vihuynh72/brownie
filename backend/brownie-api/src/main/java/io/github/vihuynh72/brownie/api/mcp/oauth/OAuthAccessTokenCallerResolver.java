package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.api.mcp.McpCallerResolver;
import io.github.vihuynh72.brownie.api.mcp.McpSettings;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Resolves OAuth access tokens presented to {@code /mcp}. The
 * authorization service holds only {@code h1:} fingerprints and already
 * turns a presented value into one for the lookup, so this resolver hands
 * it the raw token; everything after the lookup is checked anyway: the
 * token must be live, issued for this deployment's one configured
 * resource, carried by a {@code user:<id>} principal, and backed by a
 * grant that is still live. The caller the chain then sees carries the
 * intersection of what the token claims and what the grant still allows
 * -- either side can only narrow it.
 */
public final class OAuthAccessTokenCallerResolver implements McpCallerResolver {

    private final OAuth2AuthorizationService authorizationService;
    private final McpGrantService grantService;
    private final String requiredResource;

    public OAuthAccessTokenCallerResolver(
            OAuth2AuthorizationService authorizationService, McpGrantService grantService, McpSettings settings) {
        this.authorizationService = authorizationService;
        this.grantService = grantService;
        this.requiredResource = settings.resource();
    }

    @Override
    public Optional<McpCaller> resolve(String bearerToken) {
        OAuth2Authorization authorization =
                authorizationService.findByToken(bearerToken, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null
                || authorization.getAccessToken() == null
                || !authorization.getAccessToken().isActive()) {
            return Optional.empty();
        }
        if (!requiredResource.equals(requestedResource(authorization))) {
            return Optional.empty();
        }
        java.util.OptionalLong userId = McpPrincipalName.userIdOf(authorization.getPrincipalName());
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        return grantService
                .findLiveOAuthGrant(userId.getAsLong(), authorization.getRegisteredClientId())
                .map(grant -> {
                    grantService.markUsed(grant.grantId());
                    return new McpCaller(
                            grant.userId(),
                            grant.workspaceId(),
                            grant.grantId(),
                            grant.name(),
                            intersect(grant.scopes(), authorization.getAuthorizedScopes()));
                });
    }

    /** What the authorization request asked for as RFC 8707 {@code resource}; both endpoints enforce it before issue, and the resource checks it again at use. */
    private String requestedResource(OAuth2Authorization authorization) {
        OAuth2AuthorizationRequest request =
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (request == null) {
            return null;
        }
        Object resource = request.getAdditionalParameters().get("resource");
        return resource instanceof String value ? value : null;
    }

    private static Set<McpScope> intersect(Set<McpScope> grantScopes, Set<String> tokenScopes) {
        Set<McpScope> effective = EnumSet.noneOf(McpScope.class);
        for (McpScope scope : grantScopes) {
            if (tokenScopes.contains(scope.token())) {
                effective.add(scope);
            }
        }
        return effective;
    }
}
