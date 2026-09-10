package io.github.vihuynh72.brownie.core.rule;

import java.util.List;

/** A rule payload is malformed, references a field that does not exist or does not have the right shape, or has an unsupported target -- nothing was persisted. Names every problem at once. */
public class RuleValidationException extends RuntimeException {

    private final List<RuleProblem> problems;

    public RuleValidationException(List<RuleProblem> problems) {
        super("Rule payload not valid: " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<RuleProblem> problems() {
        return problems;
    }
}
