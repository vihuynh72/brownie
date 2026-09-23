package io.github.vihuynh72.brownie.core.connector;

/**
 * The copied bytes were refused by the same checks an upload meets: a virus
 * was found, or the content is not a kind Brownie accepts. {@code reason} is
 * the artifact's own rejection code.
 */
public class ConnectorResourceRefusedException extends RuntimeException {

    private final String reason;

    public ConnectorResourceRefusedException(String reason) {
        super("The copy was refused by Brownie's checks (" + reason + ").");
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
