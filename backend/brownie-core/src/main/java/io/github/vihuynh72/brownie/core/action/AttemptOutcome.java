package io.github.vihuynh72.brownie.core.action;

/** What one attempt learned about the change. */
public enum AttemptOutcome {
    /** The change happened. */
    APPLIED,
    /** The change certainly did not happen. */
    NOT_APPLIED,
    /** The change may or may not have happened. */
    UNKNOWN,
    /** The attempt stopped before anything left. */
    NOT_SENT
}
