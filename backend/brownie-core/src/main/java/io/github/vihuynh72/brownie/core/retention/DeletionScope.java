package io.github.vihuynh72.brownie.core.retention;

/** What one deletion request removes: a single document, or a whole workspace with everything in it. */
public enum DeletionScope {
    DOCUMENT,
    WORKSPACE
}
