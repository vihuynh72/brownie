package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.PatchAcceptanceResult;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts a previously proposed patch (see {@code
 * GenerationController#apply} for where a proposal actually comes from
 * today) onto the document's real current revision. A separate, explicit
 * step from proposing one, matching this codebase's own "AI output is
 * proposed, never applied silently" rule for every other generated value.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/patch-proposals")
class PatchProposalController {

    private static final String DEFAULT_EDIT_REASON = "Accepted generated draft";

    private final RevisionService revisionService;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    PatchProposalController(
            RevisionService revisionService,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.revisionService = revisionService;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/{proposalId}/accept")
    PatchAcceptResponse accept(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long proposalId,
            @RequestBody AcceptPatchProposalRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        String editReason = request.editReason() == null || request.editReason().isBlank() ? DEFAULT_EDIT_REASON : request.editReason();
        PatchAcceptanceResult result = revisionService.acceptPatch(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new AcceptPatchProposalHashInput(
                        "document.accept-patch-proposal", workspaceId, documentId, proposalId, request)),
                documentId,
                proposalId,
                positive(request.expectedRevisionId(), "expectedRevisionId"),
                editReason);
        return PatchAcceptResponse.from(result);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    private static long positive(long value, String field) {
        if (value <= 0) {
            throw new DocumentRequestValidationException(field + " must be positive.");
        }
        return value;
    }

    private static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new DocumentRequestValidationException("Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        return new IdempotencyKey(value);
    }

    private record AcceptPatchProposalHashInput(
            String operation, long workspaceId, long documentId, long proposalId, AcceptPatchProposalRequest request) {
    }

    record AcceptPatchProposalRequest(long expectedRevisionId, String editReason) {
    }

    record PatchAcceptResponse(boolean applied, Map<String, String> fieldStatuses, DocumentController.DocumentRevisionResponse revision) {
        static PatchAcceptResponse from(PatchAcceptanceResult result) {
            Map<String, String> statuses = new LinkedHashMap<>();
            result.comparison().fieldStatuses().forEach((fieldId, status) -> statuses.put(fieldId, status.name()));
            DocumentController.DocumentRevisionResponse revision = result.mutation()
                    .map(mutation -> DocumentController.DocumentRevisionResponse.from(mutation.revision()))
                    .orElse(null);
            return new PatchAcceptResponse(result.mutation().isPresent(), statuses, revision);
        }
    }
}
