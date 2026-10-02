package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageReservationOutcome;
import io.github.vihuynh72.brownie.core.generation.usage.UsageSummary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The usage ledger as a list of what was written to it: each reservation,
 * each settlement and each reservation kept at its full amount. It
 * reserves everything unless told which limit to refuse with.
 */
final class RecordingUsageRepository implements MemberUsageRepository {

    record Reservation(long workspaceId, long userId, String modelName, String promptVersion, int inputTokens, int outputTokens,
                       BigDecimal costUsd) {
    }

    record Settlement(long usageId, int inputTokens, int outputTokens, BigDecimal costUsd) {
    }

    final List<Reservation> reservations = new ArrayList<>();
    final List<Settlement> settlements = new ArrayList<>();
    final List<Long> retained = new ArrayList<>();
    /** A limit's name as the database answers it ("WORKSPACE_MONTH_LIMIT"), or null to reserve. */
    String refuseWith;

    @Override
    public UsageReservationOutcome reserve(long workspaceId, long userId, String modelName, String promptVersion, String rateCard,
                                           int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd,
                                           BigDecimal runLimitUsd, MonthlyUsageLimits monthlyLimits) {
        if (refuseWith != null) {
            return new UsageReservationOutcome(refuseWith, null);
        }
        reservations.add(new Reservation(
                workspaceId, userId, modelName, promptVersion, estimatedInputTokens, estimatedMaxOutputTokens, estimatedCostUsd));
        return new UsageReservationOutcome("RESERVED", (long) reservations.size());
    }

    @Override
    public boolean settle(long workspaceId, long userId, long usageId, int inputTokens, int outputTokens, BigDecimal actualCostUsd) {
        settlements.add(new Settlement(usageId, inputTokens, outputTokens, actualCostUsd));
        return true;
    }

    @Override
    public boolean retain(long workspaceId, long userId, long usageId) {
        retained.add(usageId);
        return true;
    }

    @Override
    public Optional<UsageSummary> summary(long workspaceId, long userId, BigDecimal globalMonthLimitUsd, BigDecimal nextRequestUsd) {
        return Optional.empty();
    }
}
