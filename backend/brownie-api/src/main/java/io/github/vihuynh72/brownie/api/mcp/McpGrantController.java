package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.mcp.IssuedPersonalToken;
import io.github.vihuynh72.brownie.core.mcp.McpGrant;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import io.github.vihuynh72.brownie.core.mcp.McpRequestInvalidException;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The session-authenticated routes a signed-in person manages their MCP
 * credentials through. The whole controller exists only while the feature
 * is enabled, so a disabled deployment answers these the same 404 it
 * answers anything unmapped. Listing never carries a token or its hash --
 * the raw secret crosses the wire once, in the creation response.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/mcp")
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class McpGrantController {

    private final McpGrantService grantService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    McpGrantController(
            McpGrantService grantService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.grantService = grantService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping("/grants")
    List<GrantResponse> grants(
            @PathVariable long workspaceId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return grantService.listForUser(workspaceId, userId).stream()
                .map(GrantResponse::from)
                .toList();
    }

    @PostMapping("/personal-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedTokenResponse issuePersonalToken(
            @PathVariable long workspaceId,
            @RequestBody IssuePersonalTokenRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        IssuedPersonalToken issued =
                grantService.issuePersonalToken(workspaceId, userId, request.name(), requireScopes(request.scopes()));
        return new IssuedTokenResponse(issued.grantId(), issued.token());
    }

    @PostMapping("/grants/{grantId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(
            @PathVariable long workspaceId,
            @PathVariable long grantId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        // A revoked OAuth grant also loses every authorization it issued:
        // the repository drops those rows inside the same transaction, so
        // the access and refresh tokens die with the grant.
        grantService.revoke(workspaceId, userId, grantId);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    private static Set<McpScope> requireScopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new McpRequestInvalidException("A token needs at least one scope.");
        }
        Set<McpScope> scopes = EnumSet.noneOf(McpScope.class);
        for (String name : requested) {
            McpScope scope = McpScope.parse(name)
                    .orElseThrow(() -> new McpRequestInvalidException(
                            "Unrecognized scope \"" + name + "\"; choose from read, fill, export."));
            scopes.add(scope);
        }
        return scopes;
    }

    record IssuePersonalTokenRequest(String name, List<String> scopes) {
    }

    /** A grant as a person manages it: never the token, never its hash -- only the hint shown at creation. */
    record GrantResponse(
            long id,
            String kind,
            String name,
            List<String> scopes,
            String tokenHint,
            OffsetDateTime createdAt,
            OffsetDateTime lastUsedAt,
            OffsetDateTime revokedAt) {

        static GrantResponse from(McpGrant grant) {
            return new GrantResponse(
                    grant.id(),
                    grant.kind().name(),
                    grant.name(),
                    grant.scopes().stream().map(McpScope::token).sorted().toList(),
                    grant.tokenHint(),
                    grant.createdAt(),
                    grant.lastUsedAt(),
                    grant.revokedAt());
        }
    }

    /** The one response that ever carries the token itself; it is not stored anywhere it could be shown again. */
    record IssuedTokenResponse(long grantId, String token) {
    }
}
