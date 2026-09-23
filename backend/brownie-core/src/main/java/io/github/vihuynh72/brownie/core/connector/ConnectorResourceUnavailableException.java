package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/**
 * Something the person chose that Brownie cannot read now. {@code reason}
 * says which, so that a page can say it plainly: {@code GONE} when the
 * provider says it no longer exists (or never did, which it does not tell
 * apart), {@code CANCELLED} when it still exists but was called off.
 */
public class ConnectorResourceUnavailableException extends RuntimeException {

    public enum Reason { GONE, CANCELLED }

    private final Reason reason;

    public ConnectorResourceUnavailableException(Reason reason) {
        super("Brownie cannot read this from Google (" + reason + ").");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
