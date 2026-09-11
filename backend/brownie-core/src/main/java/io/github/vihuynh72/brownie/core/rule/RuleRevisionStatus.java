package io.github.vihuynh72.brownie.core.rule;

/**
 * A structured proposal that must be validated and accepted before use.
 * Only PROPOSED is produced today -- a rule that fails structural or
 * safety validation is never persisted at all, and no decision flow yet
 * changes a proposed rule to ACCEPTED or REJECTED. Proposed rules still
 * participate in activation's consistency check, so an inconsistent draft
 * cannot become active.
 */
public enum RuleRevisionStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED
}
