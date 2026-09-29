package io.github.vihuynh72.brownie.core.action;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exactly what adding an event to the person's calendar does, as they
 * approve it: which Google account, the main calendar, the event's title,
 * description, place and time, and everything else about it stated rather
 * than left to a default the person cannot see: no guests, no messages to
 * anyone, not repeating, no video call, the calendar's usual reminders, shown
 * as busy, and private.
 *
 * <p>{@code nonce} and {@code proposedBy} make every proposal's text, and so
 * its hash, its own. The event's id at Google is not part of it: nothing a
 * person is shown carries one.
 */
public record CalendarEventPayload(
        String nonce,
        long proposedBy,
        long workspaceId,
        long documentId,
        String documentTitle,
        long connectionId,
        String accountEmail,
        String title,
        String description,
        String location,
        EventTiming timing) {

    public CalendarEventPayload {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(documentTitle, "documentTitle");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(timing, "timing");
    }

    public String canonical() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", documentId);
        document.put("title", documentTitle);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("connection", connectionId);
        account.put("email", accountEmail);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("calendar", "PRIMARY");
        target.put("guests", "NONE");
        target.put("notifications", "NONE");
        target.put("repeats", "NEVER");
        target.put("conference", "NONE");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", title);
        event.put("description", description);
        event.put("location", location);
        event.put("when", when(timing));
        event.put("reminders", "CALENDAR_DEFAULT");
        event.put("showAs", "BUSY");
        event.put("visibility", "PRIVATE");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Payloads.SCHEMA);
        root.put("type", ActionType.CALENDAR_CREATE_EVENT.name());
        root.put("nonce", nonce);
        root.put("proposedBy", proposedBy);
        root.put("workspace", workspaceId);
        root.put("document", document);
        root.put("account", account);
        root.put("target", target);
        root.put("event", event);
        root.put("effect", Map.of("creates", "NEW_EVENT"));
        return CanonicalJson.write(root);
    }

    private static Map<String, Object> when(EventTiming timing) {
        Map<String, Object> when = new LinkedHashMap<>();
        switch (timing) {
            case EventTiming.Timed timed -> {
                when.put("allDay", false);
                when.put("timeZone", timed.timeZone());
                when.put("start", EventTiming.MOMENT.format(timed.start()));
                when.put("end", EventTiming.MOMENT.format(timed.end()));
                when.put("startIsFirstOfTwo", timed.startIsFirstOfTwo());
                when.put("endIsFirstOfTwo", timed.endIsFirstOfTwo());
            }
            case EventTiming.AllDay allDay -> {
                when.put("allDay", true);
                when.put("startDate", allDay.startDate().toString());
                when.put("endDate", allDay.endDate().toString());
            }
        }
        return when;
    }

    /** The payload this canonical text is, refusing anything Brownie would not have written. */
    public static CalendarEventPayload parse(String canonical) {
        Map<String, Object> root = Payloads.read(canonical, ActionType.CALENDAR_CREATE_EVENT);
        Map<String, Object> document = Payloads.object(root, "document");
        Map<String, Object> account = Payloads.object(root, "account");
        Map<String, Object> event = Payloads.object(root, "event");
        Map<String, Object> when = Payloads.object(event, "when");
        EventTiming timing;
        try {
            timing = Payloads.bool(when, "allDay")
                    ? new EventTiming.AllDay(
                            LocalDate.parse(Payloads.string(when, "startDate")),
                            LocalDate.parse(Payloads.string(when, "endDate")))
                    : new EventTiming.Timed(
                            Payloads.string(when, "timeZone"),
                            OffsetDateTime.parse(Payloads.string(when, "start"), EventTiming.MOMENT),
                            OffsetDateTime.parse(Payloads.string(when, "end"), EventTiming.MOMENT),
                            Payloads.bool(when, "startIsFirstOfTwo"),
                            Payloads.bool(when, "endIsFirstOfTwo"));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalStateException("A stored calendar payload's time is not one Brownie writes.", e);
        }
        CalendarEventPayload payload = new CalendarEventPayload(
                Payloads.string(root, "nonce"),
                Payloads.number(root, "proposedBy"),
                Payloads.number(root, "workspace"),
                Payloads.number(document, "id"),
                Payloads.string(document, "title"),
                Payloads.number(account, "connection"),
                Payloads.stringOrNull(account, "email"),
                Payloads.string(event, "title"),
                Payloads.string(event, "description"),
                Payloads.string(event, "location"),
                timing);
        // Writing it out again must give back the very text that was stored and hashed, fixed parts included.
        if (!payload.canonical().equals(canonical)) {
            throw new IllegalStateException("A stored calendar payload holds something a calendar payload does not.");
        }
        return payload;
    }

    /**
     * What this event is, for recognising the same event proposed twice: its
     * title and its time. A second proposal of it cannot be carried out while
     * the first might already be in the calendar.
     */
    public String siblingKey() {
        return CanonicalJson.sha256Hex(CanonicalJson.write(Map.of(
                "type", ActionType.CALENDAR_CREATE_EVENT.name(), "title", title, "when", when(timing))));
    }

    /** A payload holds a person's content, which never belongs in a log line. */
    @Override
    public String toString() {
        return "CalendarEventPayload[document=" + documentId + ", allDay=" + (timing instanceof EventTiming.AllDay) + "]";
    }
}
