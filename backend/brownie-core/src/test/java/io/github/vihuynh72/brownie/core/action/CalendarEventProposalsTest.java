package io.github.vihuynh72.brownie.core.action;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a person types becomes one exact event or is refused with a reason:
 * a time the clocks skip is refused, one they repeat is taken the first
 * time and says so, an all-day event is stored as Google counts days, and
 * text that would show differently in the calendar than on the page is not
 * sent.
 */
class CalendarEventProposalsTest {

    private static CalendarEventProposals.Request timed(String zone, String start, String end) {
        return new CalendarEventProposals.Request("Budget review", "", "", false, zone, start, end);
    }

    private static ActionNotProposableException.Reason refusal(Runnable attempt) {
        return assertThrows(ActionNotProposableException.class, attempt::run).reason();
    }

    @Test
    void aTimedEventIsTwoExactMomentsInItsNamedZone() {
        EventTiming.Timed timing = CalendarEventProposals.timed(timed("Europe/Paris", "2026-10-05T09:00", "2026-10-05T10:30"));
        assertEquals(OffsetDateTime.parse("2026-10-05T09:00:00+02:00"), timing.start());
        assertEquals(OffsetDateTime.parse("2026-10-05T10:30:00+02:00"), timing.end());
        assertEquals("Europe/Paris", timing.timeZone());
        assertFalse(timing.startIsFirstOfTwo());

        EventTiming.Timed winter = CalendarEventProposals.timed(timed("America/New_York", "2026-12-01T18:00", "2026-12-01T19:00"));
        assertEquals("-05:00", winter.start().getOffset().getId());
    }

    @Test
    void aTimeTheClocksSkipIsRefusedAndOneTheyRepeatIsTakenTheFirstTime() {
        // Paris moves its clocks from 02:00 to 03:00 on 29 March 2026, and back from 03:00 to 02:00 on 25 October 2026.
        assertEquals(ActionNotProposableException.Reason.TIME_SKIPPED,
                refusal(() -> CalendarEventProposals.timed(timed("Europe/Paris", "2026-03-29T02:30", "2026-03-29T04:00"))));
        assertEquals(ActionNotProposableException.Reason.TIME_SKIPPED,
                refusal(() -> CalendarEventProposals.timed(timed("Europe/Paris", "2026-03-29T01:00", "2026-03-29T02:15"))));

        EventTiming.Timed repeated = CalendarEventProposals.timed(timed("Europe/Paris", "2026-10-25T02:30", "2026-10-25T04:00"));
        assertTrue(repeated.startIsFirstOfTwo());
        assertFalse(repeated.endIsFirstOfTwo());
        assertEquals(OffsetDateTime.parse("2026-10-25T02:30:00+02:00"), repeated.start(), "the first 02:30, still on summer time");
    }

    @Test
    void anEventMustEndAfterItStartsLastAtMostAMonthAndBeInAKnownZone() {
        for (CalendarEventProposals.Request bad : new CalendarEventProposals.Request[] {
                timed("Europe/Paris", "2026-10-05T10:00", "2026-10-05T10:00"),
                timed("Europe/Paris", "2026-10-05T10:00", "2026-10-05T09:00"),
                timed("Europe/Paris", "2026-10-01T10:00", "2026-11-05T10:00"),
                timed("+02:00", "2026-10-05T09:00", "2026-10-05T10:00"),
                timed("GMT+2", "2026-10-05T09:00", "2026-10-05T10:00"),
                timed("SystemV/AST4", "2026-10-05T09:00", "2026-10-05T10:00"),
                timed("Mars/Olympus", "2026-10-05T09:00", "2026-10-05T10:00"),
                timed(null, "2026-10-05T09:00", "2026-10-05T10:00"),
                timed("Europe/Paris", "2026-10-05T09:00:30", "2026-10-05T10:00"),
                timed("Europe/Paris", "next tuesday", "2026-10-05T10:00"),
                timed("Europe/Paris", "1999-12-31T09:00", "1999-12-31T10:00")}) {
            assertEquals(ActionNotProposableException.Reason.INVALID, refusal(() -> CalendarEventProposals.timed(bad)), bad.toString());
        }
        assertEquals("UTC", CalendarEventProposals.timed(timed("UTC", "2026-10-05T09:00", "2026-10-05T10:00")).timeZone());
    }

    @Test
    void anAllDayEventIsItsFirstAndLastDayStoredWithTheEndDayNotIncluded() {
        EventTiming.AllDay oneDay = CalendarEventProposals.allDay(
                new CalendarEventProposals.Request("Offsite", "", "", true, null, "2026-10-05", "2026-10-05"));
        assertEquals(LocalDate.parse("2026-10-05"), oneDay.startDate());
        assertEquals(LocalDate.parse("2026-10-06"), oneDay.endDate());
        assertEquals(ActionNotProposableException.Reason.INVALID, refusal(() -> CalendarEventProposals.allDay(
                new CalendarEventProposals.Request("Offsite", "", "", true, null, "2026-10-05", "2026-10-04"))));
        assertEquals(ActionNotProposableException.Reason.INVALID, refusal(() -> CalendarEventProposals.allDay(
                new CalendarEventProposals.Request("Offsite", "", "", true, null, "2026-10-01", "2026-11-01"))));
        assertEquals(LocalDate.parse("2026-11-01"), CalendarEventProposals.allDay(
                new CalendarEventProposals.Request("Offsite", "", "", true, null, "2026-10-01", "2026-10-31")).endDate());
    }

    @Test
    void textThatWouldShowDifferentlyInTheCalendarIsNotSent() {
        assertEquals("Budget review", CalendarEventProposals.oneLine("  Budget review  ", "The title", 300, false));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.oneLine("   ", "The title", 300, false)));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.oneLine("Budget\nreview", "The title", 300, false)));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.oneLine("Budget\u2028review", "The title", 300, false)));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.oneLine("x".repeat(301), "The title", 300, false)));
        assertEquals(ActionNotProposableException.Reason.HIDDEN_CHARACTERS,
                refusal(() -> CalendarEventProposals.oneLine("Budget \u202Eweiver", "The title", 300, false)));
        assertEquals("", CalendarEventProposals.oneLine(null, "The place", 300, true));

        assertEquals("Line one\nLine two", CalendarEventProposals.description("Line one\r\nLine two\n"));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.description("See <b>this</b>")));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.description("Pay &#x202E;now")));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.description("Tom &amp; Jerry")));
        assertEquals("Q&A at 5, Tom & Jerry", CalendarEventProposals.description("Q&A at 5, Tom & Jerry"));
        assertEquals(ActionNotProposableException.Reason.INVALID,
                refusal(() -> CalendarEventProposals.description("Bell\u0007")));
        assertEquals(ActionNotProposableException.Reason.HIDDEN_CHARACTERS,
                refusal(() -> CalendarEventProposals.description("Pay \u200Bnow")));
    }

    @Test
    void anEventIdIsWhatGoogleAcceptsAndNeverTheSameTwice() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = CalendarEventProposals.newEventId();
            assertTrue(NewCalendarEvent.EVENT_ID.matcher(id).matches(), id);
            assertEquals(CalendarEventProposals.EVENT_ID_LENGTH, id.length());
            ids.add(id);
        }
        assertEquals(1000, ids.size());
    }
}
