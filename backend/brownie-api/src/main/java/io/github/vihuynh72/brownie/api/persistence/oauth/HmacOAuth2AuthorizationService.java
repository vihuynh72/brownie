package io.github.vihuynh72.brownie.api.persistence.oauth;

import io.github.vihuynh72.brownie.api.mcp.McpTokenKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * JDBC authorization storage that never persists a value which
 * authenticates. Authorization codes, access and refresh tokens and the
 * consent state are written as {@code h1:} + base64url of their
 * HMAC-SHA256 under {@code BROWNIE_MCP_TOKEN_KEY}, so a leaked table
 * yields nothing that calls {@code /mcp}; a value already carrying the
 * marker -- one read back from the table and saved again -- is written
 * unchanged, which is what keeps the write path idempotent across a
 * load-modify-save cycle.
 *
 * <p>Lookups fingerprint the presented value to find the row, then put
 * the presented value back on the authorization they return: Spring's
 * providers prove possession by comparing the token they were handed
 * against the one stored in the object, and the fingerprint itself would
 * never equal it. Every other token on the row keeps its fingerprint, so
 * re-saving loses nothing.
 */
final class HmacOAuth2AuthorizationService extends JdbcOAuth2AuthorizationService {

    private static final String MARKER = "h1:";

    private final SecretKeySpec key;

    HmacOAuth2AuthorizationService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository, McpTokenKey tokenKey) {
        super(jdbcOperations, registeredClientRepository);
        this.key = new SecretKeySpec(tokenKey.key(), "HmacSHA256");
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        super.save(fingerprinted(authorization));
    }

    @Override
    public OAuth2Authorization findByToken(String tokenValue, OAuth2TokenType tokenType) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return null;
        }
        String fingerprint = fingerprint(tokenValue);
        OAuth2Authorization authorization = super.findByToken(fingerprint, tokenType);
        if (authorization == null) {
            return null;
        }
        return withPresentedValue(authorization, fingerprint, tokenValue);
    }

    private OAuth2Authorization fingerprinted(OAuth2Authorization authorization) {
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
        fingerprintToken(builder, authorization.getToken(OAuth2AuthorizationCode.class));
        fingerprintToken(builder, authorization.getAccessToken());
        fingerprintToken(builder, authorization.getRefreshToken());
        // The consent state is stored as the "state" attribute; the
        // client's own state rides inside the authorization-request
        // attribute, where it must stay verbatim because the OAuth
        // response has to echo it back.
        builder.attributes(attributes -> {
            Object state = attributes.get(OAuth2ParameterNames.STATE);
            if (state instanceof String value) {
                attributes.put(OAuth2ParameterNames.STATE, fingerprint(value));
            }
        });
        return builder.build();
    }

    /**
     * The token the caller presented is handed back in the clear inside
     * the returned authorization: a provider that reads it off the object
     * (to invalidate it, to compare it) needs the real value, and every
     * fingerprint left in place simply re-fingerprints to itself on save.
     */
    private OAuth2Authorization withPresentedValue(
            OAuth2Authorization authorization, String fingerprint, String presentedValue) {
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
        restoreToken(builder, authorization.getToken(OAuth2AuthorizationCode.class), fingerprint, presentedValue);
        restoreToken(builder, authorization.getAccessToken(), fingerprint, presentedValue);
        restoreToken(builder, authorization.getRefreshToken(), fingerprint, presentedValue);
        Object state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        if (fingerprint.equals(state)) {
            builder.attributes(attributes -> attributes.put(OAuth2ParameterNames.STATE, presentedValue));
        }
        return builder.build();
    }

    private void fingerprintToken(
            OAuth2Authorization.Builder builder, OAuth2Authorization.Token<? extends OAuth2Token> token) {
        if (token == null) {
            return;
        }
        OAuth2Token value = token.getToken();
        builder.token(fingerprinted(value), metadata -> metadata.putAll(token.getMetadata()));
    }

    private void restoreToken(
            OAuth2Authorization.Builder builder,
            OAuth2Authorization.Token<? extends OAuth2Token> token,
            String fingerprint,
            String presentedValue) {
        if (token != null && fingerprint.equals(token.getToken().getTokenValue())
                && !fingerprint.equals(presentedValue)) {
            OAuth2Token stored = token.getToken();
            builder.token(withValue(stored, presentedValue), metadata -> metadata.putAll(token.getMetadata()));
        }
    }

    private OAuth2Token fingerprinted(OAuth2Token token) {
        String value = token.getTokenValue();
        if (value != null && value.startsWith(MARKER)) {
            return token;
        }
        return withValue(token, fingerprint(value));
    }

    private static OAuth2Token withValue(OAuth2Token token, String value) {
        if (token instanceof OAuth2AuthorizationCode code) {
            return new OAuth2AuthorizationCode(value, code.getIssuedAt(), code.getExpiresAt());
        }
        if (token instanceof OAuth2AccessToken accessToken) {
            return new OAuth2AccessToken(
                    accessToken.getTokenType(), value, accessToken.getIssuedAt(), accessToken.getExpiresAt(),
                    accessToken.getScopes());
        }
        if (token instanceof OAuth2RefreshToken refreshToken) {
            return new OAuth2RefreshToken(value, refreshToken.getIssuedAt(), refreshToken.getExpiresAt());
        }
        // User and device codes can never exist here -- their endpoints
        // are not configured -- so anything else is unexpected.
        throw new IllegalStateException("Unexpected token type on an authorization: " + token.getClass().getName());
    }

    private String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return MARKER + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
