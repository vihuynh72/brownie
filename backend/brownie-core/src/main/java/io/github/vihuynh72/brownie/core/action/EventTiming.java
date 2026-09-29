package io.github.vihuynh72.brownie.core.action;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * When a new calendar event happens, exactly as it is sent: either at two
 * moments, each written with the offset it has in the named time zone, or on
 * whole days, with the end day not included, as Google Calendar counts them.
 */
public sealed interface EventTiming {

    /** Seconds always written, and the offset as {@code +hh:mm} even for zero, so the same moment is always the same text. */
    DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    /**
     * An event with a start and an end time in {@code timeZone}. {@code
     * startIsFirstOfTwo} (and its end twin) say that the clocks go back over
     * that local time, so it happens twice that day, and the first of the two
     * was taken.
     */
    record Timed(String timeZone, OffsetDateTime start, OffsetDateTime end, boolean startIsFirstOfTwo, boolean endIsFirstOfTwo)
            implements EventTiming {

        public Timed {
            Objects.requireNonNull(timeZone, "timeZone");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.toInstant().isAfter(start.toInstant())) {
                throw new IllegalArgumentException("An event ends after it starts.");
            }
        }
    }

    /** An event on whole days, from {@code startDate} up to but not including {@code endDate}. */
    record AllDay(LocalDate startDate, LocalDate endDate) implements EventTiming {

        public AllDay {
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDate, "endDate");
            if (!endDate.isAfter(startDate)) {
                throw new IllegalArgumentException("An all-day event ends on a later day than it starts.");
            }
        }

        /** The last day the event is on, as a person names it. */
        public LocalDate lastDay() {
            return endDate.minusDays(1);
        }
    }
}
