package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.FakeModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@code extractFromExcerpts}, the pure part of {@link
 * ExtractionService} -- see that method's own javadoc for why the deep
 * artifact/extraction dependency chain never needs to be faked here.
 */
class ExtractionServiceTest {

    private static final FieldDefinition TITLE_FIELD =
            new FieldDefinition("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                    new FieldBindingTarget.ContentControlTag("meeting.title"));
    private static final FieldDefinition TASK_FIELD =
            new FieldDefinition("action.item.task", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.ContentControlTag("action.item.task"));
    private static final List<FieldDefinition> FIELDS = List.of(TITLE_FIELD, TASK_FIELD);
    private static final List<LabeledExcerpt> EXCERPTS = List.of(new LabeledExcerpt(101, "Weekly sync meeting."));

    @Test
    void aSuccessfulReplyIsParsedAndReturned() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        ExtractionResult expected = new ExtractionResult(
                Map.of(TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 101)), List.of());
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(expected);
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        assertEquals("Weekly sync", result.scalarCandidates().get(TITLE_FIELD.fieldId()).value());
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void aCandidateCitingASpanOutsideTheOfferedSetIsRejectedAsUnresolved() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        ExtractionResult fabricated = new ExtractionResult(
                Map.of(TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 999)), List.of());
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(fabricated);
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        FieldCandidate sanitized = result.scalarCandidates().get(TITLE_FIELD.fieldId());
        assertTrue(sanitized.unresolved());
        assertEquals(List.of(), sanitized.evidenceSpanIds());
    }

    /**
     * The evidence-isolation half of this phase's own "example-leakage"
     * eval category: a span ID that is a real, legitimately-existing
     * citation elsewhere (a different run, a different document, another
     * workspace entirely) is still rejected here, because it is not a
     * member of *this* run's own offered excerpt set -- the same
     * mechanism {@link #aCandidateCitingASpanOutsideTheOfferedSetIsRejectedAsUnresolved}
     * proves against a purely invented ID, exercised again here with an ID
     * chosen specifically to look like real, borrowed evidence rather than
     * an obviously fake one, since a real ID is the more realistic leakage
     * threat.
     */
    @Test
    void aCandidateCitingARealButNotOfferedSpanIsRejectedTheSameAsAFabricatedOne() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        long realSpanIdFromAnotherRunOrWorkspace = 42;
        ExtractionResult leaked = new ExtractionResult(
                Map.of(TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Borrowed value", realSpanIdFromAnotherRunOrWorkspace)), List.of());
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(leaked);
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        FieldCandidate sanitized = result.scalarCandidates().get(TITLE_FIELD.fieldId());
        assertTrue(sanitized.unresolved());
        assertEquals(List.of(), sanitized.evidenceSpanIds());
    }

    @Test
    void aCandidateCitingOnlyOfferedSpansIsKeptIntact() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        ExtractionResult valid = new ExtractionResult(
                Map.of(TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 101)), List.of());
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(valid);
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        FieldCandidate candidate = result.scalarCandidates().get(TITLE_FIELD.fieldId());
        assertFalse(candidate.unresolved());
        assertEquals(List.of(101L), candidate.evidenceSpanIds());
    }

    @Test
    void anUnresolvedCandidateIsNeverRejectedForItsEmptyEvidence() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        FieldCandidate unresolved = new FieldCandidate(TITLE_FIELD.fieldId(), null, List.of(), true, "not mentioned");
        parser.enqueue(new ExtractionResult(Map.of(TITLE_FIELD.fieldId(), unresolved), List.of()));
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        assertEquals(unresolved, result.scalarCandidates().get(TITLE_FIELD.fieldId()));
    }

    @Test
    void aRefusalThrowsExtractionFailedRatherThanBeingParsedAndDoesNotRepair() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.Refusal("policy", new ModelUsage(10, 0)));
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());

        ExtractionFailedException thrown = assertThrows(
                ExtractionFailedException.class, () -> service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled()));
        assertTrue(thrown.getMessage().contains("refused"));
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void incompleteOutputThrowsExtractionFailedAndDoesNotRepair() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.IncompleteOutput("partial", "truncated", new ModelUsage(10, 5)));
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());

        assertThrows(
                ExtractionFailedException.class, () -> service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled()));
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void unsupportedParametersThrowsExtractionFailedAndSettlesAtZeroUsage() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.UnsupportedParameters("bad param"));
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());
        UsageBudget budget = freshBudget();

        assertThrows(ExtractionFailedException.class, () -> service.extractFromExcerpts(FIELDS, EXCERPTS, budget, neverCancelled()));

        assertEquals(BigDecimal.ZERO.setScale(6), budget.actualCost());
    }

    @Test
    void aTransportFailurePropagatesAndRetainsTheReservationRatherThanRepairing() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueueFailure(new ModelTransportException("network down", true, null));
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());

        assertThrows(
                ModelTransportException.class, () -> service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled()));
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void aParseFailureTriggersExactlyOneRepairAttemptWhichThenSucceeds() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        ExtractionResult repaired = new ExtractionResult(
                Map.of(TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 101)), List.of());
        gateway.enqueue(new ModelCompletion.Success("bad json", new ModelUsage(10, 5)));
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueueFailure(new ExtractionResponseParseException("missing required field"));
        parser.enqueue(repaired);
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        assertEquals("Weekly sync", result.scalarCandidates().get(TITLE_FIELD.fieldId()).value());
        assertEquals(2, gateway.receivedRequests().size());
    }

    @Test
    void aParseFailureOnTheRepairAttemptTooPropagatesRatherThanLoopingFurther() {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        gateway.enqueue(new ModelCompletion.Success("bad json", new ModelUsage(10, 5)));
        gateway.enqueue(new ModelCompletion.Success("still bad json", new ModelUsage(10, 5)));
        parser.enqueueFailure(new ExtractionResponseParseException("missing required field"));
        parser.enqueueFailure(new ExtractionResponseParseException("missing required field again"));
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        assertThrows(
                ExtractionResponseParseException.class,
                () -> service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled()));
        assertEquals(2, gateway.receivedRequests().size());
    }

    @Test
    void malformedOutputTriggersExactlyOneRepairAttemptWhichThenSucceeds() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeExtractionResponseParser parser = new FakeExtractionResponseParser();
        ExtractionResult repaired = new ExtractionResult(
                Map.of(TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 101)), List.of());
        gateway.enqueue(new ModelCompletion.MalformedOutput("garbage", "not json", new ModelUsage(10, 5)));
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(repaired);
        ExtractionService service = new ExtractionService(null, null, gateway, parser);

        ExtractionResult result = service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled());

        assertEquals("Weekly sync", result.scalarCandidates().get(TITLE_FIELD.fieldId()).value());
        assertEquals(2, gateway.receivedRequests().size());
    }

    @Test
    void malformedOutputOnTheRepairAttemptTooThrowsExtractionFailed() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.MalformedOutput("garbage", "not json", new ModelUsage(10, 5)));
        gateway.enqueue(new ModelCompletion.MalformedOutput("still garbage", "still not json", new ModelUsage(10, 5)));
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());

        assertThrows(
                ExtractionFailedException.class, () -> service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), neverCancelled()));
        assertEquals(2, gateway.receivedRequests().size());
    }

    @Test
    void anExhaustedBudgetRefusesTheCallWithoutEverReachingTheGateway() {
        FakeModelGateway gateway = new FakeModelGateway();
        UsageBudget budget = freshBudget();
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());

        assertThrows(
                BudgetExceededException.class,
                () -> {
                    // Drain the run's 3-request allowance directly through the budget object itself, then confirm a 4th call is refused before the gateway is ever touched.
                    budget.reserveForCall(1, 1);
                    budget.settleActual(new ModelUsage(1, 1));
                    budget.reserveForCall(1, 1);
                    budget.settleActual(new ModelUsage(1, 1));
                    budget.reserveForCall(1, 1);
                    budget.settleActual(new ModelUsage(1, 1));
                    service.extractFromExcerpts(FIELDS, EXCERPTS, budget, neverCancelled());
                });
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void cancellationBeforeTheCallThrowsWithoutEverReachingTheGateway() {
        FakeModelGateway gateway = new FakeModelGateway();
        ExtractionService service = new ExtractionService(null, null, gateway, new FakeExtractionResponseParser());

        assertThrows(
                ExtractionCancelledException.class,
                () -> service.extractFromExcerpts(FIELDS, EXCERPTS, freshBudget(), () -> true));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    private static UsageBudget freshBudget() {
        return new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini());
    }

    private static CancellationSignal neverCancelled() {
        return CancellationSignal.never();
    }

    private static FieldCandidate resolved(String fieldId, String value, long spanId) {
        return new FieldCandidate(fieldId, value, List.of(spanId), false, null);
    }
}
