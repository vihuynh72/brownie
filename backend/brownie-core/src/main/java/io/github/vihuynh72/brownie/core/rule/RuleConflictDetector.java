package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a set of proposed rules can all actually hold together --
 * "conflicting approved rules are resolved before generation... if two
 * rules cannot both hold, template activation fails with a specific
 * explanation." Nothing here decides which rule to keep; every conflict
 * found is reported so a human resolves it, never guessed away. A pure
 * function, the same framework-free shape {@link TemplateBindingValidator}
 * and {@link RulePayloadValidator} already have.
 */
public final class RuleConflictDetector {

    private RuleConflictDetector() {
    }

    /** Every conflict found among {@code rules} -- empty means they can all hold at once. */
    public static List<RuleConflict> detectConflicts(
            List<FieldDefinition> fieldDefinitions, DocxStructuralGraph graph, List<RuleRevision> rules) {
        List<RuleConflict> conflicts = new ArrayList<>();
        conflicts.addAll(directContradictions(rules));
        conflicts.addAll(requirednessVsMissingValue(rules));
        conflicts.addAll(protectedFieldBindings(fieldDefinitions, graph, rules));
        return conflicts;
    }

    /**
     * Groups every single-field, single-value rule kind (everything except
     * {@code RequiredFields} and {@code AllowedSectionOrder}, whose own
     * lists are additive rather than a single authoritative value, and
     * {@code ProtectedRegion}, handled on its own below) by its own kind
     * and field. More than one distinct value in a group is a direct
     * contradiction -- two rules with the *same* value in a group are
     * redundant, not conflicting.
     */
    private static List<RuleConflict> directContradictions(List<RuleRevision> rules) {
        Map<String, List<RuleRevision>> groups = new LinkedHashMap<>();
        for (RuleRevision rule : rules) {
            singleFieldIdOf(rule.payload())
                    .ifPresent(fieldId -> groups
                            .computeIfAbsent(rule.payload().getClass().getSimpleName() + "/" + fieldId, key -> new ArrayList<>())
                            .add(rule));
        }
        List<RuleConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<RuleRevision>> entry : groups.entrySet()) {
            List<RuleRevision> group = entry.getValue();
            long distinctValues = group.stream().map(RuleRevision::payload).distinct().count();
            if (distinctValues > 1) {
                conflicts.add(new RuleConflict(
                        RuleConflictReason.DIRECT_CONTRADICTION,
                        group.stream().map(RuleRevision::id).toList(),
                        group.size() + " rules disagree on " + entry.getKey()));
            }
        }
        return conflicts;
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

    private static List<RuleConflict> requirednessVsMissingValue(List<RuleRevision> rules) {
        Set<String> requiredFieldIds = rules.stream()
                .map(RuleRevision::payload)
                .filter(RulePayload.RequiredFields.class::isInstance)
                .map(RulePayload.RequiredFields.class::cast)
                .flatMap(p -> p.fieldIds().stream())
                .collect(Collectors.toSet());

        List<RuleConflict> conflicts = new ArrayList<>();
        for (RuleRevision rule : rules) {
            if (rule.payload() instanceof RulePayload.MissingValueBehavior(String fieldId, EmptyValueResolution resolution)
                    && resolution == EmptyValueResolution.OMIT
                    && requiredFieldIds.contains(fieldId)) {
                List<Long> involved = new ArrayList<>();
                rules.stream()
                        .filter(r -> r.payload() instanceof RulePayload.RequiredFields rf && rf.fieldIds().contains(fieldId))
                        .map(RuleRevision::id)
                        .forEach(involved::add);
                involved.add(rule.id());
                conflicts.add(new RuleConflict(
                        RuleConflictReason.REQUIREDNESS_VS_MISSING_VALUE, involved,
                        "field \"" + fieldId + "\" is required but has a MissingValueBehavior of OMIT"));
            }
        }
        return conflicts;
    }

    /**
     * Resolves every {@code ProtectedRegion} target and every field's own
     * binding to the same real-location identity ({@link
     * TemplateBindingValidator.ResolvedLocation}) before comparing them --
     * two bindings can name the same physical spot through different
     * {@code FieldBindingTarget} kinds (a tag versus that same control's
     * own structural path), so comparing the raw target values would miss
     * a real overlap. A target that does not resolve unambiguously is
     * skipped here, not flagged -- {@link RulePayloadValidator} already
     * rejected that case when the rule was proposed.
     */
    private static List<RuleConflict> protectedFieldBindings(
            List<FieldDefinition> fieldDefinitions, DocxStructuralGraph graph, List<RuleRevision> rules) {
        List<RuleConflict> conflicts = new ArrayList<>();
        for (RuleRevision rule : rules) {
            if (!(rule.payload() instanceof RulePayload.ProtectedRegion(var target))) {
                continue;
            }
            Optional<TemplateBindingValidator.ResolvedLocation> protectedLocation = TemplateBindingValidator.resolveUnique(graph, target);
            if (protectedLocation.isEmpty()) {
                continue;
            }
            for (FieldDefinition field : fieldDefinitions) {
                Optional<TemplateBindingValidator.ResolvedLocation> fieldLocation =
                        TemplateBindingValidator.resolveUnique(graph, field.binding());
                if (fieldLocation.isPresent() && fieldLocation.get().equals(protectedLocation.get())) {
                    conflicts.add(new RuleConflict(
                            RuleConflictReason.PROTECTED_FIELD_BINDING, List.of(rule.id()),
                            "protected region matches field \"" + field.fieldId() + "\"'s own binding location"));
                }
            }
        }
        return conflicts;
    }
}
