package io.github.vihuynh72.brownie.core.document;

/**
 * One occurrence of an unsupported feature, in a form a user can act on:
 * {@code location} is a human-readable description of where it was found
 * (for example {@code "word/document.xml, paragraph 4"}), and {@code
 * detail} is a short, specific reason (for example the field's own
 * instruction text, or the tracked-change author).
 */
public record DocxFeatureFinding(UnsupportedDocxFeature feature, String location, String detail) {
}
