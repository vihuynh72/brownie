package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.zone.ZoneRules;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proposes adding one event, typed by the person from a document's page, to
 * their own main calendar: not repeating, with no guests, telling nobody.
 *
 * <p>The time is checked as a person means it. A timed event is two local
 * times in a named time zone; a local time the clocks skip over that day is
 * refused, and one they go back over (so it happens twice) is taken the
 * first time, which the payload says so the preview can say it too. Each
 * moment is then written with its offset, so what is approved is one exact
 * moment and not a reading of it. An all-day event is its first and last
 * day, stored as Google counts them, the end day not included.
 *
 * <p>The event's id at Google is chosen here, at random, and never changes,
 * so that sending it twice cannot make two events and the event can be
 * asked for afterwards. Nothing is sent to Google here but the token refresh
 * that shows the connection can be used.
 */
public class CalendarEventProposals {

    static final int MAX_TITLE_LENGTH = 300;
    static final int MAX_DESCRIPTION_LENGTH = 4000;
    static final int MAX_LOCATION_LENGTH = 300;
    /** Longer than any single event a document describes; a longer one is more likely a mistake than an event. */
    static final Duration MAX_TIMED_LENGTH = Duration.ofDays(31);
    static final int MAX_ALL_DAY_DAYS = 31;
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    static final LocalDate LATEST = LocalDate.of(2100, 12, 31);
    /** 26 base32hex characters: 130 random bits, far past any chance of meeting another id. */
    static final int EVENT_ID_LENGTH = 26;
    private static final char[] BASE32HEX = "0123456789abcdefghijklmnopqrstuv".toCharArray();
    /** A region's name as the time zone database spells it; never a bare offset, which does not know its own clock changes. */
    private static final Pattern ZONE_NAME = Pattern.compile("^(?:UTC|[A-Z][A-Za-z_]+(?:/[A-Za-z0-9_+-]+){1,2})$");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** A character reference, which Google Calendar would show as the character it names. */
    private static final Pattern ENTITY = Pattern.compile("&(?:#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);");

    /** What the person typed, before it is checked. Times are local, in {@code timeZone}; all-day days are the first and the last. */
    public record Request(
            String title, String description, String location, boolean allDay, String timeZone, String start, String end) {
    }

    private final ActionService actionService;
    private final ConnectorService connectorService;
    private final RevisionService revisionService;

    public CalendarEventProposals(ActionService actionService, ConnectorService connectorService, RevisionService revisionService) {
        this.actionService = Objects.requireNonNull(actionService, "actionService");
        this.connectorService = Objects.requireNonNull(connectorService, "connectorService");
        this.revisionService = Objects.requireNonNull(revisionService, "revisionService");
    }

    public ActionRequest propose(long workspaceId, long userId, long documentId, Request request) {
        actionService.requireProposable(workspaceId, userId, ActionType.CALENDAR_CREATE_EVENT);
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        String title = oneLine(request.title(), "The title", MAX_TITLE_LENGTH, false);
        String location = oneLine(request.location(), "The place", MAX_LOCATION_LENGTH, true);
        String description = description(request.description());
        EventTiming timing = request.allDay() ? allDay(request) : timed(request);

        UsableConnection calendar = connectorService.use(workspaceId, userId, ConnectorAccess.CALENDAR_EVENT_CREATION);
        CalendarEventPayload payload = new CalendarEventPayload(
                UUID.randomUUID().toString(),
                userId,
                workspaceId,
                documentId,
                Payloads.shownTitle(document.title()),
                calendar.connection().id(),
                calendar.connection().accountEmail(),
                title,
                description,
                location,
                timing);
        return actionService.propose(workspaceId, userId, new NewAction(
                documentId,
                calendar.connection().id(),
                ActionType.CALENDAR_CREATE_EVENT,
                payload.canonical(),
                payload.siblingKey(),
                null,
                null,
                null,
                null,
                newEventId()));
    }

    static String newEventId() {
        char[] id = new char[EVENT_ID_LENGTH];
        for (int i = 0; i < id.length; i++) {
            id[i] = BASE32HEX[RANDOM.nextInt(BASE32HEX.length)];
        }
        return new String(id);
    }

    /** A title or a place: on one line, of a length a calendar shows, with nothing hidden in it. */
    static String oneLine(String text, String what, int maxLength, boolean mayBeEmpty) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() && !mayBeEmpty) {
            throw invalid(what + " is empty.");
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw invalid(what + " is longer than " + maxLength + " characters.");
        }
        if (value.codePoints().anyMatch(CalendarEventProposals::breaksOrControls)) {
            throw invalid(what + " holds a line break or a control character.");
        }
        hiddenRefused(value);
        return value;
    }

    /**
     * A description may run over several lines. Google Calendar reads a
     * description as formatting: a tag, or a character reference such as
     * {@code &#x202E;}, would show something other than what was
     * approved, so a description with {@code <} or a reference in it is
     * refused.
     */
    static String description(String text) {
        String value = text == null ? "" : text.replace("\r\n", "\n").strip();
        if (value.codePointCount(0, value.length()) > MAX_DESCRIPTION_LENGTH) {
            throw invalid("The description is longer than " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        if (value.codePoints().anyMatch(c -> c != '\n' && c != '\t' && breaksOrControls(c))) {
            throw invalid("The description holds a control character.");
        }
        if (value.indexOf('<') >= 0) {
            throw invalid("Google Calendar reads < in a description as formatting, so Brownie does not send it. Leave it out.");
        }
        if (ENTITY.matcher(value).find()) {
            throw invalid("Google Calendar reads a code such as &amp; in a description as the character it stands for, so Brownie"
                    + " does not send it. Write the character itself.");
        }
        hiddenRefused(value);
        return value;
    }

    private static boolean breaksOrControls(int c) {
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE || type == Character.UNASSIGNED;
    }

    private static void hiddenRefused(String value) {
        if (VisibleText.hidesSomething(value)) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.HIDDEN_CHARACTERS,
                    "Some of the event's text holds characters that reorder it or cannot be seen.");
        }
    }

    static EventTiming.Timed timed(Request request) {
        ZoneId zone = zone(request.timeZone());
        LocalDateTime startLocal = localDateTime(request.start(), "start");
        LocalDateTime endLocal = localDateTime(request.end(), "end");
        Moment start = moment(startLocal, zone, "start");
        Moment end = moment(endLocal, zone, "end");
        Duration length = Duration.between(start.at().toInstant(), end.at().toInstant());
        if (length.isNegative() || length.isZero()) {
            throw invalid("The event ends before it starts.");
        }
        if (length.compareTo(MAX_TIMED_LENGTH) > 0) {
            throw invalid("An event can last at most " + MAX_TIMED_LENGTH.toDays() + " days.");
        }
        return new EventTiming.Timed(zone.getId(), start.at(), end.at(), start.firstOfTwo(), end.firstOfTwo());
    }

    static EventTiming.AllDay allDay(Request request) {
        LocalDate first = date(request.start(), "first day");
        LocalDate last = date(request.end(), "last day");
        if (last.isBefore(first)) {
            throw invalid("The last day is before the first.");
        }
        if (first.plusDays(MAX_ALL_DAY_DAYS).isBefore(last.plusDays(1))) {
            throw invalid("An all-day event can last at most " + MAX_ALL_DAY_DAYS + " days.");
        }
        return new EventTiming.AllDay(first, last.plusDays(1));
    }

    private static ZoneId zone(String name) {
        if (name == null || !ZONE_NAME.matcher(name).matches() || name.startsWith("SystemV/") || !ZoneId.getAvailableZoneIds().contains(name)) {
            throw invalid("The time zone is not one of the time zones' own names, such as Europe/Paris.");
        }
        return ZoneId.of(name);
    }

    private static LocalDateTime localDateTime(String value, String which) {
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(value == null ? "" : value);
        } catch (DateTimeParseException e) {
            throw invalid("The " + which + " is not a date and time.");
        }
        if (local.getSecond() != 0 || local.getNano() != 0) {
            throw invalid("The " + which + " is to the minute.");
        }
        inRange(local.toLocalDate(), which);
        return local;
    }

    private static LocalDate date(String value, String which) {
        LocalDate date;
        try {
            date = LocalDate.parse(value == null ? "" : value);
        } catch (DateTimeParseException e) {
            throw invalid("The " + which + " is not a date.");
        }
        inRange(date, which);
        return date;
    }

    private static void inRange(LocalDate date, String which) {
        if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
            throw invalid("The " + which + " is not between " + EARLIEST.getYear() + " and " + LATEST.getYear() + ".");
        }
    }

    private record Moment(OffsetDateTime at, boolean firstOfTwo) {
    }

    /** A local time as the zone's clocks show it: refused when skipped, the first of the two when it happens twice. */
    private static Moment moment(LocalDateTime local, ZoneId zone, String which) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.isEmpty()) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.TIME_SKIPPED,
                    "The " + which + " time does not happen on that day in " + zone.getId() + ": the clocks go forward over it.");
        }
        // Of the two, the larger offset is the one before the clocks go back, so it names the earlier moment.
        ZoneOffset offset = offsets.stream().max(Comparator.comparingInt(ZoneOffset::getTotalSeconds)).orElseThrow();
        return new Moment(OffsetDateTime.of(local, offset), offsets.size() > 1);
    }

    private static ActionNotProposableException invalid(String message) {
        return new ActionNotProposableException(ActionNotProposableException.Reason.INVALID, message);
    }
}
