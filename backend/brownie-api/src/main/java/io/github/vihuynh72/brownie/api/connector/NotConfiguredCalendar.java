package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.CalendarEvent;
import io.github.vihuynh72.brownie.core.connector.CalendarEventReader;
import io.github.vihuynh72.brownie.core.connector.CalendarWindow;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;

import java.time.Instant;

/** Stands in for Google Calendar on a deployment where Google is not set up: every read is refused with one clear reason. */
final class NotConfiguredCalendar implements CalendarEventReader {

    @Override
    public CalendarWindow listEvents(String accessToken, Instant from, Instant to, int maxEvents) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public CalendarEvent readEvent(String accessToken, String eventId) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public String calendarTimeZone(String accessToken) {
        throw new ConnectorNotConfiguredException();
    }
}
