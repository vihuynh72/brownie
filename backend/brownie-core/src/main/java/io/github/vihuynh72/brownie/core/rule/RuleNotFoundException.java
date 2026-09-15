package io.github.vihuynh72.brownie.core.rule;

/** No rule by that ID is visible in the caller's workspace -- whether it never existed or belongs to a different template. */
public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(long ruleId) {
        super("No rule " + ruleId + " in this workspace.");
    }
}
