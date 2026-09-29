package io.github.vihuynh72.brownie.core.action;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * What Google says about an event Brownie added: enough to compare with what
 * was approved. A timed event has its two moments and each one's time zone;
 * an all-day event has its two dates. A text Google left out is empty.
 * {@code link} is the page that opens it.
 */
public record SavedCalendarEvent(
        String id,
        String status,
        String eventType,
        String title,
        String description,
        String location,
        OffsetDateTime startAt,
        String startTimeZone,
        LocalDate startDate,
        OffsetDateTime endAt,
        String endTimeZone,
        LocalDate endDate,
        int attendeeCount,
        boolean repeats,
        boolean usesDefaultReminders,
        int reminderOverrideCount,
        String transparency,
        String visibility,
        boolean hasConference,
        String link) {

    public SavedCalendarEvent {
        Objects.requireNonNull(id, "id");
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        location = location == null ? "" : location;
    }

    /** An event's content is a person's own, and never belongs in a log line. */
    @Override
    public String toString() {
        return "SavedCalendarEvent[status=" + status + "]";
    }
}
