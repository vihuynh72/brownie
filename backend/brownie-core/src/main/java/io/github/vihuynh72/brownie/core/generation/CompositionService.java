package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composes concise, rule-bounded prose for a template's own scalar TEXT
 * fields (for example a decisions summary) from a document's already-
 * accepted facts -- the same grounded, budget-bounded, single-repair
 * shape {@link ExtractionService} already establishes for finding facts
 * in the first place, applied here to writing new prose FROM facts
 * already on the record rather than finding facts in raw source text. A
 * composed candidate that cites a fact this run did not actually offer is
 * rejected into an explicitly unresolved candidate the same way {@link
 * ExtractionService} rejects a fabricated or out-of-scope source-span
 * citation, at fact granularity rather than raw-span granularity.
 *
 * <p>Repeated fields (for example one action item per row) are
 * deliberately out of scope: batching several items' own distinct
 * evidence contexts into one bounded model call while keeping each
 * item's citations correctly isolated from the others is a genuinely
 * separate problem -- the same class of complexity this codebase already
 * deferred once for repeated-field conflict detection (see {@code
 * QuestionDetectionService}). An action item's own task/owner/due values
 * are also already reasonably concise, atomic facts that do not need the
 * same prose-synthesis this class provides for a field like decisions.
 */
public class CompositionService {

    private static final int MAX_OUTPUT_TOKENS = 1500;
    private static final int MAX_ATTEMPTS = 2;

    private final ModelGateway modelGateway;
    private final CompositionResponseParser responseParser;

    public CompositionService(ModelGateway modelGateway, CompositionResponseParser responseParser) {
        this.modelGateway = modelGateway;
        this.responseParser = responseParser;
    }

    /**
     * Composes {@code composableFieldIds} from {@code acceptedFacts},
     * returning a full accepted-facts view: every untouched field's
     * candidate passes through unchanged, and each composed field is
     * replaced with its newly composed candidate. Refuses outright,
     * before the model gateway is ever touched, if a required field
     * (this template version's own {@link FieldRequiredness#REQUIRED}, or
     * one named by an attached {@link RulePayload.RequiredFields} rule)
     * has no resolved accepted fact yet -- this plan's own "no paid call
     * runs while the workflow is waiting for input" rule.
     */
    public ExtractionResult compose(
            List<FieldDefinition> templateFields,
            List<String> composableFieldIds,
            List<RuleRevision> rules,
            ExtractionResult acceptedFacts,
            UsageBudget budget,
            CancellationSignal cancellationSignal)
            throws ModelTransportException, CompositionFailedException, CompositionResponseParseException, BudgetExceededException,
                    CompositionCancelledException, RequiredFactsUnresolvedException {
        Map<String, FieldDefinition> fieldsById = new LinkedHashMap<>();
        templateFields.forEach(field -> fieldsById.put(field.fieldId(), field));
        composableFieldIds.forEach(fieldId -> requireComposableScalarTextField(fieldId, fieldsById));
        requireNoUnresolvedRequiredFacts(templateFields, rules, acceptedFacts);

        List<KnownFact> facts = toKnownFacts(acceptedFacts);
        Map<String, Integer> maxCharactersByFieldId = maxCharactersByFieldId(rules);
        Set<Long> allowedSpanIds = new HashSet<>();
        facts.forEach(fact -> {
            if (fact.citableSpanId() != null) {
                allowedSpanIds.add(fact.citableSpanId());
            }
        });

        ModelRequest request = CompositionPromptBuilder.build(composableFieldIds, maxCharactersByFieldId, facts, MAX_OUTPUT_TOKENS);
        int estimatedInputTokens = UsageBudget.estimateTokens(promptText(request));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cancellationSignal.isCancellationRequested()) {
                throw new CompositionCancelledException();
            }

            budget.reserveForCall(estimatedInputTokens, request.maxOutputTokens());
            ModelCompletion completion;
            try {
                completion = modelGateway.complete(request);
            } catch (ModelTransportException e) {
                budget.retainReservationAfterLostResponse();
                throw e;
            }

            boolean isLastAttempt = attempt == MAX_ATTEMPTS;
            switch (completion) {
                case ModelCompletion.Success success -> {
                    budget.settleActual(success.usage());
                    try {
                        Map<String, FieldCandidate> rawComposed = responseParser.parse(success.content(), composableFieldIds);
                        Map<String, FieldCandidate> sanitizedComposed =
                                sanitizeAndValidate(rawComposed, allowedSpanIds, maxCharactersByFieldId);
                        return merge(acceptedFacts, sanitizedComposed);
                    } catch (CompositionResponseParseException e) {
                        if (isLastAttempt) {
                            throw e;
                        }
                    }
                }
                case ModelCompletion.MalformedOutput malformed -> {
                    budget.settleActual(malformed.usage());
                    if (isLastAttempt) {
                        throw new CompositionFailedException(
                                "The model's reply was not usable after one repair attempt: " + malformed.reason());
                    }
                }
                case ModelCompletion.Refusal refusal -> {
                    budget.settleActual(refusal.usage());
                    throw new CompositionFailedException("The model refused to compose: " + refusal.reason());
                }
                case ModelCompletion.IncompleteOutput incomplete -> {
                    budget.settleActual(incomplete.usage());
                    throw new CompositionFailedException("The model's reply was cut off: " + incomplete.reason());
                }
                case ModelCompletion.UnsupportedParameters unsupported -> {
                    budget.settleActual(new ModelUsage(0, 0));
                    throw new CompositionFailedException("The model provider rejected this request: " + unsupported.reason());
                }
            }
        }
        throw new IllegalStateException("Unreachable: the loop above always returns or throws by its last attempt.");
    }

    private static String promptText(ModelRequest request) {
        StringBuilder text = new StringBuilder();
        for (ModelMessage message : request.messages()) {
            text.append(message.content());
        }
        return text.toString();
    }

    private static void requireComposableScalarTextField(String fieldId, Map<String, FieldDefinition> fieldsById) {
        FieldDefinition field = fieldsById.get(fieldId);
        if (field == null) {
            throw new IllegalArgumentException("Unknown field '" + fieldId + "' is not defined by this template version.");
        }
        if (field.cardinality() != FieldCardinality.SCALAR || field.type() != FieldType.TEXT) {
            throw new IllegalArgumentException(
                    "Field '" + fieldId + "' is not a scalar text field; this class only composes scalar text fields.");
        }
    }

    private static void requireNoUnresolvedRequiredFacts(
            List<FieldDefinition> templateFields, List<RuleRevision> rules, ExtractionResult acceptedFacts)
            throws RequiredFactsUnresolvedException {
        Set<String> requiredFieldIds = new HashSet<>();
        for (FieldDefinition field : templateFields) {
            if (field.requiredness() == FieldRequiredness.REQUIRED) {
                requiredFieldIds.add(field.fieldId());
            }
        }
        for (RuleRevision rule : rules) {
            if (rule.payload() instanceof RulePayload.RequiredFields requiredFields) {
                requiredFieldIds.addAll(requiredFields.fieldIds());
            }
        }

        List<String> unresolved = new ArrayList<>();
        for (String fieldId : requiredFieldIds) {
            FieldCandidate candidate = acceptedFacts.scalarCandidates().get(fieldId);
            if (candidate == null || candidate.unresolved()) {
                unresolved.add(fieldId);
            }
        }
        if (!unresolved.isEmpty()) {
            throw new RequiredFactsUnresolvedException(List.copyOf(unresolved));
        }
    }

    private static Map<String, Integer> maxCharactersByFieldId(List<RuleRevision> rules) {
        Map<String, Integer> maxCharacters = new HashMap<>();
        for (RuleRevision rule : rules) {
            if (rule.payload() instanceof RulePayload.MaxTextLength maxTextLength) {
                maxCharacters.put(maxTextLength.fieldId(), maxTextLength.maxCharacters());
            }
        }
        return maxCharacters;
    }

    /**
     * Offers one {@link KnownFact} per resolved scalar accepted fact,
     * field order preserved. An unresolved fact, or one with a null
     * value despite being marked resolved, is not offered at all -- the
     * composer has nothing usable to draw from it, and it must not appear
     * to have been considered.
     */
    static List<KnownFact> toKnownFacts(ExtractionResult acceptedFacts) {
        List<KnownFact> facts = new ArrayList<>();
        acceptedFacts.scalarCandidates().forEach((fieldId, candidate) -> {
            if (candidate.unresolved() || candidate.value() == null) {
                return;
            }
            Long citableSpanId = candidate.evidenceSpanIds().isEmpty() ? null : candidate.evidenceSpanIds().get(0);
            facts.add(new KnownFact(fieldId, fieldId + ": " + candidate.value(), citableSpanId));
        });
        return facts;
    }

    private static Map<String, FieldCandidate> sanitizeAndValidate(
            Map<String, FieldCandidate> rawComposed, Set<Long> allowedSpanIds, Map<String, Integer> maxCharactersByFieldId) {
        Map<String, FieldCandidate> sanitized = new LinkedHashMap<>();
        rawComposed.forEach((fieldId, candidate) -> {
            FieldCandidate afterEvidenceCheck = sanitizeEvidence(candidate, allowedSpanIds);
            sanitized.put(fieldId, enforceMaxLength(afterEvidenceCheck, maxCharactersByFieldId.get(fieldId)));
        });
        return sanitized;
    }

    private static FieldCandidate sanitizeEvidence(FieldCandidate candidate, Set<Long> allowedSpanIds) {
        if (candidate.unresolved() || allowedSpanIds.containsAll(candidate.evidenceSpanIds())) {
            return candidate;
        }
        return new FieldCandidate(
                candidate.fieldId(), null, List.of(), true, "Cited a fact that was not offered for this composition.");
    }

    /** Never silently truncates text a person did not ask to be cut -- an over-length composed value is downgraded to unresolved instead, so a caller can retry or ask a person to shorten it manually. */
    private static FieldCandidate enforceMaxLength(FieldCandidate candidate, Integer maxCharacters) {
        if (candidate.unresolved() || maxCharacters == null || candidate.value() == null || candidate.value().length() <= maxCharacters) {
            return candidate;
        }
        return new FieldCandidate(
                candidate.fieldId(), null, List.of(), true,
                "Composed text was " + candidate.value().length() + " characters, over this field's " + maxCharacters
                        + "-character limit.");
    }

    private static ExtractionResult merge(ExtractionResult acceptedFacts, Map<String, FieldCandidate> composed) {
        Map<String, FieldCandidate> merged = new LinkedHashMap<>(acceptedFacts.scalarCandidates());
        merged.putAll(composed);
        return new ExtractionResult(merged, acceptedFacts.repeatedItems());
    }
}
