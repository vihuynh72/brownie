package io.github.vihuynh72.brownie.core.compile;

/**
 * Reasons a fill pass refuses to write approved content into a template.
 * These describe a broken or stale binding contract, not a content-value
 * problem -- {@code DocumentContentValidator} already rejected a bad value
 * before a revision could ever be persisted, so a fill-time failure means
 * the template's own pinned bindings no longer match its source bytes.
 */
public enum TemplateFillProblemReason {
    BINDING_NOT_FOUND,
    AMBIGUOUS_BINDING,
    MISMATCHED_REPEATED_LENGTHS,
    CARDINALITY_MISMATCH,
    UNREADABLE_TEMPLATE
}
