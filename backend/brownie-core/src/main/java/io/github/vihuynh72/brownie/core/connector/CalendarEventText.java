package io.github.vihuynh72.brownie.core.connector;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A calendar event written out as the text Brownie keeps as its source. The
 * same event and zone always give the same text, byte for byte.
 *
 * <p>Each fact is its own paragraph, so each can be cited on its own. The
 * times are labelled as planned, because a calendar says when something was
 * meant to happen, not when or whether it did. A timed event's times name
 * their time zone and UTC offset: the zone the event itself names, else the
 * calendar's. An all-day event is written as dates, its last day included
 * rather than the calendar's exclusive end. Who was invited is left out,
 * because an invitation is not attendance. A description that is HTML is
 * reduced to its text; one that is not is kept as written.
 */
public final class CalendarEventText {

    static final String HEADING = "Google Calendar event";
    static final String PLANNED_TIMES = "These are the times the event was planned for in the calendar."
            + " They do not show when it actually started or ended, or whether it took place.";
    static final String PLANNED_DATES = "These are the dates the event was planned for in the calendar, with no time of day."
            + " They do not show whether it took place.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd (EEEE)", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** Only these tag names make a description HTML; a stray "a < b" in plain text does not. */
    private static final Pattern KNOWN_TAG = Pattern.compile(
            "(?i)</?(?:a|b|i|u|s|br|p|div|span|ul|ol|li|strong|em|h[1-6]|blockquote|pre|code|font)\\b[^<>]*>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(?:p|div|h[1-6]|ul|ol|blockquote|pre)\\s*>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li\\b[^<>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?i)</?[a-z][a-z0-9]*\\b[^<>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);");
    private static final Pattern THREE_OR_MORE_NEWLINES = Pattern.compile("\\n{3,}");

    private CalendarEventText() {
    }

    /** The text for {@code event}; {@code calendarZone} is the calendar's own zone, or null when it is not known. */
    public static String render(CalendarEvent event, ZoneId calendarZone) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(HEADING);
        String title = oneLine(event.summary());
        paragraphs.add("Title: " + (title.isEmpty() ? "(no title)" : title));
        if (event.status() == CalendarEventStatus.TENTATIVE) {
            paragraphs.add("Status: tentative. The event had not been confirmed when Brownie read it.");
        }
        if (event.start().isAllDay()) {
            LocalDate first = event.start().date();
            LocalDate last = lastDay(event);
            paragraphs.add(first.equals(last)
                    ? "Planned date: " + DATE.format(first) + ", all day"
                    : "Planned dates: " + DATE.format(first) + " to " + DATE.format(last) + ", all day");
            paragraphs.add(PLANNED_DATES);
        } else {
            paragraphs.add("Planned start: " + moment(event.start(), calendarZone));
            // The calendar says there is no end; the one it still sends is a placeholder, not a planned time.
            paragraphs.add("Planned end: " + (event.endUnspecified() ? "not given in the calendar" : moment(event.end(), calendarZone)));
            paragraphs.add(PLANNED_TIMES);
        }
        if (event.recurring()) {
            paragraphs.add("Repeats: this is one occurrence of a repeating event.");
        }
        String location = oneLine(event.location());
        if (!location.isEmpty()) {
            paragraphs.add("Location: " + location);
        }
        String description = description(event.description());
        if (!description.isEmpty()) {
            paragraphs.add("Description:");
            paragraphs.add(description);
        }
        return String.join("\n\n", paragraphs) + "\n";
    }

    /** A zone by its IANA name, or null when the name is missing or not one Java knows. */
    static ZoneId zoneOrNull(String name) {
        if (isBlank(name)) {
            return null;
        }
        try {
            return ZoneId.of(name.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** An all-day event's end date is the day after its last; a malformed end falls back to the first day. */
    private static LocalDate lastDay(CalendarEvent event) {
        LocalDate first = event.start().date();
        LocalDate end = event.end().date();
        if (end == null || !end.isAfter(first)) {
            return first;
        }
        return end.minusDays(1);
    }

    private static String moment(CalendarEventTime time, ZoneId calendarZone) {
        if (time.isAllDay()) {
            return DATE.format(time.date()) + ", all day";
        }
        ZoneId own = zoneOrNull(time.timeZone());
        ZoneId zone = own != null ? own : calendarZone;
        OffsetDateTime at = zone == null ? time.dateTime() : time.dateTime().atZoneSameInstant(zone).toOffsetDateTime();
        String offset = utcOffset(at.getOffset());
        return DATE.format(at) + " " + TIME.format(at) + " (" + (zone == null ? offset : zone.getId() + ", " + offset) + ")";
    }

    private static String utcOffset(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "UTC" : "UTC" + offset.getId();
    }

    private static String description(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String text = clean(raw);
        if (KNOWN_TAG.matcher(text).find()) {
            text = LINE_BREAK.matcher(text).replaceAll("\n");
            text = BLOCK_END.matcher(text).replaceAll("\n\n");
            text = LIST_ITEM.matcher(text).replaceAll("\n- ");
            text = ANY_TAG.matcher(text).replaceAll("");
            text = clean(decodeEntities(text));
        }
        text = withoutTrailingBlanks(text);
        text = THREE_OR_MORE_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /** One pass, so "&amp;lt;" becomes "&lt;" and not "<"; a code point that is not printable text stays as written. */
    private static String decodeEntities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> numericEntity(name, matcher.group());
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String numericEntity(String name, String asWritten) {
        boolean hex = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
        int codePoint;
        try {
            codePoint = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
        } catch (NumberFormatException e) {
            return asWritten;
        }
        if (!Character.isValidCodePoint(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)
                || (Character.getType(codePoint) == Character.CONTROL && codePoint != '\n' && codePoint != '\t')) {
            return asWritten;
        }
        return new String(Character.toChars(codePoint));
    }

    /** Line endings as newlines, and no control characters but newline and tab. */
    private static String clean(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', '\n').replace(' ', '\n');
        StringBuilder out = new StringBuilder(unified.length());
        unified.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || Character.getType(codePoint) != Character.CONTROL) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    /**
     * The text on one line, its lines joined by single spaces; empty for null
     * or nothing printable. Done line by line rather than with a pattern, so
     * a long run of spaces costs one pass, not one pass per space.
     */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        StringJoiner joined = new StringJoiner(" ");
        for (String line : clean(text).split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                joined.add(stripped);
            }
        }
        return joined.toString();
    }

    /** Spaces and tabs at the end of each line removed, in one pass over the text. */
    private static String withoutTrailingBlanks(String text) {
        StringJoiner joined = new StringJoiner("\n");
        for (String line : text.split("\n", -1)) {
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            joined.add(line.substring(0, end));
        }
        return joined.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
