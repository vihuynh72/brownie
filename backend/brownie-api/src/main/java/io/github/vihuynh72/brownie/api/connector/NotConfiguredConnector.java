package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.connector.google.GoogleScopes;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorOAuthClient;
import io.github.vihuynh72.brownie.core.connector.ConnectorTokenCipher;
import io.github.vihuynh72.brownie.core.connector.ProviderAccount;
import io.github.vihuynh72.brownie.core.connector.ProviderTokens;
import io.github.vihuynh72.brownie.core.connector.SealedToken;
import io.github.vihuynh72.brownie.core.connector.TokenBinding;

import java.util.Set;

/**
 * Stands in for Google and for the token key on a deployment where neither
 * is set up: every attempt to reach Google, or to seal or open a token, is
 * refused with one clear reason, while connections already recorded can
 * still be listed and disconnected here.
 */
final class NotConfiguredConnector implements ConnectorOAuthClient, ConnectorTokenCipher {

    @Override
    public Set<String> requiredScopes(ConnectorAccess access) {
        return GoogleScopes.required(access);
    }

    @Override
    public ProviderTokens exchange(ConnectorAccess access, String authorizationCode, String codeVerifier) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public ProviderTokens refresh(ConnectorAccess access, String refreshToken) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public ProviderAccount describeAccount(ConnectorAccess access, String accessToken) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public void revoke(String token) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public SealedToken seal(String token, TokenBinding binding) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public String open(SealedToken sealed, TokenBinding binding) {
        throw new ConnectorNotConfiguredException();
    }
}
