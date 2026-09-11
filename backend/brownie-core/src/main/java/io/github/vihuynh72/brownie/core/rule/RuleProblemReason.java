package io.github.vihuynh72.brownie.core.rule;

public enum RuleProblemReason {
    /** A blank field ID, an empty list that must have at least one entry, a non-positive bound, or a duplicate entry within the payload's own list. */
    MALFORMED,
    /** The payload names a field ID that does not exist among the template version's own field definitions. */
    UNKNOWN_FIELD,
    /** The payload's own cardinality expectation (for example a repeated-only rule) does not match the referenced field's actual {@code FieldCardinality}. */
    WRONG_CARDINALITY,
    /** A field-scoped rule names a different field than its payload, or applies a whole-template-only payload to one field. */
    SCOPE_MISMATCH,
    /** A {@link RulePayload.ProtectedRegion} target does not resolve to exactly one node in the pinned extraction graph -- the same NOT_FOUND/AMBIGUOUS failure {@code TemplateBindingValidator} reports for a field's own binding, collapsed to one reason here since a rule's own target has no separate field identity to blame. */
    UNSUPPORTED_TARGET
}
