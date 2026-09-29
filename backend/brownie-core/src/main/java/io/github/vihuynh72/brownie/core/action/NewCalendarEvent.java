package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One event to add, under the id Brownie chose for it. Everything not named
 * here is fixed by the writer and stated in the payload the person approved:
 * no guests, no messages, not repeating, no video call, the calendar's usual
 * reminders, shown as busy, private.
 */
public record NewCalendarEvent(String eventId, String title, String description, String location, EventTiming timing) {

    /** Google's own rule for an id a caller chooses: base32hex letters and digits, 5 to 1024 of them. */
    public static final Pattern EVENT_ID = Pattern.compile("^[a-v0-9]{5,1024}$");

    public NewCalendarEvent {
        Objects.requireNonNull(eventId, "eventId");
        if (!EVENT_ID.matcher(eventId).matches()) {
            throw new IllegalArgumentException("Not an event id Google accepts.");
        }
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(timing, "timing");
    }

    /** The event is a person's own content, and never belongs in a log line. */
    @Override
    public String toString() {
        return "NewCalendarEvent[allDay=" + (timing instanceof EventTiming.AllDay) + "]";
    }
}
