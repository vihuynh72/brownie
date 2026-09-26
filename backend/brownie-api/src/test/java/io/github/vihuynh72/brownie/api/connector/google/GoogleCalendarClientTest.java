package io.github.vihuynh72.brownie.api.connector.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.vihuynh72.brownie.core.connector.CalendarEvent;
import io.github.vihuynh72.brownie.core.connector.CalendarEventStatus;
import io.github.vihuynh72.brownie.core.connector.CalendarWindow;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceTooLargeException;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceUnavailableException;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google Calendar stood in by WireMock over real HTTP: exactly what Brownie
 * asks for (the primary calendar only, only the fields it shows, one page),
 * how each kind of answer is read, and what never reaches the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoogleCalendarClientTest {

    private static final String ACCESS_TOKEN = "ya29.stand-in-calendar-access-token";
    private static final String EVENTS = "/calendar/v3/calendars/primary/events";
    private static final Instant FROM = Instant.parse("2026-09-01T07:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T07:00:00Z");

    private WireMockServer google;
    private GoogleCalendarClient client;

    @BeforeEach
    void startGoogle() {
        google = new WireMockServer(0);
        google.start();
        client = new GoogleCalendarClient(settings(google.baseUrl()), GoogleHttp.create(Duration.ofSeconds(2), Duration.ofSeconds(5), new ObjectMapper()));
    }

    @AfterEach
    void stopGoogle() {
        google.stop();
    }

    @Test
    void aListingAsksForOnePageOfThePrimaryCalendarsOrdinaryEventsWithOnlyTheFieldsItShows() {
        google.stubFor(get(urlPathEqualTo(EVENTS))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withQueryParam("timeMin", equalTo("2026-09-01T07:00:00Z"))
                .withQueryParam("timeMax", equalTo("2026-10-01T07:00:00Z"))
                .withQueryParam("singleEvents", equalTo("true"))
                .withQueryParam("orderBy", equalTo("startTime"))
                .withQueryParam("maxResults", equalTo("50"))
                .withQueryParam("eventTypes", equalTo("default"))
                .withQueryParam("fields", equalTo("timeZone,nextPageToken,items(id,status,summary,start,end,endTimeUnspecified,recurringEventId)"))
                .willReturn(okJson("""
                        {"timeZone":"America/Los_Angeles","nextPageToken":"more",
                         "items":[
                          {"id":"timed1","status":"confirmed","summary":"Weekly sync",
                           "start":{"dateTime":"2026-09-24T10:00:00-07:00","timeZone":"America/Los_Angeles"},
                           "end":{"dateTime":"2026-09-24T10:30:00-07:00","timeZone":"America/Los_Angeles"}},
                          {"id":"gone1","status":"cancelled"},
                          {"id":"allday1","summary":"Offsite","start":{"date":"2026-09-25"},"end":{"date":"2026-09-26"}},
                          {"id":"dropin1","summary":"Drop-in","endTimeUnspecified":true,
                           "start":{"dateTime":"2026-09-25T15:00:00Z"},"end":{"dateTime":"2026-09-25T15:00:00Z"}},
                          {"id":"series1_20260926T170000Z","status":"tentative","summary":"Standup","recurringEventId":"series1",
                           "start":{"dateTime":"2026-09-26T17:00:00Z"},"end":{"dateTime":"2026-09-26T17:15:00Z"}}]}
                        """)));

        // Google ignores anything finer than seconds, and Brownie does not send it.
        CalendarWindow window = client.listEvents(ACCESS_TOKEN, FROM.plusNanos(123_456_789), TO, 50);

        assertThat(window.timeZone()).isEqualTo("America/Los_Angeles");
        assertThat(window.truncated()).as("Google has another page").isTrue();
        assertThat(window.events()).extracting(CalendarEvent::id).containsExactly("timed1", "allday1", "dropin1", "series1_20260926T170000Z");
        CalendarEvent timed = window.events().get(0);
        assertThat(timed.start().dateTime()).isEqualTo(OffsetDateTime.parse("2026-09-24T10:00:00-07:00"));
        assertThat(timed.start().timeZone()).isEqualTo("America/Los_Angeles");
        CalendarEvent allDay = window.events().get(1);
        assertThat(allDay.status()).as("an event without a status is confirmed").isEqualTo(CalendarEventStatus.CONFIRMED);
        assertThat(allDay.start().date()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(allDay.endUnspecified()).isFalse();
        assertThat(window.events().get(2).endUnspecified()).as("the calendar says it has no end").isTrue();
        assertThat(window.events().get(3).status()).isEqualTo(CalendarEventStatus.TENTATIVE);
        assertThat(window.events().get(3).recurring()).isTrue();
        google.verify(1, anyRequestedFor(anyUrl()));
    }

    @Test
    void oneEventIsAskedForByItsIdWithItsDescriptionButNeverItsGuests() {
        google.stubFor(get(urlPathEqualTo(EVENTS + "/series1_20260926T170000Z"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withQueryParam("fields", equalTo(
                        "id,status,summary,description,location,start,end,endTimeUnspecified,recurringEventId,recurrence,eventType,htmlLink,updated,etag"))
                .willReturn(okJson("""
                        {"id":"series1_20260926T170000Z","status":"confirmed","summary":"Standup","description":"<b>Notes</b>",
                         "location":"Room 4","recurringEventId":"series1","eventType":"default",
                         "start":{"dateTime":"2026-09-26T17:00:00Z"},"end":{"dateTime":"2026-09-26T17:15:00Z"},
                         "htmlLink":"https://www.google.com/calendar/event?eid=abc","updated":"2026-09-20T18:03:04.356Z","etag":"\\"3181161784712000\\""}
                        """)));

        CalendarEvent event = client.readEvent(ACCESS_TOKEN, "series1_20260926T170000Z");

        assertThat(event.description()).isEqualTo("<b>Notes</b>");
        assertThat(event.location()).isEqualTo("Room 4");
        assertThat(event.recurring()).isTrue();
        assertThat(event.series()).as("one occurrence, not the series").isFalse();
        assertThat(event.eventType()).isEqualTo("default");
        assertThat(event.link()).isEqualTo("https://www.google.com/calendar/event?eid=abc");
        assertThat(event.updated()).isEqualTo(OffsetDateTime.parse("2026-09-20T18:03:04.356Z"));
        assertThat(event.revision()).isEqualTo("\"3181161784712000\"");
        google.verify(getRequestedFor(urlPathEqualTo(EVENTS + "/series1_20260926T170000Z")));
    }

    @Test
    void aSeriesItselfAndAnEntryThatIsNotAnOrdinaryEventAreToldApart() {
        stubEvent("series1", okJson("""
                {"id":"series1","summary":"Standup","recurrence":["RRULE:FREQ=WEEKLY;BYDAY=MO"],"eventType":"default",
                 "start":{"dateTime":"2026-09-07T17:00:00Z"},"end":{"dateTime":"2026-09-07T17:15:00Z"},"etag":"\\"1\\""}
                """));
        stubEvent("away1", okJson("""
                {"id":"away1","summary":"Out of office","eventType":"outOfOffice",
                 "start":{"dateTime":"2026-09-08T00:00:00Z"},"end":{"dateTime":"2026-09-09T00:00:00Z"},"etag":"\\"1\\""}
                """));

        assertThat(client.readEvent(ACCESS_TOKEN, "series1").series()).isTrue();
        assertThat(client.readEvent(ACCESS_TOKEN, "away1").eventType()).isEqualTo("outOfOffice");
    }

    @Test
    void anEventThatIsGoneOrCancelledIsUnavailableWithItsReasonAndAnotherEventIsNotTakenForIt() {
        stubEvent("deleted1", aResponse().withStatus(404));
        stubEvent("deleted2", aResponse().withStatus(410));
        stubEvent("called-off", okJson("{\"id\":\"called-off\",\"status\":\"cancelled\"}"));
        stubEvent("asked-for", okJson("{\"id\":\"something-else\",\"start\":{\"date\":\"2026-09-25\"},\"end\":{\"date\":\"2026-09-26\"},\"etag\":\"\\\"1\\\"\"}"));

        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "deleted1"))
                .isInstanceOf(ConnectorResourceUnavailableException.class)
                .extracting(e -> ((ConnectorResourceUnavailableException) e).reason()).isEqualTo(ConnectorResourceUnavailableException.Reason.GONE);
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "deleted1"))
                .as("said as an event, not a file")
                .extracting(e -> ((ConnectorResourceUnavailableException) e).access()).isEqualTo(ConnectorAccess.CALENDAR_EVENTS);
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "deleted2"))
                .extracting(e -> ((ConnectorResourceUnavailableException) e).reason()).isEqualTo(ConnectorResourceUnavailableException.Reason.GONE);
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "called-off"))
                .extracting(e -> ((ConnectorResourceUnavailableException) e).reason())
                .isEqualTo(ConnectorResourceUnavailableException.Reason.CANCELLED);
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "asked-for")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void refusalsAreSortedLikeEveryOtherGoogleReadAndOnlyTheirReasonsAreLogged(CapturedOutput output) {
        stubList(aResponse().withStatus(401));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50)).isInstanceOf(ProviderTokenRejectedException.class);

        stubList(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("""
                {"error":{"code":403,"message":"Google Calendar API has not been used in project 1 before or it is disabled.",
                  "errors":[{"domain":"usageLimits","reason":"accessNotConfigured"}],"status":"PERMISSION_DENIED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED",
                              "metadata":{"service":"calendar-json.googleapis.com"}}]}}
                """));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50))
                .as("the Calendar API left off in Brownie's project is setup, not the person's problem")
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll()).contains("calendar's events (HTTP 403, accessNotConfigured, SERVICE_DISABLED, PERMISSION_DENIED)");

        stubList(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody(
                "{\"error\":{\"code\":403,\"message\":\"Calendar usage limits exceeded.\",\"errors\":[{\"domain\":\"usageLimits\",\"reason\":\"quotaExceeded\"}]}}"));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50))
                .as("Calendar's own limit for one person passes").isInstanceOf(ProviderUnavailableException.class);

        stubList(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("""
                {"error":{"code":403,"message":"Request had insufficient authentication scopes.",
                  "errors":[{"message":"Insufficient Permission","domain":"global","reason":"insufficientPermissions"}],
                  "status":"PERMISSION_DENIED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT",
                              "domain":"googleapis.com","metadata":{"service":"calendar-json.googleapis.com"}}]}}
                """));
        // Every read follows a refresh whose answer names the token's permissions, and one without the calendar's is
        // already "connect again". A read refused for want of a permission Google has just said was given is Brownie
        // asking for more than it covers: setup, which connecting again cannot change, logged for whoever runs Brownie.
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50))
                .as("a read refused for a permission the token was just said to carry is setup, not the person's to fix")
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll())
                .contains("Google refused the request for the calendar's events (HTTP 403, insufficientPermissions, ACCESS_TOKEN_SCOPE_INSUFFICIENT, PERMISSION_DENIED)")
                .doesNotContain("insufficient authentication scopes");
        stubList(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("""
                {"error":{"code":403,"status":"PERMISSION_DENIED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
                """));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50))
                .as("in the newer error form alone too").isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll()).contains("calendar's events (HTTP 403, ACCESS_TOKEN_SCOPE_INSUFFICIENT, PERMISSION_DENIED)");

        stubList(aResponse().withStatus(429));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50)).isInstanceOf(ProviderUnavailableException.class);
        stubList(aResponse().withStatus(503));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50)).isInstanceOf(ProviderUnavailableException.class);
        stubList(aResponse().withStatus(400).withBody("{\"error\":{\"errors\":[{\"reason\":\"timeRangeEmpty\"}]}}"));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50)).isInstanceOf(ProviderMisconfiguredException.class);

        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain("has not been used in project");
    }

    @Test
    void anAnswerLargerThanBrownieReadsIsRefusedAndWhatItCannotReadIsNotGuessed() {
        stubList(okJson("{\"items\":[],\"timeZone\":\"" + "x".repeat(GoogleCalendarClient.LIST_ANSWER_BYTES) + "\"}"));
        assertThatThrownBy(() -> client.listEvents(ACCESS_TOKEN, FROM, TO, 50)).isInstanceOf(ConnectorResourceTooLargeException.class);

        stubEvent("bad-time", okJson("{\"id\":\"bad-time\",\"start\":{\"dateTime\":\"tomorrow at ten\"},\"end\":{\"dateTime\":\"later\"}}"));
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "bad-time")).isInstanceOf(ProviderUnavailableException.class);
        stubEvent("odd-status", okJson("{\"id\":\"odd-status\",\"status\":\"maybe\",\"start\":{\"date\":\"2026-09-25\"},\"end\":{\"date\":\"2026-09-26\"}}"));
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "odd-status")).isInstanceOf(ProviderUnavailableException.class);
        stubEvent("not-json", aResponse().withStatus(200).withBody("<html>sign in</html>"));
        assertThatThrownBy(() -> client.readEvent(ACCESS_TOKEN, "not-json")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void theCalendarsTimeZoneIsAskedForOnItsOwnAndOnlyAPlainZoneNameIsBelieved() {
        google.stubFor(get(urlPathEqualTo(EVENTS))
                .withQueryParam("maxResults", equalTo("1"))
                .withQueryParam("fields", equalTo("timeZone"))
                .willReturn(okJson("{\"timeZone\":\"Europe/Paris\"}")));
        assertThat(client.calendarTimeZone(ACCESS_TOKEN)).isEqualTo("Europe/Paris");

        google.resetAll();
        google.stubFor(get(urlPathEqualTo(EVENTS)).willReturn(okJson("{\"timeZone\":\"Europe/Paris; <b>\"}")));
        assertThat(client.calendarTimeZone(ACCESS_TOKEN)).isNull();
    }

    // ---- helpers

    private void stubList(ResponseDefinitionBuilder answer) {
        google.stubFor(get(urlPathEqualTo(EVENTS)).willReturn(answer));
    }

    private void stubEvent(String eventId, ResponseDefinitionBuilder answer) {
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + eventId)).willReturn(answer));
    }

    private static GoogleClientSettings settings(String base) {
        return new GoogleClientSettings(
                "client-123",
                "stand-in-client-secret-value",
                URI.create("http://localhost:8081/api/v1/connectors/google/callback"),
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create(base + "/token"),
                URI.create(base + "/revoke"),
                URI.create(base + "/userinfo"),
                URI.create(base),
                "https://accounts.google.com",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}
