package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectionState;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What adding an event sends and what it takes a readback to mean: the event
 * goes under the id chosen at proposal, it counts only when everything
 * approved reads back, an event the person deleted since is a made event,
 * and Google not knowing the id never counts as "not made".
 */
class CalendarEventHandlerTest {

    private static final String EVENT_ID = "abcdefghijklmnopqrstuv0123";
    private static final Instant SENT = Instant.parse("2026-09-28T12:00:00Z");

    private final FakeCalendar calendar = new FakeCalendar();
    private final CalendarEventHandler handler = new CalendarEventHandler(calendar);
    private final UsableConnection connection = new UsableConnection(new Connection(5, 7, 3, ConnectorAccess.CALENDAR_EVENT_CREATION, "acct",
            null, List.of("scope"), ConnectionState.ACTIVE, null, null, OffsetDateTime.now(), null, null), "fresh-token");

    @Test
    void theEventIsSentUnderItsChosenIdAndCountsOnlyWhenEverythingApprovedReadsBack() {
        ActionRequest action = action(CalendarEventPayloadTest.timed());
        PreparedWrite prepared = handler.prepare(action, connection);
        WriteAnswer answer = prepared.send(connection);
        assertEquals(1, calendar.inserted.size());
        NewCalendarEvent sent = calendar.inserted.getFirst();
        assertEquals(EVENT_ID, sent.eventId());
        assertEquals("Budget review", sent.title());
        assertEquals("Bring the figures.\nRoom 2.", sent.description());
        assertEquals(CalendarEventPayloadTest.timed().timing(), sent.timing());

        calendar.lookup = new CalendarEventLookup.Found(matching());
        ActionOutcome.Done done = assertInstanceOf(ActionOutcome.Done.class, prepared.readBack(connection, answer));
        assertEquals(ActionVerification.MATCHED, done.verification());
        assertEquals(EVENT_ID, done.externalId());

        // The same moments written with another offset are the same event.
        calendar.lookup = new CalendarEventLookup.Found(with(e -> copy(e, OffsetDateTime.parse("2026-10-05T07:00:00Z"),
                OffsetDateTime.parse("2026-10-05T08:30:00Z"), null, null, "Europe/Paris")));
        assertInstanceOf(ActionOutcome.Done.class, prepared.readBack(connection, answer));

        List<SavedCalendarEvent> wrong = List.of(
                with(e -> field(e, "title", "Budget reviews")),
                with(e -> field(e, "description", "")),
                with(e -> field(e, "location", "Elsewhere")),
                with(e -> field(e, "status", "tentative")),
                with(e -> field(e, "eventType", "outOfOffice")),
                with(e -> field(e, "visibility", "default")),
                with(e -> field(e, "transparency", "transparent")),
                with(e -> field(e, "attendees", "1")),
                with(e -> field(e, "repeats", "true")),
                with(e -> field(e, "conference", "true")),
                with(e -> field(e, "reminders", "false")),
                with(e -> field(e, "overrides", "1")),
                with(e -> copy(e, OffsetDateTime.parse("2026-10-05T09:15:00+02:00"), e.endAt(), null, null, "Europe/Paris")),
                with(e -> copy(e, e.startAt(), e.endAt(), null, null, "Europe/Berlin")),
                with(e -> copy(e, null, null, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06"), null)));
        for (int i = 0; i < wrong.size(); i++) {
            calendar.lookup = new CalendarEventLookup.Found(wrong.get(i));
            ActionOutcome.Mismatched mismatched = assertInstanceOf(ActionOutcome.Mismatched.class, prepared.readBack(connection, answer),
                    "difference " + i);
            assertEquals(EVENT_ID, mismatched.externalId());
        }
    }

    @Test
    void anAllDayEventIsComparedByItsDays() {
        ActionRequest action = action(CalendarEventPayloadTest.allDay());
        PreparedWrite prepared = handler.prepare(action, connection);
        WriteAnswer answer = prepared.send(connection);
        SavedCalendarEvent days = new SavedCalendarEvent(EVENT_ID, "confirmed", "default", "Offsite", null, null, null, null,
                LocalDate.parse("2026-10-05"), null, null, LocalDate.parse("2026-10-07"), 0, false, true, 0, null, "private", false, null);
        calendar.lookup = new CalendarEventLookup.Found(days);
        assertEquals(ActionVerification.MATCHED, assertInstanceOf(ActionOutcome.Done.class, prepared.readBack(connection, answer)).verification());
        // As Google really answers: an all-day event's usual reminders written out as the calendar's own.
        SavedCalendarEvent spelledOut = new SavedCalendarEvent(EVENT_ID, "confirmed", "default", "Offsite", null, null, null, null,
                LocalDate.parse("2026-10-05"), null, null, LocalDate.parse("2026-10-07"), 0, false, false, 1, null, "private", false, null);
        calendar.lookup = new CalendarEventLookup.Found(spelledOut);
        assertEquals(ActionVerification.MATCHED, assertInstanceOf(ActionOutcome.Done.class, prepared.readBack(connection, answer)).verification());
        calendar.lookup = new CalendarEventLookup.Found(copy(days, null, null, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06"), null));
        assertInstanceOf(ActionOutcome.Mismatched.class, prepared.readBack(connection, answer), "one day short");
    }

    @Test
    void anEventDeletedSinceWasMadeAndGoogleNotKnowingTheIdNeverMeansNotMade() {
        ActionRequest action = action(CalendarEventPayloadTest.timed());
        List<ActionAttempt> attempts = List.of();

        calendar.lookup = new CalendarEventLookup.Removed();
        ActionOutcome.Done removed = assertInstanceOf(ActionOutcome.Done.class, handler.reconcile(action, connection, attempts, SENT.plusSeconds(3600)));
        assertEquals(ActionVerification.REMOVED_AFTERWARDS, removed.verification());
        assertEquals(EVENT_ID, removed.externalId());

        calendar.lookup = new CalendarEventLookup.Found(with(e -> field(e, "status", "cancelled")));
        assertEquals(ActionVerification.REMOVED_AFTERWARDS,
                assertInstanceOf(ActionOutcome.Done.class, handler.reconcile(action, connection, attempts, SENT)).verification());

        calendar.lookup = new CalendarEventLookup.Missing();
        // Even a day after sending: Google forgets a deleted event, so its not knowing the id proves nothing.
        ActionOutcome.StillUnknown unknown = assertInstanceOf(ActionOutcome.StillUnknown.class,
                handler.reconcile(action, connection, attempts, SENT.plusSeconds(86_400)));
        assertNull(unknown.externalId());

        PreparedWrite prepared = handler.prepare(action, connection);
        assertEquals(EVENT_ID, assertInstanceOf(ActionOutcome.StillUnknown.class,
                prepared.readBack(connection, new WriteAnswer.Applied(200, List.of(), EVENT_ID, null, null))).externalId());

        // Unless an answer named it: then it was made, and Google forgetting it once it had time to show it means it
        // was deleted since. Right after the send, a missing event may just not be there yet, as the readback says.
        List<ActionAttempt> named = List.of(new ActionAttempt(1, 90, 1, AttemptKind.EXECUTE, SENT, SENT.plusSeconds(180), SENT,
                SENT.plusSeconds(1), AttemptOutcome.UNKNOWN, 200, EVENT_ID, null));
        assertEquals(EVENT_ID, assertInstanceOf(ActionOutcome.StillUnknown.class,
                handler.reconcile(action, connection, named, SENT.plusSeconds(3))).externalId());
        assertEquals(ActionVerification.REMOVED_AFTERWARDS, assertInstanceOf(ActionOutcome.Done.class,
                handler.reconcile(action, connection, named, SENT.plus(ActionService.SETTLE_INTERVAL).plusSeconds(1))).verification());

        calendar.lookup = new CalendarEventLookup.Found(matching());
        assertEquals(ActionVerification.MATCHED,
                assertInstanceOf(ActionOutcome.Done.class, handler.reconcile(action, connection, attempts, SENT)).verification());
        assertEquals(0, calendar.inserted.size(), "asking never sends");
    }

    @Test
    void aTimeZoneIsComparedByItsClockRulesNotItsSpelling() {
        assertTrue(CalendarEventHandler.sameZone("Asia/Kolkata", "Asia/Calcutta"));
        assertTrue(CalendarEventHandler.sameZone("UTC", "Etc/UTC"));
        assertTrue(CalendarEventHandler.sameZone("Europe/Paris", "Europe/Paris"));
        assertFalse(CalendarEventHandler.sameZone("Europe/Paris", "Europe/Berlin"), "the same offset today, different clocks in the past");
        assertFalse(CalendarEventHandler.sameZone("Europe/Paris", null));
        assertFalse(CalendarEventHandler.sameZone("Europe/Paris", "Nowhere/Else"));
    }

    // --- fixtures ---

    @Test
    void aRecordNamingAnythingElseThanItsPayloadSendsNothing() {
        ActionRequest action = action(CalendarEventPayloadTest.timed());
        for (long[] ids : new long[][] {{8, 3, 42, 5}, {7, 4, 42, 5}, {7, 3, 43, 5}, {7, 3, 42, 6}}) {
            ActionRequest other = new ActionRequest(90, ids[0], ids[1], ids[2], ids[3], ActionType.CALENDAR_CREATE_EVENT,
                    action.payloadCanonical(), action.payloadHash(), action.siblingKey(), null, null, null, null, EVENT_ID,
                    ActionState.EXECUTING, SENT, SENT.plusSeconds(1800), SENT, SENT.plusSeconds(900), 1L, SENT.plusSeconds(180), null, null,
                    null, null, null, null, null, null);
            assertThrows(IllegalStateException.class, () -> handler.prepare(other, connection));
        }
        assertEquals(0, calendar.inserted.size());
    }

    private static ActionRequest action(CalendarEventPayload payload) {
        String canonical = payload.canonical();
        return new ActionRequest(90, 7, 3, 42, 5, ActionType.CALENDAR_CREATE_EVENT, canonical, CanonicalJson.sha256Hex(canonical),
                payload.siblingKey(), null, null, null, null, EVENT_ID, ActionState.EXECUTING, SENT, SENT.plusSeconds(1800), SENT,
                SENT.plusSeconds(900), 1L, SENT.plusSeconds(180), null, null, null, null, null, null, null, null);
    }

    private static SavedCalendarEvent matching() {
        return new SavedCalendarEvent(EVENT_ID, "confirmed", "default", "Budget review", "Bring the figures.\nRoom 2.", "Town hall",
                OffsetDateTime.parse("2026-10-05T09:00:00+02:00"), "Europe/Paris", null,
                OffsetDateTime.parse("2026-10-05T10:30:00+02:00"), "Europe/Paris", null,
                0, false, true, 0, null, "private", false, "https://www.google.com/calendar/event?eid=abc");
    }

    private static SavedCalendarEvent with(UnaryOperator<SavedCalendarEvent> change) {
        return change.apply(matching());
    }

    private static SavedCalendarEvent copy(SavedCalendarEvent e, OffsetDateTime startAt, OffsetDateTime endAt, LocalDate startDate,
            LocalDate endDate, String zone) {
        return new SavedCalendarEvent(e.id(), e.status(), e.eventType(), e.title(), e.description(), e.location(), startAt, zone, startDate,
                endAt, zone, endDate, e.attendeeCount(), e.repeats(), e.usesDefaultReminders(), e.reminderOverrideCount(), e.transparency(),
                e.visibility(), e.hasConference(), e.link());
    }

    private static SavedCalendarEvent field(SavedCalendarEvent e, String name, String value) {
        return new SavedCalendarEvent(e.id(),
                name.equals("status") ? value : e.status(),
                name.equals("eventType") ? value : e.eventType(),
                name.equals("title") ? value : e.title(),
                name.equals("description") ? value : e.description(),
                name.equals("location") ? value : e.location(),
                e.startAt(), e.startTimeZone(), e.startDate(), e.endAt(), e.endTimeZone(), e.endDate(),
                name.equals("attendees") ? Integer.parseInt(value) : e.attendeeCount(),
                name.equals("repeats") ? Boolean.parseBoolean(value) : e.repeats(),
                name.equals("reminders") ? Boolean.parseBoolean(value) : e.usesDefaultReminders(),
                name.equals("overrides") ? Integer.parseInt(value) : e.reminderOverrideCount(),
                name.equals("transparency") ? value : e.transparency(),
                name.equals("visibility") ? value : e.visibility(),
                name.equals("conference") ? Boolean.parseBoolean(value) : e.hasConference(),
                e.link());
    }

    private static final class FakeCalendar implements CalendarEventWriter {
        final List<NewCalendarEvent> inserted = new ArrayList<>();
        CalendarEventLookup lookup = new CalendarEventLookup.Missing();

        @Override
        public WriteAnswer insertEvent(String accessToken, NewCalendarEvent event) {
            inserted.add(event);
            return new WriteAnswer.Applied(200, List.of(), event.eventId(), null, null);
        }

        @Override
        public CalendarEventLookup findEvent(String accessToken, String eventId) {
            return lookup;
        }
    }
}
