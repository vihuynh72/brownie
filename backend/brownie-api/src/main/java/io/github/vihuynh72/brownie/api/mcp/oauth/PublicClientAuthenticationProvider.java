package io.github.vihuynh72.brownie.api.mcp.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Authenticates the public client a bare {@code client_id} names, for the
 * requests the stock public-client provider refuses to look at. Spring's
 * {@code PublicClientAuthenticationProvider} exists to verify PKCE at the
 * code exchange and so requires a {@code code} and {@code code_verifier}
 * on every request it touches -- an unsolicited pair there means
 * {@code invalid_client} before the refresh or revocation logic ever
 * runs. This provider backs away from anything carrying PKCE parameters
 * (the exchange stays with the stock provider) and otherwise answers for
 * a client_id that resolves to a client registered with
 * {@code token_endpoint_auth_method} {@code none}.
 */
final class PublicClientAuthenticationProvider implements AuthenticationProvider {

    private final RegisteredClientRepository registeredClientRepository;

    PublicClientAuthenticationProvider(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || token.getAdditionalParameters().containsKey("code")
                || token.getAdditionalParameters().containsKey("code_verifier")) {
            return null;
        }
        RegisteredClient client = registeredClientRepository.findByClientId(token.getPrincipal().toString());
        if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT, "Unknown public client.", null));
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
