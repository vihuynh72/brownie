package io.github.vihuynh72.brownie.core.generation.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsageServiceTest {

    private static final MonthlyUsageLimits LIMITS = new MonthlyUsageLimits(new BigDecimal("2.00"), new BigDecimal("15.00"));
    private static final BigDecimal SMALLEST = new BigDecimal("0.001801");
    private static final BigDecimal FIRST_REQUEST_OF_A_RUN = new BigDecimal("0.009001");

    /**
     * The ledger refuses a reservation that would pass the limit, so the
     * amount used stops short of the limit and almost never equals it. A
     * check that waited for "used is at least the limit" would let work
     * start that can only fail at its first request.
     */
    @Test
    void workIsRefusedWhenItsFirstRequestWouldNotFitNotOnlyWhenTheLimitIsAlreadyMet() {
        StubRepository repository = new StubRepository();
        UsageService service = new UsageService(repository, LIMITS, SMALLEST);

        repository.used = new BigDecimal("1.990000");
        assertDoesNotThrow(() -> service.requireAllowanceFor(7, 1, FIRST_REQUEST_OF_A_RUN));

        repository.used = new BigDecimal("1.995000");
        UsageLimitReachedException refused =
                assertThrows(UsageLimitReachedException.class, () -> service.requireAllowanceFor(7, 1, FIRST_REQUEST_OF_A_RUN));
        assertEquals(UsageLimitKind.WORKSPACE_MONTH, refused.kind());
    }

    /** The web shows this sentence word for word, so the allowance reads as money: "$5.00", not "$5". */
    @Test
    void theRefusalNamesTheWorkspaceAllowanceInDollarsAndCents() {
        StubRepository repository = new StubRepository();
        repository.used = new BigDecimal("5");
        UsageService whole = new UsageService(repository, new MonthlyUsageLimits(new BigDecimal("5"), new BigDecimal("15")), SMALLEST);
        UsageService half = new UsageService(repository, new MonthlyUsageLimits(new BigDecimal("1.5"), new BigDecimal("15")), SMALLEST);

        assertEquals("This workspace has used its model allowance of $5.00 for this month.",
                assertThrows(UsageLimitReachedException.class, () -> whole.requireAllowanceFor(7, 1, FIRST_REQUEST_OF_A_RUN)).getMessage());
        assertEquals("This workspace has used its model allowance of $1.50 for this month.",
                assertThrows(UsageLimitReachedException.class, () -> half.requireAllowanceFor(7, 1, FIRST_REQUEST_OF_A_RUN)).getMessage());
    }

    @Test
    void theSharedAllowanceIsAskedAboutTheSameRequestAndNeverReportedAsAnAmount() {
        StubRepository repository = new StubRepository();
        UsageService service = new UsageService(repository, LIMITS, SMALLEST);
        repository.sharedExhausted = true;

        UsageLimitReachedException refused =
                assertThrows(UsageLimitReachedException.class, () -> service.requireAllowanceFor(7, 1, FIRST_REQUEST_OF_A_RUN));

        assertEquals(UsageLimitKind.GLOBAL_MONTH, refused.kind());
        assertFalse(refused.getMessage().contains("15"));
        // The pre-check asks about the request it is about to allow; the summary a person reads asks about the cheapest one.
        service.summary(7, 1);
        assertEquals(List.of(FIRST_REQUEST_OF_A_RUN, SMALLEST), repository.askedAbout);
    }

    @Test
    void someoneWhoIsNotAMemberHasUsedNothing() {
        StubRepository repository = new StubRepository();
        repository.member = false;
        UsageService service = new UsageService(repository, LIMITS, SMALLEST);

        assertEquals(0, service.summary(7, 99).workspaceMonthRequests());
        assertEquals(0, BigDecimal.ZERO.compareTo(service.summary(7, 99).workspaceMonthCostUsd()));
    }

    @Test
    void theCheapestRequestMustCostSomething() {
        assertThrows(IllegalArgumentException.class, () -> new UsageService(new StubRepository(), LIMITS, BigDecimal.ZERO));
    }

    private static final class StubRepository implements MemberUsageRepository {

        private BigDecimal used = BigDecimal.ZERO;
        private boolean sharedExhausted;
        private boolean member = true;
        private final List<BigDecimal> askedAbout = new ArrayList<>();

        @Override
        public UsageReservationOutcome reserve(
                long workspaceId, long userId, String modelName, String promptVersion, String rateCard, int estimatedInputTokens,
                int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd, BigDecimal runLimitUsd, MonthlyUsageLimits monthlyLimits) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean settle(long workspaceId, long userId, long usageId, int inputTokens, int outputTokens, BigDecimal actualCostUsd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retain(long workspaceId, long userId, long usageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UsageSummary> summary(long workspaceId, long userId, BigDecimal globalMonthLimitUsd, BigDecimal nextRequestUsd) {
            askedAbout.add(nextRequestUsd);
            return member ? Optional.of(new UsageSummary(used, 3, sharedExhausted)) : Optional.empty();
        }
    }
}
