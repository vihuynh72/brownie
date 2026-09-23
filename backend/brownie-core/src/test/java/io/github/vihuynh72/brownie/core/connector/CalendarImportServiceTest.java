package io.github.vihuynh72.brownie.core.connector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the import refuses before it asks anything of anyone: a window that
 * is missing, backwards, too short to mean anything or longer than a month,
 * and an event id that cannot be one. The service is built with no
 * collaborators at all, so any of these reaching the connection, the
 * document or Google would fail the test with a different exception. The
 * flow itself is proven end to end against real Postgres, storage, scanner
 * and a stand-in Google in the API's integration test.
 */
class CalendarImportServiceTest {

    private static final Instant FROM = Instant.parse("2026-09-01T07:00:00Z");

    private final CalendarImportService service = new CalendarImportService(null, null, null, null, null, null, null, null, null);

    @Test
    void aWindowThatIsMissingBackwardsTooShortOrTooLongIsRefusedBeforeAnythingIsAsked() {
        assertThrows(InvalidCalendarRequestException.class, () -> service.events(1, 1, null, FROM));
        assertThrows(InvalidCalendarRequestException.class, () -> service.events(1, 1, FROM, null));
        assertThrows(InvalidCalendarRequestException.class, () -> service.events(1, 1, FROM, FROM.minusSeconds(60)));
        assertThrows(InvalidCalendarRequestException.class, () -> service.events(1, 1, FROM, FROM.plusSeconds(59)));
        assertThrows(InvalidCalendarRequestException.class,
                () -> service.events(1, 1, FROM, FROM.plus(CalendarImportService.MAX_WINDOW).plus(Duration.ofHours(2)).plusSeconds(1)));
        assertThrows(InvalidCalendarRequestException.class,
                () -> service.events(1, 1, Instant.parse("1969-12-31T00:00:00Z"), Instant.parse("1970-01-02T00:00:00Z")));
        assertThrows(InvalidCalendarRequestException.class,
                () -> service.events(1, 1, Instant.parse("9999-12-30T00:00:00Z"), Instant.parse("+10000-01-01T00:00:00Z")));
        // The longest allowed windows get past the check, to the connection this test deliberately does not have:
        // 31 days, and October in Paris, local midnight to local midnight, which is an hour longer because the clocks go back.
        assertThrows(NullPointerException.class, () -> service.events(1, 1, FROM, FROM.plus(Duration.ofDays(31))));
        assertThrows(NullPointerException.class, () -> service.events(1, 1,
                OffsetDateTime.parse("2026-10-01T00:00:00+02:00").toInstant(), OffsetDateTime.parse("2026-11-01T00:00:00+01:00").toInstant()));
    }

    @Test
    void aFileNameIsCutBetweenCharactersAndAlwaysKeepsItsExtension() {
        String emoji = "\uD83D\uDE00";
        String many = CalendarImportService.fileName(event(emoji.repeat(300)));
        assertTrue(many.length() <= 255 && many.endsWith(".txt"), many.length() + " " + many.substring(many.length() - 4));
        assertFalse(Character.isHighSurrogate(many.charAt(many.length() - 5)), "no character split in two");
        assertEquals(255, CalendarImportService.fileName(event(emoji.repeat(125) + "ab")).length());
        assertEquals("Q3 - Q4 plan.txt", CalendarImportService.fileName(event(" Q3 / Q4 plan ")));
        assertEquals("Calendar event.txt", CalendarImportService.fileName(event("\u0000")));
    }

    @Test
    void anEventIdThatCannotBeOneIsRefusedBeforeTheDocumentOrGoogleIsAsked() {
        for (String id : new String[] {null, "", "../../calendars/someone-else", "has space", "a".repeat(1025), "event?x=1"}) {
            assertThrows(InvalidCalendarRequestException.class, () -> service.importEvent(1, 1, 1, id), String.valueOf(id));
        }
        assertThrows(NullPointerException.class, () -> service.importEvent(1, 1, 1, "abc123def456_20260924T170000Z"),
                "an occurrence of a repeating event is a valid id and gets past the check");
    }

    private static CalendarEvent event(String summary) {
        CalendarEventTime at = CalendarEventTime.at(OffsetDateTime.parse("2026-09-24T10:00:00Z"), null);
        return new CalendarEvent("event1", CalendarEventStatus.CONFIRMED, summary, null, null, at, at, false, false, false, "default",
                null, null, "\"1\"");
    }
}
