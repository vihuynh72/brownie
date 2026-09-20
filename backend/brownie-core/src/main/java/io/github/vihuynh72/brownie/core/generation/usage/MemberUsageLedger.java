package io.github.vihuynh72.brownie.core.generation.usage;

import io.github.vihuynh72.brownie.core.model.ModelUsage;

import java.math.BigDecimal;
import java.util.Objects;

/** The ledger for one request a signed-in person makes directly, charged to that person in that workspace. */
public final class MemberUsageLedger implements UsageLedger {

    private final MemberUsageRepository usageRepository;
    private final long workspaceId;
    private final long userId;
    private final String modelName;
    private final ModelPricing pricing;
    private final UsageLimits runLimits;
    private final MonthlyUsageLimits monthlyLimits;

    public MemberUsageLedger(
            MemberUsageRepository usageRepository,
            long workspaceId,
            long userId,
            String modelName,
            ModelPricing pricing,
            UsageLimits runLimits,
            MonthlyUsageLimits monthlyLimits) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository");
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.runLimits = Objects.requireNonNull(runLimits, "runLimits");
        this.monthlyLimits = Objects.requireNonNull(monthlyLimits, "monthlyLimits");
    }

    @Override
    public long reserve(String promptVersion, int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd)
            throws BudgetExceededException {
        return usageRepository
                .reserve(workspaceId, userId, modelName, promptVersion, pricing.rateCard(), estimatedInputTokens,
                        estimatedMaxOutputTokens, estimatedCostUsd, runLimits.maxReservedCostUsd(), monthlyLimits)
                .requireReserved(runLimits, monthlyLimits);
    }

    @Override
    public void settle(long reservationId, ModelUsage usage, BigDecimal actualCostUsd) {
        usageRepository.settle(workspaceId, userId, reservationId, usage.inputTokens(), usage.outputTokens(), actualCostUsd);
    }

    @Override
    public void retain(long reservationId) {
        usageRepository.retain(workspaceId, userId, reservationId);
    }
}
