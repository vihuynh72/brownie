package io.github.vihuynh72.brownie.core.retention;

/** What the database answered when asked to take a document back out of the trash. */
public enum RestoreOutcome {
    RESTORED,
    /** The request exists but was already deleted for good. */
    NOT_OPEN,
    NOT_FOUND
}
