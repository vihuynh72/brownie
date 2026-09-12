package io.github.vihuynh72.brownie.core.rule;

/** How a scalar field with no value, or a repeatable region with no items, is handled -- shared by {@link RulePayload.MissingValueBehavior} and {@link RulePayload.RepeatableRegionEmptyBehavior} since both name the same three-way choice for two different field shapes. */
public enum EmptyValueResolution {
    OMIT,
    BLANK,
    PLACEHOLDER_TEXT
}
