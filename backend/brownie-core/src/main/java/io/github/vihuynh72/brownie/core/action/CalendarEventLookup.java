package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/** What Google says about an event Brownie added, asked for by the id Brownie chose. */
public sealed interface CalendarEventLookup {

    /** The event is there; {@code event} is what Google says it is. */
    record Found(SavedCalendarEvent event) implements CalendarEventLookup {

        public Found {
            Objects.requireNonNull(event, "event");
        }
    }

    /** Google knows the event and says it was deleted: it was made, and removed since. */
    record Removed() implements CalendarEventLookup {
    }

    /**
     * Google knows no event by this id. That does not prove it was never
     * made: Google stops knowing an event some time after it is deleted.
     */
    record Missing() implements CalendarEventLookup {
    }
}
