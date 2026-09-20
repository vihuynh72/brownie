package io.github.vihuynh72.brownie.core.generation.usage;

import io.github.vihuynh72.brownie.core.model.ModelUsage;

import java.math.BigDecimal;

/**
 * The durable record behind a {@link UsageBudget}: every physical model
 * request is written down before it is sent and closed when its answer is
 * in, so a bound holds across attempts, resumes, processes and restarts
 * instead of for the length of one method call. An implementation is bound
 * to whoever is spending (one leased job, or one signed-in person), which
 * is why none of these methods names a workspace or a job.
 */
public interface UsageLedger {

    /**
     * Records one upcoming request at the most it could cost, or refuses it
     * when a durable limit would be exceeded. Returns a handle for exactly
     * one later {@link #settle} or {@link #retain}.
     */
    long reserve(String promptVersion, int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd)
            throws BudgetExceededException;

    /** The provider answered and reported what it billed. */
    void settle(long reservationId, ModelUsage usage, BigDecimal actualCostUsd);

    /** Nobody can say what happened to the request, so the full reservation stands. */
    void retain(long reservationId);

    /**
     * Keeps nothing. For code that has no durable ledger to write to: a
     * unit test, or a caller that only wants the run's own in-memory bounds.
     */
    UsageLedger NONE = new UsageLedger() {
        @Override
        public long reserve(String promptVersion, int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd) {
            return 0L;
        }

        @Override
        public void settle(long reservationId, ModelUsage usage, BigDecimal actualCostUsd) {
        }

        @Override
        public void retain(long reservationId) {
        }
    };
}
