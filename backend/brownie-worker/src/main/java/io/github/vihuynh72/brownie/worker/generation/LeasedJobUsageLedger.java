package io.github.vihuynh72.brownie.worker.generation;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLedger;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.job.JobLeaseToken;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.worker.persistence.JdbcWorkerUsageRepository;

import java.math.BigDecimal;

/**
 * The ledger for one attempt at one job. Every request it records is
 * charged to the job the lease names, so the run's bound on requests and
 * spend is counted across this attempt, earlier attempts and resumes
 * alike, which a budget held only in this attempt's memory never could.
 */
final class LeasedJobUsageLedger implements UsageLedger {

    private final JdbcWorkerUsageRepository usageRepository;
    private final JobLeaseToken lease;
    private final String modelName;
    private final ModelPricing pricing;
    private final UsageLimits runLimits;
    private final MonthlyUsageLimits monthlyLimits;

    LeasedJobUsageLedger(
            JdbcWorkerUsageRepository usageRepository,
            JobLeaseToken lease,
            String modelName,
            ModelPricing pricing,
            UsageLimits runLimits,
            MonthlyUsageLimits monthlyLimits) {
        this.usageRepository = usageRepository;
        this.lease = lease;
        this.modelName = modelName;
        this.pricing = pricing;
        this.runLimits = runLimits;
        this.monthlyLimits = monthlyLimits;
    }

    @Override
    public long reserve(String promptVersion, int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd)
            throws BudgetExceededException {
        return usageRepository
                .reserve(lease, modelName, promptVersion, pricing.rateCard(), estimatedInputTokens, estimatedMaxOutputTokens,
                        estimatedCostUsd, runLimits, monthlyLimits)
                .requireReserved(runLimits, monthlyLimits);
    }

    @Override
    public void settle(long reservationId, ModelUsage usage, BigDecimal actualCostUsd) {
        usageRepository.settle(lease.jobId(), reservationId, usage.inputTokens(), usage.outputTokens(), actualCostUsd);
    }

    @Override
    public void retain(long reservationId) {
        usageRepository.retain(lease.jobId(), reservationId);
    }
}
