package io.github.vihuynh72.brownie.core.connector;

/** The provider could not be reached, timed out, or failed on its own side. Trying again later may work. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
