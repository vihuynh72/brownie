package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a person may ask about the model allowance, and the check made
 * before paid work is accepted at all. The ledger refuses each request
 * when it is about to be sent; this refuses the work one step earlier, so
 * a run that could never make its first request is not queued, staged and
 * picked up by a worker only to fail there.
 */
public class UsageService {

    private final MemberUsageRepository usageRepository;
    private final MonthlyUsageLimits monthlyLimits;
    private final BigDecimal smallestRequestUsd;

    /** {@code smallestRequestUsd} is what the cheapest request the application makes would hold; "used up" means even that would not fit. */
    public UsageService(MemberUsageRepository usageRepository, MonthlyUsageLimits monthlyLimits, BigDecimal smallestRequestUsd) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository");
        this.monthlyLimits = Objects.requireNonNull(monthlyLimits, "monthlyLimits");
        this.smallestRequestUsd = Objects.requireNonNull(smallestRequestUsd, "smallestRequestUsd");
        if (smallestRequestUsd.signum() <= 0) {
            throw new IllegalArgumentException("smallestRequestUsd must be positive.");
        }
    }

    public MonthlyUsageLimits monthlyLimits() {
        return monthlyLimits;
    }

    /** A person who is not a member of the workspace has used nothing of it; authorization is the caller's concern, not this method's. */
    public UsageSummary summary(long workspaceId, long userId) {
        return summaryBefore(workspaceId, userId, smallestRequestUsd);
    }

    private UsageSummary summaryBefore(long workspaceId, long userId, BigDecimal nextRequestUsd) {
        return usageRepository
                .summary(workspaceId, userId, monthlyLimits.globalUsd(), nextRequestUsd)
                .orElseGet(() -> new UsageSummary(BigDecimal.ZERO, 0, false));
    }

    /**
     * Refuses when a request holding at least {@code nextRequestUsd} would
     * not fit. Comparing the amount used with the limit alone would almost
     * never refuse anything: the ledger stops the sum short of the limit,
     * so work would be accepted here only to fail at its first request.
     */
    public void requireAllowanceFor(long workspaceId, long userId, BigDecimal nextRequestUsd) {
        Objects.requireNonNull(nextRequestUsd, "nextRequestUsd");
        UsageSummary summary = summaryBefore(workspaceId, userId, nextRequestUsd);
        if (summary.workspaceMonthCostUsd().add(nextRequestUsd).compareTo(monthlyLimits.workspaceUsd()) > 0) {
            throw new UsageLimitReachedException(UsageLimitKind.WORKSPACE_MONTH, UsageReservationOutcome.workspaceMonthUsedUp(monthlyLimits));
        }
        if (summary.sharedAllowanceExhausted()) {
            throw new UsageLimitReachedException(UsageLimitKind.GLOBAL_MONTH,
                    "The model allowance everyone shares is used up for this month.");
        }
    }
}
