package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;
import java.util.Set;

/**
 * What the provider handed back for a consent or a refresh: a short-lived
 * access token, a refresh token (only from a consent, and only when the
 * provider chose to send one), and the permissions it actually granted.
 */
public record ProviderTokens(String accessToken, String refreshToken, Set<String> grantedScopes) {

    public ProviderTokens {
        Objects.requireNonNull(accessToken, "accessToken");
        grantedScopes = Set.copyOf(grantedScopes);
    }

    /** The permissions only. A token in a log line or an exception message is a token handed to whoever reads it. */
    @Override
    public String toString() {
        return "ProviderTokens[grantedScopes=" + grantedScopes + ", refreshToken=" + (refreshToken == null ? "absent" : "present") + "]";
    }
}
