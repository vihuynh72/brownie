package io.github.vihuynh72.brownie.api.usage;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.generation.usage.UsageService;
import io.github.vihuynh72.brownie.core.generation.usage.UsageSummary;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * What this workspace has used of its model allowance this calendar month.
 * It never reports what other workspaces used, only whether the allowance
 * everyone shares is used up, because that is the part that affects this
 * person.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/usage")
class UsageController {

    private final UsageService usageService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    UsageController(
            UsageService usageService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.usageService = usageService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping
    UsageResponse usage(@PathVariable long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        UsageSummary summary = usageService.summary(workspaceId, userId);
        BigDecimal limit = usageService.monthlyLimits().workspaceUsd();
        BigDecimal remaining = limit.subtract(summary.workspaceMonthCostUsd()).max(BigDecimal.ZERO);
        return new UsageResponse(
                summary.workspaceMonthCostUsd(),
                limit,
                remaining,
                summary.workspaceMonthRequests(),
                summary.sharedAllowanceExhausted());
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    /** Amounts are US dollars. {@code monthUsedUsd} counts a request still in flight, and one whose outcome was never learned, at the most it could have cost. */
    record UsageResponse(
            BigDecimal monthUsedUsd,
            BigDecimal monthLimitUsd,
            BigDecimal monthRemainingUsd,
            long monthRequests,
            boolean sharedAllowanceExhausted) {
    }
}
