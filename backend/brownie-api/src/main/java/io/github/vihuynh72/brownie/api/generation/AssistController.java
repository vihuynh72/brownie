package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Assist composer's two calls: interpret a typed request into one
 * bounded command with its scope, then execute exactly that. Execution
 * changes nothing on the document itself: a change or a rewrite comes
 * back as a patch proposal for the ordinary accept route, an explanation
 * is text, and a draft request is carried out by the workspace through
 * the existing generation route.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/assist")
class AssistController {

    private final AssistService assistService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    AssistController(
            AssistService assistService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.assistService = assistService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /** No side effects: what the request would do and to which field or finding, or what Brownie can do instead. */
    @PostMapping("/interpret")
    AssistInterpretationResponse interpret(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody AssistTextRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return AssistInterpretationResponse.from(assistService.interpret(workspaceId, userId, documentId, request.text()));
    }

    /** Executes the interpreted command against the revision the person was looking at (412 if it moved on). */
    @PostMapping("/execute")
    AssistExecutionResponse execute(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody AssistExecuteRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        if (request.expectedRevisionId() <= 0) {
            throw new AssistRequestValidationException("expectedRevisionId must be positive.");
        }
        return AssistExecutionResponse.from(
                assistService.execute(workspaceId, userId, documentId, request.text(), request.expectedRevisionId()));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record AssistTextRequest(String text) {
    }

    record AssistExecuteRequest(String text, long expectedRevisionId) {
    }

    record AssistScopeResponse(String fieldId, String label, String currentValue, String findingMessage) {
        static AssistScopeResponse from(AssistService.Scope scope) {
            return scope == null ? null : new AssistScopeResponse(scope.fieldId(), scope.label(), scope.currentValue(), scope.findingMessage());
        }
    }

    record AssistInterpretationResponse(
            String kind, String summary, AssistScopeResponse scope, boolean executable, boolean usesModel, List<String> help) {
        static AssistInterpretationResponse from(AssistService.Interpretation interpretation) {
            return new AssistInterpretationResponse(
                    interpretation.kind().name(),
                    interpretation.summary(),
                    AssistScopeResponse.from(interpretation.scope()),
                    interpretation.executable(),
                    interpretation.usesModel(),
                    interpretation.help());
        }
    }

    record AssistExecutionResponse(
            String kind, String summary, GenerationController.PatchProposalResponse proposal, String explanation, List<String> help) {
        static AssistExecutionResponse from(AssistService.Execution execution) {
            return new AssistExecutionResponse(
                    execution.kind().name(),
                    execution.summary(),
                    execution.proposal() == null ? null : GenerationController.PatchProposalResponse.fromProposal(execution.proposal()),
                    execution.explanation(),
                    execution.help());
        }
    }
}
