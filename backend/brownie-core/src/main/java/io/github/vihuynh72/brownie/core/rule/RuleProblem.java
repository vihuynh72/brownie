package io.github.vihuynh72.brownie.core.rule;

/** One reason a rule payload was rejected. {@code detail} is a short, self-contained explanation -- which field, which bound -- not the whole payload dumped back. */
public record RuleProblem(RuleProblemReason reason, String detail) {
}
