package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.mcp.McpToolCall;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A document's MCP tool-call history, for its version history in the web
 * app. Session-authenticated like every document route; the document must
 * be this workspace's, so a guessed id in another workspace is a plain
 * not-found.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/mcp-calls")
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class McpDocumentCallsController {

    private final McpToolCallRepository toolCallRepository;
    private final RevisionService revisionService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    McpDocumentCallsController(
            McpToolCallRepository toolCallRepository,
            RevisionService revisionService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.toolCallRepository = toolCallRepository;
        this.revisionService = revisionService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping
    List<ToolCallResponse> calls(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        revisionService
                .findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return toolCallRepository.findForDocument(workspaceId, userId, documentId).stream()
                .map(ToolCallResponse::from)
                .toList();
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record ToolCallResponse(long id, long grantId, String tool, String outcome, String code, OffsetDateTime at) {

        static ToolCallResponse from(McpToolCall call) {
            return new ToolCallResponse(
                    call.id(), call.grantId(), call.tool(), call.outcome().name(), call.code(), call.at());
        }
    }
}
