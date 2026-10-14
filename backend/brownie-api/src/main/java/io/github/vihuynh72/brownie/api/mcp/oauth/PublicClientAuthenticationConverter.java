package io.github.vihuynh72.brownie.api.mcp.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Authenticates a bare {@code client_id} with no secret as a public
 * client. Spring's own {@code PublicClientAuthenticationConverter} only
 * fires on requests carrying PKCE parameters -- the code exchange -- so
 * without this a public client can never authenticate a refresh or a
 * revocation call at all, which chat clients need to do. Requests that do
 * carry a secret or an Authorization header are left for the confidential
 * converters, and the PKCE exchange itself keeps Spring's converter.
 */
final class PublicClientAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String clientId = request.getParameter("client_id");
        if (clientId == null
                || clientId.isBlank()
                || request.getHeader("Authorization") != null
                || request.getParameter("client_secret") != null) {
            return null;
        }
        Map<String, Object> additionalParameters = new HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (!"client_id".equals(name) && values.length == 1) {
                additionalParameters.put(name, values[0]);
            }
        });
        return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null, additionalParameters);
    }
}
