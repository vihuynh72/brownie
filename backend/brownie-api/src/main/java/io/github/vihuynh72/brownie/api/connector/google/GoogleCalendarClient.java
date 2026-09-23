package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.CalendarEvent;
import io.github.vihuynh72.brownie.core.connector.CalendarEventReader;
import io.github.vihuynh72.brownie.core.connector.CalendarEventStatus;
import io.github.vihuynh72.brownie.core.connector.CalendarEventTime;
import io.github.vihuynh72.brownie.core.connector.CalendarWindow;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceTooLargeException;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceUnavailableException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Google Calendar, asked only what reading the person's primary calendar
 * needs: its events in a window, one event by its id, and its time zone.
 * Every request is a GET of that calendar's events; nothing here can create,
 * change or delete an event, list the person's calendars, or reach anyone
 * else's. Each asks Google for only the fields it uses, so an event's guests
 * and conference details are never read, and each answer is read to a stated
 * size at most.
 */
public class GoogleCalendarClient implements CalendarEventReader {

    /** Fifty events' titles and times are tens of kilobytes; this leaves room for long titles and nothing more. */
    static final int LIST_ANSWER_BYTES = 512 * 1024;
    /** One event with a long description; Brownie does not copy more than this from one event. */
    static final int EVENT_ANSWER_BYTES = 1024 * 1024;

    private static final String EVENTS_PATH = "/calendar/v3/calendars/primary/events";
    private static final String LIST_FIELDS =
            "timeZone,nextPageToken,items(id,status,summary,start,end,endTimeUnspecified,recurringEventId)";
    private static final String EVENT_FIELDS = "id,status,summary,description,location,start,end,endTimeUnspecified,"
            + "recurringEventId,recurrence,eventType,htmlLink,updated,etag";
    private static final Pattern ZONE_NAME = Pattern.compile("^[A-Za-z0-9/_+-]{1,64}$");

    private final GoogleClientSettings settings;
    private final GoogleHttp http;

    public GoogleCalendarClient(GoogleClientSettings settings, GoogleHttp http) {
        this.settings = settings;
        this.http = http;
    }

    /** Repeating events come expanded into occurrences, in start order; only ordinary events, not working locations, focus time and the like. */
    @Override
    public CalendarWindow listEvents(String accessToken, Instant from, Instant to, int maxEvents) {
        URI uri = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                .path(EVENTS_PATH)
                .queryParam("timeMin", from.truncatedTo(ChronoUnit.SECONDS).toString())
                .queryParam("timeMax", to.truncatedTo(ChronoUnit.SECONDS).toString())
                .queryParam("singleEvents", "true")
                .queryParam("orderBy", "startTime")
                .queryParam("maxResults", maxEvents)
                .queryParam("eventTypes", "default")
                .queryParam("fields", LIST_FIELDS)
                .encode()
                .build()
                .toUri();
        GoogleHttp.Answer answer = get(uri, accessToken, LIST_ANSWER_BYTES);
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.CALENDAR_EVENTS, "calendar's events");
        }
        JsonNode body = http.json(answer);
        List<CalendarEvent> events = new ArrayList<>();
        boolean more = GoogleHttp.text(body, "nextPageToken") != null;
        for (JsonNode item : body.path("items")) {
            if (events.size() == maxEvents) {
                more = true;
                break;
            }
            // A listing without deleted events has none, but one that slipped through is not something to offer.
            if (!"cancelled".equals(GoogleHttp.text(item, "status"))) {
                events.add(event(item));
            }
        }
        return new CalendarWindow(zoneName(body), events, more);
    }

    @Override
    public CalendarEvent readEvent(String accessToken, String eventId) {
        URI uri = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                .path(EVENTS_PATH + "/{eventId}")
                .queryParam("fields", EVENT_FIELDS)
                .encode()
                .buildAndExpand(eventId)
                .toUri();
        GoogleHttp.Answer answer = get(uri, accessToken, EVENT_ANSWER_BYTES);
        // Google does not tell an event that was deleted apart from one that never existed, and neither does Brownie.
        if (answer.status() == 404 || answer.status() == 410) {
            throw new ConnectorResourceUnavailableException(ConnectorResourceUnavailableException.Reason.GONE);
        }
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.CALENDAR_EVENTS, "calendar event");
        }
        JsonNode body = http.json(answer);
        // A cancelled event may carry nothing but its id, so it is recognised before anything else is read from it.
        if ("cancelled".equals(GoogleHttp.text(body, "status"))) {
            throw new ConnectorResourceUnavailableException(ConnectorResourceUnavailableException.Reason.CANCELLED);
        }
        CalendarEvent event = event(body);
        if (!event.id().equals(eventId)) {
            throw new ProviderUnavailableException("Google answered with a different event than the one asked for.");
        }
        return event;
    }

    @Override
    public String calendarTimeZone(String accessToken) {
        URI uri = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                .path(EVENTS_PATH)
                .queryParam("maxResults", 1)
                .queryParam("fields", "timeZone")
                .encode()
                .build()
                .toUri();
        GoogleHttp.Answer answer = get(uri, accessToken, GoogleHttp.SMALL_ANSWER_BYTES);
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.CALENDAR_EVENTS, "calendar's time zone");
        }
        return zoneName(http.json(answer));
    }

    private GoogleHttp.Answer get(URI uri, String accessToken, int maxBytes) {
        try {
            return http.send(http.restClient().get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON), maxBytes);
        } catch (GoogleHttp.AnswerTooLargeException e) {
            throw new ConnectorResourceTooLargeException();
        }
    }

    private static CalendarEvent event(JsonNode item) {
        String id = GoogleHttp.text(item, "id");
        if (id == null || id.isBlank() || id.length() > 1024) {
            throw new ProviderUnavailableException("Google answered with an event that has no id.");
        }
        String status = GoogleHttp.text(item, "status");
        CalendarEventStatus eventStatus = switch (status == null ? "confirmed" : status) {
            case "confirmed" -> CalendarEventStatus.CONFIRMED;
            case "tentative" -> CalendarEventStatus.TENTATIVE;
            case "cancelled" -> CalendarEventStatus.CANCELLED;
            default -> throw new ProviderUnavailableException("Google answered with an event status Brownie does not know.");
        };
        return new CalendarEvent(
                id,
                eventStatus,
                GoogleHttp.text(item, "summary"),
                GoogleHttp.text(item, "description"),
                GoogleHttp.text(item, "location"),
                time(item.path("start")),
                time(item.path("end")),
                item.path("endTimeUnspecified").asBoolean(false),
                GoogleHttp.text(item, "recurringEventId") != null,
                // A series itself, rather than one of its occurrences, carries its repeat rules.
                item.path("recurrence").isArray() && !item.path("recurrence").isEmpty(),
                GoogleHttp.text(item, "eventType"),
                GoogleHttp.text(item, "htmlLink"),
                instantOrNull(GoogleHttp.text(item, "updated")),
                GoogleHttp.text(item, "etag"));
    }

    private static CalendarEventTime time(JsonNode node) {
        String dateTime = GoogleHttp.text(node, "dateTime");
        String date = GoogleHttp.text(node, "date");
        try {
            if (dateTime != null) {
                String zone = GoogleHttp.text(node, "timeZone");
                return CalendarEventTime.at(OffsetDateTime.parse(dateTime), zone != null && ZONE_NAME.matcher(zone).matches() ? zone : null);
            }
            if (date != null) {
                return CalendarEventTime.allDay(LocalDate.parse(date));
            }
        } catch (DateTimeParseException e) {
            // Falls through: a time Brownie cannot read is no time at all.
        }
        throw new ProviderUnavailableException("Google answered with an event time Brownie cannot read.");
    }

    private static OffsetDateTime instantOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String zoneName(JsonNode body) {
        String zone = GoogleHttp.text(body, "timeZone");
        return zone != null && ZONE_NAME.matcher(zone).matches() ? zone : null;
    }
}
