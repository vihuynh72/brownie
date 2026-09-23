package io.github.vihuynh72.brownie.core.connector;

import java.time.Instant;

/**
 * Reading the person's own primary calendar, and nothing else: the events in
 * a window, one event by its id, and the calendar's time zone. Every call is
 * a read; nothing here can create, change or delete an event, or reach any
 * other calendar.
 *
 * <p>Failures come back as the connection's usual kinds: {@link
 * ProviderTokenRejectedException} when the access token is refused, {@link
 * ProviderUnavailableException} when the provider cannot answer now or is
 * limiting requests, {@link ProviderMisconfiguredException} when it refuses
 * Brownie's own setup (the calendar service not enabled for Brownie's
 * project, for one), and {@link ConnectorBlockedByOrganizationException}
 * when the organization managing the account does not allow the app. Reading
 * one event can also end in {@link ConnectorResourceUnavailableException}
 * (it no longer exists) and any answer in {@link
 * ConnectorResourceTooLargeException} (larger than Brownie reads).
 */
public interface CalendarEventReader {

    /** Events that overlap {@code from} to {@code to}, repeating events expanded into their occurrences, in start order, at most {@code maxEvents}. */
    CalendarWindow listEvents(String accessToken, Instant from, Instant to, int maxEvents);

    CalendarEvent readEvent(String accessToken, String eventId);

    /** The calendar's own time zone, as an IANA name; null when it gave none. */
    String calendarTimeZone(String accessToken);
}
