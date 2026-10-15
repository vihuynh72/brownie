package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.core.mcp.McpScope;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * The rules a registration must live under, and the client it becomes.
 * The stock validator is replaced because it refuses a {@code scope}
 * field outright, while the clients this server registers for send one;
 * everything else it checked is repeated here and the pieces that settle
 * what this server is -- redirect URIs that are https or loopback http, a
 * name, the client authentication methods we answer, the two grant types --
 * are enforced on top. The converter then turns a valid registration into the client
 * that gets stored: PKCE and consent on for everyone, opaque one-hour
 * access tokens, refresh tokens that rotate and live until revoked, and
 * a hashed secret only when the client asked to be confidential.
 */
final class McpClientRegistration {

    private static final int NAME_LIMIT = 200;
    private static final StringKeyGenerator CLIENT_SECRETS =
            new Base64StringKeyGenerator(java.util.Base64.getUrlEncoder().withoutPadding(), 48);

    private static final Set<String> CLIENT_AUTH_METHODS = Set.of(
            ClientAuthenticationMethod.NONE.getValue(),
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(),
            ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue());
    private static final Set<String> GRANT_TYPES = Set.of(
            AuthorizationGrantType.AUTHORIZATION_CODE.getValue(), AuthorizationGrantType.REFRESH_TOKEN.getValue());

    private McpClientRegistration() {
    }

    /** Replaces the stock DCR validator: everything it refused on safety grounds is refused here too, except {@code scope}, which we accept. */
    static void validate(OAuth2ClientRegistrationAuthenticationContext context) {
        OAuth2ClientRegistration registration =
                ((OAuth2ClientRegistrationAuthenticationToken) context.getAuthentication())
                        .getClientRegistration();
        List<String> redirectUris = registration.getRedirectUris();
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw invalidClientMetadata("redirect_uris is required");
        }
        for (String redirectUri : redirectUris) {
            validateRedirectUri(redirectUri);
        }
        String clientName = registration.getClientName();
        if (clientName == null || clientName.isBlank() || clientName.length() > NAME_LIMIT) {
            throw invalidClientMetadata("client_name is required and stays under " + NAME_LIMIT + " characters");
        }
        String authMethod = registration.getTokenEndpointAuthenticationMethod();
        if (authMethod != null && !CLIENT_AUTH_METHODS.contains(authMethod)) {
            throw invalidClientMetadata("token_endpoint_auth_method must be one of " + CLIENT_AUTH_METHODS);
        }
        List<String> grantTypes = registration.getGrantTypes();
        if (grantTypes != null && !GRANT_TYPES.containsAll(grantTypes)) {
            throw invalidClientMetadata("grant_types may only ask for " + GRANT_TYPES);
        }
        if (registration.getJwkSetUrl() != null
                && !"https".equalsIgnoreCase(registration.getJwkSetUrl().getProtocol())) {
            throw invalidClientMetadata("jwks_uri must use https");
        }
    }

    /** The client a valid registration becomes. */
    static RegisteredClient convert(OAuth2ClientRegistration registration) {
        String authMethod = registration.getTokenEndpointAuthenticationMethod();
        ClientAuthenticationMethod method = authMethod == null
                // RFC 7591 defaults an absent token_endpoint_auth_method to
                // client_secret_basic.
                ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                : new ClientAuthenticationMethod(authMethod);
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mcp-" + UUID.randomUUID())
                .clientName(registration.getClientName().trim())
                .clientAuthenticationMethod(method)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(registration.getRedirectUris()))
                .scopes(scopes -> scopes.addAll(allowedScopes(registration.getScopes())))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .refreshTokenTimeToLive(Duration.ofDays(3650))
                        .reuseRefreshTokens(false)
                        .build());
        if (!ClientAuthenticationMethod.NONE.equals(method)) {
            // Stored bcrypt-hashed by the registration provider's password
            // encoder; the plaintext crosses the wire once, in the
            // registration response.
            builder.clientSecret(CLIENT_SECRETS.generateKey());
        }
        return builder.build();
    }

    /**
     * The client's requested scopes intersected with the ones this server
     * issues; a client that sends no {@code scope} field -- or none that
     * are ours -- gets the full set.
     */
    private static Set<String> allowedScopes(List<String> requested) {
        Set<String> ours = new LinkedHashSet<>();
        for (McpScope scope : McpScope.values()) {
            ours.add(scope.token());
        }
        if (requested == null || requested.isEmpty()) {
            return ours;
        }
        Set<String> granted = new LinkedHashSet<>(requested);
        granted.retainAll(ours);
        return granted.isEmpty() ? ours : granted;
    }

    private static void validateRedirectUri(String redirectUri) {
        URI parsed;
        try {
            parsed = URI.create(redirectUri);
        } catch (IllegalArgumentException e) {
            throw invalidRedirectUri("redirect_uri is not parseable", redirectUri);
        }
        if (parsed.getFragment() != null) {
            throw invalidRedirectUri("redirect_uri must not contain a fragment", redirectUri);
        }
        String scheme = parsed.getScheme();
        if (scheme == null) {
            throw invalidRedirectUri("redirect_uri has no scheme", redirectUri);
        }
        // The only two shapes a chat app's callback may take: https to
        // wherever it lives, or http to the loopback host it listens on
        // during authorization. Anything else -- a private scheme a desktop
        // app would register, say -- hands the code to a place this server
        // has no reason to send one.
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme) && isLoopback(parsed.getHost());
        if (!https && !loopbackHttp) {
            throw invalidRedirectUri("redirect_uri must use https, or http on a loopback address", redirectUri);
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("[::1]");
    }

    private static OAuth2AuthenticationException invalidClientMetadata(String detail) {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_client_metadata", "Invalid client registration: " + detail + ".", null));
    }

    private static OAuth2AuthenticationException invalidRedirectUri(String detail, String uri) {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_redirect_uri", "Invalid client registration: " + detail + " ('" + uri + "').", null));
    }
}
