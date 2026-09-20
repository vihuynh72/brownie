package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;

/**
 * One model request as every paid caller makes it: check for cancellation,
 * reserve the most it could cost, send it, and on a failure in transit
 * keep the reservation (the provider may have served and billed a request
 * whose answer never arrived) and, when the failure is one that waiting
 * can fix, try again under the same rules. Lives in one place so that the
 * order of those steps cannot differ between the callers that spend money.
 */
public final class BoundedModelCall {

    private BoundedModelCall() {
    }

    public static ModelCompletion complete(
            ModelGateway modelGateway,
            ModelRequest request,
            int estimatedInputTokens,
            UsageBudget budget,
            CancellationSignal cancellationSignal,
            TransportRetryPolicy retryPolicy)
            throws ModelTransportException, BudgetExceededException, ModelCallCancelledException {
        int transportFailures = 0;
        ModelTransportException lastTransportFailure = null;
        while (true) {
            if (cancellationSignal.isCancellationRequested()) {
                throw new ModelCallCancelledException();
            }
            try {
                budget.reserveForCall(estimatedInputTokens, request.maxOutputTokens(), request.promptVersion());
            } catch (BudgetExceededException refused) {
                // Another try that the budget will not pay for is simply not
                // made, and the failure that prompted it stands: the caller
                // then treats this as the transient failure it was, instead
                // of as a run that overspent, which would end it for good.
                if (lastTransportFailure != null) {
                    throw lastTransportFailure;
                }
                throw refused;
            }
            try {
                return modelGateway.complete(request);
            } catch (ModelTransportException failure) {
                budget.retainReservationAfterLostResponse();
                if (!failure.retryable() || transportFailures >= retryPolicy.maxRetries()) {
                    throw failure;
                }
                lastTransportFailure = failure;
                transportFailures++;
                try {
                    retryPolicy.pause().forDuration(retryPolicy.delayBefore(transportFailures));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
    }
}
