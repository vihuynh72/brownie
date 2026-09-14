package io.github.vihuynh72.brownie.core.rule;

/** No rule by that ID exists in this workspace's template, or it has already been decided -- {@code PROPOSED} is the only status a decision can ever be made from. */
public class RuleDecisionConflictException extends RuntimeException {

    public RuleDecisionConflictException(long ruleId) {
        super("Rule " + ruleId + " does not exist, or is no longer PROPOSED.");
    }
}
