package io.github.vihuynh72.brownie.core.action;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An event's payload states everything the event will be, reads back only
 * as exactly the text Brownie wrote, and names the event itself for
 * recognising the same event proposed twice.
 */
class CalendarEventPayloadTest {

    static CalendarEventPayload timed() {
        return new CalendarEventPayload("nonce-1", 3, 7, 42, "Spring Budget Planning minutes", 5, "owner@example.org",
                "Budget review", "Bring the figures.\nRoom 2.", "Town hall", new EventTiming.Timed("Europe/Paris",
                        OffsetDateTime.parse("2026-10-05T09:00:00+02:00"), OffsetDateTime.parse("2026-10-05T10:30:00+02:00"), false, false));
    }

    static CalendarEventPayload allDay() {
        return new CalendarEventPayload("nonce-2", 3, 7, 42, "Minutes", 5, null, "Offsite", "", "",
                new EventTiming.AllDay(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-07")));
    }

    @Test
    void theCanonicalTextStatesEveryDefaultAndReadsBackAsItself() {
        String canonical = timed().canonical();
        assertTrue(canonical.contains("\"guests\":\"NONE\""));
        assertTrue(canonical.contains("\"notifications\":\"NONE\""));
        assertTrue(canonical.contains("\"repeats\":\"NEVER\""));
        assertTrue(canonical.contains("\"conference\":\"NONE\""));
        assertTrue(canonical.contains("\"reminders\":\"CALENDAR_DEFAULT\""));
        assertTrue(canonical.contains("\"showAs\":\"BUSY\""));
        assertTrue(canonical.contains("\"visibility\":\"PRIVATE\""));
        assertTrue(canonical.contains("\"start\":\"2026-10-05T09:00:00+02:00\""));
        assertEquals(timed(), CalendarEventPayload.parse(canonical));

        String days = allDay().canonical();
        assertTrue(days.contains("\"allDay\":true"));
        assertTrue(days.contains("\"endDate\":\"2026-10-07\""));
        assertEquals(allDay(), CalendarEventPayload.parse(days));
        assertEquals(LocalDate.parse("2026-10-06"), ((EventTiming.AllDay) allDay().timing()).lastDay());
    }

    @Test
    void aPayloadWithAnythingBrownieNeverWritesIsRefused() {
        String canonical = timed().canonical();
        for (String[] change : new String[][] {
                {"\"guests\":\"NONE\"", "\"guests\":\"ALL\""},
                {"\"notifications\":\"NONE\"", "\"notifications\":\"ALL\""},
                {"\"visibility\":\"PRIVATE\"", "\"visibility\":\"PUBLIC\""},
                {"\"calendar\":\"PRIMARY\"", "\"calendar\":\"OTHER\""},
                {"\"creates\":\"NEW_EVENT\"", "\"creates\":\"CHANGE\""},
                {"\"start\":\"2026-10-05T09:00:00+02:00\"", "\"start\":\"2026-10-05T09:00+02:00\""},
                {"\"type\":\"CALENDAR_CREATE_EVENT\"", "\"type\":\"DRIVE_SAVE_FILE\""}}) {
            String altered = canonical.replace(change[0], change[1]);
            assertNotEquals(canonical, altered, change[0]);
            assertThrows(IllegalStateException.class, () -> CalendarEventPayload.parse(altered), change[0]);
        }
    }

    @Test
    void theSameTitleAtTheSameTimeIsTheSameEventWhateverElseDiffers() {
        CalendarEventPayload other = new CalendarEventPayload("nonce-9", 3, 7, 99, "Another document", 6, "owner@example.org",
                "Budget review", "Other words", "Elsewhere", timed().timing());
        assertEquals(timed().siblingKey(), other.siblingKey());
        CalendarEventPayload later = new CalendarEventPayload("nonce-1", 3, 7, 42, "Spring Budget Planning minutes", 5, "owner@example.org",
                "Budget review", "", "", new EventTiming.Timed("Europe/Paris",
                        OffsetDateTime.parse("2026-10-05T10:00:00+02:00"), OffsetDateTime.parse("2026-10-05T11:00:00+02:00"), false, false));
        assertNotEquals(timed().siblingKey(), later.siblingKey());
        assertFalse(timed().toString().contains("Budget review"));
    }
}
