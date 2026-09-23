package io.github.vihuynh72.brownie.api.retention;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.PendingRevocations;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionRequest;
import io.github.vihuynh72.brownie.core.retention.DeletionScope;
import io.github.vihuynh72.brownie.core.retention.DeletionService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Trash, restore and permanent deletion. None of these routes takes an
 * idempotency key because each is idempotent by what it does: trashing a
 * trashed document, restoring a restored one and deleting a deleted one all
 * answer with the entry that already says so, and nothing is created twice.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/deletions")
class DeletionController {

    private final DeletionService deletionService;
    private final ConnectorService connectorService;
    private final SessionRevoker sessionRevoker;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    DeletionController(
            DeletionService deletionService,
            ConnectorService connectorService,
            SessionRevoker sessionRevoker,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.deletionService = deletionService;
        this.connectorService = connectorService;
        this.sessionRevoker = sessionRevoker;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /**
     * A document goes to the trash (201, restorable). A workspace is deleted
     * for good in this same call (200), and every session of the person who
     * asked has ended by the time it returns, because there is nothing left
     * for those sessions to open. While a worker is still stopping one of
     * the workspace's jobs the answer is a 409 and nothing, sessions
     * included, has changed.
     */
    @PostMapping
    ResponseEntity<?> create(
            @PathVariable long workspaceId,
            @RequestBody CreateDeletionRequest request,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletRequest httpRequest) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        DeletionScope scope = request.toScope();
        if (scope == DeletionScope.DOCUMENT) {
            long documentId = positive(request.documentId(), "documentId");
            DeletionRequest trashed = deletionService.trashDocument(workspaceId, userId, documentId);
            return ResponseEntity.status(HttpStatus.CREATED).body(DeletionResponse.from(trashed));
        }

        if (request.documentId() != null) {
            throw new DeletionRequestValidationException("documentId must be absent when scope is WORKSPACE.");
        }
        // Google is asked to forget this person's access only once the workspace, and with it every stored token, is
        // really gone: the tokens are read first, because the deletion removes the rows that hold them, and a
        // deletion that is refused must leave the person's connections exactly as they were.
        PendingRevocations googleAccess = connectorService.prepareForWorkspaceDeletion(workspaceId, userId);
        long deletionId = deletionService.deleteWorkspace(workspaceId, userId);
        connectorService.revokeAfterWorkspaceDeletion(googleAccess);
        sessionRevoker.revokeAll(principal.getIssuer().toString(), principal.getSubject());
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(new WorkspaceDeletionResponse(deletionId));
    }

    @GetMapping
    List<DeletionResponse> findAll(@PathVariable long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return deletionService.findAll(workspaceId, userId).stream().map(DeletionResponse::from).toList();
    }

    @GetMapping("/{deletionId}")
    DeletionResponse find(
            @PathVariable long workspaceId, @PathVariable long deletionId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return DeletionResponse.from(deletionService.find(workspaceId, userId, positive(deletionId, "deletionId")));
    }

    @PostMapping("/{deletionId}/restore")
    DeletionResponse restore(
            @PathVariable long workspaceId, @PathVariable long deletionId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return DeletionResponse.from(deletionService.restoreDocument(workspaceId, userId, positive(deletionId, "deletionId")));
    }

    @PostMapping("/{deletionId}/purge")
    DeletionResponse purge(
            @PathVariable long workspaceId, @PathVariable long deletionId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return DeletionResponse.from(deletionService.purgeDocument(workspaceId, userId, positive(deletionId, "deletionId")));
    }

    private void requireAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new DeletionRequestValidationException(field + " must be positive.");
        }
        return value;
    }

    record CreateDeletionRequest(String scope, Long documentId) {

        DeletionScope toScope() {
            if (scope == null || scope.isBlank()) {
                throw new DeletionRequestValidationException("scope must be one of " + List.of(DeletionScope.values()) + ".");
            }
            try {
                return DeletionScope.valueOf(scope);
            } catch (IllegalArgumentException e) {
                throw new DeletionRequestValidationException("scope must be one of " + List.of(DeletionScope.values()) + ".");
            }
        }
    }

    /**
     * {@code title} is present only while a document is still in the
     * trash; {@code pendingObjectCount} is how many stored files of
     * something deleted for good are still waiting to be removed.
     */
    record DeletionResponse(
            long id,
            String scope,
            long targetId,
            String state,
            String title,
            OffsetDateTime requestedAt,
            OffsetDateTime purgeAfter,
            OffsetDateTime restoredAt,
            OffsetDateTime purgedAt,
            OffsetDateTime verifiedAt,
            int pendingObjectCount) {

        static DeletionResponse from(DeletionRequest request) {
            return new DeletionResponse(
                    request.id(),
                    request.scope().name(),
                    request.targetId(),
                    request.state().name(),
                    request.targetTitle(),
                    request.requestedAt(),
                    request.purgeAfter(),
                    request.restoredAt(),
                    request.purgedAt(),
                    request.verifiedAt(),
                    request.pendingObjectCount());
        }
    }

    record WorkspaceDeletionResponse(long deletionId) {
    }
}
