package io.github.vihuynh72.brownie.core.revision;

/**
 * The result of explicit checks against one field/item for one revision.
 * Every field starts {@link #NOT_RUN} today -- no validator exists yet in
 * this codebase; this dimension exists now so that later, dedicated
 * validation work has a real place to record a result, not a placeholder
 * invented ahead of its own need.
 */
public enum ValidationState {
    NOT_RUN,
    PASSED,
    WARNING,
    BLOCKING,
    UNAVAILABLE
}
