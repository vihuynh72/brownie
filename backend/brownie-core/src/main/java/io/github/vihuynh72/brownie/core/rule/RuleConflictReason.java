package io.github.vihuynh72.brownie.core.rule;

public enum RuleConflictReason {
    /** Two or more proposed rules of the same kind, naming the same field, disagree on the one value that kind can only ever hold once. */
    DIRECT_CONTRADICTION,
    /** A field is required by its definition or a {@link RulePayload.RequiredFields} rule but also has a {@link RulePayload.MissingValueBehavior}; a required field cannot use an absent-value fallback. */
    REQUIREDNESS_VS_MISSING_VALUE,
    /** A {@link RulePayload.ProtectedRegion}'s own target overlaps a field binding at the same node or an ancestor/descendant node, so filling the field would touch content marked untouchable. */
    PROTECTED_FIELD_BINDING
}
