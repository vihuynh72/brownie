package io.github.vihuynh72.brownie.core.generation.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The web shows a refusal's sentence word for word, so the amounts in it
 * read as money and the counts as a person would say them, whatever the
 * settings they came from look like.
 */
class UsageReservationOutcomeTest {

    private static final MonthlyUsageLimits MONTHLY = new MonthlyUsageLimits(new BigDecimal("2"), new BigDecimal("15"));

    @Test
    void aRunLimitReadsAsRequestsAndDollarsAndCents() {
        assertEquals("This run has reached its limit of 6 model requests or $1.50.",
                refusal("RUN_LIMIT", new UsageLimits(6, 40_000, 8_000, new BigDecimal("1.5")), MONTHLY).getMessage());
        assertEquals("This run has reached its limit of 3 model requests or $0.10.",
                refusal("RUN_LIMIT", UsageLimits.defaultRunLimits(), MONTHLY).getMessage());
    }

    @Test
    void aRunLimitOfOneRequestSaysOneRequest() {
        assertEquals("This run has reached its limit of 1 model request or $5.00.",
                refusal("RUN_LIMIT", new UsageLimits(1, 40_000, 8_000, new BigDecimal("5")), MONTHLY).getMessage());
    }

    @Test
    void aWorkspaceLimitReadsInDollarsAndCents() {
        BudgetExceededException refused = refusal("WORKSPACE_MONTH_LIMIT", UsageLimits.defaultRunLimits(), MONTHLY);

        assertEquals(UsageLimitKind.WORKSPACE_MONTH, refused.kind());
        assertEquals("This workspace has used its model allowance of $2.00 for this month.", refused.getMessage());
    }

    @Test
    void anAmountKeepsItsCentsItsThousandsSeparatorAndAnyFinerDigitsItReallyHas() {
        assertEquals("$5.00", UsageReservationOutcome.dollars(new BigDecimal("5")));
        assertEquals("$1.50", UsageReservationOutcome.dollars(new BigDecimal("1.5")));
        assertEquals("$0.10", UsageReservationOutcome.dollars(new BigDecimal("0.100000")));
        assertEquals("$10.00", UsageReservationOutcome.dollars(new BigDecimal("1E+1")));
        assertEquals("$1,000.00", UsageReservationOutcome.dollars(new BigDecimal("1000")));
        assertEquals("$0.005", UsageReservationOutcome.dollars(new BigDecimal("0.005")));
    }

    private static BudgetExceededException refusal(String outcome, UsageLimits runLimits, MonthlyUsageLimits monthlyLimits) {
        return assertThrows(BudgetExceededException.class,
                () -> new UsageReservationOutcome(outcome, null).requireReserved(runLimits, monthlyLimits));
    }
}
