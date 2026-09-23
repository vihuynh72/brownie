package io.github.vihuynh72.brownie.core.connector;

/**
 * The provider refused a token or a consent: it was revoked, it expired, or
 * it was never valid. Retrying cannot help; only connecting again can.
 */
public class ProviderTokenRejectedException extends RuntimeException {

    public ProviderTokenRejectedException(String message) {
        super(message);
    }
}
