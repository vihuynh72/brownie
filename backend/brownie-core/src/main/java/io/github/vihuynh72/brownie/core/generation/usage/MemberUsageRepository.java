package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The usage ledger as a signed-in person's own requests reach it. Every
 * write crosses a database routine that takes the spender from the acting
 * person, never from an argument, so a request can only ever be charged to
 * whoever made it.
 */
public interface MemberUsageRepository {

    UsageReservationOutcome reserve(
            long workspaceId,
            long userId,
            String modelName,
            String promptVersion,
            String rateCard,
            int estimatedInputTokens,
            int estimatedMaxOutputTokens,
            BigDecimal estimatedCostUsd,
            BigDecimal runLimitUsd,
            MonthlyUsageLimits monthlyLimits);

    boolean settle(long workspaceId, long userId, long usageId, int inputTokens, int outputTokens, BigDecimal actualCostUsd);

    boolean retain(long workspaceId, long userId, long usageId);

    /** Empty when the person is not a member of the workspace. */
    Optional<UsageSummary> summary(long workspaceId, long userId, BigDecimal globalMonthLimitUsd, BigDecimal nextRequestUsd);
}
