package io.github.vihuynh72.brownie.core.evidence;

/**
 * A span's exact, immutable original-source excerpt, resolved fresh
 * rather than cached: what a source inspector actually shows a user, not
 * just a reassuring link icon (per the plan's own words on this point).
 * {@code excerptText} is the true original text -- for a plain-text span,
 * the corresponding original-offset substring (recovered through the
 * normalized-to-original mapping), not the normalized text the locator
 * itself is expressed in.
 */
public record ResolvedEvidence(SourceSpan span, String excerptText) {
}
