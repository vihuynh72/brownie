package io.github.vihuynh72.brownie.api.support;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.support.SupportGrant;
import io.github.vihuynh72.brownie.core.support.SupportGrantScope;
import io.github.vihuynh72.brownie.core.support.SupportGrantService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A workspace owner letting support in for a bounded time, seeing what is
 * open, and taking it back. There is no route here, or anywhere else, by
 * which support itself reads a workspace: this is the record such a route
 * would have to find before it did.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/support-grants")
class SupportGrantController {

    private final SupportGrantService supportGrantService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    SupportGrantController(
            SupportGrantService supportGrantService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.supportGrantService = supportGrantService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SupportGrantResponse create(
            @PathVariable long workspaceId, @RequestBody CreateSupportGrantRequest request, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        if (request.days() == null || request.days() < SupportGrantService.MIN_DAYS || request.days() > SupportGrantService.MAX_DAYS) {
            throw new SupportGrantRequestValidationException(
                    "days must be between " + SupportGrantService.MIN_DAYS + " and " + SupportGrantService.MAX_DAYS + ".");
        }
        return SupportGrantResponse.from(supportGrantService.grant(workspaceId, userId, request.toScope(), request.days()));
    }

    @GetMapping
    List<SupportGrantResponse> findAll(@PathVariable long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return supportGrantService.findAll(workspaceId, userId).stream().map(SupportGrantResponse::from).toList();
    }

    @PostMapping("/{grantId}/revoke")
    SupportGrantResponse revoke(
            @PathVariable long workspaceId, @PathVariable long grantId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        if (grantId <= 0) {
            throw new SupportGrantRequestValidationException("grantId must be positive.");
        }
        return SupportGrantResponse.from(supportGrantService.revoke(workspaceId, userId, grantId));
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

    record CreateSupportGrantRequest(String scope, Integer days) {

        SupportGrantScope toScope() {
            try {
                return SupportGrantScope.valueOf(scope == null ? "" : scope);
            } catch (IllegalArgumentException e) {
                throw new SupportGrantRequestValidationException("scope must be one of " + List.of(SupportGrantScope.values()) + ".");
            }
        }
    }

    record SupportGrantResponse(
            long id, String scope, OffsetDateTime grantedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt, boolean active) {

        static SupportGrantResponse from(SupportGrant grant) {
            return new SupportGrantResponse(
                    grant.id(), grant.scope().name(), grant.grantedAt(), grant.expiresAt(), grant.revokedAt(),
                    grant.isActiveAt(OffsetDateTime.now()));
        }
    }
}
