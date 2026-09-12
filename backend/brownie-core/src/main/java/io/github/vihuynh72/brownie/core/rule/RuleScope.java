package io.github.vihuynh72.brownie.core.rule;

/** What a rule applies to: the whole template, or one specific field, addressed the same way {@link io.github.vihuynh72.brownie.core.template.FieldDefinition#fieldId()} is. */
public sealed interface RuleScope {

    record WholeTemplate() implements RuleScope {
    }

    record SingleField(String fieldId) implements RuleScope {
    }
}
