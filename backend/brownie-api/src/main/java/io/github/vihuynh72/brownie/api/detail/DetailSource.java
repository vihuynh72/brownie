package io.github.vihuynh72.brownie.api.detail;

/** The form field and document a saved detail was approved on; null for a hand-written one. */
public record DetailSource(Long documentId, String label, String title) {
}
