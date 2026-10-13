package io.github.vihuynh72.brownie.core.batch;

/**
 * Which bounded step a lease is running. One lease per workspace covers
 * every kind -- the values a workspace never uses still name steps its other
 * batches could hold, so the set is the whole enum, not the steps built so
 * far.
 */
public enum BatchStepKind {
    FILL,
    ACCEPT,
    CHECK,
    EXPORT,
    COMPOSE
}
