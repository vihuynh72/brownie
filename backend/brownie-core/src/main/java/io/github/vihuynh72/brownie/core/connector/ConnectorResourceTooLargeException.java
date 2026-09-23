package io.github.vihuynh72.brownie.core.connector;

/** What the provider would hand back is larger than Brownie accepts for any source. */
public class ConnectorResourceTooLargeException extends RuntimeException {

    public ConnectorResourceTooLargeException() {
        super("This is larger than Brownie accepts as a source.");
    }
}
