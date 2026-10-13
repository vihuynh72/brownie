package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.generation.BoundedModelCall;
import io.github.vihuynh72.brownie.core.generation.CancellationSignal;
import io.github.vihuynh72.brownie.core.generation.ModelCallCancelledException;
import io.github.vihuynh72.brownie.core.generation.TransportRetryPolicy;
import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageLedger;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Sends one row's compose request through the shared, paid path: the call
 * is reserved against the direct-request limits and the monthly
 * allowances before it is sent, settled from what the provider reports,
 * and retried only for transport failures the waiting rules allow --
 * exactly the spending discipline every other caller on a person's
 * request thread follows.
 *
 * <p>Every way a call can fail is an outcome the step records on the row,
 * not an exception up the request: the monthly allowances being spent
 * answers NO_ALLOWANCE (the step stops, since the rows after it would
 * answer the same), and anything else is a failure code the row carries.
 */
public final class BatchComposer {

    private static final Logger log = LoggerFactory.getLogger(BatchComposer.class);

    public enum Status {
        COMPOSED,
        FAILED,
        NO_ALLOWANCE
    }

    /** What one call resolved to: the texts to clean and propose, a failure code for the row, or the step-stopping NO_ALLOWANCE. */
    public record Outcome(Status status, Map<String, String> texts, BatchRowProblemCode failureCode) {

        static Outcome composed(Map<String, String> texts) {
            return new Outcome(Status.COMPOSED, texts, null);
        }

        static Outcome failed(BatchRowProblemCode code) {
            return new Outcome(Status.FAILED, Map.of(), code);
        }

        static Outcome noAllowance() {
            return new Outcome(Status.NO_ALLOWANCE, Map.of(), null);
        }
    }

    private final ModelGateway modelGateway;
    private final BatchComposeReplyParser replyParser;
    private final MemberUsageRepository usageRepository;
    private final UsageLimits directRequestLimits;
    private final MonthlyUsageLimits monthlyUsageLimits;
    private final ModelPricing modelPricing;
    private final String modelName;
    private final TransportRetryPolicy transportRetryPolicy;

    public BatchComposer(
            ModelGateway modelGateway,
            BatchComposeReplyParser replyParser,
            MemberUsageRepository usageRepository,
            UsageLimits directRequestLimits,
            MonthlyUsageLimits monthlyUsageLimits,
            ModelPricing modelPricing,
            String modelName,
            TransportRetryPolicy transportRetryPolicy) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.replyParser = Objects.requireNonNull(replyParser, "replyParser");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository");
        this.directRequestLimits = Objects.requireNonNull(directRequestLimits, "directRequestLimits");
        this.monthlyUsageLimits = Objects.requireNonNull(monthlyUsageLimits, "monthlyUsageLimits");
        this.modelPricing = Objects.requireNonNull(modelPricing, "modelPricing");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.transportRetryPolicy = Objects.requireNonNull(transportRetryPolicy, "transportRetryPolicy");
    }

    /**
     * One bounded call for one row. The request the prompt builder made is
     * sent as-is; the reply is decoded against exactly the ids asked for.
     */
    public Outcome compose(long workspaceId, long userId, ModelRequest request, java.util.Set<String> expectedIds) {
        UsageBudget budget = new UsageBudget(
                directRequestLimits,
                modelPricing,
                new MemberUsageLedger(
                        usageRepository, workspaceId, userId, modelName, modelPricing, directRequestLimits, monthlyUsageLimits));

        ModelCompletion completion;
        try {
            completion = BoundedModelCall.complete(
                    modelGateway, request, UsageBudget.estimateInputTokens(request), budget,
                    CancellationSignal.never(), transportRetryPolicy);
        } catch (BudgetExceededException e) {
            log.info("Batch compose for workspace {} has no allowance left: {}", workspaceId, e.getMessage());
            return Outcome.noAllowance();
        } catch (ModelTransportException e) {
            log.info("Batch compose could not reach the model for workspace {}: {}", workspaceId, e.getMessage());
            return Outcome.failed(BatchRowProblemCode.MODEL_UNAVAILABLE);
        } catch (ModelCallCancelledException e) {
            return Outcome.failed(BatchRowProblemCode.MODEL_UNAVAILABLE);
        }

        switch (completion) {
            case ModelCompletion.Success success -> {
                budget.settleActual(success.usage());
                try {
                    BatchComposeReply reply = replyParser.parse(success.content(), expectedIds);
                    return Outcome.composed(reply.texts());
                } catch (BatchComposeReplyParseException e) {
                    log.info("Batch compose reply was unusable for workspace {}: {}", workspaceId, e.getMessage());
                    return Outcome.failed(BatchRowProblemCode.RESPONSE_UNREADABLE);
                }
            }
            case ModelCompletion.Refusal refusal -> {
                budget.settleActual(refusal.usage());
                log.info("Batch compose was refused for workspace {}: {}", workspaceId, refusal.reason());
                return Outcome.failed(BatchRowProblemCode.RESPONSE_UNREADABLE);
            }
            case ModelCompletion.MalformedOutput malformed -> {
                budget.settleActual(malformed.usage());
                log.info("Batch compose reply was malformed for workspace {}: {}", workspaceId, malformed.reason());
                return Outcome.failed(BatchRowProblemCode.RESPONSE_UNREADABLE);
            }
            case ModelCompletion.IncompleteOutput incomplete -> {
                budget.settleActual(incomplete.usage());
                log.info("Batch compose reply was cut off for workspace {}: {}", workspaceId, incomplete.reason());
                return Outcome.failed(BatchRowProblemCode.RESPONSE_UNREADABLE);
            }
            case ModelCompletion.UnsupportedParameters unsupported -> {
                budget.settleActual(new ModelUsage(0, 0));
                log.warn("The model provider rejected a batch compose request for workspace {}: {}", workspaceId, unsupported.reason());
                return Outcome.failed(BatchRowProblemCode.RESPONSE_UNREADABLE);
            }
        }
    }
}
