package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.action.CalendarEventLookup;
import io.github.vihuynh72.brownie.core.action.CalendarEventWriter;
import io.github.vihuynh72.brownie.core.action.NewCalendarEvent;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;

import java.util.List;

/**
 * Stands in for Google Calendar where Google is not set up. Nothing is
 * offered there, so none of this is reached; if it were, nothing would
 * leave, and an event would be answered as not made.
 */
final class NotConfiguredCalendarWriter implements CalendarEventWriter {

    @Override
    public WriteAnswer insertEvent(String accessToken, NewCalendarEvent event) {
        return new WriteAnswer.NotAppliedRetryable(null, List.of(), false);
    }

    @Override
    public CalendarEventLookup findEvent(String accessToken, String eventId) {
        throw new ConnectorNotConfiguredException();
    }
}
