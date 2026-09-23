package io.github.vihuynh72.brownie.core.connector;

/**
 * Something the person chose that Brownie does not copy: for a calendar, a
 * repeating series as a whole rather than one of its occurrences, or an entry
 * that is not an ordinary event (a working location, focus time, time out of
 * office and the like).
 */
public class ConnectorResourceUnsupportedException extends RuntimeException {

    public ConnectorResourceUnsupportedException(String message) {
        super(message);
    }
}
