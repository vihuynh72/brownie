package io.github.vihuynh72.brownie.api.mcp.oauth;

import java.time.Instant;
import java.util.Base64;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Issues refresh tokens to every client the server registers. The stock
 * {@code OAuth2RefreshTokenGenerator} returns null for public clients --
 * {@code token_endpoint_auth_method=none} with the authorization-code
 * grant -- on the reasoning that a client without a secret cannot hold a
 * long-lived credential safely. The clients this server exists for are
 * exactly public chat clients, and OAuth 2.1's answer to that is rotation
 * plus revocation, both of which the clients' registrations carry, so the
 * check that would withhold the token is dropped.
 */
final class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private final StringKeyGenerator keys =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!context.getTokenType().equals(org.springframework.security.oauth2.server.authorization.OAuth2TokenType.REFRESH_TOKEN)) {
            return null;
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(keys.generateKey(), issuedAt, expiresAt);
    }
}
