package io.github.vihuynh72.brownie.core.connector;

/** A request to read a calendar that Brownie will not send on: a window that is empty, backwards or too long, or an event id that cannot be one. */
public class InvalidCalendarRequestException extends IllegalArgumentException {

    public InvalidCalendarRequestException(String message) {
        super(message);
    }
}
