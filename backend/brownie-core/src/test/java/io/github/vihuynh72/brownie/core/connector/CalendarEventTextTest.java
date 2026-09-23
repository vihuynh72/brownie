package io.github.vihuynh72.brownie.core.connector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text an event becomes is what extraction, citations and the model will
 * read, so its exact form is pinned here: planned-time wording, explicit
 * zones, all-day dates with the last day included, one fact per paragraph,
 * and nothing but text.
 */
class CalendarEventTextTest {

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    @Test
    void aTimedEventIsWrittenAsPlannedTimesInItsOwnZoneOneFactPerParagraph() {
        CalendarEvent event = timed("Weekly sync", "2026-09-24T10:00:00-07:00", "2026-09-24T10:30:00-07:00", "America/Los_Angeles")
                .withLocation("Room 4").withDescription("Bring the Q3 numbers.").build();

        String text = CalendarEventText.render(event, LOS_ANGELES);

        assertEquals("""
                Google Calendar event

                Title: Weekly sync

                Planned start: 2026-09-24 (Thursday) 10:00 (America/Los_Angeles, UTC-07:00)

                Planned end: 2026-09-24 (Thursday) 10:30 (America/Los_Angeles, UTC-07:00)

                These are the times the event was planned for in the calendar. They do not show when it actually started or ended, or whether it took place.

                Location: Room 4

                Description:

                Bring the Q3 numbers.
                """, text);
        assertEquals(text, CalendarEventText.render(event, LOS_ANGELES), "the same event always gives the same text");
    }

    @Test
    void aTimeWithoutItsOwnZoneIsShownInTheCalendarsZoneAndWithoutAnyZoneByItsOffsetAlone() {
        CalendarEvent inUtc = timed("Call", "2026-09-24T08:00:00Z", "2026-09-24T09:00:00Z", null).build();

        assertTrue(CalendarEventText.render(inUtc, ZoneId.of("Europe/Paris"))
                .contains("Planned start: 2026-09-24 (Thursday) 10:00 (Europe/Paris, UTC+02:00)"));
        assertTrue(CalendarEventText.render(inUtc, null).contains("Planned start: 2026-09-24 (Thursday) 08:00 (UTC)"));
        CalendarEvent offsetOnly = timed("Call", "2026-09-24T10:00:00-07:00", "2026-09-24T11:00:00-07:00", "Not/AZone").build();
        assertTrue(CalendarEventText.render(offsetOnly, null).contains("Planned end: 2026-09-24 (Thursday) 11:00 (UTC-07:00)"),
                "a zone name Java does not know is not guessed at");
        CalendarEvent winter = timed("Call", "2027-01-14T15:00:00Z", "2027-01-14T16:00:00Z", "America/New_York").build();
        assertTrue(CalendarEventText.render(winter, LOS_ANGELES).contains("2027-01-14 (Thursday) 10:00 (America/New_York, UTC-05:00)"),
                "the event's own zone wins over the calendar's, with that date's own offset");
    }

    @Test
    void anAllDayEventIsWrittenAsDatesWithItsLastDayIncludedAndNoTimeOfDay() {
        CalendarEvent oneDay = allDay("Offsite", "2026-09-24", "2026-09-25").build();
        CalendarEvent threeDays = allDay("Conference", "2026-09-24", "2026-09-27").build();

        String single = CalendarEventText.render(oneDay, LOS_ANGELES);
        assertTrue(single.contains("\n\nPlanned date: 2026-09-24 (Thursday), all day\n\n"));
        assertTrue(single.contains(CalendarEventText.PLANNED_DATES));
        assertFalse(single.contains("Planned start"));
        assertTrue(CalendarEventText.render(threeDays, LOS_ANGELES)
                .contains("Planned dates: 2026-09-24 (Thursday) to 2026-09-26 (Saturday), all day"));
    }

    @Test
    void aTentativeRepeatingUntitledEventSaysSoAndLeavesOutWhatItDoesNotHave() {
        CalendarEvent event = timed(null, "2026-09-24T10:00:00-07:00", "2026-09-24T10:30:00-07:00", null)
                .withStatus(CalendarEventStatus.TENTATIVE).recurring().build();

        List<String> paragraphs = List.of(CalendarEventText.render(event, LOS_ANGELES).strip().split("\n\n"));

        assertEquals("Title: (no title)", paragraphs.get(1));
        assertEquals("Status: tentative. The event had not been confirmed when Brownie read it.", paragraphs.get(2));
        assertTrue(paragraphs.contains("Repeats: this is one occurrence of a repeating event."));
        assertTrue(paragraphs.stream().noneMatch(p -> p.startsWith("Location") || p.startsWith("Description")));
    }

    @Test
    void anHtmlDescriptionIsReducedToItsTextAndPlainTextIsKeptAsWritten() {
        CalendarEvent html = timed("Board", "2026-09-24T10:00:00-07:00", "2026-09-24T11:00:00-07:00", null)
                .withDescription("<p>Agenda:</p><ul><li>Budget &amp; hiring</li><li>Q&amp;A</li></ul><br>See <a href=\"https://x\">notes</a>"
                        + " &amp;lt; &#0; &#233;")
                .build();
        CalendarEvent plain = timed("Board", "2026-09-24T10:00:00-07:00", "2026-09-24T11:00:00-07:00", null)
                .withDescription("a < b > c &amp; d\r\nline two   \n\n\n\nlast")
                .build();

        assertTrue(CalendarEventText.render(html, null).endsWith("Description:\n\nAgenda:\n\n- Budget & hiring\n- Q&A\n\nSee notes &lt; &#0; é\n"));
        assertTrue(CalendarEventText.render(plain, null).endsWith("Description:\n\na < b > c &amp; d\nline two\n\nlast\n"));
    }

    @Test
    void theTextIsTextNoControlCharactersAndNoLineBreaksInsideATitleOrPlace() {
        CalendarEvent event = timed("Board\u0000 meeting\r\nfinal", "2026-09-24T10:00:00-07:00", "2026-09-24T11:00:00-07:00", null)
                .withLocation("  Room\u0007 4\n  East wing ")
                .withDescription("\u0000")
                .build();

        String text = CalendarEventText.render(event, null);

        assertTrue(text.contains("\n\nTitle: Board meeting final\n\n"));
        assertTrue(text.endsWith("\n\nLocation: Room 4 East wing\n"), "the last paragraph, since the description came to nothing");
        assertFalse(text.contains("Description"), "a description of nothing printable is no description");
        assertTrue(text.codePoints().allMatch(c -> c == '\n' || !Character.isISOControl(c)));
        assertTrue(text.startsWith(CalendarEventText.HEADING), "never mistaken for a PDF or a Word file by its first bytes");
    }

    @Test
    void anEventTheCalendarSaysHasNoEndIsNotGivenTheEndGoogleFillsInForIt() {
        CalendarEvent event = timed("Drop-in", "2026-09-24T10:00:00-07:00", "2026-09-24T10:00:00-07:00", null).endUnspecified().build();

        String text = CalendarEventText.render(event, LOS_ANGELES);

        assertTrue(text.contains("\n\nPlanned end: not given in the calendar\n\n"));
        assertFalse(text.contains("Planned end: 2026"));
    }

    @Test
    void aLongRunOfSpacesCostsOnePassNotOnePerSpace() {
        String spaces = " ".repeat(1024 * 1024);
        CalendarEvent event = timed(spaces + "Title" + spaces, "2026-09-24T10:00:00-07:00", "2026-09-24T11:00:00-07:00", null)
                .withLocation(spaces)
                .withDescription("<b>x</b>" + spaces + "\t" + spaces)
                .build();

        String text = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CalendarEventText.render(event, LOS_ANGELES),
                "a description or title anyone who invites the person can write must not tie up a request");

        assertTrue(text.contains("\n\nTitle: Title\n\n"));
        assertTrue(text.endsWith("Description:\n\nx\n"));
    }

    // ---- helpers

    private static Builder timed(String summary, String start, String end, String zone) {
        return new Builder(summary,
                CalendarEventTime.at(OffsetDateTime.parse(start), zone),
                CalendarEventTime.at(OffsetDateTime.parse(end), zone));
    }

    private static Builder allDay(String summary, String start, String end) {
        return new Builder(summary, CalendarEventTime.allDay(LocalDate.parse(start)), CalendarEventTime.allDay(LocalDate.parse(end)));
    }

    private static final class Builder {
        private final String summary;
        private final CalendarEventTime start;
        private final CalendarEventTime end;
        private CalendarEventStatus status = CalendarEventStatus.CONFIRMED;
        private String description;
        private String location;
        private boolean recurring;
        private boolean endUnspecified;

        Builder(String summary, CalendarEventTime start, CalendarEventTime end) {
            this.summary = summary;
            this.start = start;
            this.end = end;
        }

        Builder withStatus(CalendarEventStatus value) {
            status = value;
            return this;
        }

        Builder withDescription(String value) {
            description = value;
            return this;
        }

        Builder withLocation(String value) {
            location = value;
            return this;
        }

        Builder recurring() {
            recurring = true;
            return this;
        }

        Builder endUnspecified() {
            endUnspecified = true;
            return this;
        }

        CalendarEvent build() {
            return new CalendarEvent("event1", status, summary, description, location, start, end, endUnspecified, recurring, false,
                    "default", "https://www.google.com/calendar/event?eid=abc", OffsetDateTime.parse("2026-09-20T18:03:04Z"), "\"1\"");
        }
    }
}
