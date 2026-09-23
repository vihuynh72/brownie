package io.github.vihuynh72.brownie.core.connector;

import java.util.Set;

/**
 * The few calls a connection makes to the provider's authorization server:
 * turning a consent into tokens, getting a fresh access token from a stored
 * refresh token, asking which account a token belongs to, and revoking.
 *
 * <p>Failures come back as three distinct kinds, because each means
 * something different to the person: {@link ProviderTokenRejectedException}
 * (the provider refused this token or consent; connecting again is the only
 * way forward), {@link ProviderUnavailableException} (it could not be reached
 * or failed; trying later may work), and {@link ProviderMisconfiguredException}
 * (it refused Brownie's own credentials; only whoever runs Brownie can fix it).
 * Asking which account a token belongs to can also end in {@link
 * ConnectorBlockedByOrganizationException} (the organization managing the
 * account does not allow this app; only its administrator can change that).
 */
public interface ConnectorOAuthClient {

    /** The permissions a consent must include for this kind of access to work at all. */
    Set<String> requiredScopes(ConnectorAccess access);

    ProviderTokens exchange(ConnectorAccess access, String authorizationCode, String codeVerifier);

    ProviderTokens refresh(ConnectorAccess access, String refreshToken);

    ProviderAccount describeAccount(ConnectorAccess access, String accessToken);

    /** Asks the provider to forget this token and everything granted with it. A token it already did not know counts as revoked. */
    void revoke(String token);
}
