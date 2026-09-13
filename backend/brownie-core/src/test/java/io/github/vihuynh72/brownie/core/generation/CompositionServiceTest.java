package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.FakeModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.rule.RuleCategory;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link CompositionService}, mirroring {@code ExtractionServiceTest}'s own structure for its sibling generation pipeline. */
class CompositionServiceTest {

    private static final FieldDefinition TITLE_FIELD =
            new FieldDefinition("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                    new FieldBindingTarget.ContentControlTag("meeting.title"));
    private static final FieldDefinition DECISIONS_FIELD =
            new FieldDefinition("meeting.decisions", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.ContentControlTag("meeting.decisions"));
    private static final List<FieldDefinition> FIELDS = List.of(TITLE_FIELD, DECISIONS_FIELD);
    private static final List<String> COMPOSABLE = List.of(DECISIONS_FIELD.fieldId());

    private static final ExtractionResult ACCEPTED_FACTS = new ExtractionResult(
            Map.of(
                    TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 101),
                    DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "the club voted to buy new uniforms and reserve the van", 102)),
            List.of());

    @Test
    void aSuccessfulReplyIsComposedAndMergedWithUntouchedFacts() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeCompositionResponseParser parser = new FakeCompositionResponseParser();
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(Map.of(DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "Approved new uniforms and van reservation.", 102)));
        CompositionService service = new CompositionService(gateway, parser);

        ExtractionResult result = service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled());

        assertEquals("Approved new uniforms and van reservation.", result.scalarCandidates().get(DECISIONS_FIELD.fieldId()).value());
        assertEquals("Weekly sync", result.scalarCandidates().get(TITLE_FIELD.fieldId()).value());
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void aComposedCandidateCitingAFactOutsideTheOfferedSetIsRejectedAsUnresolved() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeCompositionResponseParser parser = new FakeCompositionResponseParser();
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(Map.of(DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "Fabricated.", 999)));
        CompositionService service = new CompositionService(gateway, parser);

        ExtractionResult result = service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled());

        FieldCandidate sanitized = result.scalarCandidates().get(DECISIONS_FIELD.fieldId());
        assertTrue(sanitized.unresolved());
        assertEquals(List.of(), sanitized.evidenceSpanIds());
    }

    @Test
    void aComposedCandidateCitingOnlyOfferedFactsIsKeptIntact() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeCompositionResponseParser parser = new FakeCompositionResponseParser();
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(Map.of(DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "Approved uniforms.", 102)));
        CompositionService service = new CompositionService(gateway, parser);

        ExtractionResult result = service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled());

        FieldCandidate candidate = result.scalarCandidates().get(DECISIONS_FIELD.fieldId());
        assertFalse(candidate.unresolved());
        assertEquals(List.of(102L), candidate.evidenceSpanIds());
    }

    @Test
    void composedTextOverItsOwnMaxLengthRuleIsDowngradedToUnresolvedRatherThanTruncated() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeCompositionResponseParser parser = new FakeCompositionResponseParser();
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueue(Map.of(DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "This composed sentence is deliberately longer than the rule allows.", 102)));
        CompositionService service = new CompositionService(gateway, parser);
        List<RuleRevision> rules = List.of(maxLengthRule(DECISIONS_FIELD.fieldId(), 10));

        ExtractionResult result = service.compose(FIELDS, COMPOSABLE, rules, ACCEPTED_FACTS, freshBudget(), neverCancelled());

        FieldCandidate candidate = result.scalarCandidates().get(DECISIONS_FIELD.fieldId());
        assertTrue(candidate.unresolved());
        assertTrue(candidate.ambiguityReason().contains("10-character limit"));
    }

    @Test
    void anUnresolvedRequiredFactRefusesComposingWithoutEverReachingTheGateway() {
        FakeModelGateway gateway = new FakeModelGateway();
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());
        ExtractionResult factsWithUnresolvedTitle = new ExtractionResult(
                Map.of(
                        TITLE_FIELD.fieldId(), new FieldCandidate(TITLE_FIELD.fieldId(), null, List.of(), true, "not mentioned"),
                        DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "the club voted to buy new uniforms", 102)),
                List.of());

        RequiredFactsUnresolvedException thrown = assertThrows(
                RequiredFactsUnresolvedException.class,
                () -> service.compose(FIELDS, COMPOSABLE, List.of(), factsWithUnresolvedTitle, freshBudget(), neverCancelled()));

        assertEquals(List.of(TITLE_FIELD.fieldId()), thrown.unresolvedRequiredFieldIds());
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void composingAFieldNotDefinedByTheTemplateThrows() {
        CompositionService service = new CompositionService(new FakeModelGateway(), new FakeCompositionResponseParser());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.compose(FIELDS, List.of("meeting.nonexistent"), List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled()));
    }

    @Test
    void composingARepeatedOrNonTextFieldThrows() {
        CompositionService service = new CompositionService(new FakeModelGateway(), new FakeCompositionResponseParser());
        FieldDefinition dateField = new FieldDefinition("meeting.date", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.date"));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.compose(List.of(dateField), List.of("meeting.date"), List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled()));
    }

    @Test
    void aRefusalThrowsCompositionFailedRatherThanBeingParsedAndDoesNotRepair() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.Refusal("policy", new ModelUsage(10, 0)));
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());

        CompositionFailedException thrown = assertThrows(
                CompositionFailedException.class,
                () -> service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled()));
        assertTrue(thrown.getMessage().contains("refused"));
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void unsupportedParametersThrowsCompositionFailedAndSettlesAtZeroUsage() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.UnsupportedParameters("bad param"));
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());
        UsageBudget budget = freshBudget();

        assertThrows(CompositionFailedException.class, () -> service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, budget, neverCancelled()));

        assertEquals(BigDecimal.ZERO.setScale(6), budget.actualCost());
    }

    @Test
    void aTransportFailurePropagatesAndRetainsTheReservationRatherThanRepairing() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueueFailure(new ModelTransportException("network down", true, null));
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());

        assertThrows(
                ModelTransportException.class,
                () -> service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled()));
        assertEquals(1, gateway.receivedRequests().size());
    }

    @Test
    void aParseFailureTriggersExactlyOneRepairAttemptWhichThenSucceeds() throws Exception {
        FakeModelGateway gateway = new FakeModelGateway();
        FakeCompositionResponseParser parser = new FakeCompositionResponseParser();
        gateway.enqueue(new ModelCompletion.Success("bad json", new ModelUsage(10, 5)));
        gateway.enqueue(new ModelCompletion.Success("{}", new ModelUsage(10, 5)));
        parser.enqueueFailure(new CompositionResponseParseException("missing required field"));
        parser.enqueue(Map.of(DECISIONS_FIELD.fieldId(), resolved(DECISIONS_FIELD.fieldId(), "Approved uniforms.", 102)));
        CompositionService service = new CompositionService(gateway, parser);

        ExtractionResult result = service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled());

        assertEquals("Approved uniforms.", result.scalarCandidates().get(DECISIONS_FIELD.fieldId()).value());
        assertEquals(2, gateway.receivedRequests().size());
    }

    @Test
    void malformedOutputOnTheRepairAttemptTooThrowsCompositionFailed() {
        FakeModelGateway gateway = new FakeModelGateway();
        gateway.enqueue(new ModelCompletion.MalformedOutput("garbage", "not json", new ModelUsage(10, 5)));
        gateway.enqueue(new ModelCompletion.MalformedOutput("still garbage", "still not json", new ModelUsage(10, 5)));
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());

        assertThrows(
                CompositionFailedException.class,
                () -> service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), neverCancelled()));
        assertEquals(2, gateway.receivedRequests().size());
    }

    @Test
    void anExhaustedBudgetRefusesTheCallWithoutEverReachingTheGateway() {
        FakeModelGateway gateway = new FakeModelGateway();
        UsageBudget budget = freshBudget();
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());

        assertThrows(
                BudgetExceededException.class,
                () -> {
                    budget.reserveForCall(1, 1);
                    budget.settleActual(new ModelUsage(1, 1));
                    budget.reserveForCall(1, 1);
                    budget.settleActual(new ModelUsage(1, 1));
                    budget.reserveForCall(1, 1);
                    budget.settleActual(new ModelUsage(1, 1));
                    service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, budget, neverCancelled());
                });
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void cancellationBeforeTheCallThrowsWithoutEverReachingTheGateway() {
        FakeModelGateway gateway = new FakeModelGateway();
        CompositionService service = new CompositionService(gateway, new FakeCompositionResponseParser());

        assertThrows(
                CompositionCancelledException.class,
                () -> service.compose(FIELDS, COMPOSABLE, List.of(), ACCEPTED_FACTS, freshBudget(), () -> true));
        assertTrue(gateway.receivedRequests().isEmpty());
    }

    @Test
    void toKnownFactsOffersResolvedFactsAndMarksAnUncitedOneAsNotCitable() {
        ExtractionResult facts = new ExtractionResult(
                Map.of(
                        TITLE_FIELD.fieldId(), resolved(TITLE_FIELD.fieldId(), "Weekly sync", 101),
                        DECISIONS_FIELD.fieldId(), new FieldCandidate(DECISIONS_FIELD.fieldId(), "typed by hand", List.of(), false, null)),
                List.of());

        List<KnownFact> knownFacts = CompositionService.toKnownFacts(facts);

        KnownFact uncited = knownFacts.stream().filter(f -> f.fieldId().equals(DECISIONS_FIELD.fieldId())).findFirst().orElseThrow();
        assertEquals(null, uncited.citableSpanId());
        KnownFact cited = knownFacts.stream().filter(f -> f.fieldId().equals(TITLE_FIELD.fieldId())).findFirst().orElseThrow();
        assertEquals(101L, cited.citableSpanId());
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

    private static RuleRevision maxLengthRule(String fieldId, int maxCharacters) {
        RulePayload payload = new RulePayload.MaxTextLength(fieldId, maxCharacters);
        return new RuleRevision(
                1, 1, 1, 1, RuleCategory.VALIDATION, new RuleScope.SingleField(fieldId), payload,
                "rule-vocabulary-v1", RuleRevisionStatus.PROPOSED, "test rule", 1, OffsetDateTime.now());
    }
}
