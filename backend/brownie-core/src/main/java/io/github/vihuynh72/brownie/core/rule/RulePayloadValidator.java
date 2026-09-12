package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides whether a {@link RuleScope} and {@link RulePayload} are actually
 * valid against one template version's own field definitions and pinned
 * extraction graph -- the "validation of rule payloads" half of this
 * task's own job, kept separate from persistence the same way {@link
 * TemplateBindingValidator} is. Every payload type here is a closed,
 * typed shape (see {@link RulePayload}'s own javadoc), so accepting
 * arbitrary code, expressions, or regular expressions from an ordinary
 * user is not a validation rule to enforce here -- it is structurally
 * impossible to submit in the first place.
 */
public final class RulePayloadValidator {

    private RulePayloadValidator() {
    }

    /** Every problem found -- empty means {@code scope} and {@code payload} are both valid against {@code fieldDefinitions}/{@code graph}. */
    public static List<RuleProblem> validate(
            List<FieldDefinition> fieldDefinitions, DocxStructuralGraph graph, RuleScope scope, RulePayload payload) {
        Map<String, FieldDefinition> byFieldId =
                fieldDefinitions.stream().collect(Collectors.toMap(FieldDefinition::fieldId, Function.identity(), (a, b) -> a));
        List<RuleProblem> problems = new ArrayList<>();

        if (scope instanceof RuleScope.SingleField(String fieldId) && !byFieldId.containsKey(fieldId)) {
            problems.add(new RuleProblem(RuleProblemReason.UNKNOWN_FIELD, "scope references unknown field \"" + fieldId + "\""));
        }

        switch (payload) {
            case RulePayload.RequiredFields(List<String> fieldIds) -> validateFieldIdList(fieldIds, byFieldId, problems, null);
            case RulePayload.MaxTextLength(String fieldId, int maxCharacters) -> {
                requireKnownField(fieldId, byFieldId, problems);
                if (maxCharacters <= 0) {
                    problems.add(new RuleProblem(RuleProblemReason.MALFORMED, "maxCharacters must be positive"));
                }
            }
            case RulePayload.MaxItemCount(String fieldId, int maxItems) -> {
                requireKnownRepeatedField(fieldId, byFieldId, problems);
                if (maxItems <= 0) {
                    problems.add(new RuleProblem(RuleProblemReason.MALFORMED, "maxItems must be positive"));
                }
            }
            case RulePayload.AllowedSectionOrder(List<String> orderedSectionIds) ->
                    validateFieldIdList(orderedSectionIds, null, problems, "orderedSectionIds");
            case RulePayload.DateDisplayFormat(String fieldId, DateFormatStyle style) -> requireKnownField(fieldId, byFieldId, problems);
            case RulePayload.AllowedSourceKinds(String fieldId, List<?> allowedKinds) -> {
                requireKnownField(fieldId, byFieldId, problems);
                if (allowedKinds.isEmpty()) {
                    problems.add(new RuleProblem(RuleProblemReason.MALFORMED, "allowedKinds must not be empty"));
                }
            }
            case RulePayload.MissingValueBehavior(String fieldId, EmptyValueResolution resolution) -> requireKnownField(fieldId, byFieldId, problems);
            case RulePayload.AllowedOverflowBehavior(String fieldId, OverflowResolution resolution) -> requireKnownField(fieldId, byFieldId, problems);
            case RulePayload.RepeatableRegionEmptyBehavior(String fieldId, EmptyValueResolution resolution) ->
                    requireKnownRepeatedField(fieldId, byFieldId, problems);
            case RulePayload.ProtectedRegion(var target) -> {
                int matches = TemplateBindingValidator.matchCount(graph, target);
                if (matches != 1) {
                    problems.add(new RuleProblem(
                            RuleProblemReason.UNSUPPORTED_TARGET,
                            matches == 0 ? "protected-region target not found in the extraction graph" : "protected-region target is ambiguous"));
                }
            }
        }
        validateScopeCompatibility(scope, payload, problems);
        return problems;
    }

    private static void validateScopeCompatibility(
            RuleScope scope, RulePayload payload, List<RuleProblem> problems) {
        if (!(scope instanceof RuleScope.SingleField(String scopedFieldId))) {
            return;
        }

        switch (payload) {
            case RulePayload.RequiredFields(List<String> fieldIds) -> {
                if (fieldIds.size() != 1 || !fieldIds.contains(scopedFieldId)) {
                    problems.add(new RuleProblem(
                            RuleProblemReason.SCOPE_MISMATCH,
                            "a field-scoped RequiredFields rule must name exactly its scoped field"));
                }
            }
            case RulePayload.AllowedSectionOrder ignored -> problems.add(new RuleProblem(
                    RuleProblemReason.SCOPE_MISMATCH,
                    "this payload kind can only be scoped to the whole template"));
            case RulePayload.ProtectedRegion ignored -> problems.add(new RuleProblem(
                    RuleProblemReason.SCOPE_MISMATCH,
                    "this payload kind can only be scoped to the whole template"));
            default -> singleFieldIdOf(payload).ifPresent(payloadFieldId -> {
                if (!payloadFieldId.equals(scopedFieldId)) {
                    problems.add(new RuleProblem(
                            RuleProblemReason.SCOPE_MISMATCH,
                            "scope references field \"" + scopedFieldId + "\" but payload targets \"" + payloadFieldId + "\""));
                }
            });
        }
    }

    private static Optional<String> singleFieldIdOf(RulePayload payload) {
        return switch (payload) {
            case RulePayload.MaxTextLength p -> Optional.of(p.fieldId());
            case RulePayload.MaxItemCount p -> Optional.of(p.fieldId());
            case RulePayload.DateDisplayFormat p -> Optional.of(p.fieldId());
            case RulePayload.AllowedSourceKinds p -> Optional.of(p.fieldId());
            case RulePayload.MissingValueBehavior p -> Optional.of(p.fieldId());
            case RulePayload.AllowedOverflowBehavior p -> Optional.of(p.fieldId());
            case RulePayload.RepeatableRegionEmptyBehavior p -> Optional.of(p.fieldId());
            case RulePayload.RequiredFields ignored -> Optional.empty();
            case RulePayload.AllowedSectionOrder ignored -> Optional.empty();
            case RulePayload.ProtectedRegion ignored -> Optional.empty();
        };
    }

    private static void requireKnownField(String fieldId, Map<String, FieldDefinition> byFieldId, List<RuleProblem> problems) {
        if (fieldId == null || fieldId.isBlank()) {
            problems.add(new RuleProblem(RuleProblemReason.MALFORMED, "fieldId must not be blank"));
        } else if (!byFieldId.containsKey(fieldId)) {
            problems.add(new RuleProblem(RuleProblemReason.UNKNOWN_FIELD, "unknown field \"" + fieldId + "\""));
        }
    }

    private static void requireKnownRepeatedField(String fieldId, Map<String, FieldDefinition> byFieldId, List<RuleProblem> problems) {
        requireKnownField(fieldId, byFieldId, problems);
        FieldDefinition field = byFieldId.get(fieldId);
        if (field != null && field.cardinality() != FieldCardinality.REPEATED) {
            problems.add(new RuleProblem(
                    RuleProblemReason.WRONG_CARDINALITY, "field \"" + fieldId + "\" is " + field.cardinality() + ", not REPEATED"));
        }
    }

    /**
     * Shared by {@code RequiredFields} (checked against real field IDs) and
     * {@code AllowedSectionOrder} (checked only for structural well-
     * formedness, since no independent "section" concept exists yet to
     * validate its identifiers against -- {@code fieldCheck == null}
     * signals that case).
     */
    private static void validateFieldIdList(
            List<String> ids, Map<String, FieldDefinition> fieldCheck, List<RuleProblem> problems, String label) {
        String what = label == null ? "fieldIds" : label;
        if (ids.isEmpty()) {
            problems.add(new RuleProblem(RuleProblemReason.MALFORMED, what + " must not be empty"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                problems.add(new RuleProblem(RuleProblemReason.MALFORMED, what + " contains a blank entry"));
            } else if (!seen.add(id)) {
                problems.add(new RuleProblem(RuleProblemReason.MALFORMED, what + " contains a duplicate entry \"" + id + "\""));
            } else if (fieldCheck != null && !fieldCheck.containsKey(id)) {
                problems.add(new RuleProblem(RuleProblemReason.UNKNOWN_FIELD, what + " references unknown field \"" + id + "\""));
            }
        }
    }
}
