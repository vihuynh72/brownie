package io.github.vihuynh72.brownie.core.rule;

/**
 * A structured proposal that must be validated and accepted before use.
 * Only PROPOSED is produced today -- a rule that fails structural or
 * safety validation is never persisted at all, and nothing in this phase
 * yet decides ACCEPTED or REJECTED; that decision, and the conflict
 * detection it depends on, belongs to a later task.
 */
public enum RuleRevisionStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED
}
