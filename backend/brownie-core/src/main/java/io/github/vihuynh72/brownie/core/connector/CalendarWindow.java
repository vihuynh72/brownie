package io.github.vihuynh72.brownie.core.connector;

import java.util.List;

/**
 * The events of a calendar in a window of time, in the order they start, as
 * many as Brownie asked for: {@code truncated} when the calendar holds more
 * in that window, so a page can say so rather than suggest there are no
 * others. {@code timeZone} is the calendar's own zone, when it gave one.
 */
public record CalendarWindow(String timeZone, List<CalendarEvent> events, boolean truncated) {

    public CalendarWindow {
        events = List.copyOf(events);
    }
}
