package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.rule.RuleConflict;
import io.github.vihuynh72.brownie.core.rule.RuleConflictDetector;
import io.github.vihuynh72.brownie.core.rule.RuleConflictException;
import io.github.vihuynh72.brownie.core.rule.RulePayloadValidator;
import io.github.vihuynh72.brownie.core.rule.RuleProblem;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleValidationException;

import java.util.List;

/**
 * What a template version must satisfy before documents can use it, whether
 * a person activates a draft ({@link TemplateService#activate}) or corrects
 * the fill spots of an open document ({@link TemplateDerivationService}):
 * it binds at least one field, every binding resolves in the file's
 * structure, and every rule still in play is valid against the final field
 * list and conflicts with no other. Proving a sample fill and render is the
 * last step of both and stays with each caller, since a correction that only
 * renames reuses the render it already has.
 *
 * <p>A {@code REJECTED} rule is left out before validation and conflict
 * detection see it: a rejected proposal never applies to a generated
 * document, so it must not block anything.
 */
public final class ActivationGate {

    private ActivationGate() {
    }

    /** {@code subject} names the version in messages, for example "Template 4 draft version 2". */
    public static void check(String subject, List<FieldDefinition> fields, DocxStructuralGraph graph, List<RuleRevision> rules) {
        requireFields(subject, fields);
        requireBindable(TemplateBindingValidator.validate(graph, fields));
        requireRulesHold(fields, graph, rules);
    }

    /** The first check alone: at least one field is bound. Activating a draft may skip it when the form is meant to open with no places yet. */
    public static void requireFields(String subject, List<FieldDefinition> fields) {
        if (fields.isEmpty()) {
            throw new TemplateVersionStateConflictException(
                    subject + " has no field definitions; a template cannot be activated with nothing bound.");
        }
    }

    /** Refuses the version when its bindings, checked against the reading of its own kind, have any problem. */
    public static void requireBindable(List<UnsupportedBinding> bindingProblems) {
        if (!bindingProblems.isEmpty()) {
            throw new TemplateBindingValidationException(bindingProblems);
        }
    }

    /**
     * Every rule still in play is valid against {@code fields} and conflicts
     * with no other. {@code graph} is null for a PDF version, which has no
     * Word structure: then only what would need one is refused.
     */
    public static void requireRulesHold(List<FieldDefinition> fields, DocxStructuralGraph graph, List<RuleRevision> rules) {
        List<RuleRevision> inPlay = rules.stream().filter(rule -> rule.status() != RuleRevisionStatus.REJECTED).toList();
        if (inPlay.isEmpty()) {
            return;
        }
        List<RuleProblem> ruleProblems = inPlay.stream()
                .flatMap(rule -> RulePayloadValidator
                        .validate(fields, graph, rule.scope(), rule.payload())
                        .stream()
                        .map(problem -> new RuleProblem(problem.reason(), "rule " + rule.id() + ": " + problem.detail())))
                .toList();
        if (!ruleProblems.isEmpty()) {
            throw new RuleValidationException(ruleProblems);
        }
        List<RuleConflict> conflicts = RuleConflictDetector.detectConflicts(fields, graph, inPlay);
        if (!conflicts.isEmpty()) {
            throw new RuleConflictException(conflicts);
        }
    }
}
