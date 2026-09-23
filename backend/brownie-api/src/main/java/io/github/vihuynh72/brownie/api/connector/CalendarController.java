package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.source.DocumentSourceResponse;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.connector.CalendarEvent;
import io.github.vihuynh72.brownie.core.connector.CalendarEventTime;
import io.github.vihuynh72.brownie.core.connector.CalendarImportService;
import io.github.vihuynh72.brownie.core.connector.CalendarWindow;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Reading the person's own Google calendar: the events in a window, to
 * choose from, and one chosen event copied into a document as a source.
 *
 * <p>The window is two instants with their UTC offsets, so the page decides
 * what "this week" means where the person is, and the server never guesses a
 * time zone. Both answers carry the person's own calendar, so neither may be
 * kept by a shared cache. Both routes sit under the connection's own path,
 * which is what puts them in the rate class for work that calls Google.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/connections/google/calendar")
class CalendarController {

    private final CalendarImportService calendarImportService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    CalendarController(
            CalendarImportService calendarImportService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.calendarImportService = calendarImportService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping("/events")
    ResponseEntity<CalendarEventsResponse> events(
            @PathVariable("workspaceId") long workspaceId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        CalendarWindow window = calendarImportService.events(workspaceId, userId, instant(from, "from"), instant(to, "to"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CalendarEventsResponse.from(window));
    }

    /** Copies the event into the document, or links a copy already made of it with the same text; {@code newCopy} says which. */
    @PostMapping("/imports")
    ResponseEntity<CalendarImportResponse> importEvent(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody ImportRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        if (request.documentId() == null || request.documentId() <= 0) {
            throw new ConnectionRequestValidationException("documentId must be positive.");
        }
        CalendarImportService.ImportOutcome outcome =
                calendarImportService.importEvent(workspaceId, userId, request.documentId(), request.eventId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new CalendarImportResponse(DocumentSourceResponse.from(outcome.source()), outcome.newCopy()));
    }

    private static Instant instant(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ConnectionRequestValidationException(name + " is required.");
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new ConnectionRequestValidationException(name + " must be a date and time with its UTC offset, such as 2026-09-24T00:00:00-07:00.");
        }
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record ImportRequest(Long documentId, String eventId) {
    }

    record CalendarImportResponse(DocumentSourceResponse source, boolean newCopy) {
    }

    /** {@code truncated} when the calendar holds more events in the window than were returned; {@code timeZone} is the calendar's own. */
    record CalendarEventsResponse(String timeZone, boolean truncated, List<CalendarEventResponse> events) {

        static CalendarEventsResponse from(CalendarWindow window) {
            return new CalendarEventsResponse(
                    window.timeZone(), window.truncated(), window.events().stream().map(CalendarEventResponse::from).toList());
        }
    }

    /**
     * One event to choose from. An all-day event has {@code startDate} and
     * {@code endDate}, its last day included; a timed one has {@code startsAt}
     * and {@code endsAt} with their offsets ({@code endsAt} null when the
     * calendar says the event has no end), and {@code timeZone} when the event
     * names its own. Everything here is as planned in the calendar.
     */
    record CalendarEventResponse(
            String id,
            String title,
            String status,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            boolean recurring) {

        static CalendarEventResponse from(CalendarEvent event) {
            CalendarEventTime start = event.start();
            CalendarEventTime end = event.end();
            String title = event.summary() == null || event.summary().isBlank() ? null : event.summary().strip();
            if (start.isAllDay()) {
                LocalDate last = end.isAllDay() && end.date().isAfter(start.date()) ? end.date().minusDays(1) : start.date();
                return new CalendarEventResponse(
                        event.id(), title, event.status().name(), true, start.date(), last, null, null, null, event.recurring());
            }
            return new CalendarEventResponse(
                    event.id(), title, event.status().name(), false, null, null,
                    start.dateTime(), end.isAllDay() || event.endUnspecified() ? null : end.dateTime(), start.timeZone(), event.recurring());
        }
    }
}
