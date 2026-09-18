package io.github.vihuynh72.brownie.core.generation.usage;

import io.github.vihuynh72.brownie.core.model.ModelUsage;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Enforces one extraction run's own aggregate bounds by reserving a
 * worst-case estimate before every physical model call and settling it
 * against the gateway's own real, reported {@link ModelUsage} afterward --
 * reserve before each physical request, settle actual
 * usage after the response. Not persisted across process
 * restarts or shared across runs: a real, named boundary, since nothing
 * in this codebase yet has a durable generation run to anchor a
 * cross-request ledger to (see {@code ExtractionService}'s own class
 * documentation). Estimating input tokens exactly would need a real
 * tokenizer this module does not have; {@link #estimateTokens(String)}
 * uses a documented, deliberately conservative characters-per-token
 * heuristic instead, safe to overestimate but never to underestimate,
 * since a reservation exists to protect the budget, not to be precise.
 */
public final class UsageBudget {

    private final UsageLimits limits;
    private final ModelPricing pricing;

    private int physicalRequestsMade = 0;
    private int actualInputTokens = 0;
    private int actualOutputTokens = 0;
    private BigDecimal actualCost = BigDecimal.ZERO;

    private int pendingInputTokens = 0;
    private int pendingOutputTokens = 0;
    private BigDecimal pendingCost = BigDecimal.ZERO;
    private boolean callInFlight = false;

    public UsageBudget(UsageLimits limits, ModelPricing pricing) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
    }

    /**
     * Reserves the worst-case cost of one upcoming physical call, or
     * refuses it outright if any bound would be exceeded. Must be
     * followed by exactly one of {@link #settleActual(ModelUsage)} or
     * {@link #retainReservationAfterLostResponse()} before the next call.
     */
    public void reserveForCall(int estimatedInputTokens, int estimatedMaxOutputTokens) throws BudgetExceededException {
        if (callInFlight) {
            throw new IllegalStateException("A previous reservation was never settled or retained.");
        }
        if (physicalRequestsMade >= limits.maxPhysicalRequests()) {
            throw new BudgetExceededException(
                    "This run already made its maximum of " + limits.maxPhysicalRequests() + " physical model requests.");
        }
        if (actualInputTokens + estimatedInputTokens > limits.maxInputTokens()) {
            throw new BudgetExceededException("This call would exceed the run's " + limits.maxInputTokens() + " input token limit.");
        }
        if (actualOutputTokens + estimatedMaxOutputTokens > limits.maxOutputTokens()) {
            throw new BudgetExceededException("This call would exceed the run's " + limits.maxOutputTokens() + " output token limit.");
        }
        BigDecimal estimatedCallCost = pricing.estimateCost(estimatedInputTokens, estimatedMaxOutputTokens);
        if (actualCost.add(estimatedCallCost).compareTo(limits.maxReservedCostUsd()) > 0) {
            throw new BudgetExceededException("This call would exceed the run's $" + limits.maxReservedCostUsd() + " reserved spend limit.");
        }

        physicalRequestsMade++;
        pendingInputTokens = estimatedInputTokens;
        pendingOutputTokens = estimatedMaxOutputTokens;
        pendingCost = estimatedCallCost;
        callInFlight = true;
    }

    /** The call completed and reported real usage: settle against that, which may be less than what was reserved. */
    public void settleActual(ModelUsage usage) {
        requireCallInFlight();
        actualInputTokens += usage.inputTokens();
        actualOutputTokens += usage.outputTokens();
        actualCost = actualCost.add(pricing.estimateCost(usage.inputTokens(), usage.outputTokens()));
        clearPending();
    }

    /**
     * The call's outcome is unknown (a lost response, a timeout with no
     * confirmation) -- keep the full worst-case reservation rather than
     * refunding it, since the provider may have processed, and be billing
     * for, a request whose result this process never saw.
     */
    public void retainReservationAfterLostResponse() {
        requireCallInFlight();
        actualInputTokens += pendingInputTokens;
        actualOutputTokens += pendingOutputTokens;
        actualCost = actualCost.add(pendingCost);
        clearPending();
    }

    private void requireCallInFlight() {
        if (!callInFlight) {
            throw new IllegalStateException("No reservation is outstanding to settle or retain.");
        }
    }

    private void clearPending() {
        pendingInputTokens = 0;
        pendingOutputTokens = 0;
        pendingCost = BigDecimal.ZERO;
        callInFlight = false;
    }

    public int physicalRequestsMade() {
        return physicalRequestsMade;
    }

    public BigDecimal actualCost() {
        return actualCost;
    }

    /** A deliberately conservative, tokenizer-free estimate: about one token per four characters, rounded up, never below one. Safe to overestimate; never trust it to undershoot. */
    public static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }
}
