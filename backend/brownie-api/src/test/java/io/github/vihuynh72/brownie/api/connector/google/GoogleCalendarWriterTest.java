package io.github.vihuynh72.brownie.api.connector.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.core.action.ActionFailure;
import io.github.vihuynh72.brownie.core.action.CalendarEventLookup;
import io.github.vihuynh72.brownie.core.action.EventTiming;
import io.github.vihuynh72.brownie.core.action.NewCalendarEvent;
import io.github.vihuynh72.brownie.core.action.SavedCalendarEvent;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google Calendar stood in by WireMock over real HTTP: exactly what adding
 * an event sends (one request to the main calendar, under Brownie's id,
 * telling nobody, with every default stated and no guest), what each kind of
 * answer is taken to prove, how an added event is read back, and what never
 * reaches the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoogleCalendarWriterTest {

    private static final String ACCESS_TOKEN = "ya29.stand-in-calendar-writing-token";
    private static final String EVENTS = "/calendar/v3/calendars/primary/events";
    private static final String EVENT_ID = "abcdefghijklmnopqrstuv0123";
    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer google;
    private GoogleCalendarWriter writer;

    @BeforeEach
    void startGoogle() {
        google = new WireMockServer(0);
        google.start();
        writer = writer(google.baseUrl(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stopGoogle() {
        google.stop();
    }

    @Test
    void anEventIsSentOnceToTheMainCalendarUnderBrowniesIdTellingNobodyWithEveryDefaultStated() throws Exception {
        google.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(okJson("""
                {"id":"%s","status":"confirmed","htmlLink":"https://www.google.com/calendar/event?eid=abc"}
                """.formatted(EVENT_ID))));

        WriteAnswer answer = writer.insertEvent(ACCESS_TOKEN, timed());

        WriteAnswer.Applied applied = (WriteAnswer.Applied) answer;
        assertThat(applied.externalId()).isEqualTo(EVENT_ID);
        assertThat(applied.link()).isEqualTo("https://www.google.com/calendar/event?eid=abc");
        List<LoggedRequest> sent = google.findAll(postRequestedFor(urlPathEqualTo(EVENTS)));
        assertThat(sent).hasSize(1);
        LoggedRequest request = sent.getFirst();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
        assertThat(queryOf(request.getUrl())).isEqualTo(Map.of("sendUpdates", "none", "conferenceDataVersion", "0", "supportsAttachments", "false"));
        Map<String, Object> body = bodyOf(request);
        assertThat(body).isEqualTo(Map.ofEntries(
                Map.entry("id", EVENT_ID),
                Map.entry("eventType", "default"),
                Map.entry("summary", "Budget review"),
                Map.entry("description", "Bring the figures.\nRoom 2."),
                Map.entry("location", "Town hall"),
                Map.entry("start", Map.of("dateTime", "2026-10-05T09:00:00+02:00", "timeZone", "Europe/Paris")),
                Map.entry("end", Map.of("dateTime", "2026-10-05T10:30:00+02:00", "timeZone", "Europe/Paris")),
                Map.entry("reminders", Map.of("useDefault", true)),
                Map.entry("transparency", "opaque"),
                Map.entry("visibility", "private"),
                Map.entry("guestsCanInviteOthers", false)));
        assertThat(body).doesNotContainKey("attendees").doesNotContainKey("recurrence").doesNotContainKey("conferenceData");

        google.resetAll();
        google.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(okJson("{\"id\":\"" + EVENT_ID + "\"}")));
        writer.insertEvent(ACCESS_TOKEN, new NewCalendarEvent(EVENT_ID, "Offsite", "", "",
                new EventTiming.AllDay(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-07"))));
        Map<String, Object> allDay = bodyOf(google.findAll(postRequestedFor(urlPathEqualTo(EVENTS))).getFirst());
        assertThat(allDay.get("start")).isEqualTo(Map.of("date", "2026-10-05"));
        assertThat(allDay.get("end")).isEqualTo(Map.of("date", "2026-10-07"));
        assertThat(allDay).as("empty text is left out, not sent empty").doesNotContainKey("description").doesNotContainKey("location");
    }

    @Test
    void everyAnswerIsSortedByWhatItProvesAboutTheEvent(CapturedOutput output) {
        assertThat(answerTo(aResponse().withStatus(409).withBody(error("duplicate")))).as("already added under this id")
                .isInstanceOf(WriteAnswer.Exists.class);
        WriteAnswer refused = answerTo(aResponse().withStatus(401));
        assertThat(((WriteAnswer.NotAppliedRetryable) refused).tokenRefused()).isTrue();
        assertThat(answerTo(aResponse().withStatus(403).withBody(error("rateLimitExceeded")))).isInstanceOf(WriteAnswer.NotAppliedRetryable.class);
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody(error("quotaExceeded"))))).isEqualTo(ActionFailure.LIMIT_REACHED);
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody(error("forbidden"))))).isEqualTo(ActionFailure.PERMISSION_REFUSED);
        assertThat(failureOf(answerTo(aResponse().withStatus(400).withBody(error("invalid"))))).isEqualTo(ActionFailure.PROVIDER_REFUSED);
        assertThat(answerTo(aResponse().withStatus(500))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(okJson("{\"id\":\"someothereventid12345\"}"))).as("an answer naming another event")
                .isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(aResponse().withStatus(200).withBody("<html>not json</html>")))
                .as("accepted under Brownie's own id: reading it back decides what it is")
                .isInstanceOfSatisfying(WriteAnswer.Applied.class, applied -> assertThat(applied.externalId()).isEqualTo(EVENT_ID));
        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain("Budget review").doesNotContain("free text");
    }

    @Test
    void anAnswerThatNeverArrivesInTimeIsUnknown() {
        google.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(okJson("{\"id\":\"" + EVENT_ID + "\"}").withFixedDelay(3_000)));
        GoogleCalendarWriter impatient = writer(google.baseUrl(), Duration.ofMillis(500));
        assertThat(impatient.insertEvent(ACCESS_TOKEN, timed())).isInstanceOf(WriteAnswer.Unknown.class);
    }

    @Test
    void anAddedEventIsReadBackByItsIdWithEveryFieldThatIsCompared() {
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + EVENT_ID))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withQueryParam("fields", equalTo("id,status,eventType,summary,description,location,start,end,attendees,"
                        + "recurrence,recurringEventId,reminders,transparency,visibility,conferenceData,htmlLink"))
                .willReturn(okJson("""
                        {"id":"%s","status":"confirmed","eventType":"default","summary":"Budget review",
                         "description":"Bring the figures.\\nRoom 2.","location":"Town hall",
                         "start":{"dateTime":"2026-10-05T09:00:00+02:00","timeZone":"Europe/Paris"},
                         "end":{"dateTime":"2026-10-05T10:30:00+02:00","timeZone":"Europe/Paris"},
                         "reminders":{"useDefault":true},"visibility":"private",
                         "htmlLink":"https://www.google.com/calendar/event?eid=abc"}
                        """.formatted(EVENT_ID))));

        SavedCalendarEvent event = ((CalendarEventLookup.Found) writer.findEvent(ACCESS_TOKEN, EVENT_ID)).event();

        assertThat(event.id()).isEqualTo(EVENT_ID);
        assertThat(event.status()).isEqualTo("confirmed");
        assertThat(event.description()).isEqualTo("Bring the figures.\nRoom 2.");
        assertThat(event.startAt()).isEqualTo(OffsetDateTime.parse("2026-10-05T09:00:00+02:00"));
        assertThat(event.startTimeZone()).isEqualTo("Europe/Paris");
        assertThat(event.startDate()).isNull();
        assertThat(event.attendeeCount()).isZero();
        assertThat(event.repeats()).isFalse();
        assertThat(event.usesDefaultReminders()).isTrue();
        assertThat(event.transparency()).as("Google leaves the default out").isNull();
        assertThat(event.hasConference()).isFalse();
        assertThat(event.link()).isEqualTo("https://www.google.com/calendar/event?eid=abc");
        assertThat(google.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    @Test
    void anEventGoneOrDeletedIsToldApartAndNothingElseIsTakenForIt() {
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + EVENT_ID)).willReturn(aResponse().withStatus(404)));
        assertThat(writer.findEvent(ACCESS_TOKEN, EVENT_ID)).isInstanceOf(CalendarEventLookup.Missing.class);
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + EVENT_ID)).willReturn(aResponse().withStatus(410)));
        assertThat(writer.findEvent(ACCESS_TOKEN, EVENT_ID)).isInstanceOf(CalendarEventLookup.Removed.class);
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + EVENT_ID)).willReturn(okJson("{\"id\":\"" + EVENT_ID + "\",\"status\":\"cancelled\"}")));
        assertThat(writer.findEvent(ACCESS_TOKEN, EVENT_ID)).isInstanceOf(CalendarEventLookup.Removed.class);
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + EVENT_ID)).willReturn(okJson("{\"id\":\"anothereventid12345\"}")));
        assertThatThrownBy(() -> writer.findEvent(ACCESS_TOKEN, EVENT_ID)).isInstanceOf(ProviderUnavailableException.class);
        google.stubFor(get(urlPathEqualTo(EVENTS + "/" + EVENT_ID)).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> writer.findEvent(ACCESS_TOKEN, EVENT_ID)).isInstanceOf(ProviderTokenRejectedException.class);
        assertThatThrownBy(() -> writer.findEvent(ACCESS_TOKEN, "../settings")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyGoogleCalendarsOwnPagesAreOfferedAsLinks() {
        assertThat(GoogleCalendarWriter.link("https://www.google.com/calendar/event?eid=abc")).isNotNull();
        assertThat(GoogleCalendarWriter.link("https://calendar.google.com/calendar/event?eid=abc")).isNotNull();
        assertThat(GoogleCalendarWriter.link("https://www.google.com/search?q=x")).isNull();
        assertThat(GoogleCalendarWriter.link("http://www.google.com/calendar/event?eid=abc")).isNull();
        assertThat(GoogleCalendarWriter.link("https://calendar.google.com.example.org/x")).isNull();
        assertThat(GoogleCalendarWriter.link("https://user@calendar.google.com/x")).isNull();
        assertThat(GoogleCalendarWriter.link("javascript:alert(1)")).isNull();
    }

    private WriteAnswer answerTo(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        google.resetAll();
        google.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(response));
        return writer.insertEvent(ACCESS_TOKEN, timed());
    }

    private static NewCalendarEvent timed() {
        return new NewCalendarEvent(EVENT_ID, "Budget review", "Bring the figures.\nRoom 2.", "Town hall", new EventTiming.Timed(
                "Europe/Paris", OffsetDateTime.parse("2026-10-05T09:00:00+02:00"), OffsetDateTime.parse("2026-10-05T10:30:00+02:00"),
                false, false));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(LoggedRequest request) {
        return JSON.readValue(request.getBodyAsString(), Map.class);
    }

    private static ActionFailure failureOf(WriteAnswer answer) {
        assertThat(answer).isInstanceOf(WriteAnswer.NotAppliedFinal.class);
        return ((WriteAnswer.NotAppliedFinal) answer).failure();
    }

    private static String error(String reason) {
        return "{\"error\":{\"code\":0,\"message\":\"free text Brownie never logs\",\"errors\":[{\"reason\":\"" + reason + "\"}]}}";
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> values = new java.util.HashMap<>();
        org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((name, list) -> values.put(name, java.net.URLDecoder.decode(list.getFirst(), StandardCharsets.UTF_8)));
        return values;
    }

    private static GoogleCalendarWriter writer(String base, Duration readTimeout) {
        GoogleClientSettings settings = new GoogleClientSettings(
                "client-123",
                "stand-in-client-secret-value",
                URI.create("http://localhost:8081/api/v1/connectors/google/callback"),
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create(base + "/token"),
                URI.create(base + "/revoke"),
                URI.create(base + "/userinfo"),
                URI.create(base),
                URI.create(base),
                "https://accounts.google.com",
                Duration.ofSeconds(2),
                readTimeout);
        return new GoogleCalendarWriter(settings, GoogleHttp.create(Duration.ofSeconds(2), readTimeout, new ObjectMapper()));
    }
}
