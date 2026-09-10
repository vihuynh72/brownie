package io.github.vihuynh72.brownie.core.document;

/**
 * A plain-text artifact's own immutable text alongside its line-ending-
 * normalized form. Deliberately the simplest of the three formats' graphs
 * -- plain text has no pages, no paragraphs, no structure to model, only
 * text -- and deliberately does not persist the offset mapping between
 * the two: {@code originalText} alone is enough to recompute it on demand
 * via {@code NormalizedText.normalizeLineEndings}, a pure, deterministic,
 * cheap function of the text itself.
 */
public record PlainTextStructuralGraph(String parserVersion, String originalText, String normalizedText) {
}
