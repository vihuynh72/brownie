package io.github.vihuynh72.brownie.api.action;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.action.ActionNotFoundException;
import io.github.vihuynh72.brownie.core.action.ActionRequest;
import io.github.vihuynh72.brownie.core.action.ActionService;
import io.github.vihuynh72.brownie.core.action.CalendarEventProposals;
import io.github.vihuynh72.brownie.core.action.DocAppendProposals;
import io.github.vihuynh72.brownie.core.action.DriveSaveProposals;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A person's proposed changes to their outside accounts: reading them,
 * approving one (which carries it out), asking what became of one whose
 * outcome is unknown, withdrawing one, and saying one has checked an outcome
 * Brownie cannot know.
 *
 * <p>Approving is the only route that makes Brownie change anything in a
 * person's account, and it takes the hash of the payload the person was
 * shown, from their own signed-in session and with the session's CSRF token,
 * like every change. Once anything may have been sent, every route answers
 * with the action and its state, never with an error that would invite
 * sending it again. Every answer carries the person's own content, so none
 * may be kept by a shared cache.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/actions")
class ActionController {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    private final ActionService actionService;
    private final DriveSaveProposals driveSaveProposals;
    private final CalendarEventProposals calendarEventProposals;
    private final DocAppendProposals docAppendProposals;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;
    private final ObjectMapper objectMapper;

    ActionController(
            ActionService actionService,
            DriveSaveProposals driveSaveProposals,
            CalendarEventProposals calendarEventProposals,
            DocAppendProposals docAppendProposals,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository,
            ObjectMapper objectMapper) {
        this.actionService = actionService;
        this.driveSaveProposals = driveSaveProposals;
        this.calendarEventProposals = calendarEventProposals;
        this.docAppendProposals = docAppendProposals;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
        this.objectMapper = objectMapper;
    }

    /** The actions proposed for one document, newest first. */
    @GetMapping
    ResponseEntity<List<ActionResponse>> list(
            @PathVariable("workspaceId") long workspaceId,
            @RequestParam(name = "documentId", required = false) Long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        if (documentId == null || documentId <= 0) {
            throw new ActionRequestValidationException("documentId must be positive.");
        }
        Instant now = actionService.now();
        List<ActionResponse> actions = actionService.forDocument(workspaceId, userId, documentId).stream()
                .map(action -> response(workspaceId, userId, action, now))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(actions);
    }

    /**
     * Proposes saving the document's latest export to the person's Drive: the
     * exact Word or PDF file, or a Google Doc converted from the Word file.
     * Nothing is saved until the answer's payload is approved.
     */
    @PostMapping("/drive-saves")
    ResponseEntity<ActionResponse> proposeDriveSave(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody DriveSaveRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        if (request == null || request.documentId() == null || request.documentId() <= 0) {
            throw new ActionRequestValidationException("documentId must be positive.");
        }
        DriveSaveProposals.Kind kind = parseKind(request.kind());
        ActionRequest proposed = driveSaveProposals.propose(workspaceId, userId, request.documentId(), kind);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response(workspaceId, userId, proposed, actionService.now()));
    }

    /**
     * Proposes adding one event to the person's main calendar: not repeating,
     * with no guests, telling nobody. Nothing is added until the answer's
     * payload is approved.
     */
    @PostMapping("/calendar-events")
    ResponseEntity<ActionResponse> proposeCalendarEvent(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody CalendarEventRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        if (request == null || request.documentId() == null || request.documentId() <= 0) {
            throw new ActionRequestValidationException("documentId must be positive.");
        }
        if (request.allDay() == null) {
            throw new ActionRequestValidationException("allDay must be true or false.");
        }
        if (request.title() == null || request.start() == null || request.end() == null) {
            throw new ActionRequestValidationException("title, start and end are required.");
        }
        ActionRequest proposed = calendarEventProposals.propose(workspaceId, userId, request.documentId(),
                new CalendarEventProposals.Request(request.title(), request.description(), request.location(), request.allDay(),
                        request.timeZone(), request.start(), request.end()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response(workspaceId, userId, proposed, actionService.now()));
    }

    /**
     * Proposes adding the text of the document's current version to the end
     * of a Google Doc that a save of the caller's made. Nothing is added until
     * the answer's payload is approved.
     */
    @PostMapping("/doc-appends")
    ResponseEntity<ActionResponse> proposeDocAppend(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody DocAppendRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        if (request == null || request.documentId() == null || request.documentId() <= 0) {
            throw new ActionRequestValidationException("documentId must be positive.");
        }
        if (request.targetActionId() == null || request.targetActionId() <= 0) {
            throw new ActionRequestValidationException("targetActionId must name a save that made a Google Doc.");
        }
        ActionRequest proposed = docAppendProposals.propose(workspaceId, userId, request.documentId(), request.targetActionId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response(workspaceId, userId, proposed, actionService.now()));
    }

    @GetMapping("/{actionId}")
    ResponseEntity<ActionResponse> get(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("actionId") long actionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        ActionRequest action = actionService.find(workspaceId, userId, actionId).orElseThrow(() -> new ActionNotFoundException(actionId));
        return answer(workspaceId, userId, action);
    }

    /** Approves exactly the payload whose hash is sent, and carries it out; the answer says what became of it. */
    @PostMapping("/{actionId}/approve")
    ResponseEntity<ActionResponse> approve(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("actionId") long actionId,
            @RequestBody ApproveRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        if (request == null || request.payloadHash() == null || !HASH.matcher(request.payloadHash()).matches()) {
            throw new ActionRequestValidationException("payloadHash must be the 64-character hash the action was shown with.");
        }
        return answer(workspaceId, userId, actionService.approve(workspaceId, userId, actionId, request.payloadHash()));
    }

    @PostMapping("/{actionId}/reconcile")
    ResponseEntity<ActionResponse> reconcile(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("actionId") long actionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        return answer(workspaceId, userId, actionService.reconcile(workspaceId, userId, actionId));
    }

    @PostMapping("/{actionId}/cancel")
    ResponseEntity<ActionResponse> cancel(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("actionId") long actionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        return answer(workspaceId, userId, actionService.cancel(workspaceId, userId, actionId));
    }

    @PostMapping("/{actionId}/acknowledge")
    ResponseEntity<ActionResponse> acknowledge(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("actionId") long actionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = permitted(principal, workspaceId);
        return answer(workspaceId, userId, actionService.acknowledgeUnknown(workspaceId, userId, actionId));
    }

    private ResponseEntity<ActionResponse> answer(long workspaceId, long userId, ActionRequest action) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(workspaceId, userId, action, actionService.now()));
    }

    private ActionResponse response(long workspaceId, long userId, ActionRequest action, Instant now) {
        return ActionResponse.from(action, actionService.attempts(workspaceId, userId, action.id()), now, objectMapper);
    }

    private long permitted(OidcUser principal, long workspaceId) {
        long userId = userIdentityRepository
                .findByIssuerAndSubject(principal.getIssuer().toString(), principal.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new)
                .id();
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.ACT_ON_CONNECTED_ACCOUNTS);
        return userId;
    }

    private static DriveSaveProposals.Kind parseKind(String kind) {
        if (kind != null) {
            for (DriveSaveProposals.Kind candidate : DriveSaveProposals.Kind.values()) {
                if (candidate.name().equals(kind)) {
                    return candidate;
                }
            }
        }
        throw new ActionRequestValidationException("kind must be WORD_FILE, PDF_FILE or GOOGLE_DOC.");
    }

    record ApproveRequest(String payloadHash) {
    }

    record DriveSaveRequest(Long documentId, String kind) {
    }

    record DocAppendRequest(Long documentId, Long targetActionId) {
    }

    record CalendarEventRequest(
            Long documentId, String title, String description, String location, Boolean allDay, String timeZone, String start,
            String end) {
    }
}
