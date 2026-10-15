package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.api.mcp.McpSettings;
import java.util.Collection;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

/**
 * This authorization server exists to issue tokens for exactly one
 * resource -- the configured {@code <public origin>/mcp} -- so the RFC
 * 8707 {@code resource} parameter is not negotiated, it is required and
 * checked. At authorize a missing or different value is refused with
 * {@code invalid_target}; at token a {@code resource} that was sent and
 * does not match is refused the same way.
 */
final class McpResourceEnforcement {

    private static final String INVALID_TARGET = "invalid_target";

    private final String requiredResource;

    McpResourceEnforcement(McpSettings settings) {
        this.requiredResource = settings.resource();
    }

    /**
     * Authorize-side validation, added to the code-request validator
     * chain. The same chain runs twice per request -- once in the
     * endpoint's early validating filter, where the context carries only
     * the authentication token, and once in the provider, where the built
     * {@link OAuth2AuthorizationRequest} exists -- so the parameters are
     * read from whichever one this pass was handed.
     */
    void validateAuthorizeRequest(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationRequest request = context.getAuthorizationRequest();
        Object resource = request != null
                ? request.getAdditionalParameters().get("resource")
                : ((OAuth2AuthorizationCodeRequestAuthenticationToken) context.getAuthentication())
                        .getAdditionalParameters().get("resource");
        if (!requiredResource.equals(singleResource(resource))) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(INVALID_TARGET, "resource must be " + requiredResource, null),
                    (OAuth2AuthorizationCodeRequestAuthenticationToken) context.getAuthentication());
        }
    }

    /**
     * Token-side validation, run as the first request converter at the
     * token endpoint. A {@code resource} that is sent and is not ours is
     * refused outright; absence stays permitted because RFC 8707 makes
     * the parameter optional on the exchange and the authorization
     * already pinned it at authorize time.
     */
    org.springframework.security.web.authentication.AuthenticationConverter tokenRequestConverter() {
        return request -> {
            String[] resources = request.getParameterValues("resource");
            if (resources != null && !requiredResource.equals(singleResource(resources))) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error(INVALID_TARGET, "resource must be " + requiredResource, null));
            }
            return null;
        };
    }

    /** The client must name this server alone; a list that happens to contain it widens the audience, which we do not mint. */
    private static String singleResource(Object resource) {
        if (resource instanceof String value) {
            return value;
        }
        if (resource instanceof Collection<?> values && values.size() == 1) {
            Object only = values.iterator().next();
            return only instanceof String s ? s : null;
        }
        if (resource instanceof String[] values && values.length == 1) {
            return values[0];
        }
        return null;
    }
}
