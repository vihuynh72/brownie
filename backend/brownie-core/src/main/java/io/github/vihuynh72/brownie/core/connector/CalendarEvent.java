package io.github.vihuynh72.brownie.core.connector;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One event as Brownie read it from a calendar. A listing carries what a
 * person needs to choose an event (its id, status, title, times and whether
 * it repeats); an event read by its id also carries its description,
 * location, the link to it in the calendar, when it last changed, the
 * calendar's version of it ({@code revision}), whether it is a repeating
 * series itself rather than one occurrence of one ({@code series}), and its
 * type ({@code eventType}, such as {@code default} for an ordinary event).
 * {@code endUnspecified} means the calendar says the event has no end time;
 * the end it still carries is only a placeholder. Who was invited is never
 * read.
 */
public record CalendarEvent(
        String id,
        CalendarEventStatus status,
        String summary,
        String description,
        String location,
        CalendarEventTime start,
        CalendarEventTime end,
        boolean endUnspecified,
        boolean recurring,
        boolean series,
        String eventType,
        String link,
        OffsetDateTime updated,
        String revision) {

    public CalendarEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }
}
