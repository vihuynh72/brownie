package io.github.vihuynh72.brownie.core.generation.usage;

import io.github.vihuynh72.brownie.core.model.ModelUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageBudgetTest {

    private static final UsageLimits THREE_REQUEST_LIMITS = new UsageLimits(3, 40_000, 8_000, new BigDecimal("0.10"));

    @Test
    void aFourthReservationIsRefusedAfterThreeRequests() throws Exception {
        UsageBudget budget = new UsageBudget(THREE_REQUEST_LIMITS, ModelPricing.gpt5Mini());
        for (int i = 0; i < 3; i++) {
            budget.reserveForCall(1000, 500);
            budget.settleActual(new ModelUsage(1000, 500));
        }

        assertThrows(BudgetExceededException.class, () -> budget.reserveForCall(1, 1));
        assertEquals(3, budget.physicalRequestsMade());
    }

    @Test
    void aReservationThatWouldExceedTheInputTokenLimitIsRefused() {
        UsageBudget budget = new UsageBudget(new UsageLimits(3, 100, 8_000, new BigDecimal("10")), ModelPricing.gpt5Mini());

        assertThrows(BudgetExceededException.class, () -> budget.reserveForCall(101, 10));
    }

    @Test
    void aReservationThatWouldExceedTheOutputTokenLimitIsRefused() {
        UsageBudget budget = new UsageBudget(new UsageLimits(3, 40_000, 100, new BigDecimal("10")), ModelPricing.gpt5Mini());

        assertThrows(BudgetExceededException.class, () -> budget.reserveForCall(10, 101));
    }

    @Test
    void aReservationThatWouldExceedTheCostLimitIsRefused() {
        UsageBudget budget = new UsageBudget(new UsageLimits(3, 40_000, 8_000, new BigDecimal("0.000001")), ModelPricing.gpt5Mini());

        assertThrows(BudgetExceededException.class, () -> budget.reserveForCall(10_000, 10_000));
    }

    /** A person may read these, word for word: numbers a person can read, money in dollars and cents, plurals right. */
    @Test
    void eachRefusalSaysWhichLimitInWordsAPersonCanRead() throws Exception {
        UsageBudget oneRequest = new UsageBudget(new UsageLimits(1, 40_000, 8_000, new BigDecimal("0.1")), ModelPricing.gpt5Mini());
        oneRequest.reserveForCall(10, 10);
        oneRequest.settleActual(new ModelUsage(10, 10));
        assertEquals("This run has already made its limit of 1 model request.",
                assertThrows(BudgetExceededException.class, () -> oneRequest.reserveForCall(1, 1)).getMessage());

        UsageBudget tokens = new UsageBudget(new UsageLimits(3, 40_000, 8_000, new BigDecimal("10")), ModelPricing.gpt5Mini());
        assertEquals("This request would take the run past its limit of 40,000 tokens sent to the model.",
                assertThrows(BudgetExceededException.class, () -> tokens.reserveForCall(40_001, 1)).getMessage());
        assertEquals("This request would take the run past its limit of 8,000 tokens written by the model.",
                assertThrows(BudgetExceededException.class, () -> tokens.reserveForCall(1, 8_001)).getMessage());

        UsageBudget money = new UsageBudget(new UsageLimits(3, 400_000, 400_000, new BigDecimal("0.1")), ModelPricing.gpt5Mini());
        assertEquals("This request would take the run past its spending limit of $0.10.",
                assertThrows(BudgetExceededException.class, () -> money.reserveForCall(300_000, 300_000)).getMessage());
    }

    @Test
    void settlingWithLessThanTheReservedEstimateOnlyCountsTheRealUsage() throws Exception {
        UsageBudget budget = new UsageBudget(new UsageLimits(3, 1000, 1000, new BigDecimal("10")), ModelPricing.gpt5Mini());
        budget.reserveForCall(900, 900);

        budget.settleActual(new ModelUsage(10, 10));

        // A second call reserving another 900/900 would have exceeded the
        // 1000 limit if the first call's full worst-case estimate were
        // still counted, proving settlement replaced the estimate with
        // the smaller real number rather than adding on top of it.
        budget.reserveForCall(900, 900);
    }

    @Test
    void aLostResponseRetainsTheFullWorstCaseReservationRatherThanRefundingIt() throws Exception {
        UsageBudget budget = new UsageBudget(new UsageLimits(3, 1000, 1000, new BigDecimal("10")), ModelPricing.gpt5Mini());
        budget.reserveForCall(900, 900);

        budget.retainReservationAfterLostResponse();

        assertThrows(BudgetExceededException.class, () -> budget.reserveForCall(900, 900));
    }

    @Test
    void reservingTwiceWithoutSettlingOrRetainingInBetweenIsRejected() throws Exception {
        UsageBudget budget = new UsageBudget(THREE_REQUEST_LIMITS, ModelPricing.gpt5Mini());
        budget.reserveForCall(1, 1);

        assertThrows(IllegalStateException.class, () -> budget.reserveForCall(1, 1));
    }

    @Test
    void estimateTokensIsConservativeAndNeverBelowOne() {
        assertEquals(1, UsageBudget.estimateTokens(""));
        assertEquals(1, UsageBudget.estimateTokens("abc"));
        assertTrue(UsageBudget.estimateTokens("a".repeat(400)) >= 100);
    }

    /** What the provider billed is what gets written down, under the handle the reservation was given. */
    @Test
    void everyReservationIsClosedInTheLedgerExactlyOnceWithWhatActuallyHappened() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();
        UsageLedger ledger = new UsageLedger() {
            private long nextId = 7;

            @Override
            public long reserve(String promptVersion, int in, int out, java.math.BigDecimal cost) {
                events.add("reserve " + promptVersion + " " + in + "/" + out + " $" + cost);
                return nextId++;
            }

            @Override
            public void settle(long id, io.github.vihuynh72.brownie.core.model.ModelUsage usage, java.math.BigDecimal cost) {
                events.add("settle " + id + " " + usage.inputTokens() + "/" + usage.outputTokens() + " $" + cost);
            }

            @Override
            public void retain(long id) {
                events.add("retain " + id);
            }
        };
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini(), ledger);

        budget.reserveForCall(1000, 2000, "extract-v1");
        budget.settleActual(new io.github.vihuynh72.brownie.core.model.ModelUsage(900, 100));
        budget.reserveForCall(1000, 2000, "extract-v1");
        budget.retainReservationAfterLostResponse();

        assertEquals(java.util.List.of(
                "reserve extract-v1 1000/2000 $0.009750",
                "settle 7 900/100 $0.001125",
                "reserve extract-v1 1000/2000 $0.009750",
                "retain 8"), events);
    }

    /** The answer is already paid for when the ledger is closed; a bookkeeping failure must not throw it away. */
    @Test
    void aLedgerThatCannotBeClosedDoesNotLoseTheAnswer() throws Exception {
        UsageLedger broken = new UsageLedger() {
            @Override
            public long reserve(String promptVersion, int in, int out, java.math.BigDecimal cost) {
                return 1;
            }

            @Override
            public void settle(long id, io.github.vihuynh72.brownie.core.model.ModelUsage usage, java.math.BigDecimal cost) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public void retain(long id) {
                throw new IllegalStateException("database unavailable");
            }
        };
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini(), broken);

        budget.reserveForCall(10, 10, "extract-v1");
        budget.settleActual(new io.github.vihuynh72.brownie.core.model.ModelUsage(5, 5));
        budget.reserveForCall(10, 10, "extract-v1");
        budget.retainReservationAfterLostResponse();

        assertEquals(2, budget.physicalRequestsMade());
    }

    @Test
    void theInputEstimateCountsTheResponseSchemaBecauseTheProviderBillsItAsInput() {
        io.github.vihuynh72.brownie.core.model.ModelRequest request = new io.github.vihuynh72.brownie.core.model.ModelRequest(
                "extract-v1",
                java.util.List.of(
                        new io.github.vihuynh72.brownie.core.model.ModelMessage(
                                io.github.vihuynh72.brownie.core.model.ModelMessageRole.SYSTEM, "s".repeat(400)),
                        new io.github.vihuynh72.brownie.core.model.ModelMessage(
                                io.github.vihuynh72.brownie.core.model.ModelMessageRole.USER, "u".repeat(800))),
                new io.github.vihuynh72.brownie.core.model.JsonSchema("{" + "x".repeat(1998) + "}"),
                500);

        // 400 + 800 characters of messages and 2,000 of schema, at four characters a token.
        assertEquals(800, UsageBudget.estimateInputTokens(request));
        assertTrue(UsageBudget.estimateInputTokens(request) > UsageBudget.estimateTokens("s".repeat(400) + "u".repeat(800)));
    }

    @Test
    void textOutsideAsciiIsCountedAtMoreThanATokenACharacterNotAQuarter() {
        io.github.vihuynh72.brownie.core.model.ModelRequest chinese = new io.github.vihuynh72.brownie.core.model.ModelRequest(
                "extract-v1",
                java.util.List.of(new io.github.vihuynh72.brownie.core.model.ModelMessage(
                        io.github.vihuynh72.brownie.core.model.ModelMessageRole.USER, "\u4f1a".repeat(1000))),
                new io.github.vihuynh72.brownie.core.model.JsonSchema("{}"),
                500);

        // 1,000 characters at a token and a half each, plus the two-character schema.
        assertEquals(1501, UsageBudget.estimateInputTokens(chinese));
    }
}
