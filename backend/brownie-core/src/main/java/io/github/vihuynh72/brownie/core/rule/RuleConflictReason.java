package io.github.vihuynh72.brownie.core.rule;

public enum RuleConflictReason {
    /** Two or more proposed rules of the same kind, naming the same field, disagree on the one value that kind can only ever hold once. */
    DIRECT_CONTRADICTION,
    /** A field is named by a {@link RulePayload.RequiredFields} rule but also has a {@link RulePayload.MissingValueBehavior} of {@code OMIT} -- required, yet instructed to be silently dropped when absent. */
    REQUIREDNESS_VS_MISSING_VALUE,
    /** A {@link RulePayload.ProtectedRegion}'s own target resolves to the exact same real node a field's own binding does -- the field cannot be filled without touching content marked untouchable. */
    PROTECTED_FIELD_BINDING
}
