package io.github.vihuynh72.brownie.core.connector;

/** Whether an event is on, not yet confirmed, or called off, as its calendar says. */
public enum CalendarEventStatus {
    CONFIRMED,
    TENTATIVE,
    CANCELLED
}
