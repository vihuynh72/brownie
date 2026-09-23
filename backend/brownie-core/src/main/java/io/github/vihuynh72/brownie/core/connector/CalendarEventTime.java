package io.github.vihuynh72.brownie.core.connector;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * When an event is planned to start or to end, as the calendar holds it:
 * a date for an all-day event, or an instant with its UTC offset for a timed
 * one, together with the time zone the event itself names, when it names
 * one (an IANA name such as {@code Europe/Paris}; otherwise the calendar's
 * own zone applies).
 */
public record CalendarEventTime(LocalDate date, OffsetDateTime dateTime, String timeZone) {

    public CalendarEventTime {
        if ((date == null) == (dateTime == null)) {
            throw new IllegalArgumentException("An event time is either a date or a date and time, never both or neither.");
        }
    }

    public static CalendarEventTime allDay(LocalDate date) {
        return new CalendarEventTime(date, null, null);
    }

    public static CalendarEventTime at(OffsetDateTime dateTime, String timeZone) {
        return new CalendarEventTime(null, dateTime, timeZone);
    }

    public boolean isAllDay() {
        return date != null;
    }
}
