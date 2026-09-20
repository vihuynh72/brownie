package io.github.vihuynh72.brownie.core.generation.usage;

import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Enforces one extraction run's own aggregate bounds by reserving a
 * worst-case estimate before every physical model call and settling it
 * against the gateway's own real, reported {@link ModelUsage} afterward --
 * reserve before each physical request, settle actual
 * usage after the response. The run's own bounds are checked here, in
 * memory, because they are cheap and always apply; every reservation is
 * then also handed to a {@link UsageLedger}, which is what makes a bound
 * hold across attempts, resumes and restarts, and what enforces the
 * monthly allowances. Estimating input tokens exactly would need a real
 * tokenizer this module does not have; {@link #estimateTokens(String)}
 * uses a documented, deliberately conservative characters-per-token
 * heuristic instead, safe to overestimate but never to underestimate,
 * since a reservation exists to protect the budget, not to be precise.
 */
public final class UsageBudget {

    private static final Logger log = LoggerFactory.getLogger(UsageBudget.class);
    private static final String UNNAMED_PROMPT = "unnamed";

    private final UsageLimits limits;
    private final ModelPricing pricing;
    private final UsageLedger ledger;
    private long pendingReservationId;

    private int physicalRequestsMade = 0;
    private int actualInputTokens = 0;
    private int actualOutputTokens = 0;
    private BigDecimal actualCost = BigDecimal.ZERO;

    private int pendingInputTokens = 0;
    private int pendingOutputTokens = 0;
    private BigDecimal pendingCost = BigDecimal.ZERO;
    private boolean callInFlight = false;

    /** The run's own in-memory bounds only, with nothing written down. */
    public UsageBudget(UsageLimits limits, ModelPricing pricing) {
        this(limits, pricing, UsageLedger.NONE);
    }

    public UsageBudget(UsageLimits limits, ModelPricing pricing, UsageLedger ledger) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * Reserves the worst-case cost of one upcoming physical call, or
     * refuses it outright if any bound would be exceeded. Must be
     * followed by exactly one of {@link #settleActual(ModelUsage)} or
     * {@link #retainReservationAfterLostResponse()} before the next call.
     */
    public void reserveForCall(int estimatedInputTokens, int estimatedMaxOutputTokens) throws BudgetExceededException {
        reserveForCall(estimatedInputTokens, estimatedMaxOutputTokens, UNNAMED_PROMPT);
    }

    /** As above, naming the prompt the request carries so the ledger can say what each request was for. */
    public void reserveForCall(int estimatedInputTokens, int estimatedMaxOutputTokens, String promptVersion)
            throws BudgetExceededException {
        if (callInFlight) {
            throw new IllegalStateException("A previous reservation was never settled or retained.");
        }
        if (physicalRequestsMade >= limits.maxPhysicalRequests()) {
            throw new BudgetExceededException("This run has already made its limit of "
                    + UsageReservationOutcome.modelRequests(limits.maxPhysicalRequests()) + ".");
        }
        if (actualInputTokens + estimatedInputTokens > limits.maxInputTokens()) {
            throw new BudgetExceededException(
                    "This request would take the run past its limit of " + tokens(limits.maxInputTokens()) + " sent to the model.");
        }
        if (actualOutputTokens + estimatedMaxOutputTokens > limits.maxOutputTokens()) {
            throw new BudgetExceededException(
                    "This request would take the run past its limit of " + tokens(limits.maxOutputTokens()) + " written by the model.");
        }
        BigDecimal estimatedCallCost = pricing.estimateCost(estimatedInputTokens, estimatedMaxOutputTokens);
        if (actualCost.add(estimatedCallCost).compareTo(limits.maxReservedCostUsd()) > 0) {
            throw new BudgetExceededException("This request would take the run past its spending limit of "
                    + UsageReservationOutcome.dollars(limits.maxReservedCostUsd()) + ".");
        }

        // Last, and before anything here changes: if the ledger refuses, this budget is exactly as it was.
        pendingReservationId = ledger.reserve(
                promptVersion == null || promptVersion.isBlank() ? UNNAMED_PROMPT : promptVersion,
                estimatedInputTokens,
                estimatedMaxOutputTokens,
                estimatedCallCost);

        physicalRequestsMade++;
        pendingInputTokens = estimatedInputTokens;
        pendingOutputTokens = estimatedMaxOutputTokens;
        pendingCost = estimatedCallCost;
        callInFlight = true;
    }

    /** The call completed and reported real usage: settle against that, which may be less than what was reserved. */
    public void settleActual(ModelUsage usage) {
        requireCallInFlight();
        BigDecimal callCost = pricing.estimateCost(usage.inputTokens(), usage.outputTokens());
        actualInputTokens += usage.inputTokens();
        actualOutputTokens += usage.outputTokens();
        actualCost = actualCost.add(callCost);
        closeInLedger(() -> ledger.settle(pendingReservationId, usage, callCost));
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
        closeInLedger(() -> ledger.retain(pendingReservationId));
        clearPending();
    }

    /**
     * The answer is already in hand when this runs, and losing it because a
     * bookkeeping write failed would waste the money just spent. A close
     * that cannot be written is therefore reported, not thrown: the row
     * stays open and is later kept at its full reservation, which can only
     * over-count what was spent, never under-count it.
     */
    private void closeInLedger(Runnable close) {
        try {
            close.run();
        } catch (RuntimeException failure) {
            log.warn("Could not close model usage reservation {}; it stays reserved at its full amount.", pendingReservationId, failure);
        }
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
        pendingReservationId = 0L;
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

    /**
     * Everything the provider bills as input for one request: every
     * message, and the response schema, which is sent with the request
     * and counted like any other input. Leaving the schema out made a
     * real extraction's estimate about half of what it was billed for.
     */
    public static int estimateInputTokens(ModelRequest request) {
        long quarterTokens = quarterTokens(request.responseSchema().schemaJson());
        for (ModelMessage message : request.messages()) {
            quarterTokens += quarterTokens(message.content());
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (quarterTokens + 3) / 4));
    }

    /**
     * In quarters of a token: one for a plain ASCII character (about four
     * to a token), six for anything else. Text outside ASCII (Chinese,
     * Japanese, Korean, Cyrillic, accented Latin, emoji) runs at around a
     * token a character, and counting it like English would reserve a
     * fraction of what it costs.
     */
    private static long quarterTokens(String text) {
        long quarters = 0;
        for (int i = 0; i < text.length(); i++) {
            quarters += text.charAt(i) < 128 ? 1 : 6;
        }
        return quarters;
    }

    /** Tokens are what the model counts in; a person reading the limit needs the number to be readable. */
    private static String tokens(int count) {
        return String.format(java.util.Locale.US, "%,d", count) + (count == 1 ? " token" : " tokens");
    }
}
