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
}
