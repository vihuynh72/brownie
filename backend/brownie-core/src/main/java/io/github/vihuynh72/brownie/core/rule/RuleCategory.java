package io.github.vihuynh72.brownie.core.rule;

/** The five rule categories this product's rule model preserves. Which category a given {@link RulePayload} belongs to is fixed by its own type -- see {@link RulePayload#category()}. */
public enum RuleCategory {
    VISUAL,
    CONTENT,
    BEHAVIOR,
    VALIDATION,
    SOURCE
}
