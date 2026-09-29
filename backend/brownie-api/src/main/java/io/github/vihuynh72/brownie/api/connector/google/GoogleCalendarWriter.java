package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.action.CalendarEventLookup;
import io.github.vihuynh72.brownie.core.action.CalendarEventWriter;
import io.github.vihuynh72.brownie.core.action.EventTiming;
import io.github.vihuynh72.brownie.core.action.NewCalendarEvent;
import io.github.vihuynh72.brownie.core.action.SavedCalendarEvent;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Calendar, asked for two things only: adding one new event to the
 * person's main calendar under the id Brownie chose, and reading back the
 * event with that id. It never lists, changes, moves or deletes an event.
 *
 * <p>The event is sent with everything a default could otherwise decide:
 * an ordinary event, the calendar's usual reminders, shown as busy, private,
 * and with no guests, no repeat rule and no video call, which the write
 * itself ({@link GoogleWrite#CALENDAR_INSERT_EVENT}) backs by telling Google
 * to notify nobody and to add no conference or attachment.
 */
public class GoogleCalendarWriter implements CalendarEventWriter {

    private static final String EVENTS_PATH = "/calendar/v3/calendars/primary/events";
    private static final String EVENT_FIELDS = "id,status,eventType,summary,description,location,start,end,attendees,"
            + "recurrence,recurringEventId,reminders,transparency,visibility,conferenceData,htmlLink";
    /** One event Brownie added: its own text, at most a few kilobytes, and the fields asked for. */
    static final int EVENT_ANSWER_BYTES = 256 * 1024;

    private final GoogleClientSettings settings;
    private final GoogleHttp http;

    public GoogleCalendarWriter(GoogleClientSettings settings, GoogleHttp http) {
        this.settings = settings;
        this.http = http;
    }

    @Override
    public WriteAnswer insertEvent(String accessToken, NewCalendarEvent event) {
        GoogleHttp.WriteExchange exchange = http.write(
                settings, GoogleWrite.CALENDAR_INSERT_EVENT, null, accessToken, MediaType.APPLICATION_JSON,
                http.jsonBytes(body(event)), GoogleHttp.SMALL_ANSWER_BYTES);
        if (exchange instanceof GoogleHttp.Answered answered && answered.answer().isSuccess()) {
            JsonNode created;
            try {
                created = http.json(answered.answer());
            } catch (ProviderUnavailableException e) {
                // Google accepted it, under the id Brownie sent; reading the event back decides what it is.
                return new WriteAnswer.Applied(answered.answer().status(), List.of(), event.eventId(), null, null);
            }
            // The event is made under the id Brownie sent; an answer naming another is not one Brownie can read.
            if (!event.eventId().equals(GoogleHttp.text(created, "id"))) {
                return new WriteAnswer.Unknown(answered.answer().status(), List.of());
            }
            return new WriteAnswer.Applied(answered.answer().status(), List.of(), event.eventId(),
                    link(GoogleHttp.text(created, "htmlLink")), null);
        }
        return GoogleWriteAnswers.refusal(http, exchange, "calendar event");
    }

    /** The request body: the event as approved, with every default Brownie does not leave to Google stated. */
    static Map<String, Object> body(NewCalendarEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.eventId());
        body.put("eventType", "default");
        body.put("summary", event.title());
        if (!event.description().isEmpty()) {
            body.put("description", event.description());
        }
        if (!event.location().isEmpty()) {
            body.put("location", event.location());
        }
        switch (event.timing()) {
            case EventTiming.Timed timed -> {
                body.put("start", Map.of("dateTime", EventTiming.MOMENT.format(timed.start()), "timeZone", timed.timeZone()));
                body.put("end", Map.of("dateTime", EventTiming.MOMENT.format(timed.end()), "timeZone", timed.timeZone()));
            }
            case EventTiming.AllDay allDay -> {
                body.put("start", Map.of("date", allDay.startDate().toString()));
                body.put("end", Map.of("date", allDay.endDate().toString()));
            }
        }
        body.put("reminders", Map.of("useDefault", true));
        body.put("transparency", "opaque");
        body.put("visibility", "private");
        body.put("guestsCanInviteOthers", false);
        return body;
    }

    @Override
    public CalendarEventLookup findEvent(String accessToken, String eventId) {
        if (eventId == null || !NewCalendarEvent.EVENT_ID.matcher(eventId).matches()) {
            throw new IllegalArgumentException("Not an event id Brownie chooses.");
        }
        URI uri = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                .path(EVENTS_PATH + "/{eventId}")
                .queryParam("fields", EVENT_FIELDS)
                .encode()
                .buildAndExpand(eventId)
                .toUri();
        GoogleHttp.Answer answer;
        try {
            answer = http.send(http.restClient().get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON), EVENT_ANSWER_BYTES);
        } catch (GoogleHttp.AnswerTooLargeException e) {
            throw new ProviderUnavailableException("Google's description of an event was far larger than one Brownie added.");
        }
        if (answer.status() == 404) {
            return new CalendarEventLookup.Missing();
        }
        if (answer.status() == 410) {
            return new CalendarEventLookup.Removed();
        }
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.CALENDAR_EVENT_CREATION, "calendar event");
        }
        JsonNode found = http.json(answer);
        String id = GoogleHttp.text(found, "id");
        if (!eventId.equals(id)) {
            throw new ProviderUnavailableException("Google answered with a different event than the one asked for.");
        }
        // A cancelled event may carry nothing but its id and status: it was made, and deleted since.
        if ("cancelled".equals(GoogleHttp.text(found, "status"))) {
            return new CalendarEventLookup.Removed();
        }
        JsonNode start = found.path("start");
        JsonNode end = found.path("end");
        JsonNode reminders = found.path("reminders");
        JsonNode overrides = reminders.path("overrides");
        JsonNode attendees = found.path("attendees");
        JsonNode recurrence = found.path("recurrence");
        return new CalendarEventLookup.Found(new SavedCalendarEvent(
                id,
                GoogleHttp.text(found, "status"),
                GoogleHttp.text(found, "eventType"),
                GoogleHttp.text(found, "summary"),
                GoogleHttp.text(found, "description"),
                GoogleHttp.text(found, "location"),
                moment(start),
                GoogleHttp.text(start, "timeZone"),
                date(start),
                moment(end),
                GoogleHttp.text(end, "timeZone"),
                date(end),
                attendees.isArray() ? attendees.size() : 0,
                (recurrence.isArray() && !recurrence.isEmpty()) || GoogleHttp.text(found, "recurringEventId") != null,
                // Only a plain true counts: a missing answer is not the calendar's usual reminders.
                reminders.path("useDefault").isBoolean() && reminders.path("useDefault").asBoolean(),
                overrides.isArray() ? overrides.size() : 0,
                GoogleHttp.text(found, "transparency"),
                GoogleHttp.text(found, "visibility"),
                !found.path("conferenceData").isMissingNode() && !found.path("conferenceData").isNull(),
                link(GoogleHttp.text(found, "htmlLink"))));
    }

    private static OffsetDateTime moment(JsonNode time) {
        String dateTime = GoogleHttp.text(time, "dateTime");
        if (dateTime == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(dateTime);
        } catch (DateTimeParseException e) {
            throw new ProviderUnavailableException("Google answered with an event time Brownie cannot read.");
        }
    }

    private static LocalDate date(JsonNode time) {
        String date = GoogleHttp.text(time, "date");
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new ProviderUnavailableException("Google answered with an event date Brownie cannot read.");
        }
    }

    /** Only Google Calendar's own page over https: an address from elsewhere is never offered to the person to open. */
    static String link(String candidate) {
        if (candidate == null || candidate.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(candidate);
            boolean calendarPage = "calendar.google.com".equals(uri.getHost())
                    || ("www.google.com".equals(uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/calendar/"));
            return "https".equals(uri.getScheme()) && calendarPage && uri.getUserInfo() == null ? candidate : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
