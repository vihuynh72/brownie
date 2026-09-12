package io.github.vihuynh72.brownie.core.revision;

/** Reasons an attempted field change cannot be represented by a template. */
public enum DocumentContentProblemReason {
    UNKNOWN_FIELD,
    TYPE_MISMATCH,
    CARDINALITY_MISMATCH,
    DUPLICATE_EDIT
}
