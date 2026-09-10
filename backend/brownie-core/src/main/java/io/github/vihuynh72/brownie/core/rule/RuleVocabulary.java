package io.github.vihuynh72.brownie.core.rule;

/** Identifies this rule vocabulary's own shape -- the ten {@link RulePayload} variants and their fields -- the same role {@code parserVersion} plays for an extractor. A later change to any variant's shape bumps this, so an old {@link RuleRevision} is never silently reinterpreted against a vocabulary it was not actually validated by. */
public final class RuleVocabulary {

    public static final String SCHEMA_VERSION = "rule-vocabulary-v1";

    private RuleVocabulary() {
    }
}
