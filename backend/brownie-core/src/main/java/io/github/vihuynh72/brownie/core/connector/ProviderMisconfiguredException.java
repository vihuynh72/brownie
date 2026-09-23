package io.github.vihuynh72.brownie.core.connector;

/**
 * The provider refused Brownie's own client credentials, or answered in a way
 * that means Brownie is set up wrongly with it. Nothing the person does can
 * fix this; whoever runs Brownie has to.
 */
public class ProviderMisconfiguredException extends RuntimeException {

    public ProviderMisconfiguredException(String message) {
        super(message);
    }
}
