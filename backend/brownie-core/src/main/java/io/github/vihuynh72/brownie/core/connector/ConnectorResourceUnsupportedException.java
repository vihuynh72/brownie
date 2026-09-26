package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/**
 * Something the person chose that Brownie does not copy: for a calendar, a
 * repeating series as a whole rather than one of its occurrences, or an entry
 * that is not an ordinary event (a working location, focus time, time out of
 * office and the like); for Drive, a file that is neither a Google Doc nor a
 * plain-text file ({@code TYPE}), or a text file whose bytes are not UTF-8
 * ({@code NOT_UTF8}). {@code reason} is null for the calendar's cases, whose
 * message says what to choose instead.
 */
public class ConnectorResourceUnsupportedException extends RuntimeException {

    public enum Reason { TYPE, NOT_UTF8 }

    private final Reason reason;

    public ConnectorResourceUnsupportedException(String message) {
        super(message);
        this.reason = null;
    }

    public ConnectorResourceUnsupportedException(Reason reason) {
        super("Brownie does not copy this file (" + reason + ").");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
