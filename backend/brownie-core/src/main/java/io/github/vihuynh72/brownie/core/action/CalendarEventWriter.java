package io.github.vihuynh72.brownie.core.action;

/**
 * Adding one new event to a person's main calendar, and reading back an
 * event Brownie added. It never lists, changes, moves or deletes an event,
 * never invites anyone and never sends a message: the event it adds has no
 * guests, and Google is told to notify nobody.
 *
 * <p>Answers to reads are refused in the usual ways ({@code
 * ProviderUnavailableException}, {@code ProviderTokenRejectedException},
 * {@code ProviderMisconfiguredException}, {@code
 * ConnectorBlockedByOrganizationException}); a write never throws for what
 * Google answered, and returns what the answer proves instead.
 */
public interface CalendarEventWriter {

    /**
     * Sends the event in one request, under the id Brownie chose for it, so
     * that the same event sent again is refused by Google as one that
     * already exists rather than added twice.
     */
    WriteAnswer insertEvent(String accessToken, NewCalendarEvent event);

    /** What Google says about the event with this id, which Brownie chose. */
    CalendarEventLookup findEvent(String accessToken, String eventId);
}
