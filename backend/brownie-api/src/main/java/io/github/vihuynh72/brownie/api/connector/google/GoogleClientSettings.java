package io.github.vihuynh72.brownie.api.connector.google;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Brownie's own registration with Google, and where Google is. The addresses
 * default to Google's own and are settings only so that tests can point them
 * at a stand-in; a hosted deployment refuses to start with anything else.
 * {@code redirectUri} is the one address Google sends a person back to, which
 * must match what is registered with Google character for character.
 */
public record GoogleClientSettings(
        String clientId,
        String clientSecret,
        URI redirectUri,
        URI authorizationUri,
        URI tokenUri,
        URI revocationUri,
        URI userInfoUri,
        URI apiBaseUri,
        String issuer,
        Duration connectTimeout,
        Duration readTimeout) {

    public GoogleClientSettings {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        Objects.requireNonNull(redirectUri, "redirectUri");
        Objects.requireNonNull(authorizationUri, "authorizationUri");
        Objects.requireNonNull(tokenUri, "tokenUri");
        Objects.requireNonNull(revocationUri, "revocationUri");
        Objects.requireNonNull(userInfoUri, "userInfoUri");
        Objects.requireNonNull(apiBaseUri, "apiBaseUri");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
    }

    /** Everything but the client secret. */
    @Override
    public String toString() {
        return "GoogleClientSettings[clientId=" + clientId + ", redirectUri=" + redirectUri + "]";
    }
}
