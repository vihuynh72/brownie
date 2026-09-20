package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLedger;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimitKind;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.FakeModelGateway;
import io.github.vihuynh72.brownie.core.model.JsonSchema;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelMessageRole;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules for spending on a request that fails in transit, which are
 * easy to get subtly wrong in ways that cost money: every try is a request
 * the provider may bill, so every try is reserved and none is refunded; a
 * failure that waiting cannot fix is never retried; the run's own bound on
 * requests still holds; and nothing is sent once cancellation is asked for.
 */
class BoundedModelCallTest {

    private static final ModelRequest REQUEST = new ModelRequest(
            "test-prompt-v1", List.of(new ModelMessage(ModelMessageRole.USER, "hello")), new JsonSchema("{}"), 100);

    @Test
    void aThrottledRequestIsSentAgainAfterAPauseAndBothTriesAreWrittenDown() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueueFailure(new ModelTransportException("rate limited", true, null));
        ModelCompletion.Success success = new ModelCompletion.Success("{}", new ModelUsage(40, 12));
        gateway.enqueue(success);
        RecordingLedger ledger = new RecordingLedger();
        List<Duration> pauses = new ArrayList<>();
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini(), ledger);

        ModelCompletion completion = BoundedModelCall.complete(
                gateway, REQUEST, 10, budget, CancellationSignal.never(), retrying(2, pauses));

        assertSame(success, completion);
        assertEquals(2, gateway.receivedRequests().size());
        assertEquals(2, budget.physicalRequestsMade());
        // The lost try is kept at its full reservation; the second is still open for the caller to settle.
        assertEquals(List.of("reserve:test-prompt-v1", "retain:1", "reserve:test-prompt-v1"), ledger.events);
        assertEquals(1, pauses.size());
        assertTrue(pauses.get(0).toMillis() >= 800 && pauses.get(0).toMillis() <= 1200, "about one second, was " + pauses.get(0));
    }

    @Test
    void aFailureThatWaitingCannotFixIsReportedAtOnce() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueueFailure(new ModelTransportException("credential rejected", false, null));
        List<Duration> pauses = new ArrayList<>();
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini());

        assertThrows(ModelTransportException.class, () -> BoundedModelCall.complete(
                gateway, REQUEST, 10, budget, CancellationSignal.never(), retrying(2, pauses)));

        assertEquals(1, gateway.receivedRequests().size());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void whenEveryTryFailsTheLastFailureIsReportedAndEachTryWasReserved() {
        FakeModelGateway gateway = new FakeModelGateway();
        for (int i = 0; i < 3; i++) {
            gateway.enqueueFailure(new ModelTransportException("provider outage " + i, true, null));
        }
        RecordingLedger ledger = new RecordingLedger();
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini(), ledger);

        ModelTransportException failure = assertThrows(ModelTransportException.class, () -> BoundedModelCall.complete(
                gateway, REQUEST, 10, budget, CancellationSignal.never(), retrying(2, new ArrayList<>())));

        assertEquals("provider outage 2", failure.getMessage());
        assertEquals(3, gateway.receivedRequests().size());
        assertEquals(3, ledger.events.stream().filter(event -> event.startsWith("retain:")).count());
    }

    /**
     * A retry policy more generous than the run's own bound must lose to the
     * bound, not the other way round. And what the caller hears is the
     * outage, not "this run overspent": a retry the budget will not pay for
     * is simply not made, so the work can be tried again later instead of
     * being ended for good by its own retries.
     */
    @Test
    void aRetryTheBudgetWillNotPayForIsNotMadeAndTheFailureThatPromptedItStands() {
        FakeModelGateway gateway = new FakeModelGateway();
        List<ModelTransportException> outages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ModelTransportException outage = new ModelTransportException("provider outage " + i, true, null);
            outages.add(outage);
            gateway.enqueueFailure(outage);
        }
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini());
        int bound = UsageLimits.defaultRunLimits().maxPhysicalRequests();

        ModelTransportException reported = assertThrows(ModelTransportException.class, () -> BoundedModelCall.complete(
                gateway, REQUEST, 10, budget, CancellationSignal.never(), retrying(9, new ArrayList<>())));

        assertSame(outages.get(bound - 1), reported);
        assertTrue(reported.retryable());
        assertEquals(bound, gateway.receivedRequests().size());
    }

    /** The same when it is the ledger, not the in-memory bound, that will not pay for another try. */
    @Test
    void aLedgerThatRefusesARetryLeavesTheTransportFailureStanding() {
        FakeModelGateway gateway = new FakeModelGateway();
        ModelTransportException outage = new ModelTransportException("rate limited", true, null);
        gateway.enqueueFailure(outage);
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(1, 1)));
        RecordingLedger ledger = new RecordingLedger();
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini(), ledger);
        // The month runs out between the first try and the second.
        TransportRetryPolicy policy = new TransportRetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(8),
                duration -> ledger.refuseWith = new BudgetExceededException(UsageLimitKind.WORKSPACE_MONTH, "used up"));

        ModelTransportException reported = assertThrows(ModelTransportException.class,
                () -> BoundedModelCall.complete(gateway, REQUEST, 10, budget, CancellationSignal.never(), policy));

        assertSame(outage, reported);
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void nothingIsSentOnceCancellationHasBeenAskedFor() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueueFailure(new ModelTransportException("rate limited", true, null));
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(1, 1)));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini());
        // Cancellation arrives while the first failure is being waited out.
        TransportRetryPolicy policy = new TransportRetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(8), duration -> cancelled.set(true));

        assertThrows(ModelCallCancelledException.class,
                () -> BoundedModelCall.complete(gateway, REQUEST, 10, budget, cancelled::get, policy));

        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void aLedgerThatRefusesStopsTheRequestBeforeItIsSent() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(1, 1)));
        RecordingLedger ledger = new RecordingLedger();
        ledger.refuseWith = new BudgetExceededException(UsageLimitKind.WORKSPACE_MONTH, "This workspace has used its allowance.");
        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini(), ledger);

        BudgetExceededException refused = assertThrows(BudgetExceededException.class, () -> BoundedModelCall.complete(
                gateway, REQUEST, 10, budget, CancellationSignal.never(), TransportRetryPolicy.none()));

        assertEquals(UsageLimitKind.WORKSPACE_MONTH, refused.kind());
        assertTrue(gateway.receivedRequests().isEmpty());
        // Refused before anything changed: the budget is as it was, and can be asked again.
        assertEquals(0, budget.physicalRequestsMade());
    }

    @Test
    void theDelayDoublesAndIsCapped() {
        TransportRetryPolicy policy = new TransportRetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(3), duration -> { });

        assertInstanceOf(Duration.class, policy.delayBefore(1));
        assertTrue(policy.delayBefore(2).toMillis() >= 1600 && policy.delayBefore(2).toMillis() <= 2400);
        assertTrue(policy.delayBefore(5).toMillis() <= 3600, "capped at the maximum plus jitter");
    }

    private static TransportRetryPolicy retrying(int maxRetries, List<Duration> pauses) {
        return new TransportRetryPolicy(maxRetries, Duration.ofSeconds(1), Duration.ofSeconds(8), pauses::add);
    }

    private static final class RecordingLedger implements UsageLedger {

        final List<String> events = new ArrayList<>();
        BudgetExceededException refuseWith;
        private long nextId = 1;

        @Override
        public long reserve(String promptVersion, int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd)
                throws BudgetExceededException {
            if (refuseWith != null) {
                throw refuseWith;
            }
            events.add("reserve:" + promptVersion);
            return nextId++;
        }

        @Override
        public void settle(long reservationId, ModelUsage usage, BigDecimal actualCostUsd) {
            events.add("settle:" + reservationId);
        }

        @Override
        public void retain(long reservationId) {
            events.add("retain:" + reservationId);
        }
    }
}
