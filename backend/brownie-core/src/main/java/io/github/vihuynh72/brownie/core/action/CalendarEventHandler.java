package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Adding an approved event to the person's main calendar.
 *
 * <p>The event is sent under the id Brownie chose when it was proposed, so
 * Google refuses a second copy of it, and the event can be asked for by that
 * id afterwards. What Google holds is then compared with the approval: the
 * same id, confirmed, an ordinary event, the same title, description and
 * place, the same moments in the same time zone (or the same days), no
 * guests, not repeating, no video call, the calendar's usual reminders,
 * shown as busy, and private.
 *
 * <p>An event whose answer was lost is never sent again: Google stops
 * knowing a deleted event some time after it is deleted, so its not knowing
 * the id cannot prove the event was never made. Asking about it settles it
 * when Google holds the event (compared as above) or says it was deleted
 * since, or when an earlier answer named the event and, several minutes
 * after the last send, Google no longer knows it (it was made, and deleted
 * since); otherwise it stays unknown for the person to check.
 */
public class CalendarEventHandler implements ActionHandler {

    private final CalendarEventWriter writer;

    public CalendarEventHandler(CalendarEventWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public ActionType type() {
        return ActionType.CALENDAR_CREATE_EVENT;
    }

    @Override
    public ConnectorAccess access() {
        return ConnectorAccess.CALENDAR_EVENT_CREATION;
    }

    @Override
    public PreparedWrite prepare(ActionRequest action, UsableConnection connection) {
        CalendarEventPayload payload = CalendarEventPayload.parse(action.payloadCanonical());
        // What was approved is the payload; what is sent follows the record. They must name the same things.
        if (payload.documentId() != action.documentId()
                || payload.connectionId() != action.connectionId()
                || payload.workspaceId() != action.workspaceId()
                || payload.proposedBy() != action.userId()) {
            throw new IllegalStateException("An event's payload does not name what its record names; nothing is sent for it.");
        }
        String eventId = Objects.requireNonNull(action.providerKey(), "An event proposal always carries the id it is sent under.");
        NewCalendarEvent event = new NewCalendarEvent(eventId, payload.title(), payload.description(), payload.location(), payload.timing());
        return new PreparedWrite() {
            @Override
            public WriteAnswer send(UsableConnection usable) {
                return writer.insertEvent(usable.accessToken(), event);
            }

            @Override
            public ActionOutcome readBack(UsableConnection usable, WriteAnswer answer) {
                return switch (writer.findEvent(usable.accessToken(), eventId)) {
                    case CalendarEventLookup.Found found -> compare(payload, found.event(), eventId);
                    case CalendarEventLookup.Removed ignored -> new ActionOutcome.Done(ActionVerification.REMOVED_AFTERWARDS, eventId, null, null);
                    // Google said it was made, or already there, and cannot find it a moment later: not settled yet.
                    case CalendarEventLookup.Missing ignored -> new ActionOutcome.StillUnknown(eventId);
                };
            }
        };
    }

    @Override
    public ActionOutcome reconcile(ActionRequest action, UsableConnection connection, List<ActionAttempt> attempts, Instant now) {
        CalendarEventPayload payload = CalendarEventPayload.parse(action.payloadCanonical());
        String eventId = action.providerKey();
        // An answer that named the event proves it was made; Google forgetting it since means it was deleted since,
        // but only once Google has had time to show it: right after a send, a missing event may just not be there yet.
        boolean madeOnce = attempts.stream().anyMatch(attempt -> eventId.equals(attempt.externalId()));
        Instant lastSent = attempts.stream().map(ActionAttempt::sentAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        boolean settled = now.isAfter(lastSent.plus(ActionService.SETTLE_INTERVAL));
        return switch (writer.findEvent(connection.accessToken(), eventId)) {
            case CalendarEventLookup.Found found -> compare(payload, found.event(), eventId);
            case CalendarEventLookup.Removed ignored -> new ActionOutcome.Done(ActionVerification.REMOVED_AFTERWARDS, eventId, null, null);
            case CalendarEventLookup.Missing ignored -> !madeOnce
                    ? new ActionOutcome.StillUnknown(null)
                    : settled
                            ? new ActionOutcome.Done(ActionVerification.REMOVED_AFTERWARDS, eventId, null, null)
                            : new ActionOutcome.StillUnknown(eventId);
        };
    }

    /** Every field the approval states, compared with what Google holds; a cancelled event is one the person deleted. */
    static ActionOutcome compare(CalendarEventPayload payload, SavedCalendarEvent saved, String eventId) {
        if ("cancelled".equals(saved.status())) {
            return new ActionOutcome.Done(ActionVerification.REMOVED_AFTERWARDS, eventId, saved.link(), null);
        }
        boolean asApproved = eventId.equals(saved.id())
                && "confirmed".equals(saved.status())
                && (saved.eventType() == null || "default".equals(saved.eventType()))
                && payload.title().equals(saved.title())
                && payload.description().equals(saved.description())
                && payload.location().equals(saved.location())
                && sameTiming(payload.timing(), saved)
                && saved.attendeeCount() == 0
                && !saved.repeats()
                && !saved.hasConference()
                && usualReminders(payload.timing(), saved)
                && (saved.transparency() == null || "opaque".equals(saved.transparency()))
                && "private".equals(saved.visibility());
        return asApproved
                ? new ActionOutcome.Done(ActionVerification.MATCHED, eventId, saved.link(), null)
                : new ActionOutcome.Mismatched(eventId, saved.link());
    }

    /**
     * The same time zone, by its clock rules rather than its spelling: a zone
     * has older names (Asia/Calcutta for Asia/Kolkata), and Google may give
     * back another name for the one it was sent.
     */
    static boolean sameZone(String approved, String saved) {
        if (saved == null) {
            return false;
        }
        if (approved.equals(saved)) {
            return true;
        }
        try {
            return ZoneId.of(approved).getRules().equals(ZoneId.of(saved).getRules());
        } catch (DateTimeException e) {
            return false;
        }
    }

    /**
     * The calendar's usual reminders, as Google keeps them: for a timed event
     * as "the calendar's default"; for an all-day event Google writes the
     * calendar's usual reminders out as reminders of the event's own (the
     * default set to false, the calendar's reminders listed), which only ever
     * remind the person themselves.
     */
    private static boolean usualReminders(EventTiming timing, SavedCalendarEvent saved) {
        return timing instanceof EventTiming.AllDay || (saved.usesDefaultReminders() && saved.reminderOverrideCount() == 0);
    }

    private static boolean sameTiming(EventTiming timing, SavedCalendarEvent saved) {
        return switch (timing) {
            case EventTiming.Timed timed -> saved.startAt() != null && saved.endAt() != null
                    && saved.startDate() == null && saved.endDate() == null
                    && timed.start().toInstant().equals(saved.startAt().toInstant())
                    && timed.end().toInstant().equals(saved.endAt().toInstant())
                    && sameZone(timed.timeZone(), saved.startTimeZone())
                    && sameZone(timed.timeZone(), saved.endTimeZone());
            case EventTiming.AllDay allDay -> saved.startAt() == null && saved.endAt() == null
                    && allDay.startDate().equals(saved.startDate())
                    && allDay.endDate().equals(saved.endDate());
        };
    }
}
