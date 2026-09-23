package io.github.vihuynh72.brownie.core.connector;

/**
 * The provider accepted the consent but returned no refresh token, so there is
 * nothing Brownie could keep for later use. Asking again, with the provider's
 * consent screen shown, is what makes it send one.
 */
public class ConnectorConsentIncompleteException extends RuntimeException {

    public ConnectorConsentIncompleteException(ConnectorAccess access) {
        super("The provider returned no refresh token for " + access + ".");
    }
}
