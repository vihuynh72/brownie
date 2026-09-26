package io.github.vihuynh72.brownie.core.connector;

/**
 * What the provider would hand back is larger than Brownie accepts for any
 * source. {@code access} says it was a Drive file rather than a calendar
 * listing, when the one throwing it knows; a reader need not.
 */
public class ConnectorResourceTooLargeException extends RuntimeException {

    private final ConnectorAccess access;

    public ConnectorResourceTooLargeException() {
        this(null);
    }

    public ConnectorResourceTooLargeException(ConnectorAccess access) {
        super("This is larger than Brownie accepts as a source.");
        this.access = access;
    }

    /** Null when it was not said. */
    public ConnectorAccess access() {
        return access;
    }
}
